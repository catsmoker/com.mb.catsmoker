package com.catsmoker.app.features.gamingtools.tools.audio

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.catsmoker.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Session-0 loudness boost.
 *
 * Effects attached to the global output mix are torn down by the framework when the active
 * output device changes, so a boost applied over the speaker silently dies the moment
 * headphones are plugged in. An [AudioDeviceCallback] rebuilds the chain and re-applies the
 * level instead of leaving the user with a slider that claims to be doing something.
 *
 * This is a process-scoped singleton on purpose. The boost sits on the global mix precisely so that it
 * survives the user leaving the Gaming Tools screen and opening a game — that is the whole reason for
 * boosting session 0 rather than one player. The Gaming Tools ViewModel used to call [release] from
 * `onCleared()`, which had it exactly backwards, and because [released] is never cleared again that one
 * call killed the boost for the rest of the process: every later [applyBoost] returned at the guard,
 * the [AudioDeviceCallback] stayed unregistered, and [outputDevice] froze while the slider went on
 * reporting a level. Turning the boost *off* is [applyBoost] with level 0, which releases the effects.
 * [release] is only for a genuine end of life, and nothing in the UI has one.
 */
class AudioBoostController(private val context: Context) {
    private val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var enhancer: LoudnessEnhancer? = null
    private var dynamicsProcessing: DynamicsProcessing? = null
    private var visualizer: Visualizer? = null
    private val handler = Handler(Looper.getMainLooper())

    private var hasRestarted = false
    private var currentLevel = 0
    private var released = false

    private val _outputDevice = MutableStateFlow<String?>(null)

