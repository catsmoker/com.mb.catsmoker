package com.catsmoker.app.shared.data.model

/**
 * Contract shared by the app UI and the Xposed module that runs inside other apps' processes.
 *
 * The single vocabulary on both sides is system-property names — exactly what
 * [com.catsmoker.app.shared.data.repository.SpoofRepository.renderConfig] emits for Magisk — so
 * `Build.MODEL` is derived from `ro.product.model` rather than duplicated under a second name.
 */
object LSPosedConfig {
    const val PREFS_NAME = "lsposed_prefs"
    const val KEY_ENABLED = "lsposed_enabled"
    const val KEY_TARGET_PACKAGES = "lsposed_target_packages"
    const val KEY_DEVICE_PROPS = "lsposed_device_props"
    const val KEY_MAGISK_PROPS = "magisk_system_props"
    const val KEY_GLOBAL_ENABLED = "catsmoker_lsposed_enabled"
    const val KEY_GLOBAL_TARGET_PACKAGES_B64 = "catsmoker_lsposed_target_packages_b64"
    const val KEY_GLOBAL_DEVICE_PROPS_B64 = "catsmoker_lsposed_device_props_b64"

    /**
     * Every assigned package's profile in one document, base64'd into Settings.Global.
     *
     * Settings.Global is readable from any process, which the config ContentProvider is not:
     * package-visibility filtering on API 30+ hides our authority from apps that never declared
     * a `<queries>` entry for it, and those are precisely the apps being spoofed.
     */
    const val KEY_GLOBAL_PROFILES_B64 = "catsmoker_lsposed_profiles_b64"

    /** Broadcast that tells already-running targets to re-read their profile. */
    const val ACTION_CONFIG_CHANGED = "com.catsmoker.app.action.CONFIG_CHANGED"

    /** Key inside a rendered profile listing packages the user opted out of spoofing. */
    const val KEY_SAFE_MODE_PACKAGES = "safe_mode.packages"

    /**
     * Packages the module will never spoof, whatever the config channels say.
     *
     * From the references' banking blacklists — `GameUnlocker-main/common/config.json`
     * (`cpu_spoof.blacklist`) and `COPG-JSON/module/COPG.json` (`:blocked` entries). In those
     * Zygisk modules the blacklist is the *primary* gate, because Zygisk loads into every
     * process on the device; here the LSPosed scope array is the primary gate and no banking
     * app is in it, so this list is a backstop for the one path around it: a user who widens
     * the scope by hand in the LSPosed manager and assigns a profile. A banking app reading a
     * spoofed identity is a fraud-detection hazard out of proportion to anything spoofing it
     * could gain, so this list overrides an explicit assignment — and the module logs that it
     * did, rather than spoofing nothing silently.
     */
    val NEVER_SPOOF_PACKAGES = setOf(
        "com.bbl.mobilebanking",
        "com.sbi.YONO",
        "com.hdfcbank.payzapp",
        "com.csam.icici.bank.imobile"
    )

    /**
     * Key inside a rendered profile saying whether the profile's screen metrics should be applied.
     *
     * Named after the reference project's `ConfigManager.KEY_APPLY_SCREEN_METRICS` so both sides of
     * the config file speak the same vocabulary.
     */
    const val KEY_APPLY_SCREEN_METRICS = "device.apply_screen_metrics"

    /**
     * GL vendor string (`glGetString(GL_VENDOR)`) the profile should answer with, when set.
     *
     * Like `device.imei` this is one of our own profile keys, not a system property: there is no
     * `ro.` property behind it, so [SYSTEM_PROPERTY_PREFIXES] already keeps it out of the getprop
     * overlay and out of any channel that writes the real property store. Blank means "leave the
     * GPU strings alone" — a half-set GL identity (spoofed vendor, real renderer) is a stronger
     * fingerprint than neither, which is why the two keys are always rendered as a pair by the
     * editor.
     */
    const val KEY_GPU_VENDOR = "gpu.vendor"

    /** GL renderer string (`glGetString(GL_RENDERER)`), the partner of [KEY_GPU_VENDOR]. */
    const val KEY_GPU_RENDERER = "gpu.renderer"

