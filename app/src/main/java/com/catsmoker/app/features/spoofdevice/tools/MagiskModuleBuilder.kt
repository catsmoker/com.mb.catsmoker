package com.catsmoker.app.features.spoofdevice.tools

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds the flashable Magisk module for a spoof profile, entirely in code.
 *
 * This used to be a copy of `assets/magisk/`: [ZipOutputStream] walked the asset tree and swapped
 * one member's bytes on the way past. Two things were wrong with that, and both are why the
 * skeleton is generated here now.
 *
 * The first was silent coupling. The exported ZIP only ever contained what the asset walk happened
 * to enumerate, so `system.prop` — the one member carrying the spoof — existed in the archive only
 * because a placeholder file with a hardcoded `OPD2415` model sat in `assets/` to be overwritten.
 * Deleting what looked like dead placeholder content would have shipped a module that installs
 * cleanly and spoofs nothing.
 *
 * The second was `module.prop` going stale. It was checked in with `version=1.0.0 versionCode=5`
 * and stayed there while the app moved to 2.0.0 / 6, so every generated module misreported itself
 * in the Magisk manager. Both fields now come from the caller's real build values.
 *
 * What this deliberately does *not* do is carry the profile. It flashes [MODEL_KEYS] and
 * [KEY_PIXELPROPS_GAME] and nothing else, however much the caller hands it — see [MODEL_KEYS] for
 * why a narrow allowlist is the safe shape for this one channel, and [omittedKeys] for how the rest
 * of the profile is reported rather than silently dropped.
 *
 * ## Cross-checked against `referance/Magisk-Modules`
 *
 * Three shipped FPS/spoof modules sit in the workspace, and the members here answer a gap each one
 * of them had already closed:
 *
 * - `Unlocker-p4/customize.sh` prints `getprop ro.product.model` — the device's *real* model — so a
 *   flashing user sees what is being replaced. Ours printed only the spoof, which is the half you
 *   already know. Its README is also the one that names `MagiskHidePropsConf` as incompatible; the
 *   installer now looks for that overlap instead of leaving it in prose nobody re-reads.
 * - `HunterX-Reborn-II/common/service.sh` waits for the boot to settle (`vendor.post_boot.parsed`)
 *   before it trusts anything it reads. [serviceSh] is the same idea turned to this module's
 *   purpose: read the spoof back and report what the device actually did.
 * - `No-Fps-Cap` is the counter-example — its whole payload is three Samsung `privapp-permissions`
 *   XMLs and its own description says "Exynos only". Nothing in it generalises, which is why no
 *   `system/` overlay is generated here.
 *
 * [updateBinary]'s KernelSU/APatch branch was cross-checked against a fourth,
 * `FPS-Limitations-Patcher-v3.1`, which carried `/data/adb/ksu/bin` and `/data/adb/ap/bin` on
 * `PATH` and shared `KSU`/`KSU_VER_CODE` with its installer. **That module is no longer in
 * `referance/`** — it was removed mid-2026-08 and the folder is not under version control, so the
 * citation is unverifiable now. The branch stands on its own: it only tests two paths for existence
 * and sources neither, so nothing about it depends on a layout that tree would have confirmed.
 *
 * The multiple-root-manager refusal in [customizeSh] is cross-checked against a fifth,
 * `spoofdevice/COPG-JSON/module/customize.sh`'s `check_zygisk()`: the same three managers, counted
 * with `command -v` on each one's daemon, and the same refuse-when-more-than-one rule. Two of its
 * branches are deliberately not ported. Its `exit 1` abort — this script is *sourced* by
 * `install_module`, so the refusal neutralizes the module instead of killing the installer — and
 * its Zygisk-activity gating, which protects the native injection that module ships and this one
 * does not.
 */
object MagiskModuleBuilder {

    /**
     * Module identity in the Magisk manager.
     *
     * Held constant on purpose: Magisk keys an installed module by `id`, so changing this would
     * make the next generated ZIP install *alongside* a user's existing module instead of replacing
     * it, leaving two modules writing the same properties.
     */
    const val MODULE_ID = "fpsunlocker"
    const val MODULE_NAME = "Mobile FPS Unlocker"
    const val MODULE_AUTHOR = "catsmoker"

    /** Matches the floor `update-binary` enforces via `MAGISK_VER_CODE`. */
    const val MIN_MAGISK_VER_CODE = 20400

    /** Where [serviceSh] leaves its per-boot reading, beside the module it describes. */
    const val VERIFY_LOG_NAME = "verify.log"

    /**
     * The one binary member a module can carry: this app's own APK, which [serviceSh] installs once
     * after boot when the app is absent. Named without a `com.catsmoker.app` prefix because it sits
     * beside `system.prop` and `verify.log` in the module directory, where the other names are
     * human-facing too.
     */
    const val COMPANION_APK_NAME = "CatSmoker.apk"

    /**
     * This app's own package, spelled out rather than read from `BuildConfig` so this object stays
     * free of build-generated classes — it is pure JVM and unit-tested as such. [serviceSh] uses it
     * to detect a present companion app; if the application id ever changes, this must change with
     * it or the module would reinstall over every boot's "already present" check.
     */
    const val APPLICATION_ID = "com.catsmoker.app"

    /**
     * The only device-identity keys a generated `system.prop` carries — and the whole of the spoof
     * this channel delivers.
     *
     * ## Why this is a short allowlist and not a filter
     *
     * This channel used to flash whatever passed
     * [com.catsmoker.app.shared.data.model.LSPosedConfig.SYSTEM_PROPERTY_PREFIXES], which for a full
     * profile is forty-odd properties: every partition twin of brand/manufacturer/name/device, five
     * build fingerprints, `ro.build.id`/`.type`/`.tags`/`.description`, `ro.hardware`,
     * `ro.board.platform`, `ro.product.cpu.abi*`, `ro.soc.*`, `ro.serialno`, `ro.bootloader`,
     * `persist.sys.locale`, `persist.sys.timezone`.
     *
     * That set is safe **in-process**, where a hook answers one app and the real device is untouched.
     * It is not safe here. `system.prop` is consumed by `resetprop` at post-fs-data — before the
     * framework starts — and rewrites those keys for *everything* on the device: vendor HALs, init
     * `on property` triggers, the ABI list the runtime picks its libraries from. A `ro.hardware`,
     * `ro.board.platform` or `ro.product.cpu.abi` that disagrees with the actual silicon is not a
     * detection risk, it is a device that does not finish booting, and the fix is deleting the module
     * from recovery. `ro.build.fingerprint` and `ro.serialno` are read by vendor services with no
     * obligation to tolerate a value that never shipped.
     *
     * None of it was buying anything, either. A game's frame-rate table is keyed on the model, so the
     * model *is* the payload — which is also all `referance/Magisk-Modules/Unlocker-p4` writes, and
     * the counter-example `HunterX-Reborn-II` is the one that flashed a pile of extra keys.
     *
     * So this channel's rule is the inverse of the app's usual completeness instinct: **fewer
     * properties is strictly better.** A key added here trades boot-loop risk for nothing unless
     * something is known to read it. The goal is not to impersonate a phone; it is to hand the game
     * the minimum model identity its whitelist looks up, leaving the real Android environment intact.
     *
     * The in-process channels are unchanged and still carry the whole profile — they cannot cost a
     * boot — so this narrows one delivery path, not what the app spoofs.
     *
     * The four keys are the model and the partition twins an app can reach with `getprop`. Android
     * resolves `Build.MODEL` from `ro.product.model` alone, so the twins are here for agreement
     * rather than for effect: a `getprop ro.product.vendor.model` still reading the real phone beside
     * a spoofed `ro.product.model` is the kind of disagreement
     * [com.catsmoker.app.features.spoofdevice.root.GetPropInterceptor] exists to prevent.
     */
    val MODEL_KEYS: List<String> = listOf(
        "ro.product.model",
        "ro.product.odm.model",
        "ro.product.system.model",
        "ro.product.vendor.model"
    )

