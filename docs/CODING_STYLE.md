# Coding Style & Conventions

Conventions observed throughout the CatSmoker codebase. Follow these when
contributing so new code reads like the code around it.

## Package & file organization

- One feature per package under `features/` (e.g. `gamingtools`, `spoofdevice`).
- Shared cross-feature code lives in `shared/` (`data`, `ui`).
- App-wide wiring lives in `system/` (navigation, DI, shell, ads).
- Each screen typically has a `*Screen.kt` (Compose UI) + `*ViewModel.kt`,
  with engine/service classes kept separate from UI.

## Kotlin / Compose

- **State drives UI.** Engines expose `StateFlow`; ViewModels are thin and
  delegate to engines/services. Compose collects flows, never mutates them.
- **Immutable state copies.** Use `MutableStateFlow.update { it.copy(...) }`
  rather than read-then-write, especially from concurrent coroutines — a lost
  update is a lost event.
- Background work goes on a dedicated `CoroutineScope(SupervisorJob() +
  Dispatchers.IO)`, never on the main thread, and UI updates hop back to
  `Dispatchers.Main`.

## Shell & privileges

- All command execution is funneled through `ShellRunner`
  (`system/shell/ShellRunner.kt`), never spawned ad hoc — except when a
  blocker (like cancelling an in-flight dexopt) genuinely needs the raw
  `Process`, which is documented at the call site.
- `ShellRunner` decides the best channel (root / Shizuku / plain shell) and
  caches it.

## Honesty rules (important)

These recur across the codebase — a reviewer will expect them:

1. **Verify by read-back.** `settings put` exits `0` whether the key took or
   not. Read the value back before reporting success; a setting change is a
   fact, not a hope.
2. **Never invent a `0`.** A device at `0` RAM, `0 ms` ping, or `0 %` CPU does
   not exist. Report `null`/`unavailable` with the real reason instead.
3. **Distinguish "not applicable" from "failed".** A report field should be
   `null` when the device is too old or the feature is absent, not a refusal.
4. **Reversible by design.** Anything engaged at "on" must have a symmetric
   "off" that restores the *user's* prior state (record a snapshot first).
   Never turn off something the user set themselves.
5. **Count results, not attempts.** Tally what the shell actually answered
   (e.g. packages actually suspended), then report that tally.

## Comments & documentation

- This codebase favors **explanation-heavy KDoc** on anything non-obvious.
  Comments explain *why*, not *what* — especially around race conditions,
  OEM quirks, and the history of past bugs (see `MagiskModuleBuilder` for a
  canonical example of documenting a past mistake to prevent regression).
- Mark public API and state types with KDoc describing what they represent
  and their edge cases.

## Testing

- Pure logic gets a **unit test** under `app/src/test`, with no Android
  dependencies.
- Follow existing test structure (`ParserTest`, `BuilderTest`, `ConfigTest`).
- Tests run via `./gradlew testDebugUnitTest`.

## Miscellaneous

- No unnecessary comments or dead code — the bar for removing "placeholder"
  content is high precisely because a dead-looking file once carried
  behavior (see the Magisk asset bug).
- Shared prefs are the persistence layer; put persistence helpers where the
  feature can find them (`gaming_engine_prefs`, `AppPrefs`, etc.), and read
  existing keys before introducing new ones.

## Security

Any code touching privileged paths (shell, the exported content provider,
Shizuku, overlays, the VPN) must follow the hard rules in
[SECURITY.md](SECURITY.md) — route through `ShellRunner`, quote dynamic
arguments, preserve UID scoping, keep the snapshot/revert pairing, and never
add to the suspension allowlist.

## Strings & localization

User-visible copy goes into `res/values/strings.xml` and is read through
`stringResource(...)` / `context.getString(...)` — not hardcoded in Kotlin.
Use numbered placeholders (`%1$s`) so translations can reorder arguments.
See [TRANSLATION.md](TRANSLATION.md) for the naming conventions and the
migration backlog.