    /**
     * Refresh rate in Hz the profile should answer `Display.getRefreshRate()` with, when set.
     *
     * Another of our own profile keys with no `ro.` property behind it — and there is no
     * system-property surface for refresh rate at all, so unlike the identity keys there is no
     * `getprop` half to disagree with: hooking the Display read is the entire reachable surface,
     * and the both-channels test is satisfied by construction. Zero (the editor's blank field)
     * means "leave the panel's real rate alone".
     *
     * The mechanism is the reference project's: `processHook.spoofRefreshRate` (read in full)
     * hooks every `Display.getRefreshRate` overload and forces the profile's rate, guarded on the
     * profile actually carrying one. What is deliberately not ported: its compiled per-package
     * DEVICE_MAP (assignments and frame-rate ladders own the package→profile mapping here), its
     * per-device `refreshrate` strings (reference model-table data, not preset candidates —
     * presets stay blank like their GPU strings), and HEAD's remote-JSON fetch design.
     */
    const val KEY_SCREEN_REFRESH_RATE = "screen.refresh_rate"

    /** Accepts the `1` / `true` spellings a rendered or hand-edited config can carry. */
    fun isFlagEnabled(value: String?): Boolean =
        value == "1" || value.equals("true", ignoreCase = true)

    /**
     * Prefixes that mark a rendered-profile entry as a real Android system property.
     *
     * A rendered profile is the vocabulary for every delivery channel, so it also carries keys only
     * this app's hooks understand — `device.imei`, `screen.width`, `safe_mode.packages`. Those have
     * no business reaching a channel that writes the real property store or a `getprop` dump, where
     * they are a giveaway rather than a disguise.
     *
     * This list lives here, not beside either consumer, because two channels must filter by the
     * same one. [com.catsmoker.app.features.spoofdevice.root.GetPropInterceptor] held it privately
     * and filtered correctly while the exported Magisk module wrote `renderConfig` output straight
     * into `system.prop` — so one identical profile was clean in-process and self-reporting once
     * flashed.
     */
    val SYSTEM_PROPERTY_PREFIXES = listOf(
        "ro.", "persist.", "gsm.", "net.", "dalvik.", "sys.", "vendor.", "debug."
    )

    /** True when [key] names a real system property rather than one of our own profile keys. */
    fun isSystemProperty(key: String): Boolean = SYSTEM_PROPERTY_PREFIXES.any(key::startsWith)

    /**
     * The real system properties of a rendered profile, in render order.
     *
     * Goes through [parseDeviceProps] so a channel publishing to the property store applies exactly
     * the comment, blank-line and malformed-line handling the hooks apply when reading the same
     * text back.
     */
    fun filterToSystemProperties(rendered: String?): Map<String, String> =
        parseDeviceProps(rendered).filterKeys(::isSystemProperty)

    fun parseTargetPackages(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split("\n", ",").asSequence().map { it.trim() }.filter { it.isNotEmpty() }.toCollection(LinkedHashSet())
    }

    fun parseDeviceProps(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            // A rendered profile's own comments carry no '=' and so were already dropped, but a
            // hand-edited config commenting a property out ("# ro.product.model=Pixel") would
            // otherwise be read back as a property literally named "# ro.product.model".
            if (trimmed.startsWith('#')) return@forEach
            val idx = trimmed.indexOf('=')
            if (idx <= 0 || idx == trimmed.length - 1) return@forEach
            val key = trimmed.substring(0, idx).trim()
            val value = trimmed.substring(idx + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) result[key] = value
        }
        return result
    }

    /**
     * Joins per-package rendered profiles into one document with `[package]` section headers.
     * Rendered profiles only ever contain `key=value` and `#` lines, so `[` cannot collide.
     */
    fun renderSections(configs: Map<String, String>): String {
        val sb = StringBuilder()
        for ((packageName, config) in configs) {
            if (packageName.isBlank() || config.isBlank()) continue
            sb.append('[').append(packageName).append("]\n")
            sb.append(config.trimEnd('\n')).append('\n')
        }
        return sb.toString()
    }

    /** Extracts one package's section from a [renderSections] document. */
    fun parseSection(raw: String?, packageName: String): String? {
        if (raw.isNullOrBlank() || packageName.isBlank()) return null
        val sb = StringBuilder()
        var inSection = false
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inSection = trimmed.substring(1, trimmed.length - 1) == packageName
                return@forEach
            }
            if (inSection) sb.append(line).append('\n')
        }
        return sb.toString().ifBlank { null }
    }
}
