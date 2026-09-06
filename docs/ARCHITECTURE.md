# Architecture

A high-level map of the CatSmoker Android codebase and how the layers fit together.

## Stack

| Concern | Choice |
| --- | --- |
| Language | Kotlin 2.4 |
| UI | Jetpack Compose (Material 3, BOM 2026.08) |
| Navigation | `androidx.navigation:navigation-compose` |
| DI | Hilt (Dagger) |
| Async | Kotlin Coroutines + Flow |
| JSON | Gson |
| Root | libsu (`com.github.topjohnwu.libsu:core`) |
| Piecemeal privileges | Shizuku (`dev.rikka.shizuku:api` + `provider`) |
| Runtime hooking | LSPosed (Xposed API, `compileOnly`) |
| Min / target SDK | API 27 / API 36 |

## Module layout

Single application module under `app/`. There is no separate
`core`/`feature` Gradle split; features are separated by Kotlin packages.

```
app/src/main/java/com/catsmoker/app/
├── system/                 # app-wide wiring: entry points, DI, shell, navigation, ads
│   ├── MainActivity.kt
│   ├── CatsmokerApp.kt
│   ├── config/SpoofConfigProvider.kt
│   ├── di/                 # Hilt modules (ServiceModule, EngineModule)
│   ├── navigation/         # Routes + AppNavHost
│   ├── shell/ShellRunner.kt  # root + Shizuku command execution
│   └── ads/AdManager.kt      # Start.io ads
├── shared/
│   ├── data/               # models, repositories, presets
│   └── ui/                 # theme, reusable components
└── features/               # one package per feature screen
    ├── main/               # home dashboard + telemetry (MetricsEngine)
    ├── gamingtools/        # Gaming Mode, booster, RAM boost, overlays, firewall…
    ├── spoofdevice/        # device spoofing (LSPosed, Shizuku, Magisk)
    ├── editgamefiles/      # file engineering
    ├── permissions/        # onboarding permission flow
    ├── logs/               # engineering console
    ├── settings/
    └── about/
```

## Dependency direction

Higher-level feature screens depend on the `system` and `shared` layers,
not the other way around:

```
features/*  ──►  system/*  ──►  shared/*
            └──────────┬──────────┘
                       ▼
              Android framework + 3rd-party SDKs
```

Pure-Kotlin logic (parsers, template builders, models) has no Android
imports and is unit-tested under `app/src/test`.

## Key components

### `ShellRunner` (`system/shell/ShellRunner.kt`)
The single choke point for privileged work. It routes a command through
whatever channel is available — `su` (libsu), the Shizuku binder, or a
plain `/system/bin/sh` — and caches which channel works. Everything else
calls `exec`/`execSafe` rather than spawning processes itself.

### `CatsmokerApp` + `MainActivity`
Application entry point and Compose host. A Hilt module provides
`ShellRunner`, the engines and repositories used across screens.

### Engines
- `MetricsEngine` — telemetry/overlay data (see `METRICS_ENGINE.md`).
- `GamingEngine` — Gaming Mode + booster services (see `GAMING_MODE.md`).

### Overlay & background services
Several long-running `Service` implementations live under
`gamingtools/tools/` (crosshair, performance overlay, auto force-stop,
VPN firewall, app booster). Each reads/writes shared prefs and StateFlows
so the Compose UI and the overlay stay in sync.

## Concurrency model

- Every engine exposes `MutableStateFlow`/`StateFlow` so Compose can
  collect it, and keeps a `CoroutineScope(SupervisorJob() + Dispatchers.IO)`.
- Shell work is always launched on a background dispatcher and funneled
  through `ShellRunner`.
- Long-running sweeps (e.g. ART dexopt) guard against re-entrancy with
  `AtomicBoolean` flags and check a cancellation flag between items, so a
  stop always lands cleanly.
- Shared prefs are the persistence layer for "is Gaming Mode active",
  user game library, spoof profiles, etc. State is restored on restart.

## Persistence

- `SharedPreferences` — feature state, user game library, spoof profile
  assignments, Gaming Mode snapshots.
- Game files — read/written through SAF, Shizuku or manual export.
- Spoof profiles — JSON (Gson) with device presets.

## See also
- [`GAMING_MODE.md`](GAMING_MODE.md)
- [`METRICS_ENGINE.md`](METRICS_ENGINE.md)
- [`SPOOF_DEVICE.md`](SPOOF_DEVICE.md)
- [`SECURITY.md`](SECURITY.md)
- [`BUILD.md`](BUILD.md)
- [`CODING_STYLE.md`](CODING_STYLE.md)
