<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp" width="96" alt="YiChao Music" />

# YiChao Music

**A modern Android music player with a floating music panel, mini player, playlist management, multi-platform online search and playback speed control.**

**English** | [简体中文](README.zh-CN.md)

![License](https://img.shields.io/badge/license-AGPL--3.0-blue)
![Platform](https://img.shields.io/badge/platform-Android-brightgreen)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4.20-purple)
![AGP](https://img.shields.io/badge/AGP-9.4.0-blue)
![Gradle](https://img.shields.io/badge/Gradle-9.7.1-blue)
![Compose BOM](https://img.shields.io/badge/Compose%20BOM-2026.08.00-blue)
![minSdk](https://img.shields.io/badge/minSdk-33-orange)
![targetSdk](https://img.shields.io/badge/targetSdk-37-orange)

</div>

**YiChao Music (忆潮音乐)** is a full-featured Android music player built with Jetpack Compose. Beyond a regular in-app player, it provides a **floating music panel** and a **mini player** that work on top of any app, so music is always one tap away — in games, browsers or any other screen.

## Features

- **Floating music panel** — a full-featured playback panel rendered as a system overlay (SYSTEM_ALERT_WINDOW), usable above any app
- **Mini player** — a compact floating bar shown while the app is in the background during playback, displaying the current lyric; tap it to expand back into the full panel. Can be toggled in settings
- **Local library** — scans device storage via MediaStore, extracts embedded covers and lyrics, and imports audio through `VIEW`/`SEND` intents and the system file picker
- **Multi-platform online search** — aggregated search across Netease (网易云), QQ Music, Kugou (酷狗), Kuwo (酷我) and Migu (咪咕), with search history, quality selection (lossless / high / standard) and online caching (downloaded to the system Downloads directory, then auto-switched to local playback once cached)
- **Proxy source (代理音源)** — import third-party aggregated music sources (via local file / link / text) to customize search, playback URL, lyric and cover resolution per platform, with enable / disable / remove and automatic fallback to the built-in parser on failure; see the [忆潮代理音源规范](docs/忆潮代理音源规范.md) for the JSON spec
- **Playlist system** — smart playlists (Recently Played / Favorites / Albums / Artists) and custom playlists (create / rename / delete / batch add tracks / drag to reorder / quick switch), persisted as JSON
- **Playlist search** — a shared capsule search field filters the playlist, artist and album lists as you type, each with its own empty-state message
- **Playlist sorting** — a sort button in the playlist panel header offering default order / modified time / title / artist / album / duration, with an ascending-descending toggle; text fields use locale-aware natural ordering (Chinese by pinyin, English alphabetically, numeric — including Chinese numerals — first), and the default order anchors on the leading title before clustering tracks by artist and then by album. The chosen rule is persisted along with the playlist cache, and sorting is only offered for the default full playlist so custom playlists keep their drag order
- **Playlist import** — paste a playlist share link from any supported platform, preview the parsed track list, then download the whole playlist locally and register it as a custom playlist (Netease is parsed in-app; other platforms require a proxy source)
- **Synced lyrics** — scrolling lyrics with word-level timing (toggleable), online lyric matching/refresh, local lyric file import, embedded lyrics and raw-lyric editing (timestamp prefix validated), plus fine-grained lyric offset tuning; drag the lyrics area vertically to scrub playback in real time (release aligned with a line to play from it, otherwise it springs back)
- **Lyric typography** — per-scene font size and visible-line count for the music panel, home portrait and home landscape (with 3D intensity), adjustable in Typography settings
- **Cover management** — embedded art, local image candidates and online cover search; the new cover can be written back into the audio file. Covers are resolved in tiers: small images (list rows, playlist rows, mini player, music panel) read the system MediaStore thumbnail / album-art cache directly so the first frame is instant, while the large home immersive cover and the panel carousel keep the full embedded-art path for sharpness; enrichment prefers the system album art and only falls back to embedded art and thumbnails
- **Metadata editing** — rename song title / artist, written back to the file tags, with one-tap copy
- **Track format display** — shows the currently played source format in the progress area (container format, bit depth, sample rate, bitrate)
- **Library analysis** — locate tracks by format, detect fake lossless (spectral analysis) and suspected AI-generated music, and group online tracks; re-runnable at any time
- **Lossless upgrade** — match a lossless online source for the current track and swap the playing source in place
- **Playback speed control** — real-time playback speed adjustment via a dialog (±0.1 steps, tap the value to reset), processed natively by AudioTrack; long-press previous/next to open it on the home screen
- **Playback controls** — Media3 media session with notification & lock-screen controls, play modes (repeat all / repeat one / shuffle), favorites sorted to the top, play-next and a sleep timer (stop after current track)
- **Home gestures** — swipe right for online search, swipe left for the playlist panel, and vertical swipes to switch tracks (toggleable); immersive landscape mode with a rotating disc, a 3D cover carousel and auto-hiding floating controls (the title bar and control bar retract automatically when the playlist panel or the carousel is open, and Back closes the panel first)
- **Adaptive layout** — responsive UI based on WindowSizeClass
- **State persistence** — playlist, playback position and play mode are restored across restarts
- **Theme & localization** — System / Light / Dark themes with a circular reveal transition; in-app hot switching between 简体中文 / English / Follow System without recreating the activity
- **Crash logging** — uncaught and caught exceptions written to app-specific external storage with automatic cleanup
- **In-app update** — automatically checks GitHub Releases once a day when returning to the foreground (also manual check on the About screen), showing a dialog with the changelog; the APK can be downloaded and installed in-app or opened in the browser, and every download is verified against the SHA-256 digest published by GitHub Releases before installation (downloads that cannot be verified are rejected)

## Screens

| Screen | Contents |
| --- | --- |
| Home | Permission onboarding dialog (auto-hides once all are granted), immersive player with a rotating disc cover on a cover-colored gradient background, synced lyrics (font size & line count adjustable, drag vertically to scrub playback), refreshable, searchable playlist with sorting and share-link import, favorites, sleep timer, lossless upgrade, library analysis, landscape mode with a 3D cover carousel, online search (5 platforms with quality selection) via right swipe and playlist panel via left swipe, vertical swipe to switch tracks (long-press the cover / title for cover & lyrics refresh, lyric editing and rename) |
| Settings | Appearance (theme), Language, Playback (floating mini player / word-by-word rendering / swipe to change track), Typography (lyric font size & lines), Proxy Source (import / enable / remove third-party sources), About (version, update check, GitHub link) |

## Tech Stack

| Layer | Technology |
| --- | --- |
| Language | Kotlin 2.4.20 |
| UI | Jetpack Compose (BOM 2026.08.00) + Material 3 |
| Playback | Media3 ExoPlayer 1.11.0 + MediaSessionService |
| Navigation | AndroidX Navigation3 1.1.7 (typed routes) |
| DI | Manual DI (singletons on Application) |
| Persistence | DataStore Preferences 1.2.1 |
| Image loading | Coil 3.6.2 |
| Network | OkHttp 5.5.0 |
| Serialization | kotlinx.serialization 1.11.0 |
| Adaptive layout | androidx.window 1.5.1, material3-adaptive 1.3.0 |
| Lifecycle | androidx.lifecycle 2.11.0, activity-compose 1.13.0 |
| Build | AGP 9.4.0, Gradle 9.7.1, refreshVersions |

## Project Structure

```
.
├── app/
│   └── src/main/
│       ├── kotlin/com/yichao/evilgodxu/
│       │   ├── data/                    # Data layer
│       │   │   ├── music/               #   Music scanning / online sources / metadata / proxy source
│       │   │   │   ├── api/             #     Online music sources (Netease / QQ / Kugou / Kuwo / Migu) & HTTP client
│       │   │   │   ├── analysis/        #     Lossless-format, fake-lossless & AI-music analysis, audio info reader
│       │   │   │   ├── download/        #     Online track download & cache
│       │   │   │   ├── metadata/        #     Cover management, metadata & lyric read/write, metadata cache
│       │   │   │   ├── model/           #     Track & search data models
│       │   │   │   ├── panel/           #     Panel state holder & search logic
│       │   │   │   ├── playback/        #     Playback state, player helper & playlist sorting
│       │   │   │   ├── proxy/           #     Proxy source (import / parse / engine / store) & playlist syncer
│       │   │   │   ├── MusicScanner.kt  #     MediaStore scanning & track enrichment
│       │   │   │   └── PlaylistRefresher.kt  # Playlist refresh pipeline
│       │   │   ├── playlist/            #   Playlist store (smart & custom)
│       │   │   ├── repository/          #   Settings repository
│       │   │   └── settings/            #   Settings DataStore, playback & lyric-layout preferences
│       │   ├── floatingwindow/          # Floating panel / mini player view managers, controllers & permission flow
│       │   ├── localization/            # In-app localization manager
│       │   ├── log/                     # CrashLogManager
│       │   ├── navigation/              # Navigation3 typed routes & nav host
│       │   ├── permission/              # Permission & overlay-grant monitors
│       │   ├── screens/                 # Screens (home / settings / typography)
│       │   │   ├── home/                #   Home player + permission flow + playlists + online search
│       │   │   │   ├── compact/         #     Portrait assembly & portrait player
│       │   │   │   ├── expanded/        #     Landscape assembly & landscape player
│       │   │   │   └── component/       #     bar / dialog / panel / permission / player / playlist / search / shell / swipe
│       │   │   ├── settings/            #   Appearance / language / playback / typography / proxy source / about
│       │   │   └── typography/          #   Lyric typography settings
│       │   ├── service/                 # MediaSessionService playback engine
│       │   ├── theme/                   # Material 3 color & typography
│       │   ├── ui/                      # Shared UI (component / component/dialog / component/section / icons)
│       │   ├── update/                  # Version check, in-app update & APK hash verification
│       │   ├── utils/                   # Shared utilities
│       │   ├── windowsize/              # Window size class detection
│       │   ├── App.kt                   # Application entry (holds singletons + CompositionLocals)
│       │   ├── AppContent.kt            # Root composable (nav host + global dialogs)
│       │   ├── MainActivity.kt          # Sole activity
│       │   └── MainViewModel.kt         # Activity-scoped ViewModel (incl. AppUiState)
│       └── res/                         # Resources (values / values-en)
├── gradle/
│   ├── libs.versions.toml               # Version catalog (dependencies)
│   └── wrapper/
├── docs/                                # Dev conventions, work log & proxy source spec (忆潮代理音源规范.md)
├── LICENSE
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## Architecture

The app follows **MVVM with unidirectional data flow**: state flows down from `ViewModel` → `UiState` → UI, while events flow up from the UI to the `ViewModel`. Shared data logic lives in the `data/` layer behind a repository, and everything is wired together by **manual dependency injection** — every app-level singleton lives on the `Application` and is exposed to the UI through named CompositionLocals.

Screens are organized with a **per-form assembly pattern**:

- `{Screen}Screen.kt` — screen entry, dispatches between compact/expanded forms and handles cross-form side effects (no layout)
- `{Screen}ViewModel.kt` / `{Screen}UiState.kt` — screen-level state & events
- `{Screen}Assembly` under `compact/` and `expanded/` — per-form assembly selected by window size class & rotation
- `component/` — page-specific composables grouped into semantic subdirectories (e.g. `bar/`, `dialog/`, `panel/`, `playlist/`, `player/`, `search/`, `shell/`, `swipe/`)

Code reused by two or more features is promoted to the top level (`data/`, `theme/`, `utils/`, `ui/`); feature-specific code stays inside the feature module. The playback logic lives in `data/music` (playback, download, analysis, panel) and is exposed to the UI through a window-level `MusicPanelStateHolder`; the floating UI (full panel + mini player) is split between `floatingwindow/` (view managers) and `ui/component` (composables), while playback runs in `service/MusicPlaybackService` (Media3 ExoPlayer + `MediaSessionService`).

Beyond the screens, two pieces of logic are deliberately kept outside the UI trees so they survive recomposition and rotation: the home **panel state** (`HomePanelState`, holding playlist visibility, dialogs, swipe controller and the library-analysis session) and the shared **playback state holder**. The library-analysis session in particular lives at the home level, so closing its sheet does not abort a running analysis.

## Permissions

| Permission | Purpose |
| --- | --- |
| Display over other apps | Floating music panel & mini player |
| All files access | Import and manage local music files |
| Music access (`READ_MEDIA_AUDIO`) | Play tracks from the device library |
| Images (`READ_MEDIA_IMAGES`) | Embedded art & local cover candidates |
| Foreground service (`mediaPlayback`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`) | Background playback with notification / lock-screen controls |
| Notifications (`POST_NOTIFICATIONS`) | Update download completion notification (Android 13+) |
| Network (`INTERNET`, `ACCESS_NETWORK_STATE`) | Online search, lyrics, cover lookup and update check |
| Audio settings (`MODIFY_AUDIO_SETTINGS`) | Audio configuration for the playback engine |

Permissions are requested through a transparent onboarding activity that chains them one by one and closes automatically once all are granted.

## Getting Started

### Prerequisites

- JDK 21
- Android Studio (latest stable recommended)
- Android SDK with API 37 (`compileSdk`)

### Build

```bash
git clone https://github.com/Evilgodxu/YiChao-Music.git
cd YiChao-Music

# Debug APK
./gradlew assembleDebug

# Release APK (requires signing config, see below)
./gradlew assembleRelease
```

APKs are emitted as `YiChaoMusic-<versionName>-arm64.apk` under `app/build/outputs/apk/`. Only the `arm64-v8a` ABI is built.

### Release Signing

The release build reads signing credentials from `local.properties` in the project root:

```properties
KEYSTORE_PASSWORD=your_store_password
KEY_ALIAS=jh
KEY_PASSWORD=your_key_password
```

The keystore file is expected at `jh.keystore` in the project root (adjust `storeFile` in `app/build.gradle.kts` if needed). Both files are git-ignored — never commit them.

## Disclaimer

Online music search relies on third-party public web endpoints (Netease / QQ Music / Kugou / Kuwo / Migu), whose availability and playback policy may vary by region and song. The app is for personal study and communication only — please support the copyright holders.

## Acknowledgements

- Lyric animations and NetEase cloud music parsing originally referenced from [Qplayer](https://github.com/TIMER-err/qplayer)
- Drag-reorder of list items originally referenced from [Reorderable](https://github.com/Calvin-LL/Reorderable); now self-implemented in-app (algorithm-equivalent)
- QQ Music, Kugou, Kuwo and Migu Kotlin-native audio source parsing is based on [musicdl](https://github.com/CharlesPikachu/musicdl)

## License

[AGPL-3.0](LICENSE) © 2026 Evilgodxu
