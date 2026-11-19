export interface Env {
  ANTHROPIC_API_KEY: string;
  INVITE_CODE: string;
  TOKEN_SIGNING_KEY: string;
  YOUTUBE_API_KEY?: string;
  ALLOWED_MODELS: string;
  MAX_TOKENS_CAP: string;
  DAILY_BUDGET_USD: string;
  TOKEN_LIMITER: RateLimit;
  REGISTER_LIMITER: RateLimit;
  KV: KVNamespace;
}

export function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json", ...headers },
  });
}

export function apiError(status: number, type: string, message: string, headers: Record<string, string> = {}): Response {
  return json({ type: "error", error: { type, message } }, status, headers);
}
