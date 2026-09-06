# Translation & Localization

Current state of internationalization (i18n) in CatSmoker and how to move
strings into resources.

## TL;DR

- The project **does** use Android resources for a subset of strings:
  `app/src/main/res/values/strings.xml` (63 entries), read via
  `stringResource(R.string.*)` in Compose and `context.getString(...)` in
  ViewModels/services.
- It is **English-only and partially migrated**. There are no `values-*`
  locale folders, and many user-visible strings are still hardcoded inline in
  Kotlin (100+ across screens, dialogs, cards, and services).
- There is no runtime language switcher; translations are handled the
  standard Android way — per-locale resource folders, selected by the system.

This document records the current state, the conventions to follow when
adding strings, and a checklist for closing the migration gap.

## How strings flow today

Compose UI:

```kotlin
text = stringResource(R.string.booster_status_done, optimized, skipped, failed)
```

ViewModel / service (non-Compose context):

```kotlin
context.getString(R.string.custom_upload_failed, msg)
```

Formatting placeholders (`%1$s`, `%2$d`, `%3$s`) are already used correctly
in resources such as `booster_status_compiling` and `booster_notification_progress`,
so reordering per-language is supported.

## Naming & organization conventions

`strings.xml` is grouped by feature with banner comments
(`GENERAL`, `MAIN ACTIVITY`, `GAMING TOOLS SCREEN`, `SERVICES`, …). Follow
that layout:

- Prefix keys by feature/screen: `gt_*` (Gaming Tools), `dash_*` (dashboard),
  `logs_*`, `booster_*`, `overlay_*`, `crosshair_*`.
- Keep the explanatory comment when a string has non-obvious context — e.g.
  `dash_fps_label_ui` documents *why* it says UI FPS.
- The only array in the file is `scope` (the LSPosed module scope game list) —
  per-game metadata lives in Kotlin and must **not** be duplicated as strings.

## Known gaps (the migration backlog)

Strings still hardcoded in Kotlin, currently untranslatable:

1. **Compose screens** — dialog titles/buttons, section headers, status
   copies across `GamingToolsScreen.kt`, `SpoofDeviceScreen.kt`,
   `ProfilesListScreen.kt`, `ProfileEditorScreen.kt`, `AppAssignmentScreen.kt`,
   `EditGameFilesScreen.kt`, `SettingsScreen.kt`, `AboutScreen.kt`.
2. **Cards** — `GamingModeCard.kt`, `FixedPerformanceModeCard.kt`,
   `RamBoostCard.kt` and `GamingToolsScreen.kt` define their copy inline.
3. **Foreground service notifications** — `GamingModeService.kt`
   ("Background apps suspended • Performance locked"),
   `CrosshairOverlayService.kt` ("The crosshair is taking taps while you move it.")
   hardcode notification bodies, while other services (`AppBoosterService`,
   etc.) correctly use resources.

> [!WARNING]
> Duplication hazard: some labels already exist as resources *and* as
> hardcode (e.g. "Done"/"Centre"/"Clear" are in `strings.xml` while sibling
> strings like "Move"/"CANCEL" are inline). When migrating, prefer the
> existing resource keys and remove the inline copy.

## Adding a new locale

Create a locale folder next to `values/` and mirror the keys:

```
app/src/main/res/values-xx-rYY/strings.xml   # e.g. values-es, values-zh-rCN, values-in
```

Rules:

- **Same keys, same placeholders.** Translation values must keep `%1$s`
  markers; the target language is free to reorder them (that is why they are
  numbered).
- **No plural strings yet** — the codebase currently uses `%1$d of %2$d`
  phrasing rather than `plurals`. If a translation needs plural rules, add a
  `<plurals>` resource and a small `pluralStringResource`-style helper.
- Keep the app name and URL strings identical across locales.
- Do not translate the `scope` array (package names).

## Adding a new string — checklist

1. Add to `values/strings.xml` under the right section, or create the
   section header comment if none matches.
2. Reference it via `stringResource(R.string.<name>, args...)` in Compose or
   `context.getString(R.string.<name>, args...)` outside Compose.
3. Only put format args that vary at runtime. Never break a sentence into two
   resources — that defeats per-language reordering.
4. Verify the build still assembles (stale `R.string.*` references fail the
   build; the resource merger refuses unused ids in release).

## Useful tooling

- **Android Studio lint** flags hardcoded strings (`HardcodedText`) on XML
  and many Compose string literals. The project sets `lint.abortOnError=false`,
  so these are warnings, not build breakers — expect them to stay warnings
  until the migration backlog is cleared.
- Android Studio's **Extract string resource** refactoring (Alt+Enter on a
  hardcoded literal) is the fastest way to migrate the backlog item by item.
- CSV round-trip (Translations Editor) works for team-managed translations.

## When hardcoded strings are acceptable

A short, deliberate allowlist:

- Version/flavour literals composed, not translated (`v${BuildConfig.VERSION_NAME}`).
- Dynamic values (file names, package names, sizes) — translatable copy
  should not be concatenated into them where a placeholder works instead.
- Technical package/property identifiers.

Everything else user-facing should be a resource. If you're touching a file
that still has inline copy, migrate those strings while you're there.