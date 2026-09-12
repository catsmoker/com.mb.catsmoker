# FAQ

Common questions about building, running, and contributing to CatSmoker's
codebase.

## Why does the app need root or Shizuku?

Most of what CatSmoker does touches system-level state — `settings put`,
`pm suspend`, `cmd power`, `dumpsys SurfaceFlinger`. Android's sandbox blocks
normal apps from these, so the app needs a privileged channel: **root**
(libsu), **Shizuku** (a user-granted elevated binder on Android 11+), or the
**SAF/manual export** path for file engineering.

## What's the difference between the LSPosed and Shizuku spoofing paths?

- **LSPosed** hooks the game process at runtime to override `getprop` — no
  game files are touched. Requires root.
- **Shizuku** runs privileged commands via the Shizuku binder to apply the
  spoof to system/game data without root.

See [SPOOF_DEVICE.md](SPOOF_DEVICE.md).

## The UI says a step "didn't apply" / "unavailable". Is that a bug?

Usually no. The app verifies every change by reading it back, and it
deliberately separates **"not applicable"** (device too old, feature absent,
already on) from **"refused"** (the device rejected it) from a real bug.
Being told the truth is the design, not a failure mode. See the "Honesty
rules" in [CODING_STYLE.md](CODING_STYLE.md).

## Why does FPS show "unknown" for me sometimes?

SurfaceFlinger FPS needs a privileged channel. Without root/Shizuku the app
falls back to counting its own vsync callbacks, which measures the display
rate, not the foreground game — and only while the app is visible. The ping
to `8.8.8.8` may also be blocked on your network, which is reported as "no
reading", not `0 ms`. See [METRICS_ENGINE.md](METRICS_ENGINE.md).

## Why is the Xposed API `compileOnly`?

The LSPosed module classes only make sense inside the LSPosed runtime. The
app itself runs on a normal Android classpath, so the Xposed API is compiled
against but never bundled.

## Will my spoof break anything? Is there a safe mode?

Spoofing can make a game (or OS) behave unexpectedly. `SPOOF_SAFE_MODE` is
the emergency path to remove active hooks/profiles and revert. Gaming Mode
also records a snapshot before it changes anything so deactivation restores
your prior configuration.

## How do I add support for a new game?

Provide the game's **full package name** via an issue, Discord, or Telegram.
See [CONTRIBUTING.md](CONTRIBUTING.md).

## My PR's new class has no Android imports — why does that matter?

Pure-Kotlin logic is unit-testable without a device. Keep parsers, template
builders, and models free of Android framework imports so they slot into the
existing `app/src/test` suite. See [ARCHITECTURE.md](ARCHITECTURE.md).

## How do I build the debug APK?

```bash
./gradlew assembleDebug
```

Full details in [BUILD.md](BUILD.md).
