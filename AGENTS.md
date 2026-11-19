# Repository Guidelines

## Project Structure & Module Organization

Eta is a single-module Android assistant built with Kotlin and Jetpack Compose.

- `app/src/main/kotlin/io/github/mangi/eta/`: `agent/` contains runtime and tools, `ui/` Compose screens, `data/` persistence, and `hook/` OEM/Xposed integrations.
- `app/src/test/kotlin/`: JVM tests mirroring production packages.
- `app/src/main/res/` and `app/src/main/assets/`: localized resources, bundled skills, catalogs, and licenses.
- `app/src/main/cpp/` and `app/src/main/jniLibs/`: terminal helper source and packaged native binaries.
- `gradle/libs.versions.toml` centralizes dependencies; `docs/` explains architecture and `scripts/` maintains native components and catalogs.

## Build, Test, and Development Commands

Use JDK 25 and Android SDK 37 (`platforms;android-37.0`); configure `sdk.dir` in ignored `local.properties`. Run commands from the repository root:

- `./gradlew :app:assembleDebug`: build `app/build/outputs/apk/debug/app-debug.apk`.
- `./gradlew :app:installDebug`: install on a connected Android 13+ device or emulator; open Eta from its launcher icon.
- `./gradlew :app:testDebugUnitTest`: run JVM tests.
- `./gradlew :app:lintDebug`: run Android Lint, which fails on errors.
- `./gradlew :app:assembleRelease`: build with R8 optimization; configure signing for an installable release.

For native rebuilds, follow `docs/TERMINAL_NATIVE.md` and use the NDK version in the version catalog.

## Coding Style & Naming Conventions

Follow official Kotlin style with four-space indentation. Use PascalCase for types and Compose functions, camelCase for other functions/properties, UPPER_SNAKE_CASE for constants, and snake_case for Android resources. Use Android Studio formatting; no standalone formatter is configured. Keep English, Simplified Chinese, and Traditional Chinese resources synchronized.

## Local Fork State

This tree is a local fork: the working directory is **not** equal to `HEAD` (hundreds of
uncommitted files), so read the working tree rather than `git show HEAD:` when judging whether a
behavior exists. `docs/FORK_STATE.md` is the authoritative handover note; read it before changing
agent context handling or tools. It records:

- what exists only locally (virtual screen, tool profiles, MCP schema compaction, memory hot-zone
  compaction) and therefore must not be assumed upstream;
- what was deliberately removed to match upstream on 2026-10-28 (the 3-round compaction lock, the
  tool-profile auto-downgrade ladder, the high-resolution image preview profile);
- the Robolectric platform limit: `nativeruntime-dist-compat` ships only `linux-x86_64`,
  `mac/aarch64`, and `windows/x86_64`, so on linux-aarch64 every `RobolectricTestRunner` class fails
  during `AndroidTestEnvironment.setUpApplicationState`. All such classes use
  `testsupport/EtaRobolectricTestRunner`, which skips instead of failing; treat skipped as
  "not executed here", not as covered.

## Testing Guidelines

Use JUnit 4 and Robolectric for Android-dependent tests. Name classes `<Subject>Test` and methods with descriptive camelCase behavior names. Add regression tests for changed behavior, including persistence migrations and permission boundaries. No numeric coverage threshold is configured. Run one class with `./gradlew :app:testDebugUnitTest --tests '*AppearanceSettingsTest'`. Validate OEM hooks on the relevant ROM/device.

## Commit & Pull Request Guidelines

Follow the history's `type(scope): summary` convention, such as `fix(voice): smooth playback transitions` or `chore(deps): update dependencies`; Chinese summaries are common. Keep commits focused. PRs should describe behavior changes, link related issues, list validation, and include screenshots for UI changes. Record Android/ROM and root/LSPosed setup for integration changes.

## Security & Configuration

Keep API keys, keystores, passwords, and local SDK paths out of Git. Release signing uses `ETA_RELEASE_*` environment variables; see `.github/RELEASING.md`. Use `AgentLogger`/`ModuleLogger` and exclude sensitive payloads from logs.

## Branch & Release Discipline

For **`q3330576893/Eta-por-ultra`**, the maintainer has explicitly authorized **`main` as the sole development and stable release mainline**. This replaces the inherited upstream policy that released from `test/beta-on-latest` and reserved `main` for upstream synchronization. Full policy: `docs/BRANCH_AND_RELEASE.md`; operational signing guide: `.github/RELEASING.md`.

1. Integrate validated feature/fix work into `main`. Temporary review branches are allowed, but user-facing releases come from the intended committed `main` tree. Preserve upstream history and attribution to `Mangi-11/Eta` and `Oo0520/Eta-por`.
2. Every push to `main`, and every manual `workflow_dispatch` release run, must build a signed **Release** APK using `:app:assembleRelease`, R8 optimization, resource shrinking and `debuggable=false`; verify its signing and APK identity before publishing a public GitHub Release. Debug/Beta packages are development options, not substitutes for the public release.
3. Keep the app brand **Eta Ultra** and stable application ID `io.github.mangi.eta`. The local base version/code are `3.5.6` / `2026112100`; CI uses `3.5.6.<run_number>` and `2026112100 + run_number`. Maintain numeric version ordering and increasing version codes across future base/workflow changes.
4. Publish the fixed asset `Eta-Ultra.apk` together with SHA256 and `release-info.json`. Verify the expected commit, version, signature and assets at `https://github.com/q3330576893/Eta-por-ultra/releases/latest`; historical versions stay under `/releases`.
5. Every development update must maintain release notes or a changelog, both Chinese/English README descriptions and the repository introduction according to actual implemented behavior, then commit the complete changes. `scripts/release_metadata.py` maintains README `release-info` blocks and generated release metadata; it does not replace human review of feature descriptions. Preserve one `<!-- release-info:start -->` / `<!-- release-info:end -->` pair in each README and prevent metadata-only updates from creating release loops.
6. Keep signing material out of Git. Actions Secrets are `ETA_RELEASE_KEYSTORE_BASE64`, `ETA_RELEASE_STORE_PASSWORD`, `ETA_RELEASE_KEY_ALIAS` and `ETA_RELEASE_KEY_PASSWORD`; CI supplies the decoded keystore through `ETA_RELEASE_STORE_FILE`. Local signing material lives under the user's `AppData/Local/EtaBuildTools/signing`, outside the source tree. Reuse the same release certificate for upgrades.
7. Before local release verification, check that the branch is `main`, the source tree is clean and committed, and the intended change is in `git log`. A failed build/signature/publication must be fixed and rerun; report actual CI results instead of claiming a release from a source push alone. "Did my change ship?" is answered by comparing the intended commit with the public Release tag/commit and assets.
