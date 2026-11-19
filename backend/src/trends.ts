import { apiError, json, type Env } from "./env.ts";

/**
 * Most-viewed recent YouTube Shorts for a game, via the YouTube Data API v3.
 * search.list costs 100 quota units and videos.list 1, so results are cached per game per day.
 */
export interface TrendVideo {
  title: string;
  channel: string;
  views: number;
  likes: number | null;
  comments: number | null;
  published_at: string;
  duration_s: number;
  url: string;
}

export interface TrendsResponse {
  game: string;
  source: "youtube";
  window_days: number;
  fetched_at: string;
  videos: TrendVideo[];
}

export function slug(game: string): string {
  return game.toLowerCase().normalize("NFKD").replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "").slice(0, 60);
}

/** Parses ISO-8601 durations like "PT1M5S" to seconds. */
export function isoDurationSeconds(d: string): number {
  const m = /^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$/.exec(d);
  if (!m) return 0;
  return (Number(m[1] ?? 0) * 86400) + (Number(m[2] ?? 0) * 3600) + (Number(m[3] ?? 0) * 60) + Number(m[4] ?? 0);
}

const WINDOW_DAYS = 7;
const MAX_SHORT_SECONDS = 180;

export async function handleTrends(req: Request, env: Env): Promise<Response> {
  const url = new URL(req.url);
  const game = (url.searchParams.get("game") ?? "").trim();
  if (game.length < 2 || game.length > 80) return apiError(400, "invalid_request_error", "game must be 2-80 characters.");
  if (!env.YOUTUBE_API_KEY) return apiError(503, "trends_unavailable", "YOUTUBE_API_KEY is not configured on the Worker.");

  const day = new Date().toISOString().slice(0, 10);
  const cacheKey = `trends:v1:${slug(game)}:${day}`;
  const cached = await env.KV.get(cacheKey);
  if (cached) return new Response(cached, { headers: { "content-type": "application/json", "x-cache": "hit" } });

  const after = new Date(Date.now() - WINDOW_DAYS * 86400_000).toISOString();
  const search = new URL("https://www.googleapis.com/youtube/v3/search");
  search.search = new URLSearchParams({
    part: "snippet",
    type: "video",
    videoDuration: "short",
    order: "viewCount",
    maxResults: "25",
    publishedAfter: after,
    q: `${game} #shorts`,
    key: env.YOUTUBE_API_KEY,
  }).toString();

  const sr = await fetch(search);
  if (!sr.ok) return apiError(503, "trends_unavailable", `YouTube search failed (${sr.status}).`);
  const sj = (await sr.json()) as { items?: { id?: { videoId?: string } }[] };
  const ids = (sj.items ?? []).map((i) => i.id?.videoId).filter((x): x is string => !!x);
  if (ids.length === 0) {
    const empty: TrendsResponse = { game, source: "youtube", window_days: WINDOW_DAYS, fetched_at: new Date().toISOString(), videos: [] };
    const s = JSON.stringify(empty);
    await env.KV.put(cacheKey, s, { expirationTtl: 12 * 3600 });
    return new Response(s, { headers: { "content-type": "application/json" } });
  }

  const videosUrl = new URL("https://www.googleapis.com/youtube/v3/videos");
  videosUrl.search = new URLSearchParams({ part: "snippet,statistics,contentDetails", id: ids.join(","), key: env.YOUTUBE_API_KEY }).toString();
  const vr = await fetch(videosUrl);
  if (!vr.ok) return apiError(503, "trends_unavailable", `YouTube videos lookup failed (${vr.status}).`);
  type V = {
    id: string;
    snippet?: { title?: string; channelTitle?: string; publishedAt?: string };
    statistics?: { viewCount?: string; likeCount?: string; commentCount?: string };
    contentDetails?: { duration?: string };
  };
  const vj = (await vr.json()) as { items?: V[] };
  const num = (s?: string) => (s === undefined ? null : Number(s));
  const videos: TrendVideo[] = (vj.items ?? [])
    .map((v) => ({
      title: v.snippet?.title ?? "",
      channel: v.snippet?.channelTitle ?? "",
      views: num(v.statistics?.viewCount) ?? 0,
      likes: num(v.statistics?.likeCount),
      comments: num(v.statistics?.commentCount),
      published_at: v.snippet?.publishedAt ?? "",
      duration_s: isoDurationSeconds(v.contentDetails?.duration ?? ""),
      url: `https://www.youtube.com/shorts/${v.id}`,
    }))
    .filter((v) => v.duration_s > 0 && v.duration_s <= MAX_SHORT_SECONDS)
    .sort((a, b) => b.views - a.views);

  const out: TrendsResponse = { game, source: "youtube", window_days: WINDOW_DAYS, fetched_at: new Date().toISOString(), videos };
  const s = JSON.stringify(out);
  await env.KV.put(cacheKey, s, { expirationTtl: 12 * 3600 });
  return new Response(s, { headers: { "content-type": "application/json", "x-cache": "miss" } });
}
