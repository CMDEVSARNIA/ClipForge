# ClipForge

Android app that turns long gameplay recordings into vertical (9:16) TikTok / YouTube Shorts clips.
Claude picks the moments and writes titles, and every Claude call goes through a small Cloudflare
Worker so the Anthropic API key never ships inside the APK.

> **Status: Phase 1 of 6: import & preview.** See [Roadmap](#roadmap).

## What works now (Phase 1)

- Import a video with the Android Photo Picker (videos only). Read access is persisted so projects reopen after restart.
- Projects list (Room) with thumbnails, duration, resolution, fps, size; remove a project (the original file is never touched).
- Player (Media3 ExoPlayer): tap the video or the button to play/pause, ±5 s, ±1 frame.
- Filmstrip timeline: tap to jump, drag to scrub. **Fine scrub:** while dragging, move your finger up away from the strip to slow down (¼×, 1/20×, 1/100×), so you can land on an exact moment in a 60-minute file.
- Source info: duration, resolution (rotation-aware), frame rate, codec, bitrate, audio track, file size, plus warnings (> 60 min, no audio, non-persistent access).

## Requirements

| Tool | Version |
|---|---|
| JDK | 17 or newer (built with 21) |
| Android SDK | Platform 37 (`compileSdk`), build-tools 37 |
| Gradle | wrapper included (9.9.1) |
| Device | Android 8.0 (API 26) or newer |

Main libraries: AGP 9.5, Kotlin 2.4 (AGP built-in Kotlin), Compose BOM 2026.09 / Material 3, Media3 1.11, Room 2.8 (KSP), Navigation 2.10 (type-safe routes).

## Build the APK

```bash
# Point Gradle at your SDK (or create local.properties with sdk.dir=...)
export ANDROID_HOME=$HOME/Android/Sdk

./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # JVM unit tests
./gradlew lintDebug
```

Install on a phone with USB debugging:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or copy the APK to the phone and open it (allow "Install unknown apps" for your file manager/browser).

**Debug signing:** `app/debug.keystore` is committed on purpose. It uses the standard public debug
password (`android`), so it isn't a secret, and it means a debug APK built on any machine installs
over the previous one without "App not installed" signature conflicts. Don't use it for release builds.

### Release build

`./gradlew assembleRelease` produces an unsigned, minified APK. Sign it with your own keystore
(`apksigner sign --ks my-release.jks app-release-unsigned.apk`) or add a `release` signing config
that reads credentials from environment variables. Never commit a release keystore.

## Project layout

```
app/src/main/java/com/clipforge/
  ClipForgeApp.kt          Application + manual DI container
  MainActivity.kt          Single activity, Compose host
  data/                    Room DB (projects) + repository
  media/                   VideoProbe (metadata), ThumbnailLoader, VideoInfo/format helpers
  ui/nav/                  Type-safe navigation routes
  ui/home/                 Projects list + import
  ui/project/              Player screen + ViewModel (owns ExoPlayer)
  ui/components/           ScrubTimeline + ScrubMath (pure, unit-tested)
backend/                   Cloudflare Worker proxy (Phase 2)
```

Memory: metadata probing reads container headers only; thumbnails are decoded at keyframes and
downscaled immediately (≤192 px, LRU-capped at 4 MB). Long files never load fully into memory.

## Roadmap

1. ✅ Import & preview
2. Moment finding: frame sampling, loudness and scene cuts, Claude (Haiku) via the Worker, cost estimate, ranked clips with trim handles
3. Vertical edit & export (Media3 Transformer, 1080×1920 H.264/AAC, crop modes, WorkManager)
4. Webcam layouts (facecam presets, split / PiP)
5. Titles & captions (on-device whisper.cpp, word-highlight captions, Claude-polished text)
6. In-app assistant with tool use + share sheet

## Cloudflare Worker (Phase 2)

Setup and deployment instructions land with Phase 2, along with the `backend/` source.
