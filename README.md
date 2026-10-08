<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp" width="96" alt="Echo Tide" />

# Echo Tide · 忆潮音乐

**Immersive music player**

**An old-school, lightweight and minimal immersive music player**

**English** | [简体中文](README.zh-CN.md)

![License](https://img.shields.io/badge/license-AGPL--3.0-blue)
![Platform](https://img.shields.io/badge/platform-Android-brightgreen)
![Version](https://img.shields.io/badge/version-4.5.7-informational)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4.20-purple)
![AGP](https://img.shields.io/badge/AGP-9.4.1-blue)
![Gradle](https://img.shields.io/badge/Gradle-9.8.0-blue)
![Compose BOM](https://img.shields.io/badge/Compose%20BOM-2026.09.00-blue)
![minSdk](https://img.shields.io/badge/minSdk-34-orange)
![targetSdk](https://img.shields.io/badge/targetSdk-37-orange)

<img src="docs/Screenshot/promo-hero.webp" width="100%" alt="Echo Tide preview: framed portrait player, landscape player and landscape 3D cover carousel" />

</div>

## Showcase

Both portrait and landscape are designed for minimal distraction and maximum immersion.

| Portrait player | Landscape player | Landscape 3D carousel |
| :---: | :---: | :---: |
| <img src="docs/Screenshot/device-portrait.webp" width="215" alt="Portrait player (framed)" /> | <img src="docs/Screenshot/device-landscape.webp" width="380" alt="Landscape player (framed)" /> | <img src="docs/Screenshot/device-carousel.webp" width="380" alt="Landscape 3D cover carousel (framed)" /> |

> Real-device captures,Cover art and lyrics belong to their respective owners and are shown for interface demonstration only.

## Tech Stack

| Layer | Technology |
| --- | --- |
| Language | Kotlin 2.4.20 |
| UI | Jetpack Compose (BOM 2026.09.00) + Material 3 |
| Playback | Media3 ExoPlayer 1.11.1 + MediaSessionService |
| Navigation | AndroidX Navigation3 1.2.0 (typed routes) |
| DI | Manual DI (singletons on Application) |
| Persistence | DataStore Preferences 1.2.1 |
| Image loading | Coil 3.6.3 |
| Network | OkHttp 5.5.0 |
| Serialization | kotlinx.serialization 1.11.0 |
| Async | kotlinx.coroutines 1.11.0 (+ coroutines-guava for MediaController futures) |
| Adaptive layout | androidx.window 1.5.1, material3-adaptive 1.3.0 |
| Lifecycle | androidx.lifecycle 2.11.0, activity-compose 1.13.0 |
| Build | AGP 9.4.1, Gradle 9.8.0, refreshVersions |

## Project Structure

```
.
├── app/
│   └── src/main/
│       ├── kotlin/com/yichao/evilgodxu/
│       │   ├── data/                    # Data layer
│       │   │   ├── cache/               #   Cache inventory (categories, usage, cold-start reclaim)
│       │   │   ├── music/               #   Music scanning / online sources / metadata / proxy source
│       │   │   │   ├── api/             #     Search services, translation endpoint & HTTP client
│       │   │   │   ├── analysis/        #     Lossless-format, audio info, word-level lyric alignment, FFT & full-track spectrogram
│       │   │   │   ├── blacklist/       #     Blacklist store
│       │   │   │   ├── clip/            #     Sharing, default ringtone / alarm installer, readable URIs, spectrum image export
│       │   │   │   ├── download/        #     Online track download & cache
│       │   │   │   ├── metadata/        #     Cover management, metadata & lyric read/write (ranged streaming tag I/O), metadata cache, gallery image writes
│       │   │   │   ├── model/           #     Track & search data models (platform key as identity)
│       │   │   │   ├── panel/           #     Panel state holder, search logic & lyric alignment entry
│       │   │   │   ├── playback/        #     Playback state, player helper, queue switch, playlist sorting, USB direct output & Do Not Disturb, per-device audio sink (incl. buffer policy), output-latency measurement, audio-info snapshot (incl. Bluetooth link & codec resolution)
│       │   │   │   ├── proxy/           #     Proxy source (import / parse / engine / store) & custom-platform registry
│       │   │   │   ├── recommend/       #     Daily recommendation (chart pool, lyric features, TF-IDF, MMR)
│       │   │   │   ├── MusicScanner.kt  #     MediaStore scanning & track enrichment
│       │   │   │   └── PlaylistRefresher.kt  # Playlist refresh pipeline
│       │   │   ├── playlist/            #   Playlist store (smart & custom) & grouping
│       │   │   ├── repository/          #   Settings repository
│       │   │   └── settings/            #   Settings DataStore, playback & lyric-layout preferences, boot language mirror
│       │   ├── floatingwindow/          # Floating panel / mini player view managers, controllers & permission flow
│       │   │   └── miniplayer/          #   Mini player overlay / bar / playlist panel
│       │   ├── localization/            # In-app localization manager
│       │   ├── log/                     # CrashLogManager
│       │   ├── navigation/              # Navigation3 typed routes & nav host
│       │   ├── permission/              # Permission, overlay-grant & battery-whitelist monitors
│       │   ├── screens/                 # Screens (home / settings / cache / typography / spectrum / metadata)
│       │   │   ├── home/                #   Home player + permission flow + playlists + online search
│       │   │   │   ├── compact/         #     Portrait assembly, player & player parts
│       │   │   │   ├── expanded/        #     Landscape assembly, player & player parts
│       │   │   │   └── component/       #     analysis / audioinfo / bar / dialog / panel / permission / player / playlist / queue / search / shell / swipe
│       │   │   ├── settings/            #   Appearance / blacklist / cache / language / playback / proxy source / about
│       │   │   ├── cache/               #   Storage / cache management
│       │   │   ├── spectrum/            #   Spectrum analysis page (compact / expanded / component)
│       │   │   ├── typography/          #   Lyric typography settings
│       │   │   └── metadata/            #   Metadata editing page (compact / expanded / component)
│       │   ├── service/                 # MediaSessionService playback engine
│       │   ├── theme/                   # Material 3 color & typography
│       │   ├── ui/                      # Shared UI (component → incl. the cover-fade CoverFade and the shared dialog skeleton AppDialog / component/dialog / component/player → incl. the audio info overlay & content / component/section / icons)
│       │   ├── update/                  # Version check, in-app update & APK hash verification
│       │   ├── utils/                   # Shared utilities
│       │   ├── windowsize/              # Window size class & landscape form detection
│       │   ├── App.kt                   # Application entry (holds singletons + CompositionLocals)
│       │   ├── AppContent.kt            # Root composable (nav host + global dialogs)
│       │   ├── MainActivity.kt          # Sole activity (system bars, external intents, boot language)
│       │   └── MainViewModel.kt         # Activity-scoped ViewModel (incl. AppUiState)
│       └── res/                         # Resources (values / values-en)
├── gradle/
│   ├── libs.versions.toml               # Version catalog (dependencies)
│   └── wrapper/
├── docs/                                # Proxy source spec, screenshots & notes
├── LICENSE
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

The interface spec for custom proxy sources lives in [忆潮代理音源规范](docs/忆潮代理音源规范.md) (Chinese).

## Architecture

The app follows **MVVM with unidirectional data flow**: state flows down from `ViewModel` → `UiState` → UI, while events flow up from the UI to the `ViewModel`. Shared data logic lives in the `data/` layer behind a repository, and everything is wired together by **manual dependency injection** — every app-level singleton lives on the `Application` and is exposed to the UI through named CompositionLocals.

Screens are organized with a **per-form assembly pattern**:

- `{Screen}Screen.kt` — screen entry, dispatches between compact/expanded forms and handles cross-form side effects (no layout)
- `{Screen}ViewModel.kt` / `{Screen}UiState.kt` — screen-level state & events
- `{Screen}Assembly` under `compact/` and `expanded/` — per-form assembly selected by window size class & rotation
- `component/` — page-specific composables grouped into semantic subdirectories (e.g. `bar/`, `dialog/`, `panel/`, `playlist/`, `player/`, `search/`, `shell/`, `swipe/`)

Code reused by two or more features is promoted to the top level (`data/`, `theme/`, `utils/`, `ui/`); feature-specific code stays inside the feature module. The playback logic lives in `data/music` (playback, download, analysis, panel, recommend) and is exposed to the UI through a window-level `MusicPanelStateHolder`; the floating UI is split between `floatingwindow/` (view managers plus the mini player's own composables) and `ui/component/player` (the full panel and its sub-parts), while playback runs in `service/MusicPlaybackService` (Media3 ExoPlayer + `MediaSessionService`).

Beyond the screens, two pieces of logic are deliberately kept outside the UI trees so they survive recomposition and rotation: the home **panel state** (`HomePanelState`, holding playlist visibility, dialogs, swipe controller and the library-format analysis sheet) and the shared **playback state holder**. The daily-recommendation chart pool and the playback boot mirror follow the same idea: the pool warms up in the background from `App`, and the last playback state is mirrored to disk so the first frame after a cold start is already complete. The daily recommendation also consults `MusicBlacklist`: a song the user explicitly blocked is skipped in the coarse ranking, while over-represented features and the ones behind skipped tracks are only down-weighted, so a single block never degenerates into per-track filtering.

Spectrum analysis is a screen of its own: the page keeps its session in a track-keyed `SpectrumViewModel`, runs the decode on `Dispatchers.Default` and lets leaving the page cancel it, while the decode itself (`SpectrogramDecoder`) sits in `data/music/analysis` and turns a whole track into a directly renderable time-frequency matrix. The page is for viewing and exporting only — a long-press on the spectrogram shares it or saves it to the gallery — and the library analysis sheet keeps its per-format share statistics and format-based playlist navigation.

Lyric parsing is likewise collected in one place: `data/music/api/LyricCodec` normalises every platform's raw lyrics (plain LRC, the inline tags of enhanced LRC, QQ's QRC, Kugou's KRC, Kuwo's lrcx) into one `LyricLine` list, with all word-level timelines expressed in absolute milliseconds — so the parse results of different platforms are directly comparable, and the "word-level first, degrade to line-level when a platform ships none" policy only has to exist once. Fetching, parsing, cache writes and auto-translation live in `OnlineLyrics`, `LyricCodec`, `MusicMetadataCache` and `data/music/panel/MusicPanelLyricsTranslate` respectively, the last sharing its progress dialog with word-level alignment. Kugou's KRC is the one source that carries translations itself: they travel in the `[language]` metadata block (base64 JSON, the `type=1` segment) aligned to the lyric lines by order, so such a track shows a translation without the translation endpoint being called at all — only the zero-word-offset translation lines still merge by timestamp.

The audio info panel reads the playback path instead of being wired into any player layout: `AudioInfoCollector` assembles one `AudioInfoSnapshot` from the shared playback state, and the Compose side recomputes it off a version counter that the player's own callbacks (playback state, play-when-ready, audio session id), the `AudioDeviceCallback` (device plugged or unplugged) and `ON_RESUME` (so a permission just granted shows up at once) each bump. Every field is nullable and an unreadable field produces no row at all, which is why the same panel works over a speaker, a USB DAC and a Bluetooth link without branching on the UI side. The chain values it prints — float output, the PCM encoding actually written, bit-perfect direct — are reported upwards by the per-device sink and the USB direct-output module into that shared state, so the panel reads the same source the playback path writes rather than guessing from the source format. It also reports measured latency and the track buffer, neither of which is queryable by device: `OutputLatency` reads `AudioTrack.getTimestamp` against the playback head and the written-frames position, splitting the chain at the audio track into "after track" and "track residency" and summing the two into the full chain, while `OutputLatencySampler` averages the jittering samples; `PlaybackBufferPolicy` bounds that residency by requesting 512 PCM frames instead of Media3's fixed 500 ms. During USB direct output `DirectOutputDoNotDisturb` mirrors the output grade to system DND — entering "alarms only" while bit-perfect or source-format direct holds and restoring the previous filter once the grade disappears, so notification and ringtone interruptions stay quiet without touching the media stream.

Tag rewriting on lossless and linear-PCM containers goes through `TagSource`, which exposes only ranged reads and ranged copies: the tag layout is computed from the headers plus a window at the end of the file, and the audio body is streamed across from its original offsets. A Hi-Res file several hundred megabytes long is therefore no longer held in memory for a tag edit. Container layouts — ID3v2, M4A/MP4 box tables, FLAC Vorbis comments, Ogg page sequences and the IFF/RIFF-style chunks of AIFF, DSDIFF, DSF, APE and WAV — only have to produce "header bytes + audio body range + tail bytes" for the writer.

Metadata editing is a page built on that path rather than inside it: the session lives in a `MetadataViewModel` keyed by track, which re-reads the file's tags every time the page comes to the foreground (a nav-stack cached ViewModel would otherwise hand the previous snapshot back as the current value) and flushes an edit that has not reached its debounce window when the page is disposed, which is why no save entry point exists. Per-line and whole-text lyric editing only differ in how the draft is turned back into a lyric string — both end at the same encoded enhanced-LRC write.

## Permissions

| Permission | Purpose |
| --- | --- |
| Display over other apps | Floating music panel & mini player |
| All files access | Import and manage local music files |
| Music access (`READ_MEDIA_AUDIO`) | Play tracks from the device library |
| Images (`READ_MEDIA_IMAGES`) | Embedded art & local cover candidates — Android 14's *selected photos* grant counts as granted |
| Bluetooth (`BLUETOOTH_CONNECT`) | Name, address and negotiated codec of the current Bluetooth output device (audio info) |
| Foreground service (`mediaPlayback`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`) | Background playback with notification / lock-screen controls |
| Notifications (`POST_NOTIFICATIONS`) | Update download completion notification (Android 13+) |
| Network (`INTERNET`, `ACCESS_NETWORK_STATE`) | Online search, lyrics, cover lookup and update check |
| Audio settings (`MODIFY_AUDIO_SETTINGS`) | Audio configuration for the playback engine, incl. the dynamic mixer attributes requested by USB direct output |
| Do Not Disturb access (`ACCESS_NOTIFICATION_POLICY`) | Switches DND to "alarms only" during USB direct output to mute notification/ringtone interruptions (media is not muted) |
| Wake lock (`WAKE_LOCK`) | Keeps the playback engine running with the screen off |
| Ignore battery optimizations (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) | Keeps background playback from being killed by the system |
| Install packages (`REQUEST_INSTALL_PACKAGES`) | Launching the system installer for an in-app update |
| Write settings (`WRITE_SETTINGS`) | Setting a track as the default ringtone / alarm sound |

Permissions are requested from the onboarding dialog one at a time: the three library permissions are required, while Bluetooth, notifications and the battery whitelist are optional and listed only while missing. A partial photo grant (Android 14 *selected photos*) is accepted in place of full image access, since the query still returns the pictures the user picked. The Bluetooth permission is additionally requested on the spot by the audio info panel when the current output is a Bluetooth device.

## Getting Started

### Prerequisites

- JDK 21
- Android Studio (latest stable recommended)
- Android SDK with API 37 (`compileSdk`)

### Build

```bash
git clone https://github.com/Evilgodxu/EchoTide-Music.git
cd EchoTide-Music

# Debug APK
./gradlew assembleDebug

# Release APK (requires signing config, see below)
./gradlew assembleRelease
```

APKs are emitted as `EchoTideMusic-<versionName>-arm64-v8a.apk` under `app/build/outputs/apk/`. Only the `arm64-v8a` ABI is built.

### Release Signing

The release build reads signing credentials from `local.properties` in the project root:

```properties
KEYSTORE_PASSWORD=your_store_password
KEY_ALIAS=jh
KEY_PASSWORD=your_key_password
```

The keystore file is expected at `jh.keystore` in the project root (adjust `storeFile` in `app/build.gradle.kts` if needed). Both files are git-ignored — never commit them.

### Promo Image

The framed screenshots and the hero image used in this README are generated from the device captures in `docs/Screenshot/` by `tools/make_promo_hero.py`. The script derives the bezel thickness, body radius and side-key placement proportionally from the screen's short edge, writing three transparent framed captures (`device-*.webp`) plus the composed `promo-hero.webp`.

```bash
python tools/make_promo_hero.py
```

## Disclaimer

Search services rely on public web endpoints. The built-in search services are used for basic song, cover and lyric search only — playback is not a capability they promise, so the app can play trial clips and free tracks in theory only, when a source returns a playable URL; the audio-quality upgrade depends on a proxy source. Lyric auto-translation relies on Youdao's public keyless translation endpoint, whose availability, rate limiting and translation quality are entirely up to that service. Availability varies by region and song. The app is for personal study and communication only — please support the copyright holders.

## Acknowledgements

- NetEase Cloud Music parsing originally referenced from [Qplayer](https://github.com/TIMER-err/qplayer)
- Drag-reorder of list items [Reorderable](https://github.com/Calvin-LL/Reorderable); now self-implemented in-app (algorithm-equivalent)
- NetEase Cloud Music, Kugou, Kuwo, Migu and QQ Kotlin-native audio source parsing is based on [musicdl](https://github.com/CharlesPikachu/musicdl)

## License

[AGPL-3.0](LICENSE) © 2026 Evilgodxu
