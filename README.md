<div align="center">

<img src="icons/android/ic_launcher-web.png" alt="animadroid logo" width="128" height="128" />

# animadroid

**A native Android port of [ani-cli](https://github.com/pystardust/ani-cli)** — search, stream and download anime with a
fast Jetpack Compose UI built on **Material 3 Expressive**.

[![Latest release](https://img.shields.io/github/v/release/borovas-dev/animadroid?include_prereleases&style=for-the-badge&logo=github&label=release)](https://github.com/borovas-dev/animadroid/releases)
[![Build](https://img.shields.io/github/actions/workflow/status/borovas-dev/animadroid/ci.yml?branch=main&style=for-the-badge&logo=githubactions&logoColor=white&label=CI)](https://github.com/borovas-dev/animadroid/actions/workflows/ci.yml)
[![License: GPL-3.0](https://img.shields.io/github/license/borovas-dev/animadroid?style=for-the-badge&color=blue)](LICENSE)
[![Downloads](https://img.shields.io/github/downloads/borovas-dev/animadroid/total?style=for-the-badge&logo=android&logoColor=white&color=3DDC84)](https://github.com/borovas-dev/animadroid/releases)

![Android 12+](https://img.shields.io/badge/Android-12%2B%20(API%2031)-3DDC84?style=flat-square&logo=android&logoColor=white)
![Target API 37](https://img.shields.io/badge/target-API%2037-3DDC84?style=flat-square&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.5-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203%20Expressive-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)
![Media3](https://img.shields.io/badge/Media3-ExoPlayer-FF6F00?style=flat-square&logo=google&logoColor=white)
[![Stars](https://img.shields.io/github/stars/borovas-dev/animadroid?style=flat-square&logo=github)](https://github.com/borovas-dev/animadroid/stargazers)

[**Download**](https://github.com/borovas-dev/animadroid/releases) ·
[Features](#-features) ·
[Providers](#-providers) ·
[Build](#-build) ·
[Credits](#-credits)

</div>

---

## 📲 Install

1. Download the latest APK from the [**Releases**](https://github.com/borovas-dev/animadroid/releases) page
2. Open it on your phone and allow "install unknown apps" for your browser or file manager when asked
3. Requires **Android 12 (API 31) or newer**. Recommendations additionally need about 6 GB of RAM and a ~2.5 GB download

## 🆕 What's new in 0.2.0
**Sources and languages**
- **Sources by language, with automatic fallback**: *Settings → Playback* has a content language (Auto follows your phone) instead of a long provider list. If a source fails or times out, the next one in the chain is tried. If a show's own source has no playable episodes (e.g. Shinden lists some titles without players), the details screen switches to the next source that does.
- **New providers**: KickAssAnime and AnimeParadise (English fallbacks), AniLibria (Russian) and AnimeFire (Portuguese, beta)
- **Polish and Chinese UI** follows the phone language; per-app language setting on Android 13+
- **Updatable backend**: base URLs, mirrors and a few scraper parameters come from a remote config file, so many upstream breakages are fixed without a new release. Signature checking of that file is built but currently switched off
- **Updates from GitHub Releases**: optional in-app update check, with an opt-in **include pre-releases** switch (alpha and beta builds install over the current app, so "allow from this source" is needed only once)

**Generated voice-over (experimental, opt-in)**
- Switch it on in *Settings → Playback*, then choose **Dub** on a show with no recorded dub in your language. The subtitles (the provider's own in your language, else on-device translated) are read aloud by your phone's text-to-speech voice, sped up to fit each line, over the original audio, which is lowered while it speaks
- With the switch on, **Dub lists every show**; with it off, Dub lists only shows with a recorded dub, and picking a show without one plays the original audio with subtitles
- Markup such as `*`, `<i>`, `{\an8}`, `[sighs]` and music notes is not read out. Quality depends on the voice installed on your phone; if the language voice is missing the player offers to install it

**Player and settings**
- **Auto-skip** for intros and outros with undo, opt-in **next-episode prefetch**, download retries with backoff and a Wi-Fi-only option
- **Advanced** (collapsed by default, *Settings → Advanced*) now holds source strategy (reliable / best quality), source order, **Source health** (checks every source from your own network), import / export and tracking
- **AniList / MyAnimeList** progress sync (needs client ids, see [docs/tracking.md](docs/tracking.md))
- **Large screens and TV**: resizable windows, navigation rail, keyboard shortcuts and mouse menus in the player, Android TV launcher and D-pad support
- New launch screen with the app icon

**Fixes**
- Release crash when opening the player ("Cannot create an instance of class …"): caused by Hilt code generation silently skipped in some builds, now prevented by the build settings (see Build)
- Crash when holding the seek bar with previews on some MediaTek phones: frame previews now decode in software. A preview can take about half a second to appear
- Dub search no longer comes back empty for Shinden and HiAnime

## What was new in 0.1.0
- **Shinden provider** for Polish audio and subtitles, selectable under *Settings → Playback → Source*
- **On-device recommendations** now run in an isolated process, work in release builds, and use the CPU backend by default for compatibility
- **Translated subtitles** with on-device ML Kit translation (*Settings → Subtitles*), Chinese first
- Seek-bar scrub previews, native subtitle rendering, background downloads with notification actions, backup and import, launcher icons

## ✨ Features

### 🔎 Discover and watch
- **Pluggable providers** with per-language chains and automatic fallback — see [Providers](#-providers)
- **Sub / dub** switch per title, preferred quality (best, worst, 1080p to 360p) and autoplay of the next episode
- **Cover art and details** with synopsis and an episode grid, in an adaptive list-detail layout for tablets and foldables
- **Recent searches** and **favorites**, stored locally

### ▶️ Native player
- **Media3 / ExoPlayer** with HLS, a Material 3 Expressive control surface and a quality / subtitle / speed sheet
- **Seek-bar scrub previews** — a thumbnail follows your finger on the slider, from the stream's sprite sheet when available, otherwise decoded from the stream itself
- **Subtitles** rendered natively, with external tracks from the provider
- **Translated subtitles** — optional on-device machine translation (Google ML Kit) of the English track into any of ~59 languages, starting with Chinese; language packs (~30 MB) download once, translation then runs offline and never sends subtitle text anywhere
- **Generated voice-over** (experimental) — optional offline text-to-speech reading of the subtitles when "Dub" is selected but no recorded dub exists in your language; quality depends on the voice installed on your phone
- **Picture-in-Picture**, background audio with a media notification and playback controls
- **Gestures**: double-tap to seek, brightness and volume swipes, gesture lock, adjustable playback speed
- **Exact resume** — every episode remembers the second you stopped at; "Continue watching" picks up where you were

### ⏭️ ani-skip
- Optional skipping of **openings and endings** using [ani-skip](https://github.com/synacktraa/ani-skip) data (AniSkip with a MyAnimeList fallback)
- Choose between a **Skip button** and **automatic skipping**, with an adjustable offset
- Skip segments are marked on the seek bar

### 📥 Offline
- **Downloads** for offline viewing, with a foreground service, notification actions (pause, resume, cancel) and a watchdog that resumes after interruptions
- **Wi-Fi only** option for downloads
- **Backup and export** of history, progress, favorites and settings as JSON; import of an ani-cli `ani-hsts` history file

### 🤖 On-device recommendations (opt-in)
- **Gemma 4 E2B** through LiteRT-LM recommends what to watch next from your own history
- Downloaded only when you opt in, requires Wi-Fi and enough RAM, can be deleted at any time
- **Fully local inference** — your watch history never leaves the device
- Runs in an isolated process, so a native failure can never take the app down

- **AniList / MyAnimeList tracking**: optional progress sync (Settings > Tracking); needs owner-registered client ids, see [docs/tracking.md](docs/tracking.md).

### 🎨 Design
- **Material 3 Expressive**: expressive motion scheme, wavy progress and loading indicators, expressive components
- **Dynamic color** with a seeded fallback theme, light and dark, themed launcher icon
- Edge-to-edge, predictive back and Navigation 3

## 🌐 Providers

| Provider | Language | Notes |
|---|---|---|
| **HiAnime** | English sub / dub | Primary English source. Ported from ani-cli v5.1, with a built-in Cloudflare challenge solver |
| **KickAssAnime** | English sub / dub | Fallback. Separate audio tracks, soft subtitles |
| **AnimeParadise** | English sub | Second fallback |
| **Shinden** | Polish (lektor, dubbing, subtitles) | Hosts: CDA, Vidara, VK, YourUpload, Sibnet and the streamwish / filemoon family |
| **AniLibria** | Russian | Official API |
| **AnimeFire** | Portuguese (Brazil) | Beta: not yet tested against the live site |

Other languages use the English chain with on-device subtitle translation. Chinese has no native-subtitle source yet: the candidates we tried need a login or are unreliable.

Providers implement a small `AnimeProvider` interface (search, episodes, streams), so adding one does not touch the UI.

## 🗺️ Roadmap
- Translated subtitles for downloaded episodes without a network, and better handling of styled (ASS) subtitles
- More providers and more video hosts
- Signed release builds in CI, and re-enabling the signed remote config

## 🛠️ Build

Requires **JDK 21+** (CI uses 25) and Android SDK platform **37.2**.

```sh
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:assembleRelease        # minified (R8) release APK, unsigned
```

`gradle.properties` deliberately turns **parallel execution and KSP incremental processing off**: with them on, KSP intermittently dropped Hilt-generated classes, which showed up as `MissingBinding` build errors or a release-only launch crash ("Cannot create an instance of class …"). Builds are slower but reliable. After a pull or a branch switch, if Hilt errors still appear, run `./gradlew clean :app:assembleRelease`. Add `-PqaSuffix=.qa` to install a debug build next to the real app.

<details>
<summary><b>Testing</b></summary>

- Unit tests: `./gradlew testDebugUnitTest`
- Lint: `./gradlew lint`
- UI tests: `./run-ui-tests.sh [.qa]` runs `app/src/androidTest` one test per instrumentation process (DataStore allows one instance per file per process). Compose tests drive the clock manually: the app has endless animations and the Cloudflare solver polls forever, so auto-advance would never reach idle.
- QA hook (debug builds only): `adb shell am start -n <appId>/com.borovas.animadroid.MainActivity --ez debug_force_cf true` makes every non-image request fail as a Cloudflare challenge until the in-app verification sheet finishes loading.
- Hilt/KSP: if a `MissingBinding` appears after a clean build, run `./gradlew --stop`, delete `.gradle/` and `*/build/`, and rebuild; the cause is a stale KSP incremental cache.

</details>

<details>
<summary><b>Project layout</b></summary>

```
app/                 MainActivity, navigation, theme wiring
core/                model · network · database · datastore · designsystem · remoteconfig · sync
provider/            api · common · hianime · kickassanime · animeparadise · shinden · anilibria · animefire
feature/             search · details · player · aniskip · library · downloads · settings · recommend · subtitles · dubbing
```

</details>

## 🙏 Credits

animadroid stands on the work of others:

- [**Tsugumik**](https://github.com/Tsugumik) — inspiration for the project
- [**ani-cli**](https://github.com/pystardust/ani-cli) and its developers ([pystardust](https://github.com/pystardust)) — the original tool this app ports, including the provider flow and the history format
- [**ani-skip**](https://github.com/synacktraa/ani-skip) — opening and ending skip data and logic
- [**Cybernetic-Ransomware**](https://github.com/Cybernetic-Ransomware) — [alt-ani-cli-win-shinden](https://github.com/Cybernetic-Ransomware/alt-ani-cli-win-shinden), the reference for parts of the Shinden provider shell
- Google's [Gemma](https://ai.google.dev/gemma) and [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) for on-device recommendations

## ⚠️ Disclaimer

animadroid does not host, store or distribute any content. It only reads publicly available third-party sites, like
ani-cli does, and the user is responsible for what they access. See ani-cli's disclaimer.

## 📄 License

[GPL-3.0](LICENSE) — free software, like ani-cli.
