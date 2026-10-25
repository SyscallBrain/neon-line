# Contributing to NeonLine

Thanks for your interest in improving NeonLine. Bug reports, translations, accessibility feedback and pull requests are all welcome.

## Reporting bugs

Please open an issue with:

- what you did, what you expected and what happened instead;
- the level and difficulty, or the free mode, where it happened;
- your device model and Android version;
- a screenshot when the problem is visual.

## Development setup

1. Install a JDK (17 or newer; the project is built with JDK 21) and the Android SDK with platform `android-37.1`.
2. Point Gradle at them:

   ```bash
   export JAVA_HOME=/path/to/jdk
   export ANDROID_HOME=/path/to/android-sdk
   ```

3. Build and test:

   ```bash
   ./gradlew :core:test :leveltool:test :app:testDebugUnitTest
   ./gradlew :app:assembleDebug
   ```

   Or run everything the CI runs, including lint:

   ```bash
   bash tools/verify.sh
   ```

No emulator is needed. Game rules belong in `:core` and are tested on the JVM. UI logic that can be separated from Compose (timeline maths, HUD numbers, board geometry) is tested on the JVM too; the rest of the UI is checked on a real device.

## Pull requests

- Create a branch from `main` and keep each pull request focused on one change.
- Make sure `bash tools/verify.sh` passes and say in the description how you tested the change, including which device you used for UI changes.
- Add or update unit tests when you change rules, the AI, persistence or content tooling.
- Keep commits small, with messages in the imperative mood ("Add pop-out rule").

## Coding conventions

- New code is in English: identifiers, comments, log and error messages.
- Every text the player sees goes in `app/src/main/res/values/strings.xml` (English), with a translation in each `values-<lang>/strings.xml`. Do not hardcode visible text in Kotlin.
- Match the style of the surrounding code. Comments explain *why*, not *what*.
- `:core` must stay free of Android, clock and platform-random dependencies, so that results are reproducible. Randomness comes from the versioned `SplitMix64` only.
- The game, the AI, the solver and the certificate checker share `:core`. Never copy engine logic into another module or language.
- Any change to the engine's or the AI's observable behavior needs a new `engineVersion` and regenerated certificates.
- No "Connect Four" trademarks in the product, the code or the assets; the game is described as four in a line.

## Changing level content

Levels in `content/` are certified, and every build replays every certificate. When you change a level:

1. Regenerate it with `certify` (run `./gradlew :leveltool:run` to see the options), or edit its JSON and raise its `revision`.
2. Run `./gradlew :leveltool:validateCatalog :leveltool:verifyCertificates`.
3. Commit the level, the updated `content/catalog.json` and the new certificates together.

## Translations

To add a language, copy `app/src/main/res/values/strings.xml` and `level_titles.xml` to `values-<code>/`, translate every `<string>` and `<string-array>`, and leave the strings marked `translatable="false"` out. Check the result on a device, because the board labels are tight on small screens.

## License

By contributing, you agree that your contributions are licensed under the [MIT License](LICENSE).
