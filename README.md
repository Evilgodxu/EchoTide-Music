<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp" width="96" alt="Echo Tide" />

# Echo Tide · 忆潮音乐

**Immersive listening, faithful to the original sound**

**English** | [简体中文](README.zh-CN.md)

![License](https://img.shields.io/badge/license-AGPL--3.0-blue)
![Platform](https://img.shields.io/badge/platform-Android-brightgreen)
![Version](https://img.shields.io/badge/version-5.4.5-informational)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4.21-purple)
![AGP](https://img.shields.io/badge/AGP-9.4.1-blue)
![Gradle](https://img.shields.io/badge/Gradle-9.8.2-blue)
![Compose BOM](https://img.shields.io/badge/Compose%20BOM-2026.09.00-blue)
![minSdk](https://img.shields.io/badge/minSdk-34-orange)
![targetSdk](https://img.shields.io/badge/targetSdk-37-orange)

<img src="docs/Screenshot/promo-hero.webp" width="100%" alt="Echo Tide UI preview: framed portrait player, landscape player and landscape 3D cover carousel" />

</div>

## Showcase

Portrait or landscape, immersion is the only goal.

| Portrait player | Landscape player | Landscape 3D carousel |
| :---: | :---: | :---: |
| <img src="docs/Screenshot/device-portrait.webp" width="215" alt="Portrait player (framed)" /> | <img src="docs/Screenshot/device-landscape.webp" width="380" alt="Landscape player (framed)" /> | <img src="docs/Screenshot/device-carousel.webp" width="380" alt="Landscape 3D cover carousel (framed)" /> |

> The screenshots are captures from a real device. Cover art and lyrics remain the property of their respective authors and are shown for interface demonstration only.

## Tech Stack

| Layer | Technology |
| --- | --- |
| Language | Kotlin 2.4.21 |
| UI | Jetpack Compose (BOM 2026.09.00) + Material 3 |
| Playback | Media3 ExoPlayer 1.11.1 + MediaSessionService (USB exclusive output goes through the native isochronous layer) |
| Navigation | AndroidX Navigation3 1.2.0 (type-safe routes) |
| Dependency injection | Manual DI (singletons held on Application) |
| Persistence | DataStore Preferences 1.3.0 |
| Image loading | Coil 3.6.3 |
| Network | OkHttp 5.5.1 |
| Serialization | kotlinx.serialization 1.12.0 |
| Async | kotlinx.coroutines 1.11.0 (incl. coroutines-guava bridging MediaController futures) |
| Adaptive layout | androidx.window 1.5.1, material3-adaptive 1.3.0 |
| Lifecycle | androidx.lifecycle 2.11.0, activity-compose 1.13.0 |
| Native layer | NDK 28.2.13676358, CMake 3.22.1 (usbfs isochronous transfer for USB exclusive output) |
| Build | AGP 9.4.1, Gradle 9.8.2, refreshVersions |

## Project Structure

```
.
├── app/
│   └── src/main/
│       ├── kotlin/com/yichao/evilgodxu/
│       │   ├── data/                    # Data layer
│       │   │   ├── cache/               #   Cache ledger (category registry / per-category usage sampling / cold-start reclaim)
│       │   │   ├── music/               #   Music domain
│       │   │   │   ├── analysis/        #     Lossless containers, PCM formats, FFT & full-track spectrum, container header parsing
│       │   │   │   ├── api/             #     Song / cover / lyric retrieval, lyric codec (LRC / QRC / KRC / lrcx), audio quality, translation & HTTP client
│       │   │   │   ├── clip/            #     Sharing, default ringtone / alarm setup, readable local URIs, spectrum image export
│       │   │   │   ├── download/        #     Track download & cache
│       │   │   │   ├── highlight/       #     Chorus (highlight) location by pure lyric analysis, whole-library segment table (background scan + persist)
│       │   │   │   ├── metadata/        #     Cover pipeline & colour extraction, metadata / lyric read-write (ranged streaming tag I/O), embedded-tag backfill, per-level caches
│       │   │   │   ├── model/           #     Track & search data models (platform key as identity)
│       │   │   │   ├── panel/           #     Shared playback state holder, search logic & lyric translation
│       │   │   │   ├── playback/        #     Playback state, queue switching, sorting, recent plays, USB direct / exclusive output & Do Not Disturb, per-device audio output (incl. buffer policy), output-latency measurement, audio-info snapshot (incl. Bluetooth link & codec resolution)
│       │   │   │   ├── proxy/           #     Proxy sources (import / parse / engine / storage) & custom-platform registry
│       │   │   │   ├── recommend/       #     Lyric text features (shared with chorus location)
│       │   │   │   ├── MusicScanner.kt  #     MediaStore scanning & track enrichment
│       │   │   │   └── PlaylistRefresher.kt  # Playlist refresh pipeline
│       │   │   ├── persistence/         #   Persisted-data declarations & shared paths (single source of truth for on-disk file names)
│       │   │   ├── playlist/            #   Playlist store (smart / custom), grouping & per-artist track order
│       │   │   ├── repository/          #   Settings repository
│       │   │   └── settings/            #   Settings DataStore, playback & lyric-layout preferences, boot language mirror
│       │   ├── floatingwindow/          # Mini player overlay manager & controller
│       │   │   └── miniplayer/          #   Mini player overlay / compact bar / expandable playlist
│       │   ├── localization/            # In-app language management
│       │   ├── log/                     # CrashLogManager
│       │   ├── navigation/              # Navigation3 type-safe routes & navigation host
│       │   ├── permission/              # Permissions, overlay grant, battery-optimization whitelist & foreground-state monitoring
│       │   ├── screens/                 # Screens (home / settings / storage / typography / spectrum / metadata)
│       │   │   ├── home/                #   Home player + permission flow + playlists (incl. artist pages) + online search
│       │   │   │   ├── compact/         #     Portrait assembly (compact/player: portrait player)
│       │   │   │   ├── expanded/        #     Landscape assembly (expanded/player: landscape player)
│       │   │   │   └── component/       #     analysis / audioinfo / bar / dialog / panel / permission / player / playlist (incl. playlist/expanded) / queue / search / shell / swipe
│       │   │   ├── settings/            #   Appearance / storage / language / playback / proxy sources / about
│       │   │   │   └── component/       #     appearance / cache / dialog / entry / info / language / playback / proxy
│       │   │   ├── cache/               #   Storage / cache management (compact / expanded / component)
│       │   │   ├── spectrum/            #   Spectrum analysis screen (compact / expanded / component)
│       │   │   ├── typography/          #   Lyric typography settings (compact / expanded / component)
│       │   │   └── metadata/            #   Metadata editing screen (compact / expanded / component)
│       │   ├── service/                 # MediaSessionService playback engine
│       │   ├── theme/                   # Material 3 colour & typography
│       │   ├── ui/                      # Globally shared UI: cover fade / transitions / prefetch / flow-frame store, dialog & picker skeletons, player & section pieces, icons
│       │   ├── update/                  # Update check, in-app update & APK verification
│       │   ├── utils/                   # General utilities
│       │   ├── windowsize/              # Window size classes & landscape form detection
│       │   ├── App.kt                   # Application entry (holds singletons & CompositionLocals)
│       │   ├── AppContent.kt            # Root composable (navigation host & global dialogs)
│       │   ├── MainActivity.kt          # Sole Activity (system bars, external intents, boot language)
│       │   └── MainViewModel.kt         # Activity-scoped ViewModel (incl. AppUiState)
│       ├── cpp/                         # Native layer: usbfs isochronous transfer for USB exclusive output (JNI bridge + isochronous stream)
│       └── res/                         # Resources (values / values-en)
├── baselineprofile/                     # Baseline & startup profile source set
├── gradle/
│   ├── libs.versions.toml               # Version catalog (dependency management)
│   └── wrapper/
├── docs/                                # Proxy source spec, UI screenshots, notes & APK report
├── LICENSE
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

The interface spec for custom proxy sources lives in [忆潮代理音源规范](docs/忆潮代理音源规范.md).

## Permissions

| Permission | Purpose |
| --- | --- |
| Display over other apps | Mini player during background playback, and the playlist it expands into |
| All files access | Import and manage local music files |
| Music access (`READ_MEDIA_AUDIO`) | Read the device library and play from it |
| Images (`READ_MEDIA_IMAGES`, `READ_MEDIA_VISUAL_USER_SELECTED`) | Embedded cover art and local cover candidates; Android 14's partial "selected photos" grant counts as granted |
| Bluetooth (`BLUETOOTH_CONNECT`) | Read the name, address and negotiated codec of the current Bluetooth output device (audio info) |
| Foreground service (`mediaPlayback`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`) | Background playback + notification / lock-screen controls |
| Notifications (`POST_NOTIFICATIONS`) | Version-update download completion notification (Android 13+) |
| Network (`INTERNET`, `ACCESS_NETWORK_STATE`) | Online search, lyric and cover retrieval, update check |
| Audio settings (`MODIFY_AUDIO_SETTINGS`) | Audio configuration of the playback engine, including the dynamic mixer attributes USB direct output requests per the device's declaration |
| Do Not Disturb (`ACCESS_NOTIFICATION_POLICY`) | Automatically switches to "alarms only" during USB direct output, muting notifications and ringtones (the media stream is not suppressed) |
| Wake lock (`WAKE_LOCK`) | Keeps the playback engine running with the screen off |
| Ignore battery optimizations (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) | Keeps background playback from being reclaimed by the system |
| Install packages (`REQUEST_INSTALL_PACKAGES`) | Launches the system installer for an in-app update |
| Write settings (`WRITE_SETTINGS`) | Sets a track as the default incoming-call ringtone / alarm ringtone |

Permissions are requested one at a time by the onboarding dialog: the three library permissions are required, while notifications and the battery-optimization whitelist are optional and listed only when missing. Android 14's partial "selected photos" grant is treated the same as the full image permission — the query still returns the images the user has selected, which is enough to pick a cover. The Bluetooth permission is not part of onboarding: it is requested on the spot, and only when the audio-info panel finds the current output to be a Bluetooth device, so anyone who never opens that panel is never asked for it.

## Getting Started

### Requirements

- JDK 21 - 25
- Android Studio (the latest stable release is recommended)
- An Android SDK that contains API 37 (`compileSdk`)

### Build

```bash
git clone https://github.com/Evilgodxu/EchoTide-Music.git
cd EchoTide-Music

# Debug build
./gradlew assembleDebug

# Release build (requires signing configuration first, see below)
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/` as `EchoTideMusic-<versionName>-arm64-v8a.apk`; only the `arm64-v8a` ABI is built.

### Release Signing

The release build reads the signing credentials from `local.properties` in the project root:

```properties
KEYSTORE_PASSWORD=your_keystore_password
KEY_ALIAS=your_key_alias
KEY_PASSWORD=your_key_password
```

The keystore file is expected at `jh.keystore` in the project root (to relocate it, change `storeFile` in `app/build.gradle.kts`). Both files are git-ignored; never commit them.

## Disclaimer

Echo Tide is a player for a local music library. Proxy sources only declare personal network interface configurations; the app provides no runtime cracking service of any kind.

## Acknowledgements

- The NetEase Cloud Music search service once referenced [Qplayer](https://github.com/TIMER-err/qplayer)
- List drag-reorder references [Reorderable](https://github.com/Calvin-LL/Reorderable) through an equivalent implementation, and is not consumed as a dependency
- The Kotlin-native search services for NetEase Cloud Music, Kugou, Kuwo and QQ are implemented based on [musicdl](https://github.com/CharlesPikachu/musicdl)

## License

[AGPL-3.0](LICENSE) © 2026 Evilgodxu
