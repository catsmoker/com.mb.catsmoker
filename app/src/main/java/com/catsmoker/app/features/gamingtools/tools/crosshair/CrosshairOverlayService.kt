package com.catsmoker.app.features.gamingtools.tools.crosshair

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.catsmoker.app.R
import com.catsmoker.app.shared.util.DisplayMetricsProvider
import dagger.hilt.android.AndroidEntryPoint
import kotlin.math.abs
import kotlin.math.roundToInt
import javax.inject.Inject

/**
 * The crosshair overlay, and its reposition (move) mode.
 *
 * ## Why dragging is a mode rather than a property
 *
 * The crosshair's whole job is to sit over a game the user is actively tapping, so the overlay window
 * normally carries [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE] — every touch falls straight
 * through to the game. That flag is also exactly what makes the view undraggable, and it cannot simply
 * be dropped: a permanently touchable window parked in the middle of the screen would swallow the
 * taps aimed at whatever the crosshair is pointing at, which is the one place a shooter can least
 * afford to lose input.
 *
 * So the flag is toggled instead. Move mode ([ACTION_ENTER_MOVE_MODE]) clears it and the crosshair
 * becomes draggable; [ACTION_EXIT_MOVE_MODE] sets it again and the overlay goes back to being
 * invisible to touch. The user is never left holding a touch-stealing overlay by accident, because
 * every route out of move mode is available while it is on:
 *
 *  - the **Done** button in the banner (a separate always-reachable window, see below),
 *  - the notification, whose tap action ends move mode,
 *  - turning the crosshair off entirely, which tears down both windows.
 *
 * ## Why the banner is its own window
 *
 * The Done control cannot live in the crosshair window: that window is dragged under the user's
 * finger and could be left anywhere, including under a notch or off the edge, taking the only exit
 * with it. The banner is therefore a second window pinned to the top of the screen, which keeps the
 * exit in a fixed, known place for as long as move mode lasts.
 *
 * ## Position
 *
 * Kept as an offset from screen centre in [CrosshairPositionStore], and clamped to the display on the
 * way in as well as on the way out — a saved offset can be stale by the time it is read, because the
 * app's own Resolution Changer can have shrunk the panel in between. Screen size comes from
 * [DisplayMetricsProvider], the one owner of that question in this codebase; if it cannot read the
 * display the drag is left unclamped rather than clamped against an invented number.
 */
@AndroidEntryPoint
class CrosshairOverlayService : Service() {

    @Inject
    lateinit var positionStore: CrosshairPositionStore

    @Inject
    lateinit var displayMetricsProvider: DisplayMetricsProvider

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var bannerView: View? = null

    private var isMoveMode = false
    private var currentAsset = DEFAULT_ASSET

    /** Slop below which a touch is a tap, not a drag — the platform's own threshold. */
    private var touchSlop = 0

    /**
     * Maximum offset from centre on each axis, or null when the display could not be read.
     *
     * Cached because [clampToDisplay] runs on every touch move; refreshed by [refreshDragBounds] at
     * the only points the answer can change.
     */
    private var dragBounds: Pair<Int, Int>? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every path calls startForeground, including the mode actions. Those arrive as fresh
        // onStartCommand calls on a service started with startForegroundService, and Android kills a
        // service that does not post its notification — so entering move mode must not skip it.
        startForeground(NOTIFICATION_ID, buildNotification())

        // Move mode is delivered as an action on the running service rather than a fresh start, so a
        // toggle must not rebuild the overlay and lose the position mid-drag.
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                // The notification's Stop button. onDestroy removes the window and reports
                // ACTION_CROSSHAIR_SERVICE_STOPPED, which is what clears the switch in the UI — the
                // same path context.stopService takes when the in-app switch is turned off.
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_ENTER_MOVE_MODE -> {
                // Rebuilds the overlay if it is not up. Normally it is; this covers the service having
                // been restarted with a mode action as its first intent, where returning without an
                // overlay would put move mode on with nothing to drag.
                if (overlayView == null) showOverlay(currentAsset)
                setMoveMode(true)
                return START_NOT_STICKY
            }

            ACTION_EXIT_MOVE_MODE -> {
                setMoveMode(false)
                return START_NOT_STICKY
            }

