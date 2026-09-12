package com.catsmoker.app.shared.data.repository

import android.content.Context
import android.content.Intent
import com.catsmoker.app.features.gamingtools.engine.DisplayRefreshRateProvider
import com.catsmoker.app.shared.data.model.DevicePreset
import com.catsmoker.app.shared.data.model.DeviceProfile
import com.catsmoker.app.shared.data.model.LSPosedConfig
import com.catsmoker.app.shared.util.DisplayMetricsProvider
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpoofRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val displayMetrics: DisplayMetricsProvider,
    // Refresh rate, not resolution: the two display facts have two owners, and this one picks the
    // rate-ladder winner — the split is by question, not by caller.
    private val displayRefreshRate: DisplayRefreshRateProvider
) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val storeFile = File(context.filesDir, "app_profiles.json")

    /**
     * The whole store as one snapshot.
     *
     * The collections are read-only on purpose. An earlier version handed out `MutableList` /
     * `MutableMap` and callers edited them in place, which meant the already-published snapshot
     * changed underneath the UI: the "new" state then compared equal to the old one, [state]
     * conflated the emission, and a freshly created profile only showed up when the screen was
     * recomposed for some other reason. Mutations now go through [update], which swaps in a whole
     * new snapshot — the same approach the reference project takes with `AppProfileStore.State`.
     */
    data class StoreData(
        val version: Int = 1,
        val profiles: List<ProfileEntry> = emptyList(),
        val assignments: Map<String, String> = emptyMap(),
        /**
         * Per-package frame-rate ladders, from `zygisk-Tweaker-main/module/config/tweaker.json`'s
         * per-game `tweaker` arrays: a game whose device table offers more than one tier gets a
         * *different* identity per tier, and the winner is picked against the panel's measured
         * peak by [pickRateCandidate] at publish time — not frozen into the store.
         *
         * The reference's `rate` field is an int or an int array (`90` or `[90,120]`). A pair here
         * carries one rate, and the array form is expressed by listing the same profile at several
         * rates — equivalent under the pick rule, because "highest tier the panel can honor, else
         * the lowest" lands on the same model either way.
         *
         * A non-empty ladder owns its package; the plain [assignments] entry is cleared when a
         * ladder is built, so exactly one of the two ever applies.
         */
        val rateAssignments: Map<String, List<RateCandidate>> = emptyMap(),
        val globalProperties: Map<String, String> = emptyMap()
    )

    /** One rung of a package's frame-rate ladder: the profile to show the game at [rateHz]. */
    data class RateCandidate(
        val profileId: String,
        val rateHz: Int
    )

    data class ProfileEntry(
        val id: String,
        val name: String,
        val profile: DeviceProfile
    )

    private val _state = MutableStateFlow<StoreData?>(null)

    /** Latest snapshot, or null until the first [loadData]. Collect this to follow every change. */
    val state: StateFlow<StoreData?> = _state.asStateFlow()

    /** Serialises read-modify-write so two concurrent updates cannot drop each other's changes. */
    private val mutex = Mutex()

    suspend fun loadData(): StoreData {
        _state.value?.let { return it }
        return mutex.withLock {
            // Another caller may have won the race to load while this one waited for the lock.
            _state.value ?: withContext(Dispatchers.IO) { readFromDisk() }.also { _state.value = it }
        }
    }

    /**
     * Applies [transform] to the current snapshot, persists the result, then publishes it.
     *
     * Writing before publishing means the UI never shows a change that failed to reach disk: an IO
     * failure propagates to the caller so it can report the real outcome instead of a silent one.
     */
    suspend fun update(transform: (StoreData) -> StoreData): StoreData {
        // Outside the lock: loadData takes it too, and Mutex is not reentrant. Its result also
        // doubles as the fallback below, so the locked section never touches the disk to read.
        val loaded = loadData()
        return mutex.withLock {
            val updated = transform(_state.value ?: loaded)
            withContext(Dispatchers.IO) { storeFile.writeText(gson.toJson(updated)) }
            _state.value = updated
            // Tells the Xposed module in every already-running target to re-read its profile.
            context.sendBroadcast(Intent(LSPosedConfig.ACTION_CONFIG_CHANGED))
            updated
        }
    }

    private fun readFromDisk(): StoreData {
        // Anything unusable — absent, truncated, hand-edited, or parsed but carrying no profile at
        // all — falls back to the seeded default rather than leaving the UI with nothing to select.
        val parsed = runCatching {
            if (!storeFile.exists()) return@runCatching null
            gson.fromJson(storeFile.readText(), StoreData::class.java)
                ?.takeIf { it.profiles.isNotEmpty() }
                ?.let(::repaired)
        }.getOrNull()
        return parsed ?: createDefaultData()
    }

    private fun createDefaultData(): StoreData {
        val defaultProfile = DeviceProfile(
            brand = "Google",
            manufacturer = "Google",
            model = "Pixel 8 Pro",
            productName = "husky",
            deviceCode = "husky",
            buildRelease = "14",
            buildSdk = 34
        ).apply { applyFallbacks() }
        
        return StoreData(
            profiles = listOf(
                ProfileEntry(UUID.randomUUID().toString(), "Default Profile", defaultProfile)
            )
        )
    }

    suspend fun getProfileForPackage(packageName: String): DeviceProfile? {
        val data = loadData()
        return resolveProfileForPackage(data, packageName, displayRefreshRate.getMaxHardwareRefreshRate())
    }

    /**
     * A preset holding this device's real values, for the "Current" entry in the preset picker.
     *
     * Screen metrics come from [DisplayMetricsProvider] — the same reader the Resolution Changer
     * uses — so the two screens can never quote different numbers for the same panel. When the
     * platform cannot report them the fields stay at zero, which [renderConfig] omits entirely
     * rather than claiming a resolution this device does not have.
     */
    fun createCurrentDevicePreset(): DevicePreset {
        val metrics = displayMetrics.current()
        val profile = DeviceProfile(
            brand = android.os.Build.BRAND,
            manufacturer = android.os.Build.MANUFACTURER,
            model = android.os.Build.MODEL,
            productName = android.os.Build.PRODUCT,
            deviceCode = android.os.Build.DEVICE,
            board = android.os.Build.BOARD,
            hardware = android.os.Build.HARDWARE,
            buildFingerprint = android.os.Build.FINGERPRINT,
            buildId = android.os.Build.ID,
            buildDisplayId = android.os.Build.DISPLAY,
            buildIncremental = android.os.Build.VERSION.INCREMENTAL,
            buildRelease = android.os.Build.VERSION.RELEASE,
            buildSdk = android.os.Build.VERSION.SDK_INT,
            // The reference derives this from the screen too (DevicePresetCatalog.isTablet), and a
            // profile that says "tablet" while reporting a phone-sized screen is self-contradictory.
            buildCharacteristics = if (metrics.isValid && metrics.isTablet) "tablet" else "nosdcard",
            screenWidth = if (metrics.isValid) metrics.widthPixels else 0,
            screenHeight = if (metrics.isValid) metrics.heightPixels else 0,
            screenDensity = if (metrics.isValid) metrics.densityDpi else 0,
            operatorAlpha = "", // Will be fetched via TelephonyManager if needed, or leave empty
            operatorNumeric = "",
            simOperatorAlpha = "",
            simOperatorNumeric = "",
            simCountryIso = "",
            timezone = java.util.TimeZone.getDefault().id,
            locale = java.util.Locale.getDefault().toLanguageTag(),
            bootloader = android.os.Build.BOOTLOADER
        ).apply {
            securityPatch = android.os.Build.VERSION.SECURITY_PATCH
            applyFallbacks()
        }

        val screenSummary = if (metrics.isValid) {
            "${metrics.sizeLabel} @ ${metrics.densityDpi}dpi (sw${metrics.smallestWidthDp}dp)"
        } else {
            "screen metrics unavailable"
        }

        return DevicePreset(
            id = "current_device",
            brandLabel = "Current",
            modelLabel = "Device",
            summary = "Hardware values from this physical device — $screenSummary",
            profile = profile
        )
    }

    /**
     * The device identities offered in the profile editor — **author-curated data, not a derived
     * list.** Each entry is a current-generation device the author verified twice: it is present in
     * the target game's own device data, and it is offered the highest available tier (120 FPS)
     * there. That verification is the selection criterion, and it cannot be reproduced from anything
     * in this repository.
     *
     * So treat these entries the way the app treats a value read back off the device: as a fact
     * someone measured. **Do not add, substitute, reorder or remove a model here** — not from
     * `referance/Magisk-Modules`, not from a community "working models" list, not from a device
     * database, and not because a model looks older or newer than its neighbours. Add a model only
     * when the author names that model.
     *
     * The reason the prohibition needs stating: a game's frame-rate ceiling is a lookup in a table
     * the game ships, keyed on `ro.product.model`, so a shipped Magisk unlocker's model strings look
     * like authoritative evidence. They are not — they are evidence of what was in *that game's*
     * table when *that module* shipped. Whitelists change with every game update, and this list is
     * checked against the current data. A reference module's table is a different list for a
     * different moment; substituting it here replaces verified data with stale hearsay.
     */
    fun getPresets(): List<DevicePreset> {
        val list = mutableListOf<DevicePreset>()
        list.add(createCurrentDevicePreset())

        list.addAll(listOf(
            DevicePreset(
                "pixel_11_pro", "Google", "Pixel 11 Pro", "Tensor G6 - Android 17",
                DeviceProfile(
                    brand = "Google", manufacturer = "Google", model = "GM45K",
                    productName = "lynx", deviceCode = "lynx", board = "lynx",
                    hardware = "google", boardPlatform = "gs401",
                    buildRelease = "17", buildSdk = 37,
                    buildId = "UP1A.260805.001", securityPatch = "2026-08-05"
                ).apply { applyFallbacks() }
            ),
            DevicePreset(
                "galaxy_s26_ultra", "Samsung", "Galaxy S26 Ultra", "Snapdragon 9 Gen 1 - Android 16",
                DeviceProfile(
                    brand = "samsung", manufacturer = "samsung", model = "SM-S948B",
                    productName = "titan", deviceCode = "titan", board = "titan",
                    hardware = "qcom", boardPlatform = "titan",
                    buildRelease = "16", buildSdk = 36,
                    buildId = "AP4A.260105.001", securityPatch = "2026-01-01",
                    screenWidth = 1440, screenHeight = 3120, screenDensity = 500
                ).apply { applyFallbacks() }
            ),
            DevicePreset(
                "xiaomi_17_ultra", "Xiaomi", "17 Ultra", "Snapdragon 9 Gen 1 - Android 16",
                DeviceProfile(
                    brand = "Xiaomi", manufacturer = "Xiaomi", model = "25128PNA1G",
                    productName = "xuanyuan", deviceCode = "xuanyuan", board = "titan",
                    hardware = "qcom", boardPlatform = "titan",
                    buildRelease = "16", buildSdk = 36,
                    buildId = "AQ4A.260912.001", securityPatch = "2026-01-01",
                    screenWidth = 1440, screenHeight = 3200, screenDensity = 522
                ).apply { applyFallbacks() }
            ),
            DevicePreset(
                "oneplus_15", "OnePlus", "15", "Snapdragon 8 Gen 4 - Android 15",
                DeviceProfile(
                    brand = "OnePlus", manufacturer = "OnePlus", model = "CPH2747",
                    productName = "OnePlus15", deviceCode = "OnePlus15", board = "OnePlus15",
                    hardware = "qcom", boardPlatform = "sun",
                    buildRelease = "15", buildSdk = 35,
                    buildId = "UKQ1.240917.001", securityPatch = "2025-02-05"
                ).apply { applyFallbacks() }
            ),
            DevicePreset(
                "tab_s11_ultra", "Samsung", "Galaxy Tab S11 Ultra", "Snapdragon 9 Gen 1 - Android 16",
                DeviceProfile(
                    brand = "samsung", manufacturer = "samsung", model = "SM-X930",
                    productName = "gts11ultra", deviceCode = "gts11ultra", board = "titan",
                    hardware = "qcom", boardPlatform = "titan",
                    buildRelease = "16", buildSdk = 36,
                    buildId = "AP4A.260105.001", securityPatch = "2026-02-01",
                    buildCharacteristics = "tablet",
                    screenWidth = 1848, screenHeight = 2960, screenDensity = 320
                ).apply { applyFallbacks() }
            ),

            // The three gaming handsets below carry a provenance the five above do not, and the
            // difference is worth stating rather than smoothing over: the author supplied the
            // `model` of each — and only the `model` — having verified it in the game's own device
            // data at the 120 FPS tier. `model` is also the only field the whitelist lookup reads,
            // and the only field the Magisk channel flashes (`MagiskModuleBuilder.MODEL_KEYS`), so
            // the part that has to be right is the part that came from the author.
            //
            // Everything else here is a supporting value chosen to make the identity coherent, not
            // a build.prop anyone read off the physical phone. `hardware = "qcom"` is a fact about
            // the silicon (all three are Snapdragon). The release/SDK/buildId/patch pairs are the
            // ones already used by their neighbours in this list for the same Android version, so
            // the list stays internally consistent instead of gaining three invented conventions.
            // The screen sizes are the published panel resolutions with the density computed from
            // the diagonal. `deviceCode`, `productName`, `board`, `boardPlatform` and the
            // fingerprint are deliberately left to `applyFallbacks()` to derive from the model: a
            // derived `asusai2501b` is visibly derived, where a hand-written guess at the real
            // codename would read as though someone had confirmed it. Replace any of these with
            // real values if you have the device's build.prop — none of them is load-bearing.
            DevicePreset(
                "iqoo_15", "iQOO", "15", "Snapdragon 8 Elite Gen 5 - Android 16",
                DeviceProfile(
                    brand = "iQOO", manufacturer = "vivo", model = "I2501",
                    hardware = "qcom",
                    buildRelease = "16", buildSdk = 36,
                    buildId = "AP4A.260105.001", securityPatch = "2026-01-01",
                    screenWidth = 1440, screenHeight = 3168, screenDensity = 510
                ).apply { applyFallbacks() }
            ),
            DevicePreset(
                "rog_phone_9_pro", "ASUS", "ROG Phone 9 Pro", "Snapdragon 8 Elite - Android 15",
                DeviceProfile(
                    brand = "asus", manufacturer = "asus", model = "ASUSAI2501B",
                    hardware = "qcom",
                    buildRelease = "15", buildSdk = 35,
                    buildId = "UKQ1.240917.001", securityPatch = "2025-02-05",
                    screenWidth = 1080, screenHeight = 2400, screenDensity = 400
                ).apply { applyFallbacks() }
            ),
            DevicePreset(
                "redmagic_10s_pro", "REDMAGIC", "10S Pro", "Snapdragon 8 Elite - Android 15",
                DeviceProfile(
                    brand = "nubia", manufacturer = "nubia", model = "NX789J",
                    hardware = "qcom",
                    buildRelease = "15", buildSdk = 35,
                    buildId = "UKQ1.240917.001", securityPatch = "2025-02-05",
                    screenWidth = 1216, screenHeight = 2688, screenDensity = 440
                ).apply { applyFallbacks() }
            )
        ))
        return list
    }

    // Helper to generate the raw config string for ConfigProvider
    fun renderConfig(profile: DeviceProfile, globalProps: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append("# Catsmoker generated profile\n\n")

        fun addProp(key: String, value: String?) {
            if (!value.isNullOrBlank()) {
                sb.append("$key=$value\n")
            }
        }

        // Identity & Partitions
        val partitions = listOf("product", "system", "system_ext", "vendor", "vendor_dlkm", "odm", "bootimage", "system_dlkm")
        
        addProp("ro.product.brand", profile.brand)
        addProp("ro.product.manufacturer", profile.manufacturer)
        addProp("ro.product.model", profile.model)
        addProp("ro.product.name", profile.productName)
        addProp("ro.product.device", profile.deviceCode)
        addProp("ro.product.board", profile.board)
        addProp("ro.hardware", profile.hardware)
        addProp("ro.board.platform", profile.boardPlatform)

        partitions.forEach { p ->
            addProp("ro.product.$p.brand", profile.brand)
            addProp("ro.product.$p.manufacturer", profile.manufacturer)
            addProp("ro.product.$p.model", profile.model)
            addProp("ro.product.$p.name", profile.productName)
            addProp("ro.product.$p.device", profile.deviceCode)
        }

        // Build & Fingerprints
        addProp("ro.build.fingerprint", profile.buildFingerprint)
        addProp("ro.build.id", profile.buildId)
        addProp("ro.build.display.id", profile.buildDisplayId)
        addProp("ro.build.version.incremental", profile.buildIncremental)
        addProp("ro.build.version.release", profile.buildRelease)
        addProp("ro.build.version.sdk", if (profile.buildSdk > 0) profile.buildSdk.toString() else "")
        addProp("ro.build.version.security_patch", profile.securityPatch)
        addProp("ro.build.description", profile.buildDescription)
        addProp("ro.build.flavor", profile.buildFlavor)
        addProp("ro.build.product", profile.buildProduct)
        addProp("ro.build.characteristics", profile.buildCharacteristics)

        // Keep Build.TYPE/Build.TAGS consistent with the fingerprint: a "release-keys"
        // fingerprint next to a leftover "test-keys" tag is exactly what detection looks for.
        val (buildType, buildTags) = splitFingerprintSuffix(profile.buildFingerprint)
        addProp("ro.build.type", buildType)
        addProp("ro.build.tags", buildTags)

        // CPU & SoC
        addProp("ro.product.cpu.abi", profile.cpuAbi)
        addProp("ro.product.cpu.abilist", profile.cpuAbiList)
        addProp("ro.product.cpu.abilist64", profile.cpuAbiList64)
        addProp("ro.product.cpu.abilist32", profile.cpuAbiList32)
        addProp("ro.soc.model", profile.socModel)
        addProp("ro.soc.manufacturer", profile.socManufacturer)

        // GPU identity for the GL-string hooks. Rendered as a pair or not at all — the two keys
        // are only ever set together by the editor, and a lone vendor beside a real renderer is
        // a stronger mismatch signal than neither (see DeviceProfile.applyFallbacks).
        if (profile.gpuVendor.isNotBlank() && profile.gpuRenderer.isNotBlank()) {
            addProp(LSPosedConfig.KEY_GPU_VENDOR, profile.gpuVendor)
            addProp(LSPosedConfig.KEY_GPU_RENDERER, profile.gpuRenderer)
        }
        
        partitions.forEach { p ->
            addProp("ro.$p.build.fingerprint", profile.buildFingerprint)
        }
        
        // Screen
        addProp("screen.width", if (profile.screenWidth > 0) profile.screenWidth.toString() else "")
        addProp("screen.height", if (profile.screenHeight > 0) profile.screenHeight.toString() else "")
        addProp("screen.density", if (profile.screenDensity > 0) profile.screenDensity.toString() else "")
        // Rendered as one of our own profile keys (never a system property), read only by the
        // Display hook — zero means the hook is not installed for this profile at all, the same
        // blank-means-off contract the GPU strings follow.
        addProp(
            LSPosedConfig.KEY_SCREEN_REFRESH_RATE,
            if (profile.screenRefreshRate > 0) profile.screenRefreshRate.toString() else ""
        )
        
        // Network
        addProp("gsm.operator.alpha", profile.operatorAlpha)
        addProp("gsm.operator.numeric", profile.operatorNumeric)
        addProp("gsm.sim.operator.alpha", profile.simOperatorAlpha.ifBlank { profile.operatorAlpha })
        addProp("gsm.sim.operator.numeric", profile.simOperatorNumeric.ifBlank { profile.operatorNumeric })
        addProp("gsm.sim.operator.iso-country", profile.simCountryIso)
        addProp("persist.sys.timezone", profile.timezone)
        addProp("persist.sys.locale", profile.locale)
        
        // WebView
        addProp("webview.user_agent", profile.userAgent)
        
        // IDs
        addProp("ro.serialno", profile.serialNumber)
        addProp("ro.bootloader", profile.bootloader)
        addProp("ANDROID_ID", profile.androidId)
        addProp("device.imei", profile.imei)
        addProp("device.meid", profile.meid)
        addProp("device.imsi", profile.subscriberId)
        addProp("device.iccid", profile.simSerialNumber)
        addProp("device.phone_number", profile.phoneNumber)
        addProp("device.gaid", profile.gaid)
        addProp("device.gsf_id", profile.gsfId)
        addProp("device.media_drm_id", profile.mediaDrmId)
        addProp("device.app_set_id", profile.appSetId)
        
        // Global/Extra
        if (globalProps.isNotEmpty()) {
            sb.append("\n# Global Settings\n")
            globalProps.forEach { (k, v) -> 
                if (v.isNotBlank()) sb.append("$k=$v\n")
            }
        }
        
        return sb.toString()
    }

    /**
     * Pulls `type` and `tags` out of a fingerprint's `…:<type>/<tags>` tail.
     *
     * @return the parsed pair, falling back to the stock `user` / `release-keys` for a fingerprint
     *   that does not carry the suffix.
     */
    private fun splitFingerprintSuffix(fingerprint: String): Pair<String, String> {
        val tail = fingerprint.substringAfterLast(':', "")
        val type = tail.substringBefore('/', "")
        val tags = tail.substringAfter('/', "")
        return Pair(
            type.ifBlank { "user" },
            tags.ifBlank { "release-keys" }
        )
    }

    companion object {
        /**
         * Picks which ladder rung the panel earns, from `zygisk-Tweaker-main`'s stated selection
         * rule (README, "Frequently Asked Questions"): *"If `refresh_rate` is set to 144, but the
         * disguised device models do not support 144 … it will automatically lower the frame rate
         * to the closest supported value. For example, if there are two device models, one
         * supports 120 and the other supports 90, then 144 will be lowered to 120."*
         *
         * That is: **the highest tier the panel can honor, stepping down to the closest one
         * below.** The module's native code is shipped only as compiled `.so` files, so the
         * agreement asserted here is with the README's rule and the `tweaker.json` shape (both
         * read in full), not with code nobody has read.
         *
         * The panel peak is rounded to whole Hz before comparing, because Android reports
         * nominal 120 Hz panels as modes like `119.999985` — an unrounded compare would step a
         * 120 Hz rung down to 90 on a panel that is, for every purpose the game cares about, a
         * 120 Hz panel.
         *
         * Two cases the README does not cover, decided here and stated rather than smuggled in:
         * * A panel below every rung (a 60 Hz panel with 90/120 rungs) gets the **lowest** rung.
         *   The rule never raises a rung above what the panel reported, and "the closest
         *   supported value" from below is still the lowest one. The game then offers its 90 FPS
         *   tier against a panel that cannot render it — the same outcome the reference's own
         *   `fps_unlock` switch produces on such a panel.
         * * A tie (two rungs at the same rate) keeps the first in list order, i.e. the order the
         *   user built the ladder in.
         *
         * Divergences from the reference, deliberate:
         * * It freezes the detected rate into `settings.refresh_rate` once, at install (its
         *   `customize.sh` reads `dumpsys SurfaceFlinger` / `mDefaultPeakRefreshRate`, falling
         *   back to a hard-coded 90). This app re-reads the panel's measured peak
         *   ([DisplayRefreshRateProvider.getMaxHardwareRefreshRate], supported-modes scan with a
         *   60 Hz floor) at every publish and every profile lookup, so a foldable's inner panel
         *   or a changed default display re-picks without reinstalling anything.
         * * No manual override of the detected rate — the point here is that the panel's own
         *   number decides, and an override would let a user ask for a tier the hardware cannot
         *   render while the UI still said "auto-detected".
         *
         * @return the winning rung, or null when the ladder is empty — never a fabricated pick.
         */
        fun pickRateCandidate(candidates: List<RateCandidate>, panelPeakHz: Float): RateCandidate? {
            if (candidates.isEmpty()) return null
            val peak = panelPeakHz.roundToInt()
            return candidates.filter { it.rateHz <= peak }.maxByOrNull { it.rateHz }
                ?: candidates.minByOrNull { it.rateHz }
        }

        /**
         * The profile a package is actually spoofed with: its frame-rate ladder's winner when it
         * has one, otherwise its plain assignment.
         *
         * Rungs whose profile has since been deleted are ignored rather than resolved to nothing —
         * a ladder that lost its winner falls back to whatever rungs remain, and only a ladder
         * with no survivors at all falls through to the plain assignment.
         */
        fun resolveProfileForPackage(
            data: StoreData,
            packageName: String,
            panelPeakHz: Float
        ): DeviceProfile? {
            val ladder = data.rateAssignments[packageName].orEmpty()
                .filter { candidate -> data.profiles.any { it.id == candidate.profileId } }
            if (ladder.isNotEmpty()) {
                val winner = pickRateCandidate(ladder, panelPeakHz) ?: return null
                return data.profiles.firstOrNull { it.id == winner.profileId }?.profile
            }
            val profileId = data.assignments[packageName] ?: return null
            return data.profiles.firstOrNull { it.id == profileId }?.profile
        }

        /**
         * Fills in the field defaults Gson skips.
         *
         * Gson bypasses the constructor, so a store saved before [StoreData.rateAssignments]
         * existed deserializes that field as null despite the non-null type — every reader would
         * then have to defend against a value the type promises cannot happen. Repairing it once,
         * at the single place JSON enters the app, keeps the promise true everywhere else.
         */
        internal fun repaired(parsed: StoreData): StoreData {
            val legacyRates: Map<String, List<RateCandidate>>? = parsed.rateAssignments
            return if (legacyRates == null) parsed.copy(rateAssignments = emptyMap()) else parsed
        }
    }
}
