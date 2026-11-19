# ClipForge

Android app that turns long gameplay recordings into vertical (9:16) TikTok / YouTube Shorts clips.
Claude picks the moments and writes titles, and every Claude call goes through a small Cloudflare
Worker so the Anthropic API key never ships inside the APK.

> **Status: all 6 phases built.** See [Roadmap](#roadmap) and [Known limits](#known-limits).

## What works now

### Watermark (Settings → Watermark)

- Your **@handle** and/or a **logo** (any image; PNG transparency is kept), burned into every export and shown in the editor's 9:16 preview.
- **Position:** top left, top right, bottom left, or **Moving**, which hops to the next corner every 4 s so a single crop can't remove it. Plus **size** (S/M/L) and **opacity** (20–100%).
- **Safe placement:** corners sit clear of TikTok/Shorts UI (top bar, caption area, right button rail). Titles shift down below a top watermark, and a corner covered by a PiP cam box is skipped.
- A watermark credits you and makes reposting more work; it can't prevent it.

### Phase 6: assistant & share

- **Assistant** (project screen → *Assistant*): a chat scoped to the project that edits clips through **tool use**: `trim_clip`, `set_clip_range`, `set_crop`, `set_layout`, `set_title`, `set_hashtags`, `set_overlays`, `get_clip_details`. Ask things like "make clip 2 shorter", "punchier titles for the top 3", or "Split on every clip".
  - It runs its own tool loop on the phone (Messages API through the Worker; the Worker allows only client-side tools). The tools are `strict`, `tool_choice` is `auto` (Sonnet 5.5 rejects forced tool use), all results for a turn go back in one message, and the loop stops after 8 steps.
  - The history is **append-only**: assistant turns are stored and re-sent exactly as returned, thinking blocks included, because Sonnet 5.5's preserved thinking requires it. "New chat" starts fresh; nothing is trimmed.
  - Every user message carries a one-line-per-clip state summary, so manual edits are always visible to Claude. The request uses prompt caching (`cache_control`) to keep the multi-step loop cheap.
  - Changes apply immediately. **Undo** reverts everything the last request changed. Chat cost is shown live.
  - It can't export, share or delete; it tells you where to do that.
- **Share:** in a clip's Export tab, or with the share icon on an exported clip's row. **TikTok** and **YouTube** buttons appear when those apps are installed, and **Share** opens the Android share sheet. The title and hashtags are copied to the clipboard, because TikTok ignores shared text. If the exported file was deleted, you're told to export again.

### Phase 5: titles & speech captions

- **Title overlay** (editable; Claude suggests 3 options per clip) in 3 bold styles: **Punch** (white, heavy outline, caps), **Banner** (white on an orange block), **Pop** (yellow, thick outline, slight tilt). Text auto-shrinks to fit at most 3 lines.
- **Speech captions on the device:** whisper.cpp v1.9.5 through JNI (`app/src/main/cpp`). The model (tiny ≈31 MB or base ≈57 MB, q5_1) downloads on first use from Hugging Face and is SHA-256 verified. You get word timestamps and word-by-word highlighted captions (2–3 words per phrase; breaks at punctuation and pauses). Audio never leaves the phone.
- **No speech, no captions:** low-confidence output, a high no-speech probability, `[Music]`/♪ tokens and classic whisper hallucinations ("Thank you.") are rejected, and the clip is marked "No speech".
- **Claude polish** (writing model): fixes misheard game terms and punctuation index by index, so word timings survive. It also writes 3 title options and 5 hashtags, even when there's no speech.
- **Safe zones:** text stays clear of the platform UI (top 8%, bottom 20%, the right-hand button rail). Titles sit on the seam in Split and move below a top PiP; captions move above a bottom PiP.
- The overlay runs in the live 9:16 preview and the export (Media3 `OverlayEffect`; the bitmap redraws only when the highlighted word changes).
- Run it per clip in the editor, or batch via "Select → Captions & titles".

### Phase 4: webcam layouts

- **Facecam presets:** on a project, tap **Facecam → Set up**, scrub to a frame where your webcam is visible, and drag a box over it (corners resize). Presets are global and reusable across projects. You get a warning if one was drawn on a differently shaped video.
- **Layouts**, per clip in the editor or for all clips from the project screen:
  - **Split:** cam fills the top 35%, gameplay the bottom 65% (both cover-cropped, no distortion). The gameplay part follows the clip's crop mode (Pan offset, Zoom-fit box).
  - **Picture-in-picture:** the cam in a rounded box (38% of the width, its own aspect kept within 0.75–1.78), in any corner. Margins keep it clear of the platform UI: 9% from the top, 22% from the bottom, 4% from the sides.
  - **Gameplay only.**
- The live 9:16 preview and the export use the same renderer (`LayoutEffect`'s second layer), so the preview matches the file.
- PiP pairs best with Center crop or Pan. With Fit + blur, the original cam stays visible inside the full frame.

### Phase 3: vertical edit & export

- **Output:** 1080×1920 H.264 High, AAC 48 kHz (192 kbps), 30 or 60 fps (Settings; never above the source fps), 10/16 Mbps, 1 s keyframes, **faststart**. The muxer only *attempts* to put `moov` first, so every export is checked and `moov` is relocated in pure Kotlin if needed (`Mp4FastStart`).
- **Crop modes per clip and per segment:** Center crop · Fit with blurred background · Zoom-fit (a box you draw, over blur) · Pan (slide the 9:16 window). **Auto** (the default) uses Fit + blur for segments where the vision pass flagged a full-screen text card ("WASTED", results screens) or a wide establishing shot, and Center crop everywhere else.
- **Segments:** split at the playhead, choose a mode per segment, merge. Segments tile the clip and follow trims.
- **Live 9:16 preview** in the editor runs the same GPU effect as the export (`ExoPlayer.setVideoEffects`).
- **Audio:** 0.08 s fade-in, 0.4 s fade-out (a custom `AudioProcessor`), resampled to 48 kHz.
- **Trailing black frames** are trimmed at export (up to 3 s, never below 3 s of clip).
- **Batch export:** "Select to export" on the moments list (S/A tiers preselected). Clips export in rank order as `01_title_slug.mp4`, … to **Movies/ClipForge** through MediaStore, in a WorkManager foreground job with progress, Cancel, and a done notification.

How the layouts render: `export/LayoutEffect.kt` is a custom Media3 `GlEffect`. The cover region is downsampled to 1/8 size and blurred with three iterations of a separable 9-tap Gaussian, darkened, then upsampled. Up to two sharp layers with anti-aliased rounded corners are composited on top (Phase 4 uses the second for the facecam). Encoding is portrait 1080×1920 when the device's H.264 encoder supports that size; otherwise 1920×1080 with a 90° rotation flag, which all major players and platforms honor.

### Phase 2: moment finding & ranking: moment finding & ranking

- **Find moments** on any project. Before it runs you see the estimated API cost (frames, requests, models). Afterwards you see what it actually spent.
- On-device signals: audio loudness (MediaCodec decoded to PCM, RMS per 0.5 s), a frame every 2 s (512 px JPEG), scene cuts (luma + color-histogram difference with an adaptive threshold), black frames.
- **Frame analysis** (default `claude-haiku-5-5`): batches of up to 40 frames, each labeled with its timestamp, plus loudness peaks and scene cuts as text. Strict JSON through structured outputs, validated, retried once on bad output. The same pass flags full-screen text cards and wide shots for Phase 3's auto crop.
- **Trend-aware ranking** (default `claude-sonnet-5-5`): one global pass compares every candidate against the others and against **the past week's most-viewed YouTube Shorts for the game**, through the Worker. It falls back to Claude's built-in knowledge when no YouTube key is set. Output: S/A/B/C/D tier, 0–100 score, subscores (hook, payoff, clarity, emotion, trend fit), a title, hashtags and a one-line reason.
- The game is auto-detected from frames (or typed in) to look up trends.
- **Scene snapping:** clip starts snap to the nearest cut. Ends never cross into the next scene. Lengths are pulled toward 8–30 s. Cuts near clip edges are refined to about 0.125 s with a binary search over exact frames.
- **Trim editor:** handles over a zoomed filmstrip (the clip plus 20 s each side), magnetic snapping to scene cuts, ±0.1 s nudges, looped preview, editable title, reset to suggestion.
- Runs as a WorkManager foreground job with a progress notification and a Cancel action, so you can leave the screen.

### Phase 1: import & preview

- Import a video with the Android Photo Picker (videos only). Read access is persisted so projects reopen after restart.
- Projects list (Room) with thumbnails, duration, resolution, fps, size; remove a project (the original file is never touched).
- Player (Media3 ExoPlayer): tap the video or the button to play/pause, ±5 s, ±1 frame.
- Filmstrip timeline: tap to jump, drag to scrub. **Fine scrub:** while dragging, move your finger up away from the strip to slow down (¼×, 1/20×, 1/100×), so you can land on an exact moment in a 60-minute file.
- Source info: duration, resolution (rotation-aware), frame rate, codec, bitrate, audio track, file size, plus warnings (> 60 min, no audio, non-persistent access).

## Design

Dark-only, built from the logo:

| Token | Color | Use |
|---|---|---|
| Coral | `#FE624C` | The single accent: primary actions, playhead, trim handles, selection |
| Ink | `#2E2D3D` | Highest surface; text on coral (dark ink on coral ≈ 5:1 contrast, white would be ≈ 3:1) |
| Night | `#111117` → `#24232F` | Background → cards → raised surfaces (ink-tinted, not flat grey) |
| Snow / Mist | `#F3F3F6` / `#A6A5B6` | Primary / secondary text |

- **Type:** [Outfit](https://fonts.google.com/specimen/Outfit) (SIL OFL 1.1, license in `app/src/main/assets/licenses/`), the closest open match to the wordmark. One variable font file covers weights 400–800.
- **Icon:** the logo mark traced from the brand artwork with potrace into vector drawables. Adaptive launcher icon (white background, as in the logo), monochrome themed icon, and a light mark for in-app use.
- **Layout:** home is a thumbnail grid. The clip editor pins the player and splits its tools into **Trim · Frame · Text · Export** tabs. One coral primary action per screen.
- **Screenshots:** `ScreenshotTest` renders real screens with Robolectric into `app/build/screenshots/`.

## Requirements

| Tool | Version |
|---|---|
| JDK | 17 or newer (built with 21) |
| Android SDK | Platform 37 (`compileSdk`), build-tools 37, NDK 29.0.14206865, CMake 3.31.6 |
| Gradle | wrapper included (9.9.1) |
| Device | Android 8.0 (API 26) or newer |

Main libraries: AGP 9.5, Kotlin 2.4 (AGP built-in Kotlin), Compose BOM 2026.09 / Material 3, Media3 1.11, Room 2.8 (KSP), Navigation 2.10 (type-safe routes).

## Download the APK

GitHub Actions builds the app on every push (`.github/workflows/android.yml`):

- **Releases → "ClipForge (latest debug build)"**: the newest APK from the latest push. Open this page on your phone, tap the `.apk`, and install.
- **Actions → a run → Artifacts**: the same APK, zipped.
- Pushing a tag like `v1.0.0` creates a proper release with the APK attached.

## Build the APK

whisper.cpp is a **git submodule**. Clone with submodules (or init them afterwards):

```bash
git clone --recurse-submodules <repo-url>
# or, in an existing clone:
git submodule update --init --recursive
```

The native part needs the **Android NDK 29.0.14206865** and **CMake 3.31.6**. Android Studio offers to install them, or run
`sdkmanager "ndk;29.0.14206865" "cmake;3.31.6"`. Native code is always compiled optimized, even in debug builds,
because whisper in a debug build is far too slow. ABIs: `arm64-v8a`, `x86_64` (add `armeabi-v7a` in `app/build.gradle.kts` for old 32-bit phones).

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
  analysis/                Moment pipeline: loudness, frame sampling, scene cuts, snapping,
                           prompts/schemas, JSON parsing, cost estimate, WorkManager job
  net/WorkerClient.kt      The only network client (talks to the Worker)
  settings/                DataStore settings (Worker URL, app token, models)
  ui/components/           Timelines (scrub, trim) + pure math, tier badges
  export/                  Crop math, segments, GPU layout effect, audio fades, faststart,
                           Transformer exporter, batch ExportWorker
  ui/clip/                 Trim + framing + layout editor
  ui/cam/                  Facecam preset setup
  assistant/               Chat tools (pure), protocol, append-only store, tool loop
  ui/assistant/            Chat screen
  util/ShareClips.kt       TikTok / YouTube / share sheet
  captions/                whisper JNI wrapper, model download, PCM extraction, speech filter,
                           phrasing, Claude polish + titles, CaptionWorker
app/src/main/cpp/          CMake + JNI bridge + shared transcription core (whisper.cpp submodule)
tools/whisper_host_test.cpp  Runs the same transcription core on a desktop
  ui/settings/             Settings screen
backend/                   Cloudflare Worker (TypeScript): auth, proxy, budget, trends
```

Memory: metadata probing reads container headers only; thumbnails are decoded at keyframes and
downscaled immediately (≤192 px, LRU-capped at 4 MB). Long files never load fully into memory.

## Roadmap

1. ✅ Import & preview
2. ✅ Moment finding: frame sampling, loudness and scene cuts, Claude via the Worker, trend-aware ranking, cost estimate, trim editor
3. ✅ Vertical edit & export (Media3 Transformer, 1080×1920 H.264/AAC, crop modes per segment, WorkManager)
4. ✅ Webcam layouts (facecam presets, split / PiP)
5. ✅ Titles & captions (on-device whisper.cpp, word-highlight captions, Claude-polished text)
6. ✅ In-app assistant with tool use + share sheet

## Cloudflare Worker (required for Claude features)

The app never contains an Anthropic API key. It talks only to your Worker (`backend/`), which:

- holds `ANTHROPIC_API_KEY` as a Worker secret and calls the Messages API with the official `@anthropic-ai/sdk`;
- issues **per-install app tokens** (HMAC-signed) to installs that present your **invite code** once;
- validates every request: model allowlist, `max_tokens` cap, a field allowlist, no streaming, no server tools;
- rate-limits per install (30 requests/min) and per IP for registration (5/min), and enforces an approximate **daily budget per install** (default $2);
- adds server-side refusal fallbacks (`fallbacks: "default"`) for Sonnet/Opus 5.5 requests;
- serves `/v1/trends?game=…`: most-viewed Shorts from the past 7 days via the YouTube Data API, cached per game per day in KV.

### Deploy

You need a Cloudflare account (the free plan works) and Node 20+.

```bash
cd backend
npm install
npx wrangler login

# 1. KV namespace for budgets, revocations and the trends cache
npx wrangler kv namespace create CLIPFORGE_KV
#    -> paste the printed id into wrangler.toml ([[kv_namespaces]] id = "...")

# 2. Secrets (each command prompts for the value; nothing is stored in files)
npx wrangler secret put ANTHROPIC_API_KEY    # from console.anthropic.com
npx wrangler secret put INVITE_CODE          # any passphrase; you type it into the app once
npx wrangler secret put TOKEN_SIGNING_KEY    # random, e.g. `openssl rand -hex 32`
npx wrangler secret put YOUTUBE_API_KEY      # optional, see below

# 3. Deploy
npx wrangler deploy                          # prints https://clipforge-worker.<you>.workers.dev
```

In the app: **Settings → Worker URL** (the printed URL) **+ invite code → Connect.**

**YouTube trends (optional, free):** in Google Cloud Console, create a project, enable **YouTube Data API v3**,
create an **API key** (restrict it to that API), then `wrangler secret put YOUTUBE_API_KEY`.
One fresh lookup costs about 101 quota units of the default 10,000/day, and results are cached per game per day.
Without the key, ranking uses Claude's built-in knowledge and the app says so.

**Configuration** (`wrangler.toml` `[vars]`): `ALLOWED_MODELS` (add a model here before you select it in the app),
`MAX_TOKENS_CAP`, `DAILY_BUDGET_USD`. Rate limits live in the `[[ratelimits]]` blocks.

**Revoking access:** rotate `INVITE_CODE` to stop new registrations. Rotate `TOKEN_SIGNING_KEY` to sign out every install.
To revoke a single install, run `npx wrangler kv key put --binding KV "revoked:<install-id>" 1`.

**Local dev / tests:**

```bash
cp .dev.vars.example .dev.vars   # fake values are fine for local testing
npm run typecheck && npm test
npx wrangler dev                 # http://127.0.0.1:8787/health
```

### Cost

The app shows an estimate before each run (from `CostEstimator`, with prices in `ModelPricing`). With the defaults on 1080p:

| Video | Frame analysis (Haiku 5.5) | Ranking (Sonnet 5.5) | Total |
|---|---|---|---|
| 10 min | ~$0.016 | ~$0.08 | **~$0.09** |
| 60 min | ~$0.09 | ~$0.12 | **~$0.21** |

These are estimates. Thinking tokens vary. The card on each project shows what a run actually cost.

### Privacy

Sampled frames (512 px JPEGs) and timing data go to your Worker and then to the Anthropic API. The full video never leaves the phone.
Frames are cached in the app's cache directory during analysis and deleted when it finishes.

## Testing captions on a desktop

`tools/whisper_host_test.cpp` runs the app's exact transcription code (`app/src/main/cpp/whisper_core.cpp`) on Linux or macOS:

```bash
W=app/src/main/cpp/whisper.cpp
cmake -S $W -B /tmp/w -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DWHISPER_BUILD_EXAMPLES=OFF -DWHISPER_BUILD_TESTS=OFF -DGGML_OPENMP=OFF -DGGML_NATIVE=OFF
cmake --build /tmp/w -j --target whisper
g++ -O2 -std=c++17 -Iapp/src/main/cpp -I$W/include -I$W/ggml/include app/src/main/cpp/whisper_core.cpp tools/whisper_host_test.cpp \
  -o /tmp/host_test $(find /tmp/w -name libwhisper.a) $(find /tmp/w -name 'libggml*.a' | sort -r) -lpthread -ldl
curl -L -o /tmp/tiny.bin https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin
/tmp/host_test /tmp/tiny.bin $W/samples/jfk.wav      # prints word-level JSON
```

The unit test `CaptionMathTest` uses that JFK output as a fixture. `TextRenderTest` (Robolectric, native graphics) renders the
title styles and captions to `app/build/text-render/*.png` with the platform-UI zones marked in red.

## Known limits

- **Device-verified:** nothing yet. Everything above was built and tested in a Linux container without an emulator: unit tests (122), lint, real renders of the GPU layouts (WebGL, same shaders), text overlays and screens (Robolectric), and whisper.cpp transcription (host build of the same code). First runs on a phone may surface device-specific issues (encoders, GPU drivers, decoders).
- **Claude prompts haven't been exercised with a real key here.** Request shapes follow the current docs and pass the Worker's validator; output quality needs a real run.
- **Speed is unmeasured** for frame sampling on 60-minute files, 1080×1920@60 export with blur, and whisper on mid-range phones.
- Room migrations are automatic (v1→v5). The schemas are in `app/schemas/`.