            ACTION_RESET_POSITION -> {
                positionStore.clear()
                applyStoredPosition()
                return START_NOT_STICKY
            }
        }

        currentAsset = intent?.getStringExtra(EXTRA_SCOPE_ASSET) ?: DEFAULT_ASSET

        showOverlay(currentAsset)

        return START_NOT_STICKY
    }

    private fun showOverlay(assetName: String) {
        overlayView?.let { existing ->
            runCatching { windowManager?.removeView(existing) }
            overlayView = null
        }

        @android.annotation.SuppressLint("InflateParams")
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_crosshair, null)
        val image = view.findViewById<ImageView>(R.id.crosshair_image)

        try {
            assets.open("crosshair/$assetName").use { input ->
                val bitmap = BitmapFactory.decodeStream(input)
                image?.setImageBitmap(bitmap)
            }
        } catch (_: Exception) {
        }

        // Positioned from the centre so the stored offset means the same thing at any resolution, and
        // so a re-centre is simply an offset of zero. The stored offset is applied to the params
        // *before* addView, not with an updateViewLayout after it: adding at centre and then moving
        // shows the crosshair in the wrong place for a frame.
        refreshDragBounds()
        val stored = clampToDisplay(positionStore.offsetX, positionStore.offsetY)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flagsFor(moveMode = isMoveMode),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            x = stored.first
            y = stored.second
        }

        // The halo is set here rather than left to setMoveMode: the view was just inflated, so its
        // visibility is the layout's default and a re-inflate during move mode would drop it.
        view.findViewById<View>(R.id.move_halo)?.visibility =
            if (isMoveMode) View.VISIBLE else View.INVISIBLE

        overlayView = view
        overlayParams = params
        attachDragHandler(view)

        // `isRunning` and the broadcast are set from here rather than from onStartCommand so they
        // follow the window actually existing. addView can be refused — a revoked overlay permission
        // is the usual reason — and the UI switch must not claim a crosshair the user cannot see.
        val added = runCatching { windowManager?.addView(view, params) }.isSuccess
        isRunning = added
        if (added) {
            sendBroadcast(Intent(ACTION_CROSSHAIR_SERVICE_STARTED).setPackage(packageName))
        } else {
            overlayView = null
            overlayParams = null
            sendBroadcast(Intent(ACTION_CROSSHAIR_SERVICE_STOPPED).setPackage(packageName))
            stopSelf()
        }
    }

    /**
     * Window flags for a mode.
     *
     * `NOT_FOCUSABLE` is kept in both: the overlay must never take the IME or the back key from the
     * game. Only touchability changes — which is the whole of the difference between the two modes.
     */
    private fun flagsFor(moveMode: Boolean): Int {
        val base = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        return if (moveMode) base else base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    }

    private fun setMoveMode(enabled: Boolean) {
        if (isMoveMode == enabled) return
        isMoveMode = enabled

        val view = overlayView
        val params = overlayParams
        if (view != null && params != null) {
            params.flags = flagsFor(moveMode = enabled)
            runCatching { windowManager?.updateViewLayout(view, params) }
            view.findViewById<View>(R.id.move_halo)?.visibility =
                if (enabled) View.VISIBLE else View.INVISIBLE
        }

        if (enabled) showBanner() else hideBanner()

        // The notification's action is the backstop exit from move mode, so it has to track the mode.
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification())
        }

        isInMoveMode = enabled
        sendBroadcast(
            Intent(ACTION_CROSSHAIR_MOVE_MODE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_MOVE_MODE, enabled)
        )
    }

    private fun attachDragHandler(view: View) {
        // Offsets are relative to centre, so the drag tracks the params directly rather than
        // recomputing an absolute position from raw coordinates.
        var startRawX = 0f
        var startRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        view.setOnTouchListener { _, event ->
            val params = overlayParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Bounds are resolved per drag, not per move event, and a rotation or a resolution
                    // change between drags is picked up here.
                    refreshDragBounds()
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) moved = true
                    if (moved) {
                        val (x, y) = clampToDisplay(startX + dx.roundToInt(), startY + dy.roundToInt())
                        params.x = x
                        params.y = y
                        runCatching { windowManager?.updateViewLayout(view, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Persisted on release rather than on every move event, so a drag is one write.
                    if (moved) {
                        positionStore.save(params.x, params.y)
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        // A touch that never passed the slop is a tap, not a drag. Nothing is bound to
                        // it, but it is still dispatched so the view reports the click to accessibility
                        // services rather than swallowing it.
                        view.performClick()
                    }
                    true
                }

                else -> false
            }
        }
    }

    /**
     * Holds an offset inside the display, keeping the crosshair's centre on-screen.
     *
     * The limit is half the screen because the window is centre-gravity: an offset of half the width
     * puts the crosshair's own centre exactly on the edge. A margin is subtracted so the mark stays
     * grabbable rather than sitting half off the panel — a crosshair dragged fully into the corner is
     * one the user cannot pick up again.
     *
     * Bounds come from [dragBounds], resolved once per drag rather than per touch event: this runs on
     * the main thread for every `ACTION_MOVE`, and re-reading `WindowManager` a hundred times a second
     * inside an overlay whose entire purpose is not to cost the game frames would be self-defeating.
     *
     * A null [dragBounds] means the display could not be read, and the offset is returned untouched:
     * [DisplayMetricsProvider] reports that honestly instead of inventing a size, and clamping against
     * a made-up number would move the crosshair somewhere the user did not put it.
     */
    private fun clampToDisplay(x: Int, y: Int): Pair<Int, Int> {
        val bounds = dragBounds ?: return x to y
        return x.coerceIn(-bounds.first, bounds.first) to y.coerceIn(-bounds.second, bounds.second)
    }

    /**
     * Re-reads the display and caches the maximum offset on each axis.
     *
     * Called when a drag starts and when the configuration changes, which are the only moments the
     * answer can differ — including a `wm size` change made by this app's own Resolution Changer.
     */
    private fun refreshDragBounds() {
        val snapshot = displayMetricsProvider.current()
        dragBounds = if (!snapshot.isValid) {
            null
        } else {
            val margin = (MOVE_EDGE_MARGIN_DP * resources.displayMetrics.density).roundToInt()
            (snapshot.widthPixels / 2 - margin).coerceAtLeast(0) to
                (snapshot.heightPixels / 2 - margin).coerceAtLeast(0)
        }
    }

    /**
     * Re-clamps the crosshair after a rotation or resolution change.
     *
     * An offset saved in portrait can be outside a landscape screen's shorter axis, which would leave
     * the crosshair off the panel — and in move mode, un-grabbable. The stored value is deliberately
     * left alone: this moves the crosshair on screen without overwriting where the user put it, so
     * rotating back restores their position instead of having flattened it to the edge.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshDragBounds()
        val view = overlayView ?: return
        val params = overlayParams ?: return
        val (x, y) = clampToDisplay(params.x, params.y)
        if (x != params.x || y != params.y) {
            params.x = x
            params.y = y
            runCatching { windowManager?.updateViewLayout(view, params) }
        }
    }

    /**
     * Moves the overlay to the stored offset.
     *
     * Clamped on read as well as on drag: the offset may have been saved at a resolution the app's own
     * Resolution Changer has since replaced, so a value that was on-screen when written can be off it
     * now.
     */
    private fun applyStoredPosition() {
        val view = overlayView ?: return
        val params = overlayParams ?: return
        // Bounds are re-read here because this path is reached from a re-centre, with no preceding
        // drag to have refreshed them.
        refreshDragBounds()
        val (x, y) = clampToDisplay(positionStore.offsetX, positionStore.offsetY)
        params.x = x
        params.y = y
        runCatching { windowManager?.updateViewLayout(view, params) }
    }

    private fun showBanner() {
        if (bannerView != null) return

        @android.annotation.SuppressLint("InflateParams")
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_crosshair_banner, null)

        view.findViewById<View>(R.id.banner_done)?.setOnClickListener { setMoveMode(false) }
        view.findViewById<View>(R.id.banner_reset)?.setOnClickListener {
            positionStore.clear()
            applyStoredPosition()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Touchable (it carries the exit) but never focusable, so the game keeps the back key.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (BANNER_TOP_MARGIN_DP * resources.displayMetrics.density).roundToInt()
        }

        runCatching { windowManager?.addView(view, params) }
            .onSuccess { bannerView = view }
    }

    private fun hideBanner() {
        bannerView?.let { view ->
            runCatching { windowManager?.removeView(view) }
            bannerView = null
        }
    }

    /**
     * The notification, which doubles as the backstop exit from move mode.
     *
     * While move mode is on the overlay is taking touches, so the notification carries a "Done moving"
     * action that ends it — reachable even if the banner is not (a window the system declined to add,
     * an immersive game the user has to swipe the shade over).
     *
     * It is an action button rather than the notification's content intent deliberately: a content
     * intent that starts a service is what `LaunchActivityFromNotification` warns about, and an
     * explicit button is also the clearer affordance for "stop taking my taps".
     *
     * A Stop action is always present, so the overlay can be dismissed from the shade — the same
     * teardown the in-app switch's context.stopService gets, not a shortcut around it.
     */
    private fun buildNotification(): android.app.Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(
                if (isMoveMode) getString(R.string.gt_svc_cross_moving) else getString(R.string.gt_svc_cross_active)
            )
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)

        // getForegroundService, not getService: from API 26 a service started from a notification
        // action must call startForeground, and onStartCommand does that on every path.
        builder.addAction(
            0,
            getString(R.string.notification_stop),
            PendingIntent.getForegroundService(
                this,
                REQUEST_STOP_SERVICE,
                Intent(this, CrosshairOverlayService::class.java).setAction(ACTION_STOP_SERVICE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )

        if (isMoveMode) {
            builder.setContentText(getString(R.string.gt_svc_cross_taking))
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.gt_svc_cross_done),
                PendingIntent.getService(
                    this,
                    REQUEST_EXIT_MOVE_MODE,
                    Intent(this, CrosshairOverlayService::class.java).setAction(ACTION_EXIT_MOVE_MODE),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
        return builder.build()
    }

    override fun onDestroy() {
        hideBanner()
        overlayView?.let { runCatching { windowManager?.removeView(it) } }
        overlayView = null
        overlayParams = null
        isMoveMode = false
        isInMoveMode = false
        isRunning = false
        sendBroadcast(Intent(ACTION_CROSSHAIR_SERVICE_STOPPED).setPackage(packageName))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.gt_svc_cross_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        var isRunning = false

        /** Whether the overlay is currently taking touches. Mirrored to the UI so the switch can say so. */
        var isInMoveMode = false

        const val ACTION_CROSSHAIR_SERVICE_STARTED = "com.catsmoker.app.CROSSHAIR_STARTED"
        const val ACTION_CROSSHAIR_SERVICE_STOPPED = "com.catsmoker.app.CROSSHAIR_STOPPED"
        const val ACTION_CROSSHAIR_MOVE_MODE_CHANGED = "com.catsmoker.app.CROSSHAIR_MOVE_MODE_CHANGED"

        const val ACTION_ENTER_MOVE_MODE = "com.catsmoker.app.CROSSHAIR_ENTER_MOVE_MODE"
        const val ACTION_EXIT_MOVE_MODE = "com.catsmoker.app.CROSSHAIR_EXIT_MOVE_MODE"
        const val ACTION_RESET_POSITION = "com.catsmoker.app.CROSSHAIR_RESET_POSITION"

        /** Ends the service from the notification's Stop action; onDestroy does the real teardown. */
        const val ACTION_STOP_SERVICE = "com.catsmoker.app.CROSSHAIR_STOP_SERVICE"

        const val EXTRA_SCOPE_ASSET = "scope_asset_name"
        const val EXTRA_MOVE_MODE = "move_mode"

        const val DEFAULT_ASSET = "scope2.png"

        /** Keeps a dragged crosshair grabbable instead of letting it sit half off the panel. */
        private const val MOVE_EDGE_MARGIN_DP = 24f
        private const val BANNER_TOP_MARGIN_DP = 48f

        private const val CHANNEL_ID = "crosshair_channel"
        private const val NOTIFICATION_ID = 102
        private const val REQUEST_EXIT_MOVE_MODE = 1021
        private const val REQUEST_STOP_SERVICE = 1022
    }
}
