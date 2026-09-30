<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp" width="96" alt="Echo Tide" />

# Echo Tide · 忆潮音乐

**Immersive music player**

**An old-school, lightweight and minimal immersive music player**

**English** | [简体中文](README.zh-CN.md)

![License](https://img.shields.io/badge/license-AGPL--3.0-blue)
![Platform](https://img.shields.io/badge/platform-Android-brightgreen)
![Version](https://img.shields.io/badge/version-4.3.0-informational)
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

## Highlights

| | |
| --- | --- |
| **Floating music panel** | The full player runs as a system overlay — change tracks, read lyrics and search above any app |
| **Mini player** | A single lyric bar that docks to the top when you leave the app; one tap expands the full panel |
| **Word-level lyrics** | Line-level lyrics can be aligned into word-level in-app by algorithm |
| **Lyric auto-translation** | Fill in Chinese translations for a whole song in one action |
| **Library analysis** | Locate the whole library by format, flagging fake lossless and suspected AI-generated music |
| **Full-track spectrum** | A 2048-point STFT time-frequency chart with a logarithmic frequency axis and a dB scale; long-press to export a 1920 px verdict image |
| **USB exclusive output** | Hand playback to a USB DAC and ask the system for a bit-perfect stream — no mixing, no volume scaling, no effects |
| **Playback chain info** | One panel holding the whole chain: source file and size, decoder, source / output sample rate, resampling, output mode, the PCM encoding actually written and the active output device's real parameters — down to the negotiated Bluetooth codec |

---

## Feature Tour

Each feature is written as *what it is → how to use it*. Every path listed matches the current code.

### 1. Playback & UI

- **Playback speed** — 0.5× to 2.0× in real time, ±0.1 steps, handled natively by AudioTrack with no software resampling.
  - **How to use**: long-press *previous* or *next* on the home screen → the speed dialog opens → tap the value in the middle to reset to 1.0×.
- **Sleep timer** — stops after the current track finishes, then ends the app process.
  - **How to use**: the timer button on the left of the portrait title bar → ±5 minutes (1–999) → confirm. While counting down, the remaining minutes sit under the title and tapping them cancels the timer.
- **Home gestures** — three side-by-side pages: search / player / playlists, opening on the player.
  - **How to use**: swipe right → search page; swipe left → playlist page; swipe vertically → next / previous track (switchable in settings — a held swipe first shows a track preview and cancels if the movement returns near the start).
- **Adaptive layout** — WindowSizeClass switches between the single-column portrait assembly and the two-column landscape assembly.
  - **How to use**: the rotation button on the right of the portrait title bar forces landscape; in landscape, tapping the cover area enters the 3D cover carousel — drag to switch, tap the centred cover to play, tap a side cover to select it, tap the empty space to exit.
- **Theme & localization** — system / dark / light, and in-app hot switching between 简体中文 / English / follow system.
  - **How to use**: Settings → Appearance → Theme (the switch plays a circular reveal); Settings → Language → pick a language.

### 2. Lyrics

- **Lyric rendering** — word-level rendering that jumps in sync, or line-level highlighting.
  - **How to use**: lyrics scroll with playback; Settings → Playback → *Word-by-word rendering* controls whether words light up individually; a single tap on the lyrics shows or hides the fine-tune buttons (left `−` delays, right `+` advances, 100 ms per step).
- **Drag-to-scrub and fling track switching** — a vertical drag over the lyrics area scrubs playback in real time.
  - **How to use**: a slow drag is followed by the lyrics, and releasing on a line plays from it while the marker turns into a confirmation colour; an unaligned release springs back to where it was. A quick flick never moves the lyrics and is handled as a vertical swipe to switch tracks.
- **Word-level alignment** — turns lyrics that only have a line-level timeline into a word-level timeline using the audio itself.
  - **How to use**: long-press the lyrics → *Word-level alignment* → decoding and alignment run in the background with progress shown in the portrait title area, and continue while you leave the page.
- **Lyric auto-translation** — fills in Chinese translations for every lyric line that has none.
  - **How to use**: long-press the lyrics → *Edit lyrics* → *Auto-translate* → the progress dialog offers *Continue in background*; the action can be re-run at any time to fill in whatever is left.
- **Lyric editing & import** — long-press the lyrics → *Edit lyrics* → *Edit this line*, or *Local lyrics* to import any text format through the system file picker; the same long-press menu can also search the current track.
- **Lyric typography** — per-scene font size, visible-line count and landscape 3D intensity for the music panel, home portrait and home landscape.
  - **How to use**: Settings → Typography (font size 12–24 sp; line counts by scene preset — 3/5, 3/5/7/9/11 and 7/9/11 — and landscape 3D intensity 0–2 in 0.25 steps).

### 3. Music Sources

- **Local library** — scans device audio through MediaStore (only tracks with `IS_MUSIC` and a duration of at least 30 seconds, ordered by title), and imports audio through the system share sheet / open-with.
  - **How to use**: the scan starts automatically after the first permission grant; the *refresh* button in the queue panel header rescans on demand; choosing *open with Echo Tide* in a file manager, or sharing audio to the app, takes over and plays it in the background with the mini player attached.
- **Proxy sources & custom platforms** — import a third-party source to define search, playback URL, lyric and cover resolution per platform, gaining full capability.
  - **How to use**: Settings → Proxy Source → *Import source* → one of three routes: a local file, a link, or pasted text; once imported, the switch on the right of the row enables or disables it and the bin icon removes it. A source opened with Echo Tide or shared to the app is imported the same way. The proxy source development spec is documented in the [忆潮代理音源规范](docs/忆潮代理音源规范.md) (v1.2.0).
- **Daily recommendation** — a lyric-profile recommender: your favourites' lyric profile scores tracks through lexical, conceptual and rhythmic channels, then diversity-reranking (MMR) produces a five-track carousel.
  - **How to use**: the daily carousel on the online search page advances every 4 seconds; the refresh button at the top right forces a recompute; tapping a card plays it; the broken-heart button on the right of a card blacklists that track so it is no longer recommended.
- **Blacklist & skip feedback** — blacklisting takes effect immediately and persists, and skips take part in later recommendations.
  - **How to use**: swipe a queue row left → blacklist; Settings → Blacklist shows the count and can reset the whole list so those tracks take part in recommendations again.

### 4. Playlists & Queue

- **Smart playlists** — Recently Played / Favourites / Albums / Artists, derived live from the library.
  - **How to use**: swipe left to the playlist page → open any system playlist card; *Play all* at the top of the detail page plays the whole list; tapping the artist line on the home screen jumps straight to that artist's playlist (a picker appears first when a track has several artists).
- **Custom playlists** — create / rename / delete / batch add / drag to reorder.
  - **How to use**: the playlist page → *Create playlist*; *Add tracks* on the detail page multi-selects and adds in bulk; long-pressing a row's sort handle and dragging reorders it (dragging syncs to the playback queue in real time); the *more* button on the right of a playlist row renames or deletes it.
- **Playback queue** — the playback queue.
  - **How to use**: the *playlist* button at the bottom right of the player page (same place in landscape) opens it, and so does a swipe up on that button (portrait and landscape alike), alongside the floating *scroll to top* and *locate current* buttons; a queue row supports swipe left to blacklist, swipe right for the advanced menu (share / set as ringtone / set as alarm / view spectrum) and long-press to delete.
- **Playlist sorting** — a sort button in the queue panel header offering default order / modified time / title / artist / album / duration, with an ascending-descending toggle.
  - **How to use**: the sort button in the queue panel header → pick a rule (offered for the default full queue only, so custom playlists keep their own drag order).
- **Playlist switching** — the playlist subtitle in the queue panel header.
  - **How to use**: the playlist subtitle in the queue panel header (same place in landscape) opens it; it shows the current playlist (tap to switch quickly);

### 5. Analysis & Audio Quality

- **Library analysis** — one pass produces two verdicts: fake lossless and suspected AI-generated music; it also locates the whole library by format and jumps straight to it.
  - **How to use**: in portrait, long-press the *playlist* button on the control bar → the library analysis panel → the analysis starts on its own (closing the panel does not abort it — the portrait title area shows *analysing x/y*); the panel holds a format-share ring and a *locate by format* list whose first two entries are *fake lossless* and *suspected AI*; tapping any row makes that category the playback queue and jumps to the playlist; the refresh button at the top right re-runs the analysis at any time.
- **Spectrum analysis** — decodes the whole track and renders a time-frequency spectrogram.
  - **How to use**: swipe a queue row right → advanced menu → *View spectrum* → wait for the decode progress → read the spectrogram, the source file parameters and both verdicts → long-press the chart → *Share image* or *Save image*.
- **USB exclusive output** — pins playback to the USB DAC and asks the native audio policy for a bit-perfect stream (`setPreferredMixerAttributes` + `setPreferredAudioDevice`) — no mixing, no volume scaling, no effects. Mixer attributes are picked from the decoded format and re-issued whenever it changes, so the *exclusive* label never hides a silent fallback to the mixed path.
  - **How to use**: Settings → Playback → *USB exclusive* (the same switch also sits in the floating panel's playback settings) → with no DAC attached, or with a DAC that ships no bit-perfect profile, playback stays on the default mixed output; plugging the DAC in or out takes effect immediately, with no restart.
- **Audio info** — the whole playback chain in one panel, grouped into audio source / audio parameters / playback chain / current output device; fields the platform cannot report are dropped rather than shown blank.
  - **What it shows**: source file path and size, format, decoder, source / output sample rate, bitrate, channel layout, resampling, quality; output mode (bit-perfect exclusive or system mixer), audio session ID, float output, the PCM encoding the audio track actually writes, average latency, transfer state; and the current output device's type, name, address, supported sample rates, channel count, plus — on Bluetooth — link type, negotiated codec, codec sample rate, bits per sample, channel mode and device category. The panel re-reads the whole chain as playback goes on.
  - **How to use**: in portrait, swipe up on the play/pause button on the control bar → the panel slides in (the floating panel opens it the same way, by a swipe up anywhere on the panel); drag down, tap the close button or tap outside to dismiss. Reading a Bluetooth device's name and address needs the Bluetooth permission, which the panel asks for in place — a floating panel has no activity to host the system dialog, so the home permission dialog covers it there.

### 6. Floating & System Integration

- **Floating music panel** — the full player rendered as a system overlay (`TYPE_APPLICATION_OVERLAY`), able to cover any app.
  - **How to use**: Settings → Playback → enable *Floating playback* and grant the overlay permission → the mini player appears at the top while the app keeps playing in the background → **tap the cover** to expand the full panel. Inside the panel: swipe up for the audio info, swipe right for search, swipe left for playback settings, and a downward swipe closes the audio info overlay; a tap outside the card or Back closes one layer at a time, and Back on the base layer retracts the panel.
- **Mini player** — a mini capsule bar that docks to the top of the screen while playing in the background.
  - **How to use**: same *Floating playback* switch; it first shows five control buttons (play mode / previous / play-pause / next / playlist), collapses by itself after 3 seconds without touch and then shows the track title and the current lyric line, which lights up word by word as it is sung (whole-line highlighting when *Word-by-word rendering* is off); swipe left or right to change tracks, swipe down to hide (reset when the app returns to the foreground); tapping the cover expands the full panel and the playlist button opens the mini list. Returning to the foreground hides the mini player automatically.
- **External audio takeover** — audio opened or shared from a file manager or another app is taken over in the background.
  - **How to use**: choose Echo Tide when opening an audio file in a file manager, or share audio to the app from the system share sheet; no full-screen panel is shown — playback starts in the background with the mini player attached.
- **Sharing & ringtones** — a local track can be shared through the system share sheet, or set as the default ringtone / alarm sound.
  - **How to use**: swipe a queue row right → advanced menu → share / set as ringtone / set as alarm (needs the modify-system-settings permission).
- **In-app update** — automatically checks GitHub Releases once a day when returning to the foreground, or on demand; a download is verified against a SHA-256 digest before installation.
  - **How to use**: tap the version number at the bottom of Settings → a manual check runs (a new version opens a dialog with the changelog and *Download* / *Later*, while up-to-date and failure both report a message) → the download triggers the system installer on completion. A failed download offers *Open in browser*, and downloads are HTTPS only.
- **Storage management** — a dedicated page inventories usage per category, grouped by scope.
  - **How to use**: Settings → Storage management (the page resamples on entry and supports pull-to-refresh) → *Clear cache* to clean the clearable group in one tap.
- **Crash logging** — uncaught and caught exceptions are written to app-specific external storage, keeping today's log only and cleaning older ones automatically.
  - **How to use**: tap `[日志]` at the bottom of Settings to share today's log through the system share sheet.
- **Permission onboarding** — the first launch walks through permissions with a card dialog; the library permissions block, the rest are optional.
  - **How to use**: the dialog lists all-files access, music access and image access — the three the home screen cannot work without — each with its own *Grant* button; Bluetooth, notifications and the battery-optimization whitelist (*background playback*) are appended only while they are missing and never stop the dialog from closing, and the whole list scrolls so no row is cut off on a small screen. Once the three library permissions are granted the dialog closes itself and the library scan starts automatically; a system settings page hands the app back on its own.

---

## Interaction Cheat Sheet

| Where | Action | Result |
| --- | --- | --- |
| Home player page | Swipe right / left | Search page / playlist page |
| Home player page | Swipe up / down | Next / previous track (switchable in settings; a held swipe previews first and cancels when it returns near the start) |
| Portrait cover | Long-press | Search cover / local cover / save cover |
| Portrait title & artist | Long-press / tap | Copy / rename / search; tapping the artist jumps to that artist's playlist |
| Portrait lyrics area | Tap | Show or hide the lyric fine-tune buttons (100 ms per step) |
| Portrait lyrics area | Long-press | Search / local lyrics / edit lyrics (edit this line, auto-translate) / word-level alignment |
| Portrait lyrics area | Slow vertical drag | Lyrics follow your finger; releasing on a line plays from it |
| Portrait lyrics area | Quick vertical flick | Lyrics stay put and the flick switches tracks |
| Control bar | Long-press previous / next | Open the speed dialog |
| Control bar | Swipe up on play/pause | Open the audio info panel (portrait only) |
| Control bar | Long-press the queue button | Open the library analysis (portrait only) |
| Control bar | Tap the queue button | Open the playback queue panel |
| Control bar | Swipe up on the queue button | Open the playback queue panel (portrait and landscape) |
| Format line under the progress bar | Tap | Audio-quality upgrade (when a proxy source is available) |
| Queue row | Swipe left / right / long-press | Blacklist / advanced menu (share, ringtone, spectrum) / delete |
| Custom playlist row | Drag the sort handle | Reorder tracks |
| Landscape cover area | Tap | Enter the 3D cover carousel |
| Floating panel | Swipe up / swipe down | Open the audio info / close the audio info |
| Spectrum chart | Long-press | Share image / save image |
| Daily recommendation card | Tap / broken-heart button | Play / blacklist it from recommendations |

## Screens

| Screen | Contents |
| --- | --- |
| Home | Permission onboarding dialog (auto-hides once the three library permissions are granted, with Bluetooth / notifications / battery whitelist listed while missing), immersive player (full-width cover in portrait, two columns with 3D lyric perspective in landscape), synced lyrics, a refreshable, searchable and sortable playback queue, the playlist panel, the audio info panel (source / parameters / playback chain / current output device), search (custom platforms + the daily recommendation carousel), the library analysis entry, sleep timer, speed control and audio-quality upgrade |
| Settings | Appearance (theme), Language, Playback (floating playback / word-by-word rendering / swipe to change track / background flow / USB exclusive) with a Typography entry, Proxy Source (import / enable / remove), Storage management entry, Blacklist (count and reset), About (version doubles as the update check, share today's log, GitHub, QQ group) |
| Typography | Per-scene lyric font size, visible-line count and landscape 3D intensity for the music panel, home portrait and home landscape |
| Storage | Cache inventory grouped into temporary files (clearable) / app data / user data, with totals, pull-to-refresh resampling and one-tap clearing |
| Spectrum | Full-track time-frequency spectrogram (logarithmic frequency axis, dB colour scale, time labels), decoding progress, the source file's format parameters and size, and the same two verdicts the library analysis produces; long-press to share or save a 1920 px PNG |

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
│       │   │   │   ├── analysis/        #     Lossless-format, audio-quality & AI-music analysis, generator-signature forensics, audio info, word-level lyric alignment, FFT & full-track spectrogram, full-analysis lock
│       │   │   │   ├── blacklist/       #     Blacklist store
│       │   │   │   ├── clip/            #     Sharing, default ringtone / alarm installer, readable URIs, spectrum image export
│       │   │   │   ├── download/        #     Online track download & cache
│       │   │   │   ├── metadata/        #     Cover management, metadata & lyric read/write (ranged streaming tag I/O), metadata cache, gallery image writes
│       │   │   │   ├── model/           #     Track & search data models (platform key as identity)
│       │   │   │   ├── panel/           #     Panel state holder, search logic & lyric alignment entry
│       │   │   │   ├── playback/        #     Playback state, player helper, queue switch, playlist sorting, USB exclusive output, per-device audio sink, audio-info snapshot (incl. Bluetooth link & codec resolution)
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
│       │   ├── permission/              # Permission, overlay-grant & battery-whitelist monitors
│       │   ├── screens/                 # Screens (home / settings / cache / typography / spectrum)
│       │   │   ├── home/                #   Home player + permission flow + playlists + online search
│       │   │   │   ├── compact/         #     Portrait assembly, player & player parts
│       │   │   │   ├── expanded/        #     Landscape assembly, player & player parts
│       │   │   │   └── component/       #     analysis / audioinfo / bar / dialog / panel / permission / player / playlist / queue / search / shell / swipe
│       │   │   ├── settings/            #   Appearance / blacklist / cache / language / playback / proxy source / about
│       │   │   ├── cache/               #   Storage / cache management
│       │   │   ├── spectrum/            #   Spectrum analysis page (compact / expanded / component)
│       │   │   └── typography/          #   Lyric typography settings
│       │   ├── service/                 # MediaSessionService playback engine
│       │   ├── theme/                   # Material 3 color & typography
│       │   ├── ui/                      # Shared UI (component → incl. the cover-fade CoverFade / component/dialog / component/player → incl. the audio info overlay & content / component/section / icons)
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

Lyric parsing is likewise collected in one place: `data/music/api/LyricCodec` normalises every platform's raw lyrics (plain LRC, the inline tags of enhanced LRC, QQ's QRC, Kugou's KRC, Kuwo's lrcx) into one `LyricLine` list, with all word-level timelines expressed in absolute milliseconds — so the parse results of different platforms are directly comparable, and the "word-level first, degrade to line-level when a platform ships none" policy only has to exist once. Fetching, parsing, cache writes and auto-translation live in `OnlineLyrics`, `LyricCodec`, `MusicMetadataCache` and `data/music/panel/MusicPanelLyricsTranslate` respectively, the last sharing its progress dialog with word-level alignment. Kugou's KRC is the one source that carries translations itself: they travel in the `[language]` metadata block (base64 JSON, the `type=1` segment) aligned to the lyric lines by order, so such a track shows a translation without the translation endpoint being called at all — only the zero-word-offset translation lines still merge by timestamp.

The audio info panel reads the playback path instead of being wired into any player layout: `AudioInfoCollector` assembles one `AudioInfoSnapshot` from the shared playback state, and the Compose side recomputes it off a version counter that the player's own callbacks (playback state, play-when-ready, audio session id), the `AudioDeviceCallback` (device plugged or unplugged) and `ON_RESUME` (so a permission just granted shows up at once) each bump. Every field is nullable and an unreadable field produces no row at all, which is why the same panel works over a speaker, a USB DAC and a Bluetooth link without branching on the UI side. The chain values it prints — float output, the PCM encoding actually written, bit-perfect exclusive — are reported upwards by the per-device sink and the USB exclusive module into that shared state, so the panel reads the same source the playback path writes rather than guessing from the source format.

Tag rewriting on lossless and linear-PCM containers goes through `TagSource`, which exposes only ranged reads and ranged copies: the tag layout is computed from the headers plus a window at the end of the file, and the audio body is streamed across from its original offsets. A Hi-Res file several hundred megabytes long is therefore no longer held in memory for a tag edit. Container layouts — ID3v2, M4A/MP4 box tables, FLAC Vorbis comments, Ogg page sequences and the IFF/RIFF-style chunks of AIFF, DSDIFF, DSF, APE and WAV — only have to produce "header bytes + audio body range + tail bytes" for the writer.

## Permissions

| Permission | Purpose |
| --- | --- |
| Display over other apps | Floating music panel & mini player |
| All files access | Import and manage local music files |
| Music access (`READ_MEDIA_AUDIO`) | Play tracks from the device library |
| Images (`READ_MEDIA_IMAGES`) | Embedded art & local cover candidates |
| Bluetooth (`BLUETOOTH_CONNECT`) | Name, address and negotiated codec of the current Bluetooth output device (audio info) |
| Foreground service (`mediaPlayback`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`) | Background playback with notification / lock-screen controls |
| Notifications (`POST_NOTIFICATIONS`) | Update download completion notification (Android 13+) |
| Network (`INTERNET`, `ACCESS_NETWORK_STATE`) | Online search, lyrics, cover lookup and update check |
| Audio settings (`MODIFY_AUDIO_SETTINGS`) | Audio configuration for the playback engine, incl. the bit-perfect mixer request behind USB exclusive output |
| Wake lock (`WAKE_LOCK`) | Keeps the playback engine running with the screen off |
| Ignore battery optimizations (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) | Keeps background playback from being killed by the system |
| Install packages (`REQUEST_INSTALL_PACKAGES`) | Launching the system installer for an in-app update |
| Write settings (`WRITE_SETTINGS`) | Setting a track as the default ringtone / alarm sound |

Permissions are requested from the onboarding dialog one at a time: the three library permissions are required, while Bluetooth, notifications and the battery whitelist are optional and listed only while missing. The Bluetooth permission is additionally requested on the spot by the audio info panel when the current output is a Bluetooth device.

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
