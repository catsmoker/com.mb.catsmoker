# Contributing

Thanks for wanting to help CatSmoker. This project is released under
**CC BY-NC-SA 4.0** (see [LICENSE](../LICENSE)), so please read that before
contributing — it affects what you can do with your contribution.

## Before you start

- **Open an issue first** for large changes or new features. This lets
  maintainers weigh in before you invest the time (and before a big PR shows
  up unannounced).
- Check the [open issues](https://github.com/catsmoker/com.catsmoker.app/issues)
  for overlap — someone may already be working on it.
- Bug reports go to the [Issue Tracker](https://github.com/catsmoker/com.catsmoker.app/issues),
  [Discord](https://discord.com/invite/HQC5BwcXtS), or
  [Telegram](https://t.me/CATSM0KER).

## New game requests

To request support for a game you'll need its **full package name** (e.g.
`com.tencent.ig`). FRs without a package name can't be triaged.

## Development setup

See [BUILD.md](BUILD.md) for prerequisites and commands.

```bash
./gradlew assembleDebug        # build
./gradlew testDebugUnitTest    # run unit tests
```

## What we look for in a PR

- Pure logic (parsers, builders, templates) goes in a class that has **no
  Android imports** and gets a **unit test** under `app/src/test`. There are
  existing patterns to copy in `features/main/engine/parsers/` and
  `features/spoofdevice/tools/`.
- Follow the conventions in [CODING_STYLE.md](CODING_STYLE.md): document
  *why* non-obvious code exists, verify settings by reading them back, and
  never report a manufactured `0` as a measurement.
- Keep the UI honest: if a device refuses an optimization, surface the real
  reason instead of claiming success.
- Match the codebase's documentation style — this project favors
  explanation-heavy KDoc comments on anything with a surprising behavior.

## Safety expectations

This app modifies system settings, hooks game processes, and spoofs device
identity. Contributions must:

- Respect the **hard allowlist** of packages that must never be suspended
  (Shizuku, Magisk, CatSmoker itself).
- Revert cleanly — anything a feature engages at "on" must have a symmetric
  "off" that restores the *user's* prior state.
- Not introduce data collection. Privacy is a stated feature.

## License & NC clause

The non-commercial clause of CC BY-NC-SA 4.0 applies to the whole project
and any contribution you make to it. By submitting a PR you agree to license
your contribution under the project's license terms.
