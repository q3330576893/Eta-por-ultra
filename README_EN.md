# Eta Ultra

**A system-level AI agent and voice assistant for Android.** A transparent bottom capsule connects free system/Vosk speech, screen understanding, tools and multi-step tasks through the system assistant session.

[简体中文](README.md) | **English**

[Repository](https://github.com/q3330576893/Eta-por-ultra) · [Latest release](https://github.com/q3330576893/Eta-por-ultra/releases/latest) · [All releases](https://github.com/q3330576893/Eta-por-ultra/releases) · [Issues](https://github.com/q3330576893/Eta-por-ultra/issues)

Maintained by [q3330576893](https://github.com/q3330576893), Eta Ultra builds on the [Oo0520/Eta-por](https://github.com/Oo0520/Eta-por) customization fork and the original [Mangi-11/Eta](https://github.com/Mangi-11/Eta). Existing agent, system integration and community features remain part of the foundation. This iteration's voice overlay changes are listed separately below; attribution is retained at the end.

## Download and install

<!-- release-info:start -->
**Latest stable release: [3.5.6.12](https://github.com/q3330576893/Eta-por-ultra/releases/tag/v3.5.6.12)** · Android 13+ · ARM64

[Download signed Release APK](https://github.com/q3330576893/Eta-por-ultra/releases/latest/download/Eta-Ultra.apk) · [All releases](https://github.com/q3330576893/Eta-por-ultra/releases) · [Release notes](https://github.com/q3330576893/Eta-por-ultra/releases/tag/v3.5.6.12)

Source: [`3912df4`](https://github.com/q3330576893/Eta-por-ultra/commit/3912df404d783616fa827d119b401dd99ca597e3) · versionCode `2026112112`

SHA-256: `66c5a0b8d7171a6c5ff4d7d93fedf5e0e980381bfd61e3bc68ef702eb74665d0`
<!-- release-info:end -->

Install `Eta-Ultra.apk` from a Release. Stable builds retain the application ID `io.github.mangi.eta`. An in-place upgrade requires a matching application ID and signing certificate plus an increasing `versionCode`; installations from upstream or another signing key may need a data backup and migration first. Configure a model provider in the app, then enable voice, accessibility, default digital assistant, overlay or Root/LSPosed integration as needed. See [device support](docs/ROOTLESS_SUPPORT.md) for per-feature requirements.

GitHub Releases is the public distribution channel. Debug APKs and the optional development Beta variant are for development; the default user download is a signed release APK.

## Xingchi compatibility and precise access diagnostics

- **Manual Xingchi client compatibility presets**: exact Xingchi HTTPS/API-key connections offer manual **OpenCode, WorkBuddy, Codex and DeepSeek Harness** compatibility presets, restored per connection after saving. Switching replaces and deduplicates only `User-Agent`, retaining credentials and other fields; URL and protocol remain independently controlled.
- The existing compatibility action still fills a root URL with `/v1`, selects Responses and supplies OpenCode only when no nonblank identity exists. Unknown identities remain custom/current and editable under request headers. Other providers retain their defaults.
- Catalog and inference share headers. Responses replays full context and tool results with `store=false`, without a stored server-side chain. Client rejection and unassigned key groups remain distinct; identities are never rotated automatically.
- **Live verification on November 20, 2026, 12:14–12:26 UTC+8 completed streamed text and function-call/result round trips for all four final presets.** Default Eta and three versionless candidates were rejected. One WorkBuddy request received text but ended in `response.failed`; that failure was retained and one bounded repeat completed successfully.
- **Delivery recheck on November 21, 2026, 00:23–00:25 UTC+8: each of the four presets completed catalog, streamed text, function call and result replay with a completed terminal event.** A fresh local run discovered 2,450 tests with no failures and 70 platform skips; Lint and Debug assembly passed. Repository tests cover two same-host connections, database reopen and custom-identity persistence.
- These are ETA HTTP compatibility presets, not native-client launches or full application-equivalence claims. The WorkBuddy/DeepSeek Harness `1.0.0` values are tested compatibility candidates, not official current-version declarations. The download panel still identifies the actual published release.

[Captured behavior, configuration and source research](docs/XINGCHI_COMPATIBILITY_2026-11-20.md)

## 3.5.6: copy, review approvals and connected features

The implementation below is in this source revision. The download panel above identifies the version actually published.

- User-message copy remains available during generation; editing, deleting and branching retain their running-state restrictions. Only the visible user-authored text is copied.
- Memory-review candidates keep their original conversation and one-shot token. Local approval commits data with an idempotent receipt; discard/skip does not start another model turn or resume the old task. Expired cards are pruned on return.
- Quick Settings configuration accepts 1–4 strict commands. Results appear on the expanded tile itself, with the next command underneath. Cached-weather and pending-note home-screen widgets are available.
- Text/link sharing opens a local preview with save or chat-draft actions. Notes support completion, restore, deletion and widget completion; legacy data migrates. Storage keeps the latest 50 notes, up to 8,000 characters each.
- Conversation options connect sampling persistence and up to three side-by-side model answers, with independent streams/errors, cancellation, full-text copy and adoption. Comparison has no tool executor. Unsupported sampling fields are visible; extra providers incur their own usage charges.
- The persistent UTF-8 document library supports imports, conversation attachment, source pins, removal and keyword retrieval. Version/line-bound citations open the revalidated source. This is keyword search rather than claimed embedding search.
- NFC uses explicit foreground Reader Mode and exact tag bindings. Rule dry runs explain true/false/unknown conditions without scheduling or executing a task.
- Screen location matches the latest UI-tree text/descriptions, with explicit unsupported results for missing trees. There is no visual OCR/OmniParser integration. Node changes are evidence, not proof of goal completion. Revoking an established virtual route never redirects old coordinates to the main display.
- The restored settings design, default-off screen capture and removed token-statistics/run-details/local-LLM/donation features remain unchanged. Sunset reminders are excluded. App resources retain the existing Simplified Chinese policy.

[Validation record](docs/VALIDATION_2026-11-19.md) · [Changelog](CHANGELOG.md)

## 3.5: bottom capsule, free speech and task feedback

- **Invocation over the current app:** the intermediary assistant Activity is removed. The system session shows a transparent bottom capsule that wraps its content, with bounded replies and drag gestures to open the full conversation or close. OEM invocation behavior still requires device validation.
- **Free speech by default:** AUTO/system modes prefer the device recognizer and fall back once on startup failure to the on-demand Chinese Vosk model. Local ASR needs no speech API key. System TTS prefers an installed offline voice; cloud ASR/TTS remain optional.
- **Continuous speech and captions:** opt into persistent Vosk capture while the capsule is visible. Working AEC or a confirmed headset route, sustained speech and recognized words jointly gate interruption. Keyboard mode, screen off and closing stop capture. The UI presents native word offsets, actual playback ranges and a bounded voice timeline.
- **Direct daily result cards:** time, date, decimal arithmetic, today's calendar and Open-Meteo weather use deterministic handling in chat and the capsule without starting an LLM. Weather requires an explicit city; alarm/timer handoffs open the system confirmation UI and remain distinct from verified creation.
- **Tool progress and recovery:** tool/MCP/Skill/subagent rows, approvals and durable recovery remain. The run-details sheet and token-statistics UI remain removed.
- **Plans and recovery:** steps retain current-run evidence. Interrupted work resumes explicitly from durable records. The removed app-playbook feature is separate from the local memory-review approval flow.
- **Approvals and screen context:** answers retain the original conversation, run and one-shot token; manual handback requires fresh observation. View source, capture time, screenshot preview and a recapture action.
- **Focused settings:** LLM providers come first. The local LLM center and LiteRT dependency are removed; Vosk ASR and existing HTTP/Ollama configuration remain. Existing Markdown file templates and workspace preview/open/share/long-press copy are retained.

[Release notes](RELEASE_NOTES.md) · [Implementation/research comparison](docs/VOICE_ASSISTANT_GEMINI_RESEARCH.md) · [Actual validation](docs/ASSISTANT_VALIDATION_2026-11-17.md). Continuous speech uses STT → the configured model → TTS; endpoint hints use RMS and text-suffix rules.

## Settings cleanup

- Removed the entire support/donation page and settings entry, including dedicated payment links, QR-code display/export code, payment image and copy.
- The About section retains signed APK downloads, release history, source code and issue reporting.

## Current capabilities

Most capabilities below are inherited from Eta and the community customization branch. They are not all additions made in this Eta Ultra iteration.

| Area | Capabilities |
| --- | --- |
| Agent execution | A shared Agent Runtime, GUI agent, system APIs, visible tool progress, memory and learned experience, scheduled and event tasks |
| Models and extensions | Multiple providers, Codex/Grok subscription sign-in, MCP, Skills, schema validation, slash commands and historical message references |
| Terminal and workspace | Android terminal, private workspace file browsing, Markdown preview/editing, code blocks and offline KaTeX |
| Screen and system integration | Wake-time screen context, Root virtual display and mini window, continuous gestures, Xposed/OEM assistant entry points, ColorOS Fluid Cloud and status capsules |
| Conversations and collaboration | Categories, favorites/pins, search/navigation, multiple sessions, concurrent subagents and tool activity panels |
| Voice | System/Vosk/cloud ASR, system and cloud TTS, continuous conversation, actual-range captions, resume and hold to talk |
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

The local base is version `3.5.0` with `versionCode` `2026111701`. CI supplies the release run number to produce the four-part numeric version `3.5.0.<run_number>` and code `2026111701 + run_number`. The app update checker can compare numeric components, and the run number increases over time. Release builds use R8 optimization and resource shrinking with `debuggable=false`. See the [release guide](.github/RELEASING.md) for signing and publication.

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