    /**
     * Switch read by the PixelProps family of Magisk modules to decide whether *they* should present
     * this device as a Pixel to games.
     *
     * Not a framework property: nothing in Android reads it, and on a device with no such module
     * installed it is inert — "no effect" rather than "failed". It is set because where one *is*
     * installed, its game spoof and this module's model both reach the same games, `resetprop` is
     * last-one-wins, and the loser is silent. Deriving it from the selected preset makes the two
     * agree instead of fighting:
     *
     * - Pixel preset → `true`, so its Pixel work runs alongside ours.
     * - anything else → `false`, because a profile presenting a Galaxy or a Xiaomi cannot also be a
     *   Pixel, and a half-Pixel identity is a stronger signal than no spoof at all.
     *
     * It is the one non-model key flashed, so the installer names it and its value explicitly.
     */
    const val KEY_PIXELPROPS_GAME = "persist.sys.pixelprops.game"

    /**
     * @param systemProperties the profile's full rendered system properties. Only [MODEL_KEYS] and
     *   [KEY_PIXELPROPS_GAME] are flashed from it; the rest is reported by [omittedKeys] and left to
     *   the in-process channels. Passing more cannot make this module write more.
     * @param versionName mirrored into `module.prop` `version`; pass `BuildConfig.VERSION_NAME`.
     * @param versionCode mirrored into `module.prop` `versionCode`; pass `BuildConfig.VERSION_CODE`.
     * @param profileName shown by the installer so a user flashing a months-old ZIP can tell which
     *   profile it holds.
     * @param spoofedModel the profile's `ro.product.model`, used in the installer banner and the
     *   module description — and as the fallback source of the model itself when [systemProperties]
     *   carries no `ro.product.model`.
     * @param companionApk this app's own APK, optionally bundled so the module can restore it after
     *   the app is gone. Flashed as one binary ZIP member ([COMPANION_APK_NAME]) and installed once
     *   by [serviceSh] after boot, only when this package is absent, then deleted — one-shot
     *   delivery, not a boot-loop reinstall. Null omits the member. Being a `ByteArray` in a data
     *   class, it compares by identity; nothing compares specs for equality.
     */
    data class ModuleSpec(
        val systemProperties: Map<String, String>,
        val versionName: String,
        val versionCode: Int,
        val profileName: String,
        val spoofedModel: String,
        val companionApk: ByteArray? = null
    )

