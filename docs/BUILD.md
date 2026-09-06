# Build From Source

Requirements and commands for building CatSmoker locally.

## Prerequisites

- **Android Studio** (stable).
- **JDK 17**.
- **Android SDK** with the platform/NDK versions referenced below.
- **Android NDK** `27.0.12077973` (set in `app/build.gradle.kts`).

## Key versions

| Item | Value |
| --- | --- |
| AGP | 9.4.0 |
| Kotlin | 2.4.10 |
| KSP | 2.3.10 |
| Compose BOM | 2026.08.00 |
| Min SDK | 27 (Android 8.1) |
| Target SDK | 36 |
| Compile SDK | 37 |
| Version | 2.0.0 (code 6) |
| NDK | 27.0.12077973 |

## Build

From the repository root:

```bash
./gradlew assembleDebug
```

(On Windows: `.\gradlew.bat assembleDebug`.)

The debug APK is produced at:

```
app/build/outputs/apk/debug/app-debug.apk
```

## Running unit tests

```bash
./gradlew testDebugUnitTest
```

Tests live in `app/src/test/java/com/catsmoker/app/` and cover pure-Kotlin
logic: parsers, config templates, the Magisk module builder, gaming
interventions, and related utilities.

## Lint

Lint is configured **not** to abort the build on errors (`abortOnError = false`,
`checkReleaseBuilds = false`) so minor warnings never block a build. You can
still run it explicitly:

```bash
./gradlew lint
```

## Release notes

- `isMinifyEnabled = true` and `isShrinkResources = true` for the release
  build type; the release APK signs with the **debug** signing config, so a
  release-minded build should supply its own signing configuration before it
  is published.
- The Start.io ad SDK id can be overridden via `STARTIO_APP_ID` in
  `local.properties`; it defaults to a built-in value.

## Troubleshooting

- **NDK not found** — ensure the NDK version above is installed via SDK Manager.
- **Platform/build-tools missing** — let Android Studio sync and install what
  it asks for.
- **Xposed API not resolving** — the `de.robv.android.xposed:api` dependency
  is `compileOnly` inside the Gradle catalog; it is expected to resolve from
  JitPack/remote repos and only needs to compile, not package.
