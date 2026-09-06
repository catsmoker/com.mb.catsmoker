# Spoof Device

How CatSmoker lets a game see a different device — and the three channels it
ships for applying a spoof.

## What is spoofed

A "device profile" is a set of Android system properties (`ro.product.*`,
`ro.build.*`, vendor/fingerprint/security-patch values, etc.) imitating a
premium device so a game unlocks higher graphics settings, frame caps, and
quality presets.

Configuration lives in `SpoofConfigProvider` and `DevicePreset`
(`shared/data/model/DevicePreset.kt`). Profiles are persisted as JSON (Gson).

## Three application channels

| Channel | When it's used | How it works |
| --- | --- | --- |
| **LSPosed / Xposed** | Rooted devices (recommended) | Hooks the target game at runtime and overrides `getprop` for the spoofed keys. Zero file modification. |
| **Shizuku** | Non-rooted, Android 11+ | Uses the Shizuku binder to run privileged commands that write/oversee system or game data. |
| **Magisk module** | Offline / system-level | `MagiskModuleBuilder` generates a flashable `.zip` that applies the spoof at boot via `system.prop`. |

Plus **SAF / export mode** for manually granting access to game data folders.

## LSPosed module

- `features/spoofdevice/root/LSPosedModule.kt` — declares the module scope
  and lifecycle.
- `features/spoofdevice/root/GetPropInterceptor.kt` — the actual runtime
  hook that answers `getprop`/`ro.product.*` for the selected game with the
  active profile's values.
- Activation is per-game: the LSPosed manager scopes the module to the games
  you want optimized.

**Important:** the Xposed API is a `compileOnly` dependency. The module
classes are only meaningful inside the LSPosed process; the app itself keeps
running on a normal Android classpath.

## Magisk mode builder

`MagiskModuleBuilder` (`features/spoofdevice/tools/MagiskModuleBuilder.kt`)
constructs a flashable Magisk module **entirely in code** as a `ZipOutputStream`.
Two past bugs explain why:

1. **Silent coupling** — the exported zip used to inherit whatever members the
   asset tree happened to contain, so `system.prop` (the file that carries the
   spoof) existed only because a placeholder sat in `assets/`. Deleting the
   placeholder would have shipped a module that installs cleanly and spoofs
   nothing.
2. **Stale `module.prop`** — `version`/`versionCode` were hardcoded and drifted
   while the app moved on. Both now come from real build values.

The builder deliberately flashes only a narrow allowlist of model keys
(`MODEL_KEYS`), not the whole profile, and reports any omitted keys rather
than silently dropping them — narrow is the safe shape for this channel.

The `customize.sh` prints the device's *real* model during flashing (so a
user sees what is being replaced), and `service.sh` waits for boot to settle
before it trusts anything it reads, then reports what the device actually did
after applying.

## Routing & screens

Routes in `system/navigation/Routes.kt`:

- `spoof_device` — main hub
- `spoof_profiles` / `spoof_editor/{profileId}` — list & edit profiles
- `spoof_apps` — choose which games a profile applies to
- `spoof_diagnostics` — verify spoof state
- `spoof_safe_mode` — revert to safe state (see below)

## Safe mode

Spoofing can make a game (or, rarely, the OS) behave unexpectedly. The
`SPOOF_SAFE_MODE` flow exists to remove active hooks/profiles and restore a
known-good state quickly. Keep this path robust — it is the emergency exit
for anyone who applied a spoof that broke something.

## A note on scope

Spoof identity is per-game and per-profile. Toggling, editing, or deleting a
profile never touches other games' scopes, and the diagnostics screen reads
back actual `getprop` output rather than showing the intended values.
