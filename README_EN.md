# Eta Ultra

**A system-level AI agent and voice assistant for Android.** Chat, screen context, tools, terminal access and multi-step tasks share one Agent Runtime, with an immersive Gemini-style voice overlay.

[简体中文](README.md) | **English**

[Repository](https://github.com/q3330576893/Eta-por-ultra) · [Latest release](https://github.com/q3330576893/Eta-por-ultra/releases/latest) · [All releases](https://github.com/q3330576893/Eta-por-ultra/releases) · [Issues](https://github.com/q3330576893/Eta-por-ultra/issues)

Maintained by [q3330576893](https://github.com/q3330576893), Eta Ultra builds on the [Oo0520/Eta-por](https://github.com/Oo0520/Eta-por) customization fork and the original [Mangi-11/Eta](https://github.com/Mangi-11/Eta). Existing agent, system integration and community features remain part of the foundation. This iteration's voice overlay changes are listed separately below; attribution is retained at the end.

## Download and install

<!-- release-info:start -->
**Latest stable release: [3.4.1.1](https://github.com/q3330576893/Eta-por-ultra/releases/tag/v3.4.1.1)** · Android 13+ · ARM64

[Download signed Release APK](https://github.com/q3330576893/Eta-por-ultra/releases/latest/download/Eta-Ultra.apk) · [All releases](https://github.com/q3330576893/Eta-por-ultra/releases) · [Release notes](https://github.com/q3330576893/Eta-por-ultra/releases/tag/v3.4.1.1)

Source: [`d362f99`](https://github.com/q3330576893/Eta-por-ultra/commit/d362f99d555cc6a965f985baba82e557b950d23d) · versionCode `2026111602`

SHA-256: `181db91e9472b94b185f22f7e173339557a905e63353e8c64aa42e1edcda1816`
<!-- release-info:end -->

Install `Eta-Ultra.apk` from a Release. Stable builds retain the application ID `io.github.mangi.eta`. An in-place upgrade requires a matching application ID and signing certificate plus an increasing `versionCode`; installations from upstream or another signing key may need a data backup and migration first. Configure a model provider in the app, then enable voice, accessibility, default digital assistant, overlay or Root/LSPosed integration as needed. See [device support](docs/ROOTLESS_SUPPORT.md) for per-feature requirements.

GitHub Releases is the public distribution channel. Debug APKs and the optional development Beta variant are for development; the default user download is a signed release APK.

## This iteration: Gemini-style voice overlay

- **Full-screen blue/violet gradients:** separate light and dark palettes, with stable surfaces for readable text and input.
- **Four-color flowing waveform:** smoothed microphone levels, the existing Shader animation tracks, and a Canvas fallback for software rendering, loading failures or drawing errors.
- **A consistent capsule composer:** a 60dp minimum height and 32dp corners across text and listening modes, with keyboard switching, live recognition text, send and finish-recording controls.
- **Real state feedback:** connecting, listening, transcribing, processing, running tools, speaking, completed and failed states have corresponding labels and colors, with accessibility semantics.
- **Visible screen context:** source application, capture time and snapshot-age information, plus a switch that excludes screen context from the current request.
- **Existing interaction preserved:** hold to talk, release to send, drag out to cancel, suggestion chips, IME insets, drag gestures and handoff to the full conversation.

Implementation details, reference projects and future candidates are separated in the [voice overlay implementation and research notes](docs/VOICE_ASSISTANT_GEMINI_RESEARCH.md).

## Current capabilities

Most capabilities below are inherited from Eta and the community customization branch. They are not all additions made in this Eta Ultra iteration.

| Area | Capabilities |
| --- | --- |
| Agent execution | A shared Agent Runtime, GUI agent, system APIs, visible tool progress, memory and learned experience, scheduled and event tasks |
| Models and extensions | Multiple providers, Codex/Grok subscription sign-in, MCP, Skills, schema validation, slash commands and historical message references |
| Terminal and workspace | Android terminal, private workspace file browsing, Markdown preview/editing, code blocks and offline KaTeX |
| Screen and system integration | Wake-time screen context, Root virtual display and mini window, continuous gestures, Xposed/OEM assistant entry points, ColorOS Fluid Cloud and status capsules |
| Conversations and collaboration | Categories, favorites/pins, search/navigation, multiple sessions, concurrent subagents and tool activity panels |
| Voice | System/cloud ASR, multiple TTS services, automatic or manual read-aloud, resume reading, hold to talk and a global recognition switch |
| Appearance and input | Miuix UI, light/dark themes, Monet/accent colors, tap-to-steer/hold-to-queue sending, tools and skills above the composer |

Availability depends on the model, ROM, voice service, permissions and device. Current packaging primarily targets Simplified Chinese and ARM64.

## Build from source

| Component | Requirement |
| --- | --- |
| Java | JDK 25 |
| Android SDK | SDK 37, including `platforms;android-37.0` |
| Gradle | Repository Wrapper 9.7.1 |
| Minimum Android / ABI | Android 13 (API 33) / `arm64-v8a` |
| Development and release branch | `main` |

```bash
git clone https://github.com/q3330576893/Eta-por-ultra.git
cd Eta-por-ultra
./gradlew :app:assembleDebug
```

Use `gradlew.bat` on Windows. Set `sdk.dir` in the ignored local `local.properties`; the first build downloads dependencies. Native rebuild requirements are documented in [terminal native components](docs/TERMINAL_NATIVE.md).

Configure this repository's release signing certificate through environment variables before building a release:

```bash
./gradlew :app:assembleRelease
```

The local base is version `3.4.1` with `versionCode` `2026111601`. CI supplies the release run number to produce the four-part numeric version `3.4.1.<run_number>` and code `2026111601 + run_number`. The app update checker can compare numeric components, and the run number increases over time. Release builds use R8 optimization and resource shrinking with `debuggable=false`. See the [release guide](.github/RELEASING.md) for signing and publication.

## Updates and releases

`main` is the only development and stable release mainline in this repository. Every development update should keep the Chinese/English introductions and release notes or changelog aligned with implemented behavior, then commit the validated sources before pushing. CI builds the release APK, verifies signing, computes SHA256, generates `release-info.json` and release notes, and publishes a public GitHub Release.

[`scripts/release_metadata.py`](scripts/release_metadata.py) maintains the version/download sections inside the README `release-info` markers and generates release metadata. It records build and commit facts; maintainers still review the changes and update feature descriptions. Fix failed builds or signature verification before rerunning the workflow. A Debug APK is not a substitute for the release artifact.

See [branch and release discipline](docs/BRANCH_AND_RELEASE.md) and the [release operation guide](.github/RELEASING.md). Report problems through [this repository's Issues](https://github.com/q3330576893/Eta-por-ultra/issues) with device, Android/ROM, app version, reproduction steps and relevant logs.

## Documentation

- [Agent Runtime](docs/AGENT_RUNTIME.md) · [Memory and learned experience](docs/EXPERIENCE_LEARNING.md) · [Scheduled/event tasks](docs/AUTOMATION.md)
- [Root virtual display](docs/VIRTUAL_SCREEN.md) · [Device support](docs/ROOTLESS_SUPPORT.md) · [Technical notes](docs/TECHNICAL.md)
- [Voice configuration](docs/VOICE.md) · [Characters](docs/CHARACTERS.md) · [Voice overlay changes](docs/VOICE_ASSISTANT_GEMINI_RESEARCH.md)
- [Release guide](.github/RELEASING.md) · [Branch and release discipline](docs/BRANCH_AND_RELEASE.md)

## Origins, credits and license

- [Mangi-11/Eta](https://github.com/Mangi-11/Eta): the original Eta project and its Agent Runtime, system APIs, terminal, GUI agent, Xposed integration and other core foundations.
- [Oo0520/Eta-por](https://github.com/Oo0520/Eta-por): the direct community customization foundation, including conversation, Markdown, Fluid Cloud, virtual display and subagent improvements.
- Thanks to the upstream and community patch authors, and to reference/dependency projects including [Pi Coding Agent](https://github.com/earendil-works/pi), [Hermes Agent](https://github.com/NousResearch/hermes-agent), [OmniBot](https://github.com/omnimind-ai/OmniBot), [libxposed API](https://github.com/libxposed/api) and [Miuix](https://github.com/compose-miuix-ui/miuix).

Eta Ultra is an independently maintained customization fork at [q3330576893/Eta-por-ultra](https://github.com/q3330576893/Eta-por-ultra), distinct from the original author's official distribution. The repository retains the [PolyForm Noncommercial License 1.0.0](LICENSE) and original attribution. Third-party components retain their respective licenses.

> Required Notice: Copyright © 2026 蛮吉 (Mangi-11).