    /** Human-readable active output device, or null before the first query. */
    val outputDevice: StateFlow<String?> = _outputDevice.asStateFlow()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = onOutputChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = onOutputChanged()
    }

    init {
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        _outputDevice.value = describeActiveOutput()
    }

    private fun onOutputChanged() {
        if (released) return
        _outputDevice.value = describeActiveOutput()
        // The new output path needs its own effect instances; reuse yields a silent no-op.
        teardownEffects()
        hasRestarted = false
        if (currentLevel > 0) applyBoost(currentLevel)
    }

    /**
     * @param level 0..100. 0 releases every effect rather than holding a disabled one: a retained
     *   instance still occupies a slot in the global chain, so "off" has to mean nothing is attached.
     */
    fun applyBoost(level: Int) {
        if (released) return
        currentLevel = level.coerceIn(0, MAX_LEVEL)

        if (currentLevel == 0) {
            teardownEffects()
            hasRestarted = false
            return
        }

        applyLoudnessEnhancer()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) applyDynamicsProcessing()
        applyVisualizerKeepAlive()

        if (!hasRestarted) hasRestarted = restartAudioPlayback()
    }

    private fun applyLoudnessEnhancer() {
        try {
            val fx = enhancer ?: LoudnessEnhancer(GLOBAL_SESSION).also { enhancer = it }
            // Target gain is only latched reliably while the effect is disabled.
            fx.enabled = false
            fx.setTargetGain(currentLevel * GAIN_MB_PER_LEVEL)
            fx.enabled = true
        } catch (t: Throwable) {
            Log.w(TAG, "LoudnessEnhancer unavailable", t)
            runCatching { enhancer?.release() }
            enhancer = null
        }
    }

    private fun applyDynamicsProcessing() {
        try {
            val fx = dynamicsProcessing ?: DynamicsProcessing(
                0,
                GLOBAL_SESSION,
                DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    CHANNEL_COUNT,
                    false, 0,
                    false, 0,
                    false, 0,
                    true
                ).build()
            ).also { dynamicsProcessing = it }

            val limiter = fx.getLimiterByChannelIndex(0)
            limiter.isEnabled = true
            limiter.postGain = currentLevel / LEVEL_PER_DB
            limiter.attackTime = LIMITER_ATTACK_MS
            limiter.releaseTime = LIMITER_RELEASE_MS
            limiter.ratio = LIMITER_RATIO
            limiter.threshold = LIMITER_THRESHOLD_DB
            fx.setLimiterAllChannelsTo(limiter)
            fx.enabled = true
        } catch (t: Throwable) {
            Log.w(TAG, "DynamicsProcessing unavailable", t)
            runCatching { dynamicsProcessing?.release() }
            dynamicsProcessing = null
        }
    }

    /**
     * Some devices suspend the global effect chain when nothing is capturing it. An idle
     * [Visualizer] keeps session 0 warm; it needs RECORD_AUDIO, so it stays optional.
     */
    private fun applyVisualizerKeepAlive() {
        if (visualizer == null && hasRecordAudioPermission()) {
            visualizer = try {
                Visualizer(GLOBAL_SESSION).apply {
                    setDataCaptureListener(
                        object : Visualizer.OnDataCaptureListener {
                            override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, s: Int) = Unit
                            override fun onFftDataCapture(v: Visualizer?, f: ByteArray?, s: Int) = Unit
                        },
                        Visualizer.getMaxCaptureRate() / 2,
                        true,
                        false
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Visualizer unavailable", t)
                null
            }
        }
        runCatching { visualizer?.enabled = true }
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Bounces the active media session so it re-reads the effect chain. Without this the boost
     * only takes effect on the *next* track.
     *
     * @return true when a bounce was actually dispatched.
     */
    private fun restartAudioPlayback(): Boolean {
        if (!audioManager.isMusicActive) return false
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
        handler.postDelayed({ dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY) }, RESTART_DELAY_MS)
        return true
    }

    private fun dispatchMediaKey(keyCode: Int) {
        if (released) return
        runCatching {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
    }

    private fun describeActiveOutput(): String? = try {
        activeOutputDevice()?.let { device ->
            val name = device.productName?.toString()?.trim().orEmpty()
            val kind = deviceTypeLabel(device.type)
            if (name.isEmpty() || name.equals(kind, ignoreCase = true)) kind else "$kind — $name"
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Could not enumerate output devices", t)
        null
    }

    private fun activeOutputDevice(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // The framework tells us exactly where media would be routed.
            val routed = runCatching {
                audioManager.getAudioDevicesForAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
            }.getOrNull()?.firstOrNull()
            if (routed != null) return routed
        }

        // Otherwise approximate the platform's routing precedence by what is connected.
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return ROUTING_PRECEDENCE.firstNotNullOfOrNull { type ->
            devices.firstOrNull { it.type == type }
        } ?: devices.firstOrNull()
    }

    private fun deviceTypeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO ->
            context.getString(R.string.gt_audio_bt)
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET ->
            context.getString(R.string.gt_audio_wired)
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET ->
            context.getString(R.string.gt_audio_usb)
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC ->
            context.getString(R.string.gt_audio_hdmi)
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> context.getString(R.string.gt_audio_speaker)
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> context.getString(R.string.gt_audio_earpiece)
        else -> context.getString(R.string.gt_audio_unknown)
    }

    private fun teardownEffects() {
        runCatching { enhancer?.enabled = false }
        runCatching { enhancer?.release() }
        enhancer = null
        runCatching { dynamicsProcessing?.enabled = false }
        runCatching { dynamicsProcessing?.release() }
        dynamicsProcessing = null
        runCatching { visualizer?.enabled = false }
        runCatching { visualizer?.release() }
        visualizer = null
    }

    /**
     * Permanent end of life: after this the controller is inert and cannot be revived.
     *
     * Not a screen-teardown hook. Use `applyBoost(0)` to turn the boost off — that releases the effects
     * but leaves the controller able to boost again. Nothing in the UI calls this, because the boost is
     * meant to outlive every screen in the process.
     */
    fun release() {
        released = true
        // A pending media-play bounce would otherwise fire after teardown.
        handler.removeCallbacksAndMessages(null)
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        teardownEffects()
    }

    private companion object {
        const val TAG = "AudioBoostController"

        /** Session 0 = the global output mix. */
        const val GLOBAL_SESSION = 0
        const val MAX_LEVEL = 100
        const val CHANNEL_COUNT = 2

        /** 100 % → 3000 mB (30 dB) of requested make-up gain. */
        const val GAIN_MB_PER_LEVEL = 30
        const val LEVEL_PER_DB = 5f
        const val LIMITER_ATTACK_MS = 1f
        const val LIMITER_RELEASE_MS = 60f
        const val LIMITER_RATIO = 10f
        const val LIMITER_THRESHOLD_DB = -1f
        const val RESTART_DELAY_MS = 100L

        /** Rough mirror of the platform's media-output precedence, best first. */
        val ROUTING_PRECEDENCE = listOf(
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        )
    }
}
