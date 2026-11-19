# ClipForge

Android app that turns long gameplay recordings into vertical (9:16) TikTok / YouTube Shorts clips.
Claude picks the moments and writes titles, and every Claude call goes through a small Cloudflare
Worker so the Anthropic API key never ships inside the APK.

> **Status: Phase 2 of 6: moment finding & ranking.** See [Roadmap](#roadmap).

## What works now

### Phase 2: moment finding & ranking

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
  analysis/                Moment pipeline: loudness, frame sampling, scene cuts, snapping,
                           prompts/schemas, JSON parsing, cost estimate, WorkManager job
  net/WorkerClient.kt      The only network client (talks to the Worker)
  settings/                DataStore settings (Worker URL, app token, models)
  ui/components/           Timelines (scrub, trim) + pure math, tier badges
  ui/clip/                 Trim editor
  ui/settings/             Settings screen
backend/                   Cloudflare Worker (TypeScript): auth, proxy, budget, trends
```

Memory: metadata probing reads container headers only; thumbnails are decoded at keyframes and
downscaled immediately (≤192 px, LRU-capped at 4 MB). Long files never load fully into memory.

## Roadmap

1. ✅ Import & preview
2. ✅ Moment finding: frame sampling, loudness and scene cuts, Claude via the Worker, trend-aware ranking, cost estimate, trim editor
3. Vertical edit & export (Media3 Transformer, 1080×1920 H.264/AAC, crop modes, WorkManager)
4. Webcam layouts (facecam presets, split / PiP)
5. Titles & captions (on-device whisper.cpp, word-highlight captions, Claude-polished text)
6. In-app assistant with tool use + share sheet

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
