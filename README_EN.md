# Eta Ultra

A system-level AI agent and voice assistant for Android: a transparent bottom capsule wired to free system speech, screen understanding, tool calling and multi-step tasks.

[Simplified Chinese](README.md) · [Latest](https://github.com/q3330576893/Eta-por-ultra/releases/latest) · [All releases](https://github.com/q3330576893/Eta-por-ultra/releases) · [Issues](https://github.com/q3330576893/Eta-por-ultra/issues)

## Download

<!-- release-info:start -->
**Latest stable release: [3.5.6.17](https://github.com/q3330576893/Eta-por-ultra/releases/tag/v3.5.6.17)** · Android 13+ · ARM64

[Download signed Release APK](https://github.com/q3330576893/Eta-por-ultra/releases/latest/download/Eta-Ultra.apk) · [All releases](https://github.com/q3330576893/Eta-por-ultra/releases) · [Release notes](https://github.com/q3330576893/Eta-por-ultra/releases/tag/v3.5.6.17)

Source: [`3428a58`](https://github.com/q3330576893/Eta-por-ultra/commit/3428a5834caa685b079f53c8f14c7cefacf92ce1) · versionCode `2026112117`

SHA-256: `36ee45b1c300300290e72d3df0a30558ee6f0dd0bedf48632377461d6ff210eb`
<!-- release-info:end -->

Install `Eta-Ultra.apk` directly. The release keeps application ID `io.github.mangi.eta`; in-place upgrades depend on that ID, the signing certificate and an increasing `versionCode`.

Requires Android 13+ / ARM64. Configure a model provider in the app first; enable speech, accessibility, overlay and Root/LSPosed as needed.

## Capabilities

These descriptions cover the current source workspace. This round has not been published; the download metadata above identifies the released build. See [local validation](docs/MCP_ECOSYSTEM_2026-11-27.md) and the [Coolapk and public-fork comparison](docs/COOLAPK_ETA_COMPARISON_2026-11-27.md).

- **Settings**: shared glass surfaces with a high-contrast fallback, large-text layouts and accessible switches. First-launch tutorials and the tutorial settings entries have been removed.

- **Model access**: Chat Completions, Responses, Anthropic Messages and Gemini Interactions, plus multi-model comparison; prompts and tool boundaries are configured globally.
- **Ecosystem connectors**: discover and search 31 configurations by task, with a China-focused default including Amap, Baidu Maps, Bocha, Metaso, Zhipu and Bailian, plus official setup guides. User-supplied keys are encrypted locally; gateway and subscription requirements are explicit. Refreshes preserve current settings and tool selections.
- **Skills**: install Skills from GitHub repositories, including curated catalogs; five built-ins ship with the app.
- **Voice and assistant**: offline Vosk speech, system assistant session overlay, screen understanding, overlay and Root/LSPosed integration.
- **Device features**: weather and home-screen widget, scheduled tasks, long-term memory, file search, terminal and Linux environment.

## Notes

Maintained by [q3330576893](https://github.com/q3330576893), based on [Oo0520/Eta-por](https://github.com/Oo0520/Eta-por), originally [Mangi-11/Eta](https://github.com/Mangi-11/Eta). Device differences are in [device support](docs/ROOTLESS_SUPPORT.md); changes are in [CHANGELOG.md](CHANGELOG.md).
