<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp" width="96" alt="Echo Tide" />

# Echo Tide

**A modern Android music player with a floating music panel, mini player, playlist management, multi-platform online search and playback speed control.**

**English** | [简体中文](README.zh-CN.md)

![License](https://img.shields.io/badge/license-AGPL--3.0-blue)
![Platform](https://img.shields.io/badge/platform-Android-brightgreen)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4.20-purple)
![AGP](https://img.shields.io/badge/AGP-9.4.0-blue)
![Gradle](https://img.shields.io/badge/Gradle-9.7.1-blue)
![Compose BOM](https://img.shields.io/badge/Compose%20BOM-2026.09.00-blue)
![minSdk](https://img.shields.io/badge/minSdk-33-orange)
![targetSdk](https://img.shields.io/badge/targetSdk-37-orange)

</div>

**Echo Tide (忆潮音乐)** is a full-featured Android music player built with Jetpack Compose. Beyond a regular in-app player, it provides a **floating music panel** and a **mini player** that work on top of any app, so music is always one tap away — in games, browsers or any other screen. A lyric-profile **daily recommendation** carousel, a five-platform aggregated search and a full-track **spectrum analyzer** round out the listening experience.

## Features

- **Floating music panel** — a full-featured playback panel rendered as a system overlay (SYSTEM_ALERT_WINDOW), usable above any app
- **Mini player** — a compact floating bar shown while the app is in the background during playback, displaying the current lyric; tap it to expand back into the full panel. Can be toggled in settings
- **Local library** — scans device storage via MediaStore, extracts embedded covers and lyrics, and imports audio through `VIEW`/`SEND` intents and the system file picker
- **Multi-platform online search** — aggregated search across Netease (网易云), QQ Music, Kugou (酷狗), Kuwo (酷我) and Migu (咪咕). The built-in sources only provide **basic song search, cover lookup and lyric lookup** — playback is not a capability they promise, and the app plays trial clips and free tracks in theory only, when a source happens to return a playable URL. For those tracks the UI still offers search history and quality selection (lossless / high / standard), and a cache of the track is attempted locally (playback moves to the local copy once that succeeds); within the lossless tier Hi-Res is requested first and only falls back to regular lossless, so no additional tier is exposed in the picker. The platform switcher also lists **custom platforms** declared by enabled proxy sources — that is how platforms the app does not build in get search, playback, lyrics and covers (see *Proxy source*)
- **Daily recommendation** — a lyric-profile recommender: charts from all five platforms are pooled daily (refreshed at 11:00 Beijing time) and scored against your favorites' lyric profile through lexical, conceptual and rhythmic channels, then diversity-reranked (MMR) into a five-track carousel on the search page
- **Blacklist** — blacklisted tracks and skip-feedback features are persisted: blacklisted tracks are filtered out of recommendations while skipped tracks downweight similar candidates; a settings section shows the count and can reset the whole blacklist
- **Sharing & ringtones** — share a local track through the system share sheet, or set it as the default ringtone / alarm sound from its advanced menu (online tracks are not supported; setting a ringtone needs the modify-system-settings permission)
- **Proxy source (代理音源)** — import third-party aggregated music sources (via local file / link / text) to customize search, playback URL, lyric and cover resolution per platform, with enable / disable / remove and automatic fallback to the built-in parser on failure. A source can also declare **custom platforms** (any key other than `wy` / `qq` / `kg` / `kw` / `mg`), which must carry their own name and search action; such a platform has no built-in implementation, so the proxy source is its only provider, and it shows up in the platform switcher exactly where the built-in five do (online search, lyric refresh, cover refresh, lossless upgrade). The per-platform quality map gained a `hires` entry that is tried before `lossless`. The daily-recommendation chart pool deliberately stays on the five built-in platforms. See the [忆潮代理音源规范](docs/忆潮代理音源规范.md) (v1.2.0) for the JSON spec
- **Playlist system** — smart playlists (Recently Played / Favorites / Albums / Artists) and custom playlists (create / rename / delete / batch add tracks / drag to reorder / quick switch), persisted as JSON
- **Playlist search** — a shared capsule search field filters the playlist, artist and album lists as you type, each with its own empty-state message
- **Playlist sorting** — a sort button in the playlist panel header offering default order / modified time / title / artist / album / duration, with an ascending-descending toggle; text fields use locale-aware natural ordering (Chinese by pinyin, English alphabetically, numeric — including Chinese numerals — first), and the default order anchors on the leading title before clustering tracks by artist and then by album. The chosen rule is persisted along with the playlist cache, and sorting is only offered for the default full playlist so custom playlists keep their drag order
- **Playlist import** — paste a playlist share link from any supported platform, preview the parsed track list, then attempt to cache the whole playlist locally and register it as a custom playlist (Netease is parsed in-app; other platforms require a proxy source)
- **Synced lyrics** — scrolling lyrics with word-level timing (toggleable), online lyric matching/refresh, local lyric file import, embedded lyrics and raw-lyric editing (timestamp prefix validated), plus fine-grained lyric offset tuning; line-level lyrics can be aligned into word-level timing in-app (a monotonic DP alignment over the decoded audio, resumable in the background); drag the lyrics area vertically to scrub playback in real time — the gesture is claimed by the lyrics as soon as vertical movement dominates, so a horizontal drag is left entirely to the panel swipe; once claimed, the release velocity routes the gesture: a slow drag releasing on a line plays from it and turns the marker into a confirmation colour, otherwise the position springs back, while a quick flick never seeks and is handled as a vertical swipe to switch tracks instead. The music panel dims edge lines by per-line opacity instead of an overlay mask, which keeps the lyric nearest the edge readable
- **Lyric typography** — per-scene font size and visible-line count for the music panel, home portrait and home landscape (with 3D intensity), adjustable in Typography settings
- **Cover management** — embedded art, local image candidates and online cover search; the new cover can be written back into the audio file or exported to the gallery. Covers are resolved in tiers: small images (list rows, playlist rows, mini player, music panel) read the system MediaStore thumbnail / album-art cache directly so the first frame is instant, while the large home immersive cover and the panel carousel keep the full embedded-art path for sharpness; enrichment prefers the system album art and only falls back to embedded art and thumbnails
- **Metadata editing** — rename song title / artist, written back to the file tags, with one-tap copy
- **Track format display** — shows the currently played source format in the progress area (container format, bit depth, sample rate, bitrate)
- **Library analysis** — locate tracks by format, detect audio-quality anomalies (fake lossless via spectral analysis) and suspected AI-generated music, and group online tracks; re-runnable at any time. A track whose verdicts came from a full-track spectrum analysis is locked, so the segmented sampling of a library run never overwrites it — playlist filtering and library statistics therefore share one criterion, and a locally cached or lossless-upgraded track is unlocked to take part in the analysis again
- **Spectrum analysis** — a dedicated page (long-press a local track in the playlist → *View spectrum*) that decodes the whole file and renders a time-frequency spectrogram: a 2048-point STFT at 50% overlap over a Hann window, a logarithmic frequency axis (20 Hz up to Nyquist, 1-2-5 tick series) and a dB colour scale, with the chart, the frequency axis and the colour bar sharing one vertical coordinate system so readings line up with the bands pixel for pixel. Frames are merged pairwise past a cap, so memory and output size stay bounded for any track length. The same decode also yields the audio-quality and AI-music verdicts under exactly the criteria the library analysis uses, then locks them. Long-pressing the chart offers *Share image* / *Save image*, which renders a 1920 px dark PNG carrying the artist, the source-file parameters, both verdicts and a disclaimer; saving goes through the album's `pending` write path, sharing hands a `cacheDir` intermediate to `FileProvider`
- **Lossless upgrade** — match a Hi-Res or lossless online source for the current track (Hi-Res first, never downgrading to a lossy tier) and swap the playing source in place. This depends on a **proxy source**: the built-in sources do not return lossless playback URLs, so the feature is limited to platforms covered by an enabled proxy source
- **Playback speed control** — real-time playback speed adjustment via a dialog (±0.1 steps, tap the value to reset), processed natively by AudioTrack; long-press previous/next to open it on the home screen
- **Playback controls** — Media3 media session with notification & lock-screen controls, play modes (repeat all / repeat one / shuffle), favorites sorted to the top, play-next and a sleep timer (stop after current track)
- **Home gestures** — swipe right for online search, swipe left for the playlist panel, and vertical swipes to switch tracks (toggleable); tapping the artist line jumps to that artist's playlist; immersive landscape mode with a rotating disc, a 3D cover carousel and auto-hiding floating controls (the title bar and control bar retract automatically when the playlist panel or the carousel is open, and Back closes the panel first)
- **Cover-derived background & immersive chrome** — the home background is generated from the current cover: three high-saturation copies of it overlaid at fixed offsets, tinted, blurred on a canvas downscaled to 1/16 of the viewport and upscaled back, so the cost stays negligible. Until the thumbnail is ready it falls back to a gradient extracted from the cover, and on a cold start the colours persisted from last session are used for the very first frame. A *Background flow* switch under Playback settings makes the layers drift slowly (three periods of 120 s / 90 s / 70 s, opposite directions; off by default renders a single static frame). In portrait the title bar fades out 300 ms after two seconds without touch, comes back on any touch or when the player page is shown again, hides while the playlist panel or the analysis sheet is open, and stops responding to taps while faded. System bars are re-evaluated on any window layout change (rotation, split screen, free-form resize), since the size at the moment of a request may still be the pre-rotation one
- **Adaptive layout** — responsive UI based on WindowSizeClass
- **State persistence** — playlist, playback position and play mode are restored across restarts
- **Theme & localization** — System / Light / Dark themes with a circular reveal transition; in-app hot switching between 简体中文 / English / Follow System without recreating the activity
- **Crash logging** — uncaught and caught exceptions written to app-specific external storage with automatic cleanup
- **In-app update** — automatically checks GitHub Releases once a day when returning to the foreground (also manual check on the About screen), showing a dialog with the changelog; the APK can be downloaded and installed in-app or opened in the browser, and every download is verified against the SHA-256 digest published by GitHub Releases before installation (downloads that cannot be verified are rejected)
- **Storage management** — a dedicated screen inventories image cache, temp files (download, upgrade and spectrum-share intermediates), crash logs, lyric cache, audio cache, update packages, analysis cache and preferences, grouped into clearable / app-private / user-data scopes, with pull-to-refresh sampling and one-tap clearing of the app cache, crash logs and downloaded update packages

## Screens

| Screen | Contents |
| --- | --- |
| Home | Permission onboarding dialog (auto-hides once all are granted), immersive player with a rotating disc cover on a cover-derived background, synced lyrics (font size & line count adjustable, drag vertically to scrub playback, in-app word-level alignment), refreshable, searchable playlist with sorting and share-link import, favorites, sleep timer, lossless upgrade, library analysis, landscape mode with a 3D cover carousel, online search (5 built-in platforms plus any custom platforms from proxy sources, quality selection, and a daily-recommendation carousel) via right swipe and playlist panel via left swipe, vertical swipe to switch tracks, tap the artist line to jump to that artist's playlist, auto-hiding title bar in portrait (long-press the cover / title for cover & lyrics refresh, lyric editing and rename; long-press a track in the playlist for share, ringtone, alarm and view-spectrum) |
| Settings | Appearance (theme), Language, Playback (floating mini player / word-by-word rendering / swipe to change track / background flow) with a Typography entry, Blacklist (count + reset), Storage (entry to the cache screen), Proxy Source (import / enable / remove third-party sources), About (version, update check, share today's log, GitHub link, QQ group) |
| Storage | Cache inventory grouped into temp files (clearable), app data and user data, with total usage, pull-to-refresh resampling and one-tap clearing of the app cache, crash logs and update packages |
| Spectrum | Full-track time-frequency spectrogram (logarithmic frequency axis, dB colour scale, time labels), a decoding progress indicator, the source file's format parameters and size, and the same two verdicts the library analysis produces; long-press the chart to share or save a 1920 px PNG that carries artist, parameters, verdicts and a disclaimer |
| Typography | Per-scene lyric font size, visible-line count and landscape 3D intensity for the music panel, home portrait and home landscape |

## Tech Stack

| Layer | Technology |
| --- | --- |
| Language | Kotlin 2.4.20 |
| UI | Jetpack Compose (BOM 2026.09.00) + Material 3 |
| Playback | Media3 ExoPlayer 1.11.1 + MediaSessionService |
| Navigation | AndroidX Navigation3 1.1.7 (typed routes) |
| DI | Manual DI (singletons on Application) |
| Persistence | DataStore Preferences 1.2.1 |
| Image loading | Coil 3.6.2 |
| Network | OkHttp 5.5.0 |
| Serialization | kotlinx.serialization 1.11.0 |
| Async | kotlinx.coroutines 1.11.0 (+ coroutines-guava for MediaController futures) |
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
│       │   │   ├── cache/               #   Cache inventory (categories, usage, cold-start reclaim)
│       │   │   ├── music/               #   Music scanning / online sources / metadata / proxy source
│       │   │   │   ├── api/             #     Online music sources (Netease / QQ / Kugou / Kuwo / Migu) & HTTP client
│       │   │   │   ├── analysis/        #     Lossless-format, audio-quality & AI-music analysis, audio info, word-level lyric alignment, FFT & full-track spectrogram, full-analysis lock
│       │   │   │   ├── blacklist/       #     Blacklist store
│       │   │   │   ├── clip/            #     Sharing, default ringtone / alarm installer, readable URIs, spectrum image export
│       │   │   │   ├── download/        #     Online track download & cache
│       │   │   │   ├── metadata/        #     Cover management, metadata & lyric read/write, metadata cache, gallery image writes
│       │   │   │   ├── model/           #     Track & search data models (platform key as identity)
│       │   │   │   ├── panel/           #     Panel state holder, search logic & lyric alignment entry
│       │   │   │   ├── playback/        #     Playback state, player helper, queue switch & playlist sorting
│       │   │   │   ├── proxy/           #     Proxy source (import / parse / engine / store), custom-platform registry & playlist syncer
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
│       │   ├── permission/              # Permission & overlay-grant monitors
│       │   ├── screens/                 # Screens (home / settings / cache / typography / spectrum)
│       │   │   ├── home/                #   Home player + permission flow + playlists + online search
│       │   │   │   ├── compact/         #     Portrait assembly, player & player parts
│       │   │   │   ├── expanded/        #     Landscape assembly, player & player parts
│       │   │   │   └── component/       #     analysis / bar / dialog / panel / permission / player / playlist / queue / search / shell / swipe
│       │   │   ├── settings/            #   Appearance / blacklist / cache / language / playback / proxy source / about
│       │   │   ├── cache/               #   Storage / cache management
│       │   │   ├── spectrum/            #   Spectrum analysis page (compact / expanded / component)
│       │   │   └── typography/          #   Lyric typography settings
│       │   ├── service/                 # MediaSessionService playback engine
│       │   ├── theme/                   # Material 3 color & typography
│       │   ├── ui/                      # Shared UI (component / component/dialog / component/player / component/section / icons)
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
├── docs/                                # Proxy source spec (忆潮代理音源规范.md) & notes
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

Code reused by two or more features is promoted to the top level (`data/`, `theme/`, `utils/`, `ui/`); feature-specific code stays inside the feature module. The playback logic lives in `data/music` (playback, download, analysis, panel, recommend) and is exposed to the UI through a window-level `MusicPanelStateHolder`; the floating UI is split between `floatingwindow/` (view managers plus the mini player's own composables) and `ui/component/player` (the full panel and its sub-parts), while playback runs in `service/MusicPlaybackService` (Media3 ExoPlayer + `MediaSessionService`).

Beyond the screens, two pieces of logic are deliberately kept outside the UI trees so they survive recomposition and rotation: the home **panel state** (`HomePanelState`, holding playlist visibility, dialogs, swipe controller and the library-analysis session) and the shared **playback state holder**. The library-analysis session in particular lives at the home level, so closing its sheet does not abort a running analysis. The daily-recommendation chart pool and the playback boot mirror follow the same idea: the pool warms up in the background from `App`, and the last playback state is mirrored to disk so the first frame after a cold start is already complete.

Spectrum analysis is a screen of its own: the page keeps its analysis session in a track-keyed `SpectrumViewModel`, runs the decode on `Dispatchers.Default` and lets leaving the page cancel it, while the decode itself (`SpectrogramDecoder`) and the verdict entry point (`FullSpectrumAnalyzer`) sit in `data/music/analysis` beside the library analysis they share a verdict cache and a criterion with. `FullAnalysisLock` is what keeps a full-track verdict authoritative — the segmented sampling of a library run skips locked tracks instead of overwriting them.

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
| Install packages (`REQUEST_INSTALL_PACKAGES`) | Launching the system installer for an in-app update |
| Write settings (`WRITE_SETTINGS`) | Setting a track as the default ringtone / alarm sound |

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

APKs are emitted as `EchoTideMusic-<versionName>-arm64.apk` under `app/build/outputs/apk/`. Only the `arm64-v8a` ABI is built.

### Release Signing

The release build reads signing credentials from `local.properties` in the project root:

```properties
KEYSTORE_PASSWORD=your_store_password
KEY_ALIAS=jh
KEY_PASSWORD=your_key_password
```

The keystore file is expected at `jh.keystore` in the project root (adjust `storeFile` in `app/build.gradle.kts` if needed). Both files are git-ignored — never commit them.

## Disclaimer

Online music search relies on third-party public web endpoints (Netease / QQ Music / Kugou / Kuwo / Migu). The built-in sources are used for basic song search, cover lookup and lyric lookup only — playback is not a capability they promise, so the app can play trial clips and free tracks in theory only, when a source returns a playable URL; lossless upgrade depends on a proxy source. Availability varies by region and song. The app is for personal study and communication only — please support the copyright holders.

## Acknowledgements

- Lyric animations and NetEase cloud music parsing originally referenced from [Qplayer](https://github.com/TIMER-err/qplayer)
- Drag-reorder of list items originally referenced from [Reorderable](https://github.com/Calvin-LL/Reorderable); now self-implemented in-app (algorithm-equivalent)
- QQ Music, Kugou, Kuwo and Migu Kotlin-native audio source parsing is based on [musicdl](https://github.com/CharlesPikachu/musicdl)

## License

[AGPL-3.0](LICENSE) © 2026 Evilgodxu