    /**
     * Writes the complete module to [out]. The caller owns closing [out].
     *
     * @return the ZIP entry paths written, in order — the honest record of what the archive holds,
     *   rather than an assumption that a fixed set of files went in.
     */
    fun write(out: OutputStream, spec: ModuleSpec): List<String> {
        val entries = linkedMapOf(
            "META-INF/com/google/android/update-binary" to updateBinary(),
            "META-INF/com/google/android/updater-script" to UPDATER_SCRIPT,
            "module.prop" to moduleProp(spec),
            "system.prop" to systemProp(spec),
            "customize.sh" to customizeSh(spec),
            "service.sh" to serviceSh(spec),
            "action.sh" to actionSh(),
            "webroot/index.html" to webIndexHtml(),
            "webroot/cgi-bin/api.sh" to webApiSh()
        )
        // Not closed here: closing a ZipOutputStream closes the stream under it, and on the
        // MediaStore path that stream belongs to the caller's `use` block.
        val zip = ZipOutputStream(out)
        for ((path, body) in entries) {
            zip.putNextEntry(ZipEntry(path))
            zip.write(body.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        // The one binary member: written raw, not through the UTF-8 loop above, which would
        // corrupt bytes that are not valid UTF-8 — and an APK is full of them.
        if (spec.companionApk != null) {
            zip.putNextEntry(ZipEntry(COMPANION_APK_NAME))
            zip.write(spec.companionApk)
            zip.closeEntry()
        }
        zip.finish()
        return entries.keys.toList() + listOfNotNull(
            spec.companionApk?.let { COMPANION_APK_NAME }
        )
    }

    /**
     * Exactly what the generated `system.prop` will contain: [MODEL_KEYS] set to the profile's model,
     * then [KEY_PIXELPROPS_GAME].
     *
     * Empty when the profile has no model at all — there is nothing for this channel to deliver, and
     * a ZIP that installs cleanly and changes nothing is the failure this generator was written to
     * end. The caller reports that instead of shipping it.
     */
    fun bootSafeProperties(spec: ModuleSpec): Map<String, String> {
        val model = modelOf(spec)
        if (model.isBlank()) return emptyMap()
        val props = linkedMapOf<String, String>()
        for (key in MODEL_KEYS) props[key] = model
        props[KEY_PIXELPROPS_GAME] = isPixelTarget(spec).toString()
        return props
    }

    /**
     * The profile's own keys this channel deliberately does not flash, in render order.
     *
     * Named, not silently discarded. A user who set a locale, a fingerprint or an SoC in the profile
     * is entitled to know the flashed module carries none of them and that the in-process channels
     * still do — the same reason the Safe Mode caveat is stated in the file rather than assumed. This
     * is what the installer counts and what `system.prop` lists in its header.
     */
    fun omittedKeys(spec: ModuleSpec): List<String> {
        val flashed = bootSafeProperties(spec).keys
        return spec.systemProperties.keys.filterNot { it in flashed }
    }

    /**
     * Whether the selected preset is a Google Pixel, which is what [KEY_PIXELPROPS_GAME] turns on.
     *
     * Brand and manufacturer are checked first because a Pixel's `ro.product.model` is a code
     * (`GM45K`), not the word "Pixel" — keying off the model alone would read every Pixel preset as
     * non-Pixel and flash `false`. The model prefix is the fallback for a hand-written profile that
     * set a marketing name and left brand empty.
     */
    fun isPixelTarget(spec: ModuleSpec): Boolean {
        val identity = listOf("ro.product.brand", "ro.product.manufacturer")
            .mapNotNull { spec.systemProperties[it]?.trim() }
        if (identity.any { it.equals("google", ignoreCase = true) }) return true
        return modelOf(spec).startsWith("Pixel", ignoreCase = true)
    }

    /** The model to flash: the rendered property, or [ModuleSpec.spoofedModel] when it is absent. */
    private fun modelOf(spec: ModuleSpec): String = singleLine(
        spec.systemProperties["ro.product.model"]?.takeIf { it.isNotBlank() } ?: spec.spoofedModel
    )

    // ------------------------------------------------------------------ members

    /**
     * `system.prop`, which Magisk feeds to `resetprop` at post-fs-data.
     *
     * Carries [bootSafeProperties] only — see [MODEL_KEYS] for why this file stays as short as it
     * does. Two facts ship inside it rather than in the screen that generated it, because a flashed
     * module outlives that screen by months: it is device-global, so `safe_mode.packages` cannot be
     * honoured here even when the profile carries one; and every profile key left out is listed, so
     * the omission is auditable from the file itself.
     */
    private fun systemProp(spec: ModuleSpec): String = buildString {
        val flashed = bootSafeProperties(spec)
        val omitted = omittedKeys(spec)
        append("# CatSmoker spoof profile: ").append(singleLine(spec.profileName)).append('\n')
        append("# Generated by CatSmoker ").append(singleLine(spec.versionName)).append('\n')
        append("#\n")
        append("# Model identity only, on purpose. resetprop applies this file to the whole\n")
        append("# device before the framework starts, so a key that disagrees with the real\n")
        append("# silicon -- ro.hardware, ro.board.platform, ro.product.cpu.abi -- costs a boot,\n")
        append("# not just a detection. A game's FPS table reads the model, so the model is all\n")
        append("# this needs to carry. Fewer keys here is strictly safer.\n")
        append("#\n")
        append("# Applies device-wide. Per-app Safe Mode exclusions cannot apply to a\n")
        append("# system.prop and are not present in this file.\n")
        if (omitted.isNotEmpty()) {
            append("#\n")
            append("# Held back for boot safety -- ").append(omitted.size)
            append(if (omitted.size == 1) " profile key. The" else " profile keys. The")
            append(" in-process hooks\n")
            append("# (LSPosed module, config provider) still apply these per-app; this file\n")
            append("# deliberately does not:\n")
            for (key in omitted) append("#   ").append(key).append('\n')
        }
        append('\n')
        for ((key, value) in flashed) {
            append(key).append('=').append(value).append('\n')
        }
    }

    /**
     * The identity Magisk keys the module by, shared with [serviceSh].
     *
     * [serviceSh] rewrites `module.prop` after every boot to report what applied, so it has to
     * reproduce these five lines exactly. Generating both from here is what keeps a bumped
     * `versionCode` from silently reverting to a stale one on the first reboot.
     */
    private fun identityLines(spec: ModuleSpec): List<String> = listOf(
        "id=$MODULE_ID",
        "name=$MODULE_NAME",
        "version=${singleLine(spec.versionName)}",
        "versionCode=${spec.versionCode}",
        "author=$MODULE_AUTHOR"
    )

    private fun moduleProp(spec: ModuleSpec): String =
        lf(identityLines(spec) + "description=${installDescription(spec)}" + "")

    /**
     * What the module list says between flashing and the first reboot: a request, not a result.
     *
     * Single line, and never empty — Magisk shows `description` verbatim.
     */
    private fun installDescription(spec: ModuleSpec): String = buildString {
        append("Spoofs this device's model as ")
        append(singleLine(spec.spoofedModel).ifBlank { "the selected profile" })
        append(" (")
        append(bootSafeProperties(spec).size)
        append(" properties, model identity only) to unlock higher FPS and graphics tiers in mobile ")
        append("games. Reboot, then this line reports how many actually applied.")
    }

    /**
     * The installer banner. Sourced by `install_module` when present, so it is the only place the
     * flashing user sees which profile they are about to apply.
     *
     * Four things it now does beyond printing:
     *
     * - Prints the device's real `ro.product.model` beside the spoofed one, as
     *   `Unlocker-p4/customize.sh` does. The value being replaced is the half of the swap the
     *   banner was missing.
     * - Names any other enabled module writing the same property keys. `resetprop` is
     *   last-one-wins at post-fs-data and the loser is silent, so a second model-spoofing module
     *   presents as "the spoof stopped working" with nothing to point at. The scan compares actual
     *   key sets rather than a curated list of module names, so it does not go stale — with one
     *   hand-written exception, `MagiskHidePropsConf`, which sets its properties from
     *   `post-fs-data.sh` where a `system.prop` scan cannot see them. That is the module
     *   `Unlocker-p4`'s README warns about by name, for exactly this reason.
     * - States what is *not* being flashed. [omittedKeys] is the honest counterpart to a deliberately
     *   short [MODEL_KEYS]: a user who set a fingerprint or a locale in the profile would otherwise
     *   read a five-property module as a broken one. It also prints [KEY_PIXELPROPS_GAME] and its
     *   value, since that is the one key here that is not the model.
     * - Refuses to activate when more than one root manager is present. Magisk, KernelSU and APatch
     *   each load their own modules at boot while `resetprop` applies this module's `system.prop`
     *   device-wide, and with two managers racing, whose copy survives is not predictable — the
     *   same last-one-wins hazard the conflict scan names between modules, one level up. The
     *   refusal cannot `exit` (same reason as every other branch), so it removes `system.prop`
     *   instead: nothing is ever handed to `resetprop`, `service.sh` exits before it can rewrite
     *   the description, and `module.prop` is rewritten to say the module is not active and why —
     *   leaving an inert module the user can read and remove rather than a half-installed one.
     *   Cross-checked against `spoofdevice/COPG-JSON/module/customize.sh`'s `check_zygisk()`,
     *   which counts the same three managers and refuses on a count above one. Its zero-manager
     *   abort and Zygisk-activity checks are not ported: they gate the native injection that
     *   module ships and this one does not — `customize.sh` only ever runs inside a manager's
     *   installer, and this module needs no Zygisk at all.
     *
     * Nothing here calls `exit`: the script is *sourced* by `install_module`, so an `exit` would
     * take the whole installer down. Every branch is a guard.
     */
    private fun customizeSh(spec: ModuleSpec): String = lf(
        buildList {
            val flashed = bootSafeProperties(spec)
            val omitted = omittedKeys(spec)
            add("#!/sbin/sh")
            add("")
            add("ui_print \"---------------------------------\"")
            add("ui_print \"          FPS Unlocker           \"")
            add("ui_print \"       Made by catsmoker         \"")
            add("ui_print \"---------------------------------\"")
            add("ui_print \"Profile:     ${shellSafe(spec.profileName)}\"")
            add("ui_print \"Spoofed as:  ${shellSafe(spec.spoofedModel)}\"")
            // The value being replaced, straight from the device, the way Unlocker-p4 prints it.
            add("ui_print \"This device: \$(getprop ro.product.model)\"")
            add("ui_print \"Flashing:    ${flashed.size} properties (model identity only)\"")
            add("ui_print \"Pixel flag:  $KEY_PIXELPROPS_GAME=${isPixelTarget(spec)}\"")
            add("ui_print \"---------------------------------\"")
            add("")

            // One root manager must own this device before this module writes device-wide
            // properties at every boot. The probes come from COPG-JSON's check_zygisk()
            // (command -v on each manager's daemon) plus the /data/adb trees update-binary
            // already tests, so a manager counts whether its binary is on PATH or only its
            // install tree survives.
            add("ROOTS=\"\"")
            add("ROOT_COUNT=0")
            add("if command -v magisk >/dev/null 2>&1 || [ -d \"\${NVBASE:-/data/adb}/magisk\" ]; then")
            add("  ROOTS=\"\$ROOTS Magisk\"")
            add("  ROOT_COUNT=\$((ROOT_COUNT + 1))")
            add("fi")
            add("if command -v ksud >/dev/null 2>&1 || [ -d \"\${NVBASE:-/data/adb}/ksu\" ]; then")
            add("  ROOTS=\"\$ROOTS KernelSU\"")
            add("  ROOT_COUNT=\$((ROOT_COUNT + 1))")
            add("fi")
            add("if command -v apd >/dev/null 2>&1 || [ -d \"\${NVBASE:-/data/adb}/ap\" ]; then")
            add("  ROOTS=\"\$ROOTS APatch\"")
            add("  ROOT_COUNT=\$((ROOT_COUNT + 1))")
            add("fi")
            add("")
            add("if [ \"\$ROOT_COUNT\" -gt 1 ]; then")
            add("  ui_print \"! Refusing to activate this module.\"")
            add("  ui_print \"! \$ROOT_COUNT root managers detected:\$ROOTS.\"")
            add("  ui_print \"! Each loads its own modules at boot while this one\"")
            add("  ui_print \"! writes device-wide properties -- whose copy wins is\"")
            add("  ui_print \"! not predictable. Keep one manager, then re-flash.\"")
            add("  ui_print \"---------------------------------\"")
            add("  # No exit -- install_module *sources* this script, so an exit would take the")
            add("  # whole installer down mid-flight. The refusal removes what the module would")
            add("  # do instead: without system.prop nothing is ever handed to resetprop, and")
            add("  # service.sh exits before it can rewrite the description, so what module.prop")
            add("  # says now is what the module list keeps saying until the user re-flashes.")
            add("  rm -f \"\$MODPATH/system.prop\"")
            add("  {")
            add("    cat <<'$MODULE_PROP_HEREDOC'")
            addAll(identityLines(spec))
            add(MODULE_PROP_HEREDOC)
            add("    echo \"description=NOT ACTIVE -- install refused, \$ROOT_COUNT root managers detected. Keep one, then re-flash.\"")
            add("  } > \"\$MODPATH/module.prop\"")
            add("else")

            if (omitted.isNotEmpty()) {
                add("# Stated while the user can still abort, and while it can still be explained")
                add("# as a choice rather than discovered as a spoof that only half applied.")
                add("ui_print \"Held back:   ${omitted.size} profile keys not flashed.\"")
                add("ui_print \"  A device-wide build, SoC or ABI key that\"")
                add("ui_print \"  disagrees with the real hardware costs a\"")
                add("ui_print \"  boot, and no FPS table reads one. The\"")
                add("ui_print \"  in-process hooks still apply them per-app.\"")
                add("ui_print \"---------------------------------\"")
                add("")
            }

            add("# Report any other module writing the same keys. resetprop is last-one-wins at")
            add("# post-fs-data and the loser says nothing, so the overlap is named here instead")
            add("# of being discovered as a spoof that quietly stopped working.")
            add("MODID=\"\${MODID:-$MODULE_ID}\"")
            add("MODULES=\"\${NVBASE:-/data/adb}/modules\"")
            add("OURS=\"\$MODPATH/.catsmoker_keys\"")
            add("CONFLICTS=\"\"")
            add("if [ -f \"\$MODPATH/system.prop\" ]; then")
            add("  sed -n 's/^[[:space:]]*\\([a-zA-Z0-9._-]*\\)=.*/\\1/p' \"\$MODPATH/system.prop\" | sort -u > \"\$OURS\"")
            add("  for OTHER in \"\$MODULES\"/*; do")
            add("    [ -d \"\$OTHER\" ] || continue")
            add("    OTHERID=\"\${OTHER##*/}\"")
            add("    [ \"\$OTHERID\" = \"\$MODID\" ] && continue")
            add("    [ -f \"\$OTHER/disable\" ] && continue")
            add("    [ -f \"\$OTHER/system.prop\" ] || continue")
            add("    SHARED=\$(sed -n 's/^[[:space:]]*\\([a-zA-Z0-9._-]*\\)=.*/\\1/p' \"\$OTHER/system.prop\" | sort -u | grep -Fxf \"\$OURS\" | wc -l)")
            add("    [ \"\$SHARED\" -gt 0 ] 2>/dev/null && CONFLICTS=\"\$CONFLICTS \$OTHERID(\$SHARED)\"")
            add("  done")
            add("  rm -f \"\$OURS\"")
            add("fi")
            add("# MagiskHidePropsConf sets its properties from post-fs-data.sh, where the scan")
            add("# above cannot see them. It is the module Unlocker's README names by hand.")
            add("if [ -d \"\$MODULES/MagiskHidePropsConf\" ] && [ ! -f \"\$MODULES/MagiskHidePropsConf/disable\" ]; then")
            add("  CONFLICTS=\"\$CONFLICTS MagiskHidePropsConf\"")
            add("fi")
            add("if [ -n \"\$CONFLICTS\" ]; then")
            add("  ui_print \"! Also setting device properties:\"")
            add("  ui_print \"!  \$CONFLICTS\"")
            add("  ui_print \"! Whichever loads last wins. Disable the others if\"")
            add("  ui_print \"! this profile does not take effect.\"")
            add("  ui_print \"---------------------------------\"")
            add("fi")
            add("")
            add("ui_print \"Applies device-wide. Reboot to apply.\"")
            add("ui_print \"Then this module's description and\"")
            add("ui_print \"$VERIFY_LOG_NAME report what the device did.\"")
            add("ui_print \"          Happy gaming!          \"")
            add("ui_print \"---------------------------------\"")
            add("")
            add("fi")
            add("")
        }
    )

    /**
     * Post-boot verification, run by Magisk at `late_start`.
     *
     * The strongest convention in this app is to read a value back and report the measured fact
     * rather than the request — `ExecResult`, `GamingModeReport`, `MetricReadStatus` all exist for
     * that. A flashed module was the one channel with no such report: it either worked or it
     * didn't, and the user had no way to tell which properties `resetprop` had actually landed.
     * It genuinely varies, because vendor init keeps writing properties long after post-fs-data.
     *
     * A module has exactly two surfaces to report on, and this writes both: the `description`
     * Magisk shows in its module list, and [VERIFY_LOG_NAME] beside itself.
     *
     * Three details are deliberate:
     *
     * - It waits for `sys.boot_completed`, the way `HunterX-Reborn-II/common/service.sh` waits for
     *   `vendor.post_boot.parsed`, because a reading taken at `late_start` would call a property
     *   applied that is about to be overwritten. The wait is bounded, and a device that never
     *   reports it gets that recorded in the log rather than silently assumed away.
     * - "not set" and "overwritten" stay distinct — an absent property means `resetprop` never
     *   created it, a differing one means something on the device won. Those need different fixes,
     *   which is the same reason `GamingModeReport` uses nullable fields.
     * - The log is truncated, never appended: it describes the boot the user is in.
     * - A bundled companion APK ([ModuleSpec.companionApk]) is installed *after* the report is
     *   written, so `pm install` — which can take seconds — never delays the verification. The
     *   mechanism is `GameUnlocker-main/common/service.sh`'s: install the APK sitting beside the
     *   module with `pm install -g`, then delete it, making delivery one-shot rather than a
     *   reinstall at every boot. One divergence, stated: ours first checks whether the app is
     *   already installed and discards the APK as "already present" instead of running the install
     *   — same version would churn a pointless reinstall, older version would fail as a downgrade,
     *   and neither belongs in a log the user reads to learn what the device did.
     */
    private fun serviceSh(spec: ModuleSpec): String = lf(
        buildList {
            add("#!/sbin/sh")
            add("")
            add("MODDIR=\${0%/*}")
            add("PROP=\"\$MODDIR/system.prop\"")
            add("LOG=\"\$MODDIR/$VERIFY_LOG_NAME\"")
            add("")
            add("[ -f \"\$PROP\" ] || exit 0")
            add("")
            add("# Vendor init writes properties well past post-fs-data, so a reading taken now")
            add("# would report a spoof as applied that is about to be overwritten. Bounded, so a")
            add("# device that never reports boot_completed cannot leave this looping forever --")
            add("# and the timeout is recorded below rather than assumed away.")
            add("WAITED=0")
            add("BOOTED=1")
            add("while [ \"\$(getprop sys.boot_completed)\" != \"1\" ]; do")
            add("  if [ \"\$WAITED\" -ge 120 ]; then")
            add("    BOOTED=0")
            add("    break")
            add("  fi")
            add("  WAITED=\$((WAITED + 2))")
            add("  sleep 2")
            add("done")
            add("sleep 5")
            add("")
            add("TOTAL=0")
            add("OK=0")
            add("MISSING=0")
            add("CHANGED=0")
            add("")
            // The model this report names comes from system.prop itself, not from a value baked
            // in at export time: the module's WebUI can rewrite the model in place between
            // flashes, and a baked string would keep naming the model the ZIP shipped with.
            add("MODEL=\$(sed -n 's/^ro\\.product\\.model=//p' \"\$PROP\" | head -n 1)")
            add("[ -z \"\$MODEL\" ] && MODEL=\"(no model)\"")
            add("")
            add("echo \"# CatSmoker spoof verification\" > \"\$LOG\"")
            add("echo \"# profile: ${shellSafe(spec.profileName)}\" >> \"\$LOG\"")
            add("echo \"# spoofed as: \$MODEL\" >> \"\$LOG\"")
            add("echo \"# module: $MODULE_ID ${singleLine(spec.versionName)} (${spec.versionCode})\" >> \"\$LOG\"")
            add("echo \"# read back: \$(date 2>/dev/null)\" >> \"\$LOG\"")
            add("if [ \"\$BOOTED\" != \"1\" ]; then")
            add("  echo \"# sys.boot_completed never reported 1 within 120s - readings may be early\" >> \"\$LOG\"")
            add("fi")
            add("echo \"\" >> \"\$LOG\"")
            add("")
            add("# Redirected from a file, not piped: piping into this loop would run it in a")
            add("# subshell and every counter below would come back zero.")
            add("while IFS= read -r LINE; do")
            add("  case \"\$LINE\" in")
            add("    ''|'#'*) continue ;;")
            add("  esac")
            add("  KEY=\${LINE%%=*}")
            add("  WANT=\${LINE#*=}")
            add("  [ \"\$KEY\" = \"\$LINE\" ] && continue")
            add("  TOTAL=\$((TOTAL + 1))")
            add("  GOT=\$(getprop \"\$KEY\")")
            add("  if [ \"\$GOT\" = \"\$WANT\" ]; then")
            add("    OK=\$((OK + 1))")
            add("  elif [ -z \"\$GOT\" ]; then")
            add("    # resetprop never created it. Distinct from a value something else replaced.")
            add("    MISSING=\$((MISSING + 1))")
            add("    echo \"not set:     \$KEY (wanted '\$WANT')\" >> \"\$LOG\"")
            add("  else")
            add("    CHANGED=\$((CHANGED + 1))")
            add("    echo \"overwritten: \$KEY is '\$GOT' (wanted '\$WANT')\" >> \"\$LOG\"")
            add("  fi")
            add("done < \"\$PROP\"")
            add("")
            add("echo \"\" >> \"\$LOG\"")
            add("echo \"applied \$OK/\$TOTAL, \$MISSING not set, \$CHANGED overwritten after boot\" >> \"\$LOG\"")
            add("")
            add("# The module list is the only report a user sees without a file manager.")
            add("DESC=\"Applied \$OK/\$TOTAL properties\"")
            add("if [ \"\$MISSING\" -gt 0 ]; then")
            add("  DESC=\"\$DESC, \$MISSING not set\"")
            add("fi")
            add("if [ \"\$CHANGED\" -gt 0 ]; then")
            add("  DESC=\"\$DESC, \$CHANGED overwritten by the device\"")
            add("fi")
            add("DESC=\"\$DESC - spoofing as \$MODEL. Details in $VERIFY_LOG_NAME.\"")
            add("")
            add("# Rewritten whole rather than sed'd in place: property values carry slashes and")
            add("# ampersands that a sed replacement would eat.")
            add("{")
            add("  cat <<'$MODULE_PROP_HEREDOC'")
            addAll(identityLines(spec))
            add(MODULE_PROP_HEREDOC)
            add("  echo \"description=\$DESC\"")
            add("} > \"\$MODDIR/module.prop.new\" && mv -f \"\$MODDIR/module.prop.new\" \"\$MODDIR/module.prop\"")
            add("")
            // Companion-app recovery, last so the report never waits on pm install. One-shot, as
            // in the reference: the APK is discarded after the first boot that sees it.
            add("APK=\"\$MODDIR/$COMPANION_APK_NAME\"")
            add("if [ -f \"\$APK\" ]; then")
            add("    if pm list packages $APPLICATION_ID 2>/dev/null | grep -q $APPLICATION_ID; then")
            add("        echo \"companion app already present - bundled APK discarded\" >> \"\$LOG\"")
            add("    elif pm install -g \"\$APK\" >/dev/null 2>&1; then")
            add("        echo \"companion app installed from the module (permissions granted)\" >> \"\$LOG\"")
            add("    else")
            add("        # Failed is reported as failed, and the APK is still discarded: delivery")
            add("        # stays one-shot rather than retrying into every boot log.")
            add("        echo \"companion app install refused by pm - APK discarded\" >> \"\$LOG\"")
            add("    fi")
            add("    rm -f \"\$APK\"")
            add("fi")
            add("")
        }
    )

    /**
     * The root manager's action button: start a WebUI for this module without opening the app.
     *
     * Ported from `spoofdevice/GameUnlocker-main/common/action.sh`, read in full, with its shape
     * kept deliberately:
     *
     * - busybox-httpd is found through the four candidate paths the reference probes (KSU, Magisk,
     *   APatch, then `PATH`), each actually executed once before it is trusted — a busybox that
     *   exists but cannot run is the failure the probe exists to catch.
     * - The port is random in 6000–9999 from `/dev/urandom` (epoch fallback when no character
     *   device), and the server binds `127.0.0.1` only, so nothing off-device can reach it.
     * - A fresh 16-byte hex auth token is written beside the module at `0600` and appended to the
     *   URL the browser opens; [webApiSh] enforces it.
     * - The server kills itself after 300 s and removes the token with it, so neither the port nor
     *   the credential outlives the session the button started.
     * - The KSUWebUI app, when installed, gets the page launched natively instead — its sandbox is
     *   the one case [webApiSh]'s token check may be skipped for.
     *
     * Divergences, stated: the reference's terminal banner prints the module's own name; and its
     * trailing message tells the user the WebUI is open without mentioning that it also closes
     * itself — ours names the five-minute lifetime, because a server that silently stops answering
     * reads as broken otherwise.
     */
    private fun actionSh(): String = lf(
        buildList {
            add("#!/sbin/sh")
            add("")
            add("MODDIR=\${0%/*}")
            add("")
            add("echo \"==========================================\"")
            add("echo \"    CatSmoker Spoof Manager (WebUI)      \"")
            add("echo \"==========================================\"")
            add("echo \"Starting WebUI configuration...\"")
            add("")
            add("# Busybox is probed, not assumed: each candidate is executed once before it is")
            add("# trusted, so a busybox that exists but cannot run is refused here rather than")
            add("# half-way through serving a page.")
            add("find_busybox() {")
            add("    for candidate in /data/adb/ksu/bin/busybox /data/adb/magisk/busybox /data/adb/ap/bin/busybox /system/bin/busybox; do")
            add("        if [ -f \"\$candidate\" ] && [ -x \"\$candidate\" ]; then")
            add("            if \"\$candidate\" true >/dev/null 2>&1; then")
            add("                echo \"\$candidate\"")
            add("                return 0")
            add("            fi")
            add("        fi")
            add("    done")
            add("    if command -v busybox >/dev/null 2>&1; then")
            add("        sys_bb=\$(command -v busybox)")
            add("        if \"\$sys_bb\" true >/dev/null 2>&1; then")
            add("            echo \"\$sys_bb\"")
            add("            return 0")
            add("        fi")
            add("    fi")
            add("    return 1")
            add("}")
            add("")
            add("generate_random_port() {")
            add("    if [ -c \"/dev/urandom\" ]; then")
            add("        PORT=\$(od -An -N2 -tu2 /dev/urandom | tr -d ' ')")
            add("        PORT=\$((6000 + (PORT % 4000)))")
            add("    else")
            add("        PORT=\$((6000 + (\$(date +%s) % 4000)))")
            add("    fi")
            add("    echo \"\$PORT\"")
            add("}")
            add("")
            add("# KSUWebUI serves the same webroot in its own sandbox, where there is no httpd and")
            add("# no token to check. Same app id and launch shape as the reference.")
            add("if pm list packages 2>/dev/null | grep -q \"io.github.a13e300.ksuwebui\"; then")
            add("    echo \"Launching natively inside KSUWebUI App...\"")
            add("    su -c \"am start -n 'io.github.a13e300.ksuwebui/.WebUIActivity' -e id '$MODULE_ID'\" >/dev/null 2>&1")
            add("    exit 0")
            add("fi")
            add("")
            add("BB=\$(find_busybox)")
            add("if [ -z \"\$BB\" ]; then")
            add("    echo \"Error: Busybox not found! Cannot start WebUI.\"")
            add("    exit 1")
            add("fi")
            add("")
            add("RANDOM_PORT=\$(generate_random_port)")
            add("")
            add("# A fresh token per session, unreadable by anything else on the device, and removed")
            add("# with the server when it dies.")
            add("AUTH_TOKEN=\$(od -An -N16 -tx1 /dev/urandom | tr -d ' \\n')")
            add("echo \"\$AUTH_TOKEN\" > \"\$MODDIR/auth_token\"")
            add("chmod 0600 \"\$MODDIR/auth_token\"")
            add("")
            add("chmod -R 0755 \"\$MODDIR/webroot/cgi-bin\"")
            add("")
            add("BB_DIR=\$(\$BB dirname \"\$BB\")")
            add("export PATH=\"\$BB_DIR:\$PATH\"")
            add("")
            add("# A stale server from an earlier button press must not hold the port.")
            add("\$BB pkill -f \"httpd -p 127.0.0.1:\" >/dev/null 2>&1")
            add("")
            add("echo \"Starting background server and opening browser...\"")
            add("")
            add("(")
            add("    \$BB httpd -p 127.0.0.1:\$RANDOM_PORT -h \"\$MODDIR/webroot\" >/dev/null 2>&1")
            add("    sleep 300")
            add("    \$BB pkill -f \"httpd -p 127.0.0.1:\$RANDOM_PORT\" >/dev/null 2>&1")
            add("    rm -f \"\$MODDIR/auth_token\"")
            add(") &")
            add("")
            add("sleep 1")
            add("am start -a android.intent.action.VIEW -d \"http://127.0.0.1:\$RANDOM_PORT?token=\$AUTH_TOKEN\" >/dev/null 2>&1")
            add("")
            add("echo \"\"")
            add("echo \"Done! The WebUI should now be open.\"")
            add("echo \"It closes itself after 5 minutes.\"")
            add("exit 0")
            add("")
        }
    )

    /**
     * The WebUI's whole backend, as one CGI shell script.
     *
     * Ported in shape from `spoofdevice/GameUnlocker-main/webroot/cgi-bin/api.sh`, read in full.
     * What is deliberately *not* ported is most of it: the reference's config is `config.json`, so
     * every action there is a `jq` invocation — and `jq` is not present on most devices, which its
     * own error paths concede. This module's whole state is a `system.prop` of `key=value` lines,
     * so `sed` does everything and no external binary is required.
     *
     * The adaptive token rule is the reference's and is kept exactly: when `auth_token` exists (the
     * httpd path) every request must carry it; when it does not, the page is being served inside
     * KSUWebUI's native sandbox and the check is skipped.
     *
     * Two actions, both honest about what they did:
     *
     * - `get_state` reads the module's actual files — the model from `system.prop`, the real device
     *   from `getprop`, the last verification line from [VERIFY_LOG_NAME] — and says `active: false`
     *   when `system.prop` is missing rather than serving the install-time state as if it were live.
     * - `set_model` rewrites exactly [MODEL_KEYS] in `system.prop`, so the channel's narrowing
     *   survives an edit made outside the app: the WebUI cannot widen what the installer flashed.
     *   The model is charset-validated because it lands in `sed` replacements and `module.prop`.
     *   [KEY_PIXELPROPS_GAME] is deliberately left untouched — it was derived from the profile's
     *   *brand* at export time, and the model string alone cannot re-derive it. The new model takes
     *   effect at the next reboot, and [serviceSh] reports it then because it reads the model back
     *   from `system.prop` rather than from a value baked at export.
     */
    private fun webApiSh(): String = lf(
        buildList {
            add("#!/system/bin/sh")
            add("echo \"Content-Type: application/json\"")
            add("echo \"\"")
            add("")
            add("MODDIR=\"/data/adb/modules/$MODULE_ID\"")
            add("PROP=\"\$MODDIR/system.prop\"")
            add("LOG=\"\$MODDIR/$VERIFY_LOG_NAME\"")
            add("export PATH=\"\$MODDIR:\$PATH\"")
            add("")
            add("TOKEN=\$(echo \"\$QUERY_STRING\" | grep -o 'token=[^&]*' | cut -d= -f2)")
            add("ACTION=\$(echo \"\$QUERY_STRING\" | grep -o 'action=[^&]*' | cut -d= -f2)")
            add("VALUE=\$(echo \"\$QUERY_STRING\" | grep -o 'value=[^&]*' | cut -d= -f2)")
            add("")
            add("# Adaptive authentication, as in the reference: with an auth_token on disk (the")
            add("# httpd path) every request must carry it; without one the page is inside KSUWebUI's")
            add("# native sandbox and the check is skipped.")
            add("if [ -f \"\$MODDIR/auth_token\" ]; then")
            add("    EXPECTED_TOKEN=\$(cat \"\$MODDIR/auth_token\")")
            add("    if [ \"\$TOKEN\" != \"\$EXPECTED_TOKEN\" ] || [ -z \"\$TOKEN\" ]; then")
            add("        echo '{\"success\": false, \"error\": \"Unauthorized access. Invalid or missing token.\"}'")
            add("        exit 1")
            add("    fi")
            add("fi")
            add("")
            add("urldecode() {")
            add("  data=\"\$1\"")
            add("  data=\${data//+/ }")
            add("  printf '%b' \"\${data//%/\\\\x}\"")
            add("}")
            add("")
            add("# -----------------------------------------------------------------------")
            add("# get_state -- the module's live state, read from its own files")
            add("# -----------------------------------------------------------------------")
            add("if [ \"\$ACTION\" = \"get_state\" ]; then")
            add("    if [ -f \"\$PROP\" ]; then")
            add("        MODEL=\$(sed -n 's/^ro\\.product\\.model=//p' \"\$PROP\" | head -n 1 | tr -d '\"\\\\')")
            add("        ACTIVE=true")
            add("    else")
            add("        # A missing system.prop is the neutralized or hand-stripped module. Reporting")
            add("        # the install-time state as live would be the exact wrong answer.")
            add("        MODEL=\"\"")
            add("        ACTIVE=false")
            add("    fi")
            add("    REAL=\$(getprop ro.product.model | tr -d '\"\\\\')")
            add("    PIXEL=\$(sed -n 's/^persist\\.sys\\.pixelprops\\.game=//p' \"\$PROP\" 2>/dev/null | head -n 1)")
            add("    VERIFY=\"\"")
            add("    if [ -f \"\$LOG\" ]; then")
            add("        VERIFY=\$(grep '^applied ' \"\$LOG\" | tail -n 1 | tr -d '\"\\\\')")
            add("    fi")
            add("    echo \"{\\\"success\\\": true, \\\"active\\\": \$ACTIVE, \\\"model\\\": \\\"\$MODEL\\\", \\\"real\\\": \\\"\$REAL\\\", \\\"pixel\\\": \\\"\$PIXEL\\\", \\\"verify\\\": \\\"\$VERIFY\\\"}\"")
            add("")
            add("# -----------------------------------------------------------------------")
            add("# set_model -- rewrite the model identity in system.prop, nothing else")
            add("# -----------------------------------------------------------------------")
            add("elif [ \"\$ACTION\" = \"set_model\" ]; then")
            add("    VALUE=\$(urldecode \"\$VALUE\")")
            add("    if [ -z \"\$VALUE\" ]; then")
            add("        echo '{\"success\": false, \"error\": \"missing value\"}'")
            add("        exit 0")
            add("    fi")
            add("    # The value lands in sed replacements and module.prop, so it is charset-bound")
            add("    # rather than trusted: letters, digits, spaces and .()_- only, 64 chars max.")
            add("    if ! echo \"\$VALUE\" | grep -Eq '^[A-Za-z0-9 .()_-]{1,64}\$'; then")
            add("        echo '{\"success\": false, \"error\": \"a model name can only use letters, digits, spaces and .()_- (max 64)\"}'")
            add("        exit 0")
            add("    fi")
            add("    if [ ! -f \"\$PROP\" ]; then")
            add("        echo '{\"success\": false, \"error\": \"system.prop is missing - module not active. Re-flash it from the app.\"}'")
            add("        exit 0")
            add("    fi")
            add("    # Exactly the keys the installer flashes. The WebUI edits the model; it cannot")
            add("    # widen the channel from inside the module.")
            add("    for KEY in \\")
            add("        ro.product.model \\")
            add("        ro.product.odm.model \\")
            add("        ro.product.system.model \\")
            add("        ro.product.vendor.model; do")
            add("        sed -i \"s|^\$KEY=.*|\$KEY=\$VALUE|\" \"\$PROP\"")
            add("    done")
            add("    # The PixelProps switch is deliberately untouched: it was derived from the")
            add("    # profile's brand when the module was exported, and the model string alone")
            add("    # cannot re-derive brand from model.")
            add("    sed -i \"s|^description=.*|description=Spoofs this device as \$VALUE (model identity only). Reboot to apply; then this line reports what did.|\" \"\$MODDIR/module.prop\"")
            add("    echo '{\"success\": true}'")
            add("")
            add("else")
            add("    echo '{\"success\": false, \"error\": \"invalid action\"}'")
            add("fi")
            add("")
        }
    )

    /**
     * The WebUI page: module state, one model field, no external resources.
     *
     * The reference's page imports a Google font and ships a tabbed dashboard for a config this
     * module does not have. Ours is one screen for one job — read the state, change the model —
     * and self-contained, because the page is served from the module directory over localhost with
     * no guarantee the device has working networking beyond that loopback.
     *
     * Written as a raw string and stripped of `\r` at the end for the same reason every script
     * here is built with explicit `\n`: the archive must not depend on how this source file is
     * stored or checked out. It deliberately contains no `$` character, so no Kotlin template
     * escaping can silently rewrite the page.
     */
    private fun webIndexHtml(): String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>CatSmoker Spoof Manager</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
:root{--bg:#101014;--surface:#1a1a20;--border:#2a2a32;--text:#f0f0f2;--muted:#8a8a94;
--accent:#7c6ee6;--green:#22c55e;--red:#ef4444;--radius:12px}
body{background:var(--bg);color:var(--text);font-family:-apple-system,system-ui,sans-serif;
font-size:14px;line-height:1.5;padding:16px;min-height:100vh}
.wrap{max-width:560px;margin:0 auto}
h1{font-size:18px;letter-spacing:-.3px}
.sub{font-size:12px;color:var(--muted);margin-top:2px}
.card{background:var(--surface);border:1px solid var(--border);border-radius:var(--radius);
padding:16px;margin-top:14px}
.row{display:flex;justify-content:space-between;gap:12px;padding:7px 0;font-size:13px}
.row .k{color:var(--muted)}
.row .v{font-weight:600;text-align:right;word-break:break-word}
.badge{font-size:11px;font-weight:600;padding:2px 9px;border-radius:99px}
.ok{background:rgba(34,197,94,.15);color:var(--green);border:1px solid rgba(34,197,94,.3)}
.bad{background:rgba(239,68,68,.15);color:var(--red);border:1px solid rgba(239,68,68,.3)}
input{width:100%;background:#232329;border:1px solid var(--border);color:var(--text);
font-size:15px;padding:11px 13px;border-radius:8px;outline:none;font-family:inherit}
input:focus{border-color:var(--accent)}
button{margin-top:10px;width:100%;padding:11px;border:none;border-radius:8px;
background:var(--accent);color:#fff;font-size:14px;font-weight:600;font-family:inherit}
.note{font-size:12px;color:var(--muted);margin-top:10px}
#msg{font-size:13px;margin-top:10px;min-height:18px}
#msg.ok{color:var(--green)}#msg.bad{color:var(--red)}
code{background:#232329;padding:1px 5px;border-radius:4px;font-size:12px}
</style>
</head>
<body>
<div class="wrap">
<h1>CatSmoker Spoof Manager</h1>
<p class="sub">fpsunlocker module &middot; model identity only</p>

<div class="card">
<div class="row"><span class="k">Module</span><span class="v" id="state">&hellip;</span></div>
<div class="row"><span class="k">Spoofed model</span><span class="v" id="model">&hellip;</span></div>
<div class="row"><span class="k">This device</span><span class="v" id="real">&hellip;</span></div>
<div class="row"><span class="k">PixelProps flag</span><span class="v" id="pixel">&hellip;</span></div>
<div class="row"><span class="k">Last boot check</span><span class="v" id="verify">&hellip;</span></div>
</div>

<div class="card">
<b style="font-size:14px">Change the spoofed model</b>
<p class="note">Letters, digits, spaces and <code>.()_-</code> only. Applies to the whole device
at the next reboot, exactly like re-flashing the module from the app.</p>
<input id="newmodel" maxlength="64" placeholder="e.g. SM-S948B">
<button onclick="apply()">Apply model</button>
<div id="msg"></div>
</div>

<p class="note">This page is served from the module over localhost with a session token, and the
server closes itself five minutes after the action button started it. The full profile and the
per-app assignments live in the CatSmoker app; this page only edits the model this module
flashes.</p>
</div>

<script>
var TOKEN = new URLSearchParams(location.search).get('token') || '';

function api(action, value, cb) {
  var url = 'cgi-bin/api.sh?action=' + action + '&token=' + encodeURIComponent(TOKEN);
  if (value !== undefined) url += '&value=' + encodeURIComponent(value);
  fetch(url).then(function (r) { return r.json(); }).then(cb).catch(function () {
    show('Could not reach the module server. It closes itself five minutes after it started - press the action button again.', false);
  });
}

function show(text, ok) {
  var el = document.getElementById('msg');
  el.textContent = text;
  el.className = ok ? 'ok' : 'bad';
}

function fill(s) {
  document.getElementById('state').innerHTML =
    s.active ? '<span class="badge ok">Active</span>' : '<span class="badge bad">Not active</span>';
  document.getElementById('model').textContent = s.model || '(none)';
  document.getElementById('real').textContent = s.real || '(unreadable)';
  document.getElementById('pixel').textContent = s.pixel === '' ? '(not set)' : s.pixel;
  document.getElementById('verify').textContent = s.verify || '(no boot check yet)';
  document.getElementById('newmodel').value = s.model || '';
}

function apply() {
  var value = document.getElementById('newmodel').value.trim();
  api('set_model', value, function (r) {
    if (r.success) {
      show('Model saved. Reboot to apply it.', true);
      api('get_state', undefined, fill);
    } else {
      show(r.error || 'The module refused the change.', false);
    }
  });
}

api('get_state', undefined, fill);
</script>
</body>
</html>
""".trimIndent().replace("\r", "") + "\n"

    /**
     * The stock Magisk installer entry point, reproduced from the script this app shipped as an
     * asset. The Magisk path through it is left untouched — it is the known-good template, and a
     * clever rewrite of it would only be a new way to fail on someone's device.
     *
     * What changed is the failure message. Magisk, KernelSU and APatch all install this module
     * format, and this app's own UI offers to open whichever of the three is present
     * ([com.catsmoker.app.features.spoofdevice.SpoofDeviceViewModel.launchRootManager]), but only
     * Magisk ships `/data/adb/magisk/util_functions.sh`. KernelSU and APatch install modules from
     * their own manager and never source this script, so a user who reached it under either one saw
     * "Please install Magisk v20.4+" — false, and no help at all. They now get told where the
     * installer actually lives.
     *
     * No path under `/data/adb/ksu` or `/data/adb/ap` is *sourced* on the strength of that — only
     * tested for existence, so the branch cannot depend on a layout this repo has not verified.
     */
    private fun updateBinary(): String = lf(
        "#!/sbin/sh",
        "",
        "#################",
        "# Initialization",
        "#################",
        "",
        "umask 022",
        "",
        "# Print message to the console",
        "ui_print() {",
        "  echo \"\$1\"",
        "}",
        "",
        "# Function to require a newer version of Magisk",
        "require_new_magisk() {",
        "  ui_print \"*******************************\"",
        "  ui_print \" Please install Magisk v20.4+! \"",
        "  ui_print \"*******************************\"",
        "  exit 1",
        "}",
        "",
        "# KernelSU and APatch install this module format from their own manager, which",
        "# never sources this script. Reaching here under one of them means the ZIP was",
        "# flashed somewhere that cannot install it -- so say where the installer is,",
        "# instead of blaming a missing Magisk.",
        "require_own_manager() {",
        "  ui_print \"*******************************\"",
        "  ui_print \" Install this from the \$1 app: \"",
        "  ui_print \" Modules -> Install from storage\"",
        "  ui_print \"*******************************\"",
        "  exit 1",
        "}",
        "",
        "#########################",
        "# Load util_functions.sh",
        "#########################",
        "",
        "OUTFD=\$2",
        "ZIPFILE=\$3",
        "",
        "# Ensure /data is mounted",
        "mount /data 2>/dev/null",
        "",
        "# Check for the presence of util_functions.sh and Magisk version",
        "if [ ! -f /data/adb/magisk/util_functions.sh ]; then",
        "  if [ \"\$KSU\" = \"true\" ] || [ -d /data/adb/ksu ]; then",
        "    require_own_manager \"KernelSU\"",
        "  fi",
        "  if [ \"\$APATCH\" = \"true\" ] || [ -d /data/adb/ap ]; then",
        "    require_own_manager \"APatch\"",
        "  fi",
        "  require_new_magisk",
        "fi",
        ". /data/adb/magisk/util_functions.sh",
        "if [ \$MAGISK_VER_CODE -lt $MIN_MAGISK_VER_CODE ]; then",
        "  require_new_magisk",
        "fi",
        "",
        "# Execute module installation",
        "install_module",
        "exit 0",
        ""
    )

    /** The marker Magisk uses to recognise a flashable module. */
    private const val UPDATER_SCRIPT = "#MAGISK\n"

    /** Delimiter for the literal `module.prop` block [serviceSh] reprints. */
    private const val MODULE_PROP_HEREDOC = "CATSMOKER_MODULE_PROP"

    // ------------------------------------------------------------------ helpers

    /**
     * Joins with an explicit `\n`.
     *
     * The scripts here are executed by `sh` on the device, where a CR makes the shebang a "bad
     * interpreter". Building them line-by-line rather than from a multiline literal keeps that
     * independent of how this source file happens to be stored or checked out — which is what the
     * repo's `.gitattributes` had to enforce while these lived in `assets/`.
     */
    private fun lf(lines: List<String>): String = lines.joinToString("\n")

    private fun lf(vararg lines: String): String = lf(lines.asList())

    /** Collapses anything a profile name could contain into one safe `key=value` line. */
    private fun singleLine(raw: String): String =
        raw.replace('\n', ' ').replace('\r', ' ').trim()

    /** Same, plus the characters that would break out of a `ui_print "…"` argument. */
    private fun shellSafe(raw: String): String =
        singleLine(raw).replace("\\", "").replace("\"", "").replace("$", "").replace("`", "")
}
