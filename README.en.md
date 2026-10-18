# HuhuTalk

[简体中文](README.md) · [Changelog](CHANGELOG.md) · [1.1.0 release notes](docs/releases/1.1.0.md)

A phone-style remote chat and photography add-on for **Touhou Little Maid** on **Minecraft 1.21.1 / NeoForge**. Current version: **1.1.0**.

## Features

- A handheld phone item and dedicated interface, with refreshed phone, smartwatch and Wi-Fi block models.
- Private chat, stickers, voice input, image messages, contact management and natural-language commands.
- Rear camera and selfies: mirrored live selfie previews and normally oriented saved photos. The desktop camera saves immediately; the chat camera keeps its confirmation, send and retake flow.
- Selectable vision providers and models, with player and maid appearance descriptions to provide identity context. Recognition depends on the chosen model and configuration.
- Refreshed app icons and desktop layout management. The Settings app icon can be moved but cannot be deleted.
- Press O to open a standalone floating screen without holding a phone. Keep camera control and use the phone cursor plus the bound confirm key to drag its status bar.
- Settings app → Display & Rendering groups aspect ratio, phone size, font scale, brightness and E-ink Screen. Icon style and other app-specific options remain in HuhuTalk's internal settings.
- Chinese and English interface/manual selected by Minecraft language. AI response language still follows the model and conversation configuration.

## Installation and requirements

| Component | Requirement |
| --- | --- |
| Minecraft | 1.21.1 |
| Java | 21 |
| NeoForge | 21.1.243 or a newer build for 1.21.1 |
| Touhou Little Maid | Required; metadata minimum 1.5.0, development verification uses 1.5.3 for NeoForge 1.21.1 |
| JourneyMap | Optional, for waypoint-related features |
| Iris / shader packs | Optional; distributed separately |

Install the required components and place `huhutalk-1.1.0.jar` in your instance's `mods` folder. Use matching mod versions on the client and multiplayer server. Back up existing worlds and configuration before upgrading. Touhou Little Maid is required to use this add-on.

Configure Touhou Little Maid's chat model service first, then configure vision in the desktop Settings app if needed. Editing provider credentials follows Touhou Little Maid's server-side permissions. The first-entry starter kit for each world contains a Pro phone, smartwatch and Wi-Fi block. See the in-game Manual app for controls; key bindings can be changed in Minecraft settings.

## Shaders and E-ink Screen

E-ink Screen is **off by default**: the held phone's screen is drawn after shader postprocessing, preserving interface colors and stable brightness. When enabled, the screen is drawn before shader processing and participates in the pack's effects, which may include bloom or ghosting. This changes render order only; it does not simulate grayscale, a low refresh rate or separate environmental brightness. The standalone floating screen is unaffected.

Development checks covered Iris 1.8.14-beta.1, Complementary Reimagined r5.9.3 and BSL 10.1.8 during this development phase. Other loaders, versions and settings need their own testing; compatibility with every combination is not guaranteed. See [shader compatibility notes](docs/phone-screen-shader-compat.md).

## Model services and data

Chat, vision and speech features use configured external services and may incur charges. Sending an image for recognition sends that image and related prompts to the selected model service. Choose providers appropriate for your data requirements. English UI/manual support does not force English AI replies.

## Building

With Java 21, run from the repository root:

```bat
gradlew.bat build
```

The output is `build/libs/huhutalk-1.1.0.jar`. Add `--offline --console=plain` when dependencies are cached. The repository's `gradle.properties` contains local developer proxy settings; adjust them for your network. Shader development dependencies are opt-in with `-PshaderDev=true` and are not bundled in the mod. See [shader development](docs/shader-dev.md).

## Feedback and license

Report issues through [GitHub Issues](https://github.com/LUOCHUNsemianimus/huhutalk/issues), including mod/loader versions and shader pack details where relevant. Remove API keys and other private information before sharing logs.

Code is licensed under [Apache-2.0](LICENSES/Apache-2.0.txt); original artwork by 罗春 is licensed under [CC BY-NC-SA 4.0](LICENSES/CC-BY-NC-SA-4.0.md). Third-party assets retain their original licenses; Pixabay audio is not relicensed under these terms, and its provenance records remain unavailable. See [licensing scope](LICENSING.md) and [third-party notices](THIRD_PARTY_NOTICES.md). Developed by 罗春 with an AI-assisted workflow. Thanks to Touhou Little Maid, related projects and resource authors, and everyone who tested the mod.
