<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp" width="96" alt="Echo Tide" />

# Echo Tide · 忆潮音乐

**Immersive listening, true to the source**

*Built for lossless and Hi-Res libraries — direct USB output, full-track spectrum, word-level lyrics.*

**English** | [简体中文](README.zh-CN.md)

![License](https://img.shields.io/badge/license-AGPL--3.0-blue)
![Platform](https://img.shields.io/badge/platform-Android-brightgreen)
![Version](https://img.shields.io/badge/version-5.1.0-informational)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4.21-purple)
![AGP](https://img.shields.io/badge/AGP-9.4.1-blue)
![Gradle](https://img.shields.io/badge/Gradle-9.8.0-blue)
![Compose BOM](https://img.shields.io/badge/Compose%20BOM-2026.09.00-blue)
![minSdk](https://img.shields.io/badge/minSdk-34-orange)
![targetSdk](https://img.shields.io/badge/targetSdk-37-orange)

<img src="docs/Screenshot/promo-hero.webp" width="100%" alt="Echo Tide preview: framed portrait player, landscape player and landscape 3D cover carousel" />

</div>

## Highlights

- **Immersive, in both forms** — a framed portrait player and a landscape 3D cover carousel, resting on a background that borrows its colour from the cover art and drifts with the music.

- **Direct USB output** — bypasses the system mixer and writes at the bit depth the connected DAC advertises, handing notifications over to Do-Not-Disturb while it plays.

- **Lossless and Hi-Res, end to end** — WAV, AIFF, DSF, DFF, APE and more are parsed from scratch, and ranged streaming tag I/O rewrites their tags without pulling the whole file into memory.

- **Full-track spectrum analysis** — a parallel decode pipeline turns an entire track into a renderable time-frequency matrix, ready to inspect or export as an image.

- **Lyrics, down to the word** — one absolute-millisecond timeline drives word-level highlighting, with full-text editing and typography controls.

- **Highlight mode** — chorus segments are located by pure lyric analysis in a background library scan and persisted, so playback only consults a table.

- **A mini player that stays out of the way** — a floating overlay keeps the music going outside the app, and the full player is one tap away.

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
│       │   │   ├── cache/               #   Cache ledger (category registry, per-category usage sampling, cold-start reclaim)
│       │   │   ├── music/               #   Music domain
│       │   │   │   ├── analysis/        #     Lossless containers, PCM formats, FFT & full-track spectrogram, container headers
│       │   │   │   ├── api/             #     Song / cover / lyric retrieval, lyric codec (LRC / QRC / KRC / lrcx), quality, translation & HTTP client
│       │   │   │   ├── clip/            #     Sharing, default ringtone / alarm installer, readable local URIs, spectrum image export
│       │   │   │   ├── download/        #     Track download & cache
│       │   │   │   ├── highlight/       #     Chorus (highlight) location by pure lyric analysis & whole-library segment table (background scan + persist)
│       │   │   │   ├── metadata/        #     Cover pipeline & colour extraction, metadata / lyric read-write (ranged streaming tag I/O), embedded-tag backfill, caches
│       │   │   │   ├── model/           #     Track & search models (platform key as identity)
│       │   │   │   ├── panel/           #     Shared playback state holder, search logic & lyric translation
│       │   │   │   ├── playback/        #     Playback state, queue switch, sorting, recent plays, direct USB output & Do Not Disturb, per-device audio sink (incl. buffer policy), output-latency measurement, audio-info snapshot (incl. Bluetooth link & codec resolution)
│       │   │   │   ├── proxy/           #     Proxy source (import / parse / engine / store) & custom-platform registry
│       │   │   │   ├── recommend/       #     Lyric text features shared with chorus location
│       │   │   │   ├── MusicScanner.kt  #     MediaStore scanning & track enrichment
│       │   │   │   └── PlaylistRefresher.kt  # Playlist refresh pipeline
│       │   │   ├── persistence/         #   Persisted-data declarations & shared on-disk paths (single source of truth for file names)
│       │   │   ├── playlist/            #   Playlist store (smart & custom), grouping & per-artist track order
│       │   │   ├── repository/          #   Settings repository
│       │   │   └── settings/            #   Settings DataStore, playback & lyric-layout preferences, boot language mirror
│       │   ├── floatingwindow/          # Mini player overlay manager & controller
│       │   │   └── miniplayer/          #   Mini player overlay / bar / expandable playlist
│       │   ├── localization/            # In-app localization manager
│       │   ├── log/                     # CrashLogManager
│       │   ├── navigation/              # Navigation3 typed routes & nav host
│       │   ├── permission/              # Permission, overlay-grant, battery-whitelist & foreground monitors
│       │   ├── screens/                 # Screens (home / settings / cache / typography / spectrum / metadata)
│       │   │   ├── home/                #   Home player + permission flow + playlists (incl. artist pages) + online search
│       │   │   │   ├── compact/         #     Portrait assembly (compact/player: portrait player)
│       │   │   │   ├── expanded/        #     Landscape assembly (expanded/player: landscape player)
│       │   │   │   └── component/       #     analysis / audioinfo / bar / dialog / panel / permission / player / playlist (incl. playlist/expanded) / queue / search / shell / swipe
│       │   │   ├── settings/            #   Appearance / cache / language / playback / proxy source / about
│       │   │   │   └── component/       #     appearance / cache / dialog / entry / info / language / playback / proxy
│       │   │   ├── cache/               #   Storage / cache management (compact / expanded / component)
│       │   │   ├── spectrum/            #   Spectrum analysis page (compact / expanded / component)
│       │   │   ├── typography/          #   Lyric typography settings (compact / expanded / component)
│       │   │   └── metadata/            #   Metadata editing page (compact / expanded / component)
│       │   ├── service/                 # MediaSessionService playback engine
│       │   ├── theme/                   # Material 3 colour & typography
│       │   ├── ui/                      # Shared UI: cover fade / transition / prefetch / flow-frame store, dialog & picker skeletons, player & section pieces, icons
│       │   ├── update/                  # Version check, in-app update & APK hash verification
│       │   ├── utils/                   # Shared utilities
│       │   ├── windowsize/              # Window size class & landscape form detection
│       │   ├── App.kt                   # Application entry (holds singletons + CompositionLocals)
│       │   ├── AppContent.kt            # Root composable (nav host + global dialogs)
│       │   ├── MainActivity.kt          # Sole activity (system bars, external intents, boot language)
│       │   └── MainViewModel.kt         # Activity-scoped ViewModel (incl. AppUiState)
│       └── res/                         # Resources (values / values-en)
├── baselineprofile/                     # Baseline & startup profile source set
├── gradle/
│   ├── libs.versions.toml               # Version catalog (dependencies)
│   └── wrapper/
├── docs/                                # Proxy source spec, screenshots, notes & APK report
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

- **Highlight segments are computed before they are needed** — chorus location is pure lyric analysis, run as a background whole-library scan and persisted as a segment table; playback only looks the table up, so changing track or mode never triggers analysis and never depends on when the lyrics happen to arrive.

- **The audio path follows the output device** — the sink is rebuilt per device, so direct USB output can write the packed 24/32-bit formats some DACs advertise, while every other output falls back to the system mixer.

- **The audio info panel reads the playback path** — `AudioInfoCollector` builds its snapshot from the shared playback state, so one panel covers speakers, USB DACs and Bluetooth links without branching on the UI side.

- **Tag rewriting streams through ranged I/O** — `TagSource` exposes only ranged reads and ranged copies, so editing the tags of a large Hi-Res file never loads the whole file into memory.

- **Metadata editing is a page built on that path** — the session is keyed by track, re-reads the file's tags whenever the page returns to the foreground and auto-flushes pending edits, which is why the page has no save button.

- **On-disk output is declared where it is produced** — every persisted store carries its own `PersistedData` (name + on-disk kind) beside the code that writes it, and `StorageRegistry` maps each one to a category for the storage page. A name is written once, so a rename can never leave the reader and the accountant pointing at different files; anything left unregistered still surfaces as a fallback row instead of vanishing from the total.

## Permissions

| Permission | Purpose |
| --- | --- |
| Display over other apps | Mini player during background playback, and the playlist it expands into |
| All files access | Import and manage local music files |
| Music access (`READ_MEDIA_AUDIO`) | Play tracks from the device library |
| Images (`READ_MEDIA_IMAGES`, `READ_MEDIA_VISUAL_USER_SELECTED`) | Embedded art & local cover candidates — Android 14's *selected photos* grant counts as granted |
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

Permissions are requested one at a time from the onboarding dialog: the three library permissions are required, while notifications and the battery whitelist are optional and shown only when missing. A partial photo grant (Android 14 *selected photos*) counts as image access, since the query still returns the pictures the user picked. Bluetooth is kept out of onboarding: the audio info panel requests it on the spot once the current output is a Bluetooth device, so users who never open that panel are never asked for it.

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

Echo Tide is a player for the local library you already own, and is intended for personal study and communication only — please support the copyright holders.

## Acknowledgements

- NetEase Cloud Music parsing was originally referenced from [Qplayer](https://github.com/TIMER-err/qplayer)
- List-item drag-reorder from [Reorderable](https://github.com/Calvin-LL/Reorderable), now implemented in-app with an equivalent algorithm
- Kotlin-native audio source parsing for NetEase Cloud Music, Kugou, Kuwo and QQ is based on [musicdl](https://github.com/CharlesPikachu/musicdl)

## License

[AGPL-3.0](LICENSE) © 2026 Evilgodxu
