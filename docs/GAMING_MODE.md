# Gaming Mode & the Gaming Engine

This document explains what **Gaming Mode** in CatSmoker actually does and
how `GamingEngine` (`features/gamingtools/engine/GamingEngine.kt`) makes it
happen.

## What Gaming Mode applies

Activation is a sequence of discrete, independently-failing steps. Each one
is **verified by reading the value back** — the report you see in the UI is
a list of measured facts, not a slogan. If a step is refused, it lands in
`GamingModeReport.unavailable` with the device's own reason rather than a
generic "failed".

In order:

1. **Capture a snapshot** of the user's real system state *before* anything
   is changed, so turning the mode off restores their configuration — not
   ours.
2. **Trim system caches**: `pm trim-caches`, `am compact background`,
   framework pinning.
3. **Suspend background apps** (`pm suspend --user 0 <pkg>`), skipping the
   game in play, the launcher, system-critical packages, and a hard allowlist
   (Shizuku, Magisk, CatSmoker itself).
4. **Focus Mode / DND** — silences notifications *only if* notification
   policy access is granted, and confirms the margin was actually reached.
5. **Hardware locks**: pin `min_refresh_rate`/`peak_refresh_rate` to the
   panel's max, enable the OEM touch-response key, request fixed-performance
   mode, enable the two developer options (finish activities, limit cached
   processes), and restrict metered background data.
6. **Per-game extras** when a game is targeted: whitelist it from device
   idle, raise its own frame cap via `device_config`, and — on Android 12+
   — apply a `game_overlay` intervention.

## Verified-by-read-back design

`settings put` exits `0` whether or not the key took. The engine therefore
never trusts exit codes for settings; it reads the value back after writing.
Rows in `GamingModeReport` such as `lockedRefreshHz`, `discardActivities`,
and `processLimit` are the *read-back confirmation*, or null/"not applicable"
rather than "failed".

> A null report field means "this didn't apply to my device" (wrong Android
> version, feature absent, already enabled by the user). It is deliberately
> distinguished from "refused".

## Fixed Performance Mode

`toggleFixedPerformanceMode` calls `cmd power set-fixed-performance-mode-enabled`.
This reaches `PowerManagerService` → the vendor power HAL's
`MODE_FIXED_PERFORMANCE`:

- It exists on **Android 11+**; older builds get a clear "phone too old" answer.
- Vendors may accept the call and ignore it, so success means *the framework
  accepted the request* — which is all any caller (including CTS) can tell.
- The point is **consistency**, not peak speed; the fixed point is often held
  slightly below the boost ceiling for thermal headroom.

## ART Optimizer (the "booster")

`runArtOptimization` recompiles installed apps with real `cmd package
compile` dexopt:

- Skips apps already in the requested filter (querying `dumpsys package
  dexopt` first), unless `force` is set.
- Excludes CatSmoker's own package so a recompile can't kill the process
  doing the compiling.
- Counts results from what the shell actually answered (compiled / skipped /
  failed), feeds a live progress bar, and supports cancellation that never
  overwrites a completed or cancelled outcome.
- Uses `ProcessBuilder("su", ...)` directly for root so the in-flight
  process can be `destroy()`ed on cancel (Shizuku binder calls block).

## RAM Boost

`manualBoostRam` measures `MemAvailable` (from `/proc/meminfo`, falling back
to `ActivityManager`) **before** trimming, then trims caches, force-stops
background targets, waits for the kernel to reclaim, and measures again. The
freed amount is the difference — reported as `null` rather than `0` when the
device won't reveal memory figures.

## Snapshot & revert

Activation persists a snapshot of every setting it touches plus the exact set
of packages it suspended. Deactivation can therefore:

- Un-suspend precisely the packages that were frozen.
- Restore the user's original refresh/touch/DND values.
- Only touch background-data restriction if **we** engaged it (never turn off
  a restriction the user set themselves).

## Key state types

- `GamingModeState` — `Idle` / `Enabling(progress)` / `Active` / `Disabling` / `Error`.
- `GamingModeReport` — verified outcomes + a human-readable `unavailable` list.
- `BoosterState` / `BoosterOutcome` — live progress and a terminal, never-rewritten outcome.

## Secrets to keep in mind
- The device names referenced in the code (Vivo/iQOO packages, launchers,
  etc.) are behavioral heuristics, not declarations — changes there affect
  what a particular device class gets.
