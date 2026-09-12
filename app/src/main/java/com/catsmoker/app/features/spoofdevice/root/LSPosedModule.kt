package com.catsmoker.app.features.spoofdevice.root

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.view.Display
import com.catsmoker.app.shared.data.model.LSPosedConfig
import com.catsmoker.app.system.config.SpoofConfigProvider
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/**
 * Applies the spoof profile the user assigned to *this* app, inside its own process.
 *
 * Profiles are the same `key=value` text
 * [com.catsmoker.app.shared.data.repository.SpoofRepository.renderConfig] writes for Magisk, so
 * system-property names are the only vocabulary: `Build.MODEL` comes from `ro.product.model`.
 *
 * An app with no assigned profile is left completely alone — no hooks are installed at all.
 */
class LSPosedModule : IXposedHookLoadPackage {

    /** Resolved profile for this process. Empty means "do not spoof this app". */
    @Volatile
    private var props: Map<String, String> = emptyMap()

    private var hooksInstalled = false
    private var receiverRegistered = false

    /**
     * Original Build values, captured before the first overwrite. Without these, un-assigning a
     * profile could not take effect until the target app was killed.
     */
    private val originalFields = HashMap<String, Any?>()

    /** Read before anything is spoofed, so our own platform checks stay honest. */
    private val realSdkInt = Build.VERSION.SDK_INT

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        if (pkg == "android" || pkg == OWN_PACKAGE) return

        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application",
                lpparam.classLoader,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = param.args[0] as? Context ?: return
                        registerReceiver(context, lpparam)
                        reload(context, lpparam)
                    }
                })
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: cannot hook Application.attach in $pkg: ${e.message}")
        }
    }

    // ------------------------------------------------------------------- config

    private fun reload(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        val resolved = readConfig(context, lpparam.packageName)
        props = resolved

        if (resolved.isEmpty()) {
            // Put back every field we previously overwrote. Installed method hooks stay in place
            // but read `props`, so they become pass-throughs.
            if (hooksInstalled) applyBuildFields(emptyMap())
            return
        }

        applyBuildFields(resolved)
        installHooks(lpparam.classLoader)
        XposedBridge.log("$TAG: spoofing ${lpparam.packageName} as ${resolved["ro.product.model"]}")
    }

    /**
     * Resolves this package's profile, preferring the freshest channel that actually works.
     *
     * @return the parsed profile, or an empty map when the app should not be touched.
     */
    private fun readConfig(context: Context, packageName: String): Map<String, String> {
        // Checked before any channel is consulted so it cannot be overridden by an assignment —
        // the same hard floor as safe mode, but one the user cannot tick their way out of.
        if (packageName in LSPosedConfig.NEVER_SPOOF_PACKAGES) {
            XposedBridge.log("$TAG: $packageName is on the never-spoof list, leaving it alone")
            return emptyMap()
        }

        val text = queryProvider(context, packageName)
            ?: sectionFromGlobal(context.contentResolver, packageName)
            ?: legacyGlobal(context.contentResolver, packageName)
            ?: return emptyMap()

        val parsed = LSPosedConfig.parseDeviceProps(text)
        if (parsed.isEmpty()) return emptyMap()

        val safeMode = LSPosedConfig.parseTargetPackages(parsed[LSPosedConfig.KEY_SAFE_MODE_PACKAGES])
        if (packageName in safeMode) {
            XposedBridge.log("$TAG: $packageName is in safe mode, leaving it alone")
            return emptyMap()
        }
        return parsed
    }

    /** Freshest source, but package-visibility filtering hides our authority from most apps. */
    private fun queryProvider(context: Context, packageName: String): String? = try {
        context.contentResolver.call(
            SpoofConfigProvider.CONFIG_URI,
            SpoofConfigProvider.METHOD_GET_CONFIG,
            packageName,
            null
        )?.getString(SpoofConfigProvider.COLUMN_CONTENT)?.ifBlank { null }
    } catch (_: Throwable) {
        null
    }

    /** Per-package section of the Settings.Global document — readable from any process. */
    private fun sectionFromGlobal(resolver: ContentResolver, packageName: String): String? =
        LSPosedConfig.parseSection(
            readGlobal(resolver, LSPosedConfig.KEY_GLOBAL_PROFILES_B64),
            packageName
        )

    /**
     * Single-profile blob from older builds. Gated on the explicit target list: without that gate
     * this path spoofed every process the module was loaded into.
     */
    private fun legacyGlobal(resolver: ContentResolver, packageName: String): String? {
        val targets = LSPosedConfig.parseTargetPackages(
            readGlobal(resolver, LSPosedConfig.KEY_GLOBAL_TARGET_PACKAGES_B64)
        )
        if (packageName !in targets) return null
        return readGlobal(resolver, LSPosedConfig.KEY_GLOBAL_DEVICE_PROPS_B64)
    }

    private fun readGlobal(resolver: ContentResolver, key: String): String? = try {
        val raw = Settings.Global.getString(resolver, key)
        if (raw.isNullOrEmpty()) null
        else String(Base64.decode(raw, Base64.NO_WRAP), Charsets.UTF_8).ifBlank { null }
    } catch (_: Throwable) {
        null
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerReceiver(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        if (receiverRegistered) return
        receiverRegistered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = reload(context, lpparam)
        }
        val filter = IntentFilter(LSPosedConfig.ACTION_CONFIG_CHANGED)
        try {
            if (realSdkInt >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        } catch (_: Throwable) {
            receiverRegistered = false
        }
    }

    // -------------------------------------------------------------------- hooks

    /** Method hooks read [props] on every call, so they only ever need installing once. */
    private fun installHooks(classLoader: ClassLoader) {
        if (hooksInstalled) return
        hooksInstalled = true
        hookSystemProperties(classLoader)
        hookSettingsSecure(classLoader)
        hookTelephony(classLoader)
        hookBuildMethods(classLoader)
        hookWebView(classLoader)
        hookJavaSystemProperties()
        hookLocaleAndTimeZone()
        hookMediaDrm(classLoader)
        hookAdvertisingId(classLoader)
        hookAppSetId(classLoader)
        hookGlStrings()
        hookDisplayRefreshRate()
        GetPropInterceptor(lookup = ::lookupProp, properties = { props }).install()
    }

    private fun applyBuildFields(props: Map<String, String>) {
        for ((field, keys) in BUILD_FIELDS) {
            setStaticString(Build::class.java, "Build.$field", field, keys.firstNotNullOfOrNull(props::get))
        }
        for ((field, keys) in VERSION_FIELDS) {
            setStaticString(VERSION_CLASS, "VERSION.$field", field, keys.firstNotNullOfOrNull(props::get))
        }
        for ((field, key) in ABI_LIST_FIELDS) {
            setStaticAbiList(field, props[key])
        }
        applySdkInt(props["ro.build.version.sdk"]?.toIntOrNull())
    }

    /** Writes [value], or restores the captured original when it is null. */
    private fun setStaticString(clazz: Class<*>, tag: String, field: String, value: String?) {
        try {
            if (!originalFields.containsKey(tag)) {
                originalFields[tag] = XposedHelpers.getStaticObjectField(clazz, field)
            }
            XposedHelpers.setStaticObjectField(clazz, field, value ?: originalFields[tag])
        } catch (_: Throwable) {
        }
    }

    private fun applySdkInt(value: Int?) {
        try {
            if (!originalFields.containsKey(TAG_SDK_INT)) {
                originalFields[TAG_SDK_INT] = XposedHelpers.getStaticIntField(VERSION_CLASS, "SDK_INT")
            }
            val target = value ?: originalFields[TAG_SDK_INT] as? Int ?: return
            XposedHelpers.setStaticIntField(VERSION_CLASS, "SDK_INT", target)
        } catch (_: Throwable) {
        }
    }

    /** The `SUPPORTED_*_ABIS` fields are `String[]`, so the CSV from the profile has to be split. */
    private fun setStaticAbiList(field: String, csv: String?) {
        val tag = "Build.$field"
        try {
            if (!originalFields.containsKey(tag)) {
                originalFields[tag] = XposedHelpers.getStaticObjectField(Build::class.java, field)
            }
            val value = csv
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }
                ?.toTypedArray()
                ?: originalFields[tag]
            XposedHelpers.setStaticObjectField(Build::class.java, field, value)
        } catch (_: Throwable) {
        }
    }

    private fun hookSystemProperties(classLoader: ClassLoader) {
        val sysPropClass = XposedHelpers.findClassIfExists("android.os.SystemProperties", classLoader) ?: return
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val key = param.args.getOrNull(0) as? String ?: return
                val spoofed = lookupProp(key) ?: return
                val method = param.method as? Method
                param.result = when (method?.returnType) {
                    Boolean::class.javaPrimitiveType -> spoofed.equals("true", true) || spoofed == "1"
                    Int::class.javaPrimitiveType -> spoofed.toIntOrNull() ?: param.result
                    Long::class.javaPrimitiveType -> spoofed.toLongOrNull() ?: param.result
                    else -> spoofed
                }
            }
        }

        try {
            XposedHelpers.findAndHookMethod(sysPropClass, "get", String::class.java, hook)
            XposedHelpers.findAndHookMethod(sysPropClass, "get", String::class.java, String::class.java, hook)
            XposedHelpers.findAndHookMethod(sysPropClass, "getInt", String::class.java, Int::class.java, hook)
            XposedHelpers.findAndHookMethod(sysPropClass, "getLong", String::class.java, Long::class.java, hook)
            XposedHelpers.findAndHookMethod(sysPropClass, "getBoolean", String::class.java, Boolean::class.java, hook)
        } catch (_: Throwable) {
        }
    }

    /**
     * Spoofs `glGetString(GL_VENDOR)` / `glGetString(GL_RENDERER)` for the game's hardware checks.
     *
     * Unity and Unreal device-tier lookups on Android frequently read the GL strings from Java
     * through `GLES20.glGetString` (or GLES10/30/31/32 — the same static method exists in each
     * wrapper class), so a profile that changes `ro.product.model` but leaves the real GPU
     * answering "Adreno (TM) 640" is a model/GPU pair no shipped device ever had. That
     * disagreement is a stronger signal than either value alone, which is the same
     * both-channels-or-neither test [GetPropInterceptor] exists for.
     *
     * The native route (`eglQueryString`/NDK `glGetString`) cannot be reached from here — the
     * reference project hooks it with native PLT hooking under Zygisk, which LSPosed cannot
     * do — so this covers the Java-queried half of the check, which is the half a Java-based
     * tier lookup actually uses. Both keys are rendered together by `renderConfig` or not at
     * all, so the spoof can never answer a vendor from the profile beside a renderer from the
     * real silicon.
     */
    private fun hookGlStrings() {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                when (param.args.getOrNull(0) as? Int) {
                    GL_VENDOR -> props[LSPosedConfig.KEY_GPU_VENDOR]?.let { param.result = it }
                    GL_RENDERER -> props[LSPosedConfig.KEY_GPU_RENDERER]?.let { param.result = it }
                }
            }
        }
        // Each GLES wrapper class declares its own static glGetString, and a target may call any
        // of them. Every install is individually guarded: GLES30+ do not exist below API 18 and
        // a missing class is a miss, not an error.
        for (glClass in listOf("android.opengl.GLES10", "android.opengl.GLES20",
                "android.opengl.GLES30", "android.opengl.GLES31", "android.opengl.GLES32")) {
            try {
                XposedHelpers.findAndHookMethod(
                    XposedHelpers.findClassIfExists(glClass, null) ?: continue,
                    "glGetString", Int::class.javaPrimitiveType, hook
                )
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Spoofs `Display.getRefreshRate()` with the profile's rate, so a game whose frame-rate menu
     * is gated on the *display's* reported rate lists the tiers the spoofed panel claims.
     *
     * Cross-checked against the reference project's `processHook.spoofRefreshRate` (read in full):
     * it guards on the profile carrying a `refreshrate`, parses it as a float, and installs
     * `XposedBridge.hookAllMethods(Display.class, "getRefreshRate", …)` whose `afterHookedMethod`
     * forces the result — a `NumberFormatException` logged and swallowed. This port follows all
     * of that, with the divergences the app's architecture dictates:
     * * The reference hardcodes a compiled DEVICE_MAP of package → device profile; here the
     *   mapping is the user's own assignments and frame-rate ladders, and the rate travels as a
     *   rendered profile key ([LSPosedConfig.KEY_SCREEN_REFRESH_RATE]) like every other value.
     * * The reference's per-device `refreshrate` strings (165/120 Hz) are its own model-table
     *   data — reference model tables are not preset candidates, so our presets leave the field
     *   blank and the hook stays out of their rendered profiles entirely.
     * * The reference's device-spoofing half (`spoofDeviceProperties`, Build-field reflection) is
     *   not re-ported: [applyBuildFields] already owns that with original-capture restore.
     * * HEAD of the reference replaced all of this with a remote-JSON fetch (per-device profiles
     *   downloaded at runtime, its README's "no extra configuration"); that design is not ported —
     *   our profiles are local, user-owned, and offline by construction.
     *
     * Why an int in the profile when the hook forces a float: Android reports nominal rates as
     * floats (`119.999985`), but every game gate compares against user-facing tiers (60/90/120),
     * so the editor collects whole Hz and the spoof answers the value as given — no rounding of
     * the panel's own jitter is inherited by the spoof.
     *
     * `Display` resolves via `Display::class.java` rather than a classloader lookup because it is
     * a public framework class present in every target process; a hookAllMethods miss (no such
     * method — not possible today, but not our promise either) is caught and treated as a miss,
     * not an error, like every other hook here.
     */
    private fun hookDisplayRefreshRate() {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val rate = props[LSPosedConfig.KEY_SCREEN_REFRESH_RATE]?.toFloatOrNull() ?: return
                if (rate > 0f) param.result = rate
            }
        }
        try {
            XposedBridge.hookAllMethods(Display::class.java, "getRefreshRate", hook)
        } catch (_: Throwable) {
        }
    }

    private fun hookSettingsSecure(classLoader: ClassLoader) {        val settingsSecure = XposedHelpers.findClassIfExists("android.provider.Settings\$Secure", classLoader) ?: return
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.args.getOrNull(1) as? String != Settings.Secure.ANDROID_ID) return
                props["ANDROID_ID"]?.let { param.result = it }
            }
        }
        try {
            XposedHelpers.findAndHookMethod(
                settingsSecure, "getString", ContentResolver::class.java, String::class.java, hook
            )
        } catch (_: Throwable) {
        }
    }

    private fun hookTelephony(classLoader: ClassLoader) {
        val telephonyManager =
            XposedHelpers.findClassIfExists("android.telephony.TelephonyManager", classLoader) ?: return
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val key = TELEPHONY_KEYS[param.method.name] ?: return
                props[key]?.let { param.result = it }
            }
        }

        for (method in TELEPHONY_KEYS.keys) {
            // Most of these have a no-arg form plus a subscription-id overload.
            try {
                XposedHelpers.findAndHookMethod(telephonyManager, method, hook)
            } catch (_: Throwable) {
            }
            try {
                XposedHelpers.findAndHookMethod(telephonyManager, method, Int::class.java, hook)
            } catch (_: Throwable) {
            }
        }
    }

    private fun hookBuildMethods(classLoader: ClassLoader) {
        val buildClass = XposedHelpers.findClassIfExists("android.os.Build", classLoader) ?: return
        // Build.SERIAL has been hardcoded to "unknown" since API 26; getSerial() is the live read.
        try {
            XposedHelpers.findAndHookMethod(buildClass, "getSerial", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    props["ro.serialno"]?.let { param.result = it }
                }
            })
        } catch (_: Throwable) {
        }
    }

    private fun hookWebView(classLoader: ClassLoader) {
        val webSettings = XposedHelpers.findClassIfExists("android.webkit.WebSettings", classLoader) ?: return
        try {
            XposedHelpers.findAndHookMethod(
                webSettings, "getDefaultUserAgent", Context::class.java, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        props["webview.user_agent"]?.let { param.result = it }
                    }
                })
        } catch (_: Throwable) {
        }
    }

    /**
     * `java.lang.System` mirrors a couple of values apps use as device metadata, and they are read
     * straight from a snapshot taken at VM start — hooking `SystemProperties` does not cover them.
     */
    private fun hookJavaSystemProperties() {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                when (param.args.getOrNull(0) as? String) {
                    "os.arch" -> javaOsArch()?.let { param.result = it }
                    "http.agent" -> props["webview.user_agent"]?.let { param.result = it }
                }
            }
        }
        try {
            XposedHelpers.findAndHookMethod(System::class.java, "getProperty", String::class.java, hook)
            XposedHelpers.findAndHookMethod(
                System::class.java, "getProperty", String::class.java, String::class.java, hook
            )
        } catch (_: Throwable) {
        }
    }

    /** `os.arch` uses the JVM's own spelling, which is not the Android ABI name. */
    private fun javaOsArch(): String? {
        val abi = props["ro.product.cpu.abi"] ?: return null
        return when {
            abi.contains("arm64") -> "aarch64"
            abi.contains("armeabi") -> "arm"
            abi.contains("x86_64") -> "x86_64"
            abi.contains("x86") -> "x86"
            else -> abi
        }
    }

    /**
     * `persist.sys.locale` and `persist.sys.timezone` are read once during zygote start, so the
     * property hooks never see them — the defaults have to be replaced directly.
     */
    private fun hookLocaleAndTimeZone() {
        val localeHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                configuredLocale()?.let { param.result = it }
            }
        }
        try {
            XposedHelpers.findAndHookMethod(Locale::class.java, "getDefault", localeHook)
            XposedHelpers.findAndHookMethod(
                Locale::class.java, "getDefault", Locale.Category::class.java, localeHook
            )
        } catch (_: Throwable) {
        }

        try {
            XposedHelpers.findAndHookMethod(TimeZone::class.java, "getDefault", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val id = props["persist.sys.timezone"] ?: return
                    runCatching { TimeZone.getTimeZone(id) }.getOrNull()?.let { param.result = it }
                }
            })
        } catch (_: Throwable) {
        }

        try {
            XposedHelpers.findAndHookMethod(ZoneId::class.java, "systemDefault", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val id = props["persist.sys.timezone"] ?: return
                    runCatching { ZoneId.of(id) }.getOrNull()?.let { param.result = it }
                }
            })
        } catch (_: Throwable) {
        }
    }

    /** @return the profile's locale, or null when it is absent or not a usable language tag. */
    private fun configuredLocale(): Locale? {
        val tag = props["persist.sys.locale"] ?: return null
        val locale = runCatching { Locale.forLanguageTag(tag.replace('_', '-')) }.getOrNull() ?: return null
        return locale.takeIf { it.language.isNotEmpty() }
    }

    /** Widevine's `deviceUniqueId` is a common fingerprinting source that survives a factory reset. */
    private fun hookMediaDrm(classLoader: ClassLoader) {
        val mediaDrm = XposedHelpers.findClassIfExists("android.media.MediaDrm", classLoader) ?: return
        try {
            XposedHelpers.findAndHookMethod(
                mediaDrm, "getPropertyByteArray", String::class.java, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.args.getOrNull(0) as? String != MEDIA_DRM_UNIQUE_ID) return
                        decodeHex(props["device.media_drm_id"])?.let { param.result = it }
                    }
                })
        } catch (_: Throwable) {
        }
    }

    /** @return the decoded bytes, or null when [hex] is absent or not clean hex. */
    private fun decodeHex(hex: String?): ByteArray? {
        val clean = hex?.trim()?.removePrefix("0x")?.filterNot { it == ' ' || it == ':' } ?: return null
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        return runCatching {
            ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }.getOrNull()
    }

    /** Play Services classes: absent in most apps, so a miss here is normal rather than an error. */
    private fun hookAdvertisingId(classLoader: ClassLoader) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                props["device.gaid"]?.let { param.result = it }
            }
        }
        for (className in GAID_INFO_CLASSES) {
            val clazz = XposedHelpers.findClassIfExists(className, classLoader) ?: continue
            try {
                XposedHelpers.findAndHookMethod(clazz, "getId", hook)
            } catch (_: Throwable) {
            }
        }
    }

    private fun hookAppSetId(classLoader: ClassLoader) {
        val clazz = XposedHelpers.findClassIfExists(
            "com.google.android.gms.appset.AppSetIdInfo", classLoader
        ) ?: return
        try {
            XposedHelpers.findAndHookMethod(clazz, "getId", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    props["device.app_set_id"]?.let { param.result = it }
                }
            })
        } catch (_: Throwable) {
        }
        try {
            // A spoofed ID is per-app by construction, so report APP scope to match.
            XposedHelpers.findAndHookMethod(clazz, "getScope", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (props.containsKey("device.app_set_id")) param.result = APP_SET_SCOPE_APP
                }
            })
        } catch (_: Throwable) {
        }
    }

    /**
     * Resolves a system-property read against the profile, accepting the partition and legacy
     * spellings of each key that the rendered profile does not list verbatim.
     */
    private fun lookupProp(key: String): String? {        val current = props
        if (current.isEmpty()) return null
        current[key]?.let { return it }
        current["prop.$key"]?.let { return it }
        DIRECT_ALIASES[key]?.let { canonical -> current[canonical]?.let { return it } }
        return suffixAlias(key)?.let { current[it] }
    }

    /** Maps an OEM partition variant such as `ro.product.vendor.model` onto its canonical key. */
    private fun suffixAlias(key: String): String? = when {
        !key.startsWith("ro.") -> null
        key.endsWith(".build.fingerprint") -> "ro.build.fingerprint"
        key.endsWith(".build.version.incremental") -> "ro.build.version.incremental"
        key.endsWith(".build.version.release") -> "ro.build.version.release"
        key.endsWith(".build.version.sdk") -> "ro.build.version.sdk"
        key.endsWith(".build.version.security_patch") -> "ro.build.version.security_patch"
        key.endsWith(".build.id") -> "ro.build.id"
        !key.startsWith("ro.product.") -> null
        key.endsWith(".manufacturer") -> "ro.product.manufacturer"
        key.endsWith(".brand") -> "ro.product.brand"
        key.endsWith(".model") -> "ro.product.model"
        key.endsWith(".device") -> "ro.product.device"
        key.endsWith(".name") -> "ro.product.name"
        else -> null
    }

    private companion object {
        const val TAG = "CatsmokerLSP"
        const val OWN_PACKAGE = "com.catsmoker.app"
        const val TAG_SDK_INT = "VERSION.SDK_INT"
        const val MEDIA_DRM_UNIQUE_ID = "deviceUniqueId"

        /** `AppSetIdInfo.SCOPE_APP`, inlined because the GMS class is not on our classpath. */
        const val APP_SET_SCOPE_APP = 1

        /** GL enums, inlined so the hook file needs no android.opengl import. */
        const val GL_VENDOR = 0x1F00
        const val GL_RENDERER = 0x1F01

        val VERSION_CLASS: Class<*> = Build.VERSION::class.java

        /** Build field to the property keys that back it, most specific first. */
        val BUILD_FIELDS = listOf(
            "BRAND" to listOf("ro.product.brand"),
            "MANUFACTURER" to listOf("ro.product.manufacturer"),
            "MODEL" to listOf("ro.product.model"),
            "PRODUCT" to listOf("ro.product.name"),
            "DEVICE" to listOf("ro.product.device"),
            "BOARD" to listOf("ro.product.board"),
            "HARDWARE" to listOf("ro.hardware"),
            "FINGERPRINT" to listOf("ro.build.fingerprint"),
            "ID" to listOf("ro.build.id"),
            "DISPLAY" to listOf("ro.build.display.id", "ro.build.id"),
            "BOOTLOADER" to listOf("ro.bootloader"),
            "TYPE" to listOf("ro.build.type"),
            "TAGS" to listOf("ro.build.tags"),
            "SERIAL" to listOf("ro.serialno"),
            // Deprecated since API 21 but still read by native-loading and anti-emulator checks.
            "CPU_ABI" to listOf("ro.product.cpu.abi"),
            // SOC_* only exist from API 31; setStaticString swallows the miss on older platforms.
            "SOC_MODEL" to listOf("ro.soc.model"),
            "SOC_MANUFACTURER" to listOf("ro.soc.manufacturer")
        )

        /** `String[]` Build fields, backed by a comma-separated property. */
        val ABI_LIST_FIELDS = listOf(
            "SUPPORTED_ABIS" to "ro.product.cpu.abilist",
            "SUPPORTED_64_BIT_ABIS" to "ro.product.cpu.abilist64",
            "SUPPORTED_32_BIT_ABIS" to "ro.product.cpu.abilist32"
        )

        val VERSION_FIELDS = listOf(
            "RELEASE" to listOf("ro.build.version.release"),
            "RELEASE_OR_CODENAME" to listOf("ro.build.version.release"),
            "INCREMENTAL" to listOf("ro.build.version.incremental"),
            "SECURITY_PATCH" to listOf("ro.build.version.security_patch")
        )

        /** TelephonyManager method to the profile key that answers it. */
        val TELEPHONY_KEYS = mapOf(
            "getDeviceId" to "device.imei",
            "getImei" to "device.imei",
            "getMeid" to "device.meid",
            "getSubscriberId" to "device.imsi",
            "getSimSerialNumber" to "device.iccid",
            "getLine1Number" to "device.phone_number",
            "getNetworkOperatorName" to "gsm.operator.alpha",
            "getNetworkOperator" to "gsm.operator.numeric",
            "getSimOperatorName" to "gsm.sim.operator.alpha",
            "getSimOperator" to "gsm.sim.operator.numeric",
            "getSimCountryIso" to "gsm.sim.operator.iso-country",
            "getNetworkCountryIso" to "gsm.sim.operator.iso-country"
        )

        /** Keys apps read that a rendered profile never spells out. */
        val DIRECT_ALIASES = mapOf(
            "ro.build.product" to "ro.product.device",
            "ro.build.device" to "ro.product.device",
            "ro.boot.hardware" to "ro.hardware",
            "ro.boot.serialno" to "ro.serialno",
            "ro.build.version.release_or_codename" to "ro.build.version.release",
            "ro.build.version.release_or_preview_display" to "ro.build.version.release",
            "ro.product.cpu.abi2" to "ro.product.cpu.abi"
        )

        /**
         * Classes exposing a GAID `getId()`. The obfuscated one is the shape recent Play Services
         * builds ship; both are absent unless the app bundles the ads SDK.
         */
        val GAID_INFO_CLASSES = listOf(
            "com.google.android.gms.ads.identifier.AdvertisingIdClient\$Info",
            "com.google.android.gms.common.api.internal.zzx"
        )
    }
}
