<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp" width="96" alt="Echo Tide" />

# Echo Tide · 忆潮音乐

**Immersive music player**

**An old-school, lightweight and minimal immersive music player**

**English** | [简体中文](README.zh-CN.md)

![License](https://img.shields.io/badge/license-AGPL--3.0-blue)
![Platform](https://img.shields.io/badge/platform-Android-brightgreen)
![Version](https://img.shields.io/badge/version-4.7.0-informational)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4.21-purple)
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

> Real-device captures. Cover art and lyrics belong to their respective owners and are shown for interface demonstration only.

## Tech Stack

| Layer | Technology |
| --- | --- |
| Language | Kotlin 2.4.21 |
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
│       │   │   │   ├── clip/            #     Sharing, default ringtone / alarm installer, readable URIs, spectrum image export
│       │   │   │   ├── download/        #     Online track download & cache
│       │   │   │   ├── highlight/       #     Chorus (highlight) location (pure lyric analysis) & whole-library segment table (background scan + persist), drives chorus-only playback
│       │   │   │   ├── metadata/        #     Cover management, metadata & lyric read/write (ranged streaming tag I/O), metadata cache, gallery image writes
│       │   │   │   ├── model/           #     Track & search data models (platform key as identity)
│       │   │   │   ├── panel/           #     Panel state holder, search logic & lyric alignment entry
│       │   │   │   ├── playback/        #     Playback state, player helper, queue switch, playlist sorting, recent plays, chorus-segment loading, USB direct output & Do Not Disturb, per-device audio sink (incl. buffer policy), output-latency measurement, audio-info snapshot (incl. Bluetooth link & codec resolution)
│       │   │   │   ├── proxy/           #     Proxy source (import / parse / engine / store) & custom-platform registry
│       │   │   │   ├── recommend/       #     Lyric text cleanup shared with chorus location
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
│       │   │   ├── settings/            #   Appearance / cache / language / playback / proxy source / about
│       │   │   ├── cache/               #   Storage / cache management
│       │   │   ├── spectrum/            #   Spectrum analysis page (compact / expanded / component)
│       │   │   ├── typography/          #   Lyric typography settings
│       │   │   └── metadata/            #   Metadata editing page (compact / expanded / component)
│       │   ├── service/                 # MediaSessionService playback engine
│       │   ├── theme/                   # Material 3 color & typography
│       │   ├── ui/                      # Shared UI (component: cover-fade CoverFade, dialog skeleton AppDialog; also component/dialog, component/player, component/section, icons)
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

The app follows **MVVM with unidirectional data flow** — state flows down from the `ViewModel` to the `UiState` and on to the UI, while events flow back up. Shared data logic lives in `data/` behind a repository, and the object graph is assembled by **manual dependency injection**: every app-level singleton is created on the `Application` and exposed to the UI through named CompositionLocals.

Screens are organized with a **per-form assembly pattern**:

- `{Screen}Screen.kt` — screen entry, dispatches between compact/expanded forms and handles cross-form side effects (no layout)
- `{Screen}ViewModel.kt` / `{Screen}UiState.kt` — screen-level state & events
- `{Screen}Assembly` under `compact/` and `expanded/` — per-form assembly selected by window size class & rotation
- `component/` — page-specific composables grouped into semantic subdirectories (e.g. `bar/`, `dialog/`, `panel/`, `playlist/`, `player/`, `search/`, `shell/`, `swipe/`)

Code shared by two or more features is promoted to the top level (`data/`, `theme/`, `utils/`, `ui/`), while single-feature code stays inside its own module. Playback lives in `data/music` and reaches the UI through a window-level `MusicPanelStateHolder`; the engine itself runs in `service/MusicPlaybackService` (Media3 ExoPlayer + `MediaSessionService`).

A few decisions shape the rest of the codebase:

- **State that must outlive the UI tree is kept outside it** — the home panel state and the shared playback state holder survive recomposition and rotation, and the last playback state is mirrored to disk so a cold start already shows a complete first frame.

- **Analysis runs in a page-scoped session** — spectrum analysis decodes a whole track into a directly renderable time-frequency matrix off the main dispatcher, and the session is cancelled when the page is left; the page only views or exports the result.

- **Every platform's lyrics normalise in one place** — `LyricCodec` folds LRC, QRC, KRC and lrcx into a single `LyricLine` list with word timelines in absolute milliseconds, so cross-platform comparison and the "word-level first, line-level fallback" policy exist only once.

- **The audio info panel reads the playback path** — `AudioInfoCollector` builds its snapshot from the shared playback state, so one panel covers speakers, USB DACs and Bluetooth links without branching on the UI side.

- **Tag rewriting streams through ranged I/O** — `TagSource` exposes only ranged reads and ranged copies, so editing the tags of a large Hi-Res file never loads the whole file into memory.

- **Metadata editing is a page built on that path** — the session is keyed by track, re-reads the file's tags whenever the page returns to the foreground and auto-flushes pending edits, which is why the page has no save button.

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

Permissions are requested one at a time from the onboarding dialog: the three library permissions are required, while Bluetooth, notifications and the battery whitelist are optional and shown only when missing. A partial photo grant (Android 14 *selected photos*) counts as image access, since the query still returns the pictures the user picked. The audio info panel additionally requests Bluetooth permission on the spot when the output is a Bluetooth device.

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

Search services rely on public web endpoints and cover basic song, cover and lyric search only — playback is not among the capabilities they promise, so trial clips and free tracks play only when a source happens to return a playable URL, and audio-quality upgrades depend on a proxy source. Lyric auto-translation relies on Youdao's public keyless translation endpoint, whose availability, rate limiting and translation quality are entirely up to that service. Availability varies by region and song. The app is for personal study and communication only — please support the copyright holders.

## Acknowledgements

- NetEase Cloud Music parsing was originally referenced from [Qplayer](https://github.com/TIMER-err/qplayer)
- List-item drag-reorder from [Reorderable](https://github.com/Calvin-LL/Reorderable), now implemented in-app with an equivalent algorithm
- Kotlin-native audio source parsing for NetEase Cloud Music, Kugou, Kuwo and QQ is based on [musicdl](https://github.com/CharlesPikachu/musicdl)

## License

[AGPL-3.0](LICENSE) © 2026 Evilgodxu
