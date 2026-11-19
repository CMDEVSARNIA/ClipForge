import { authenticate, handleRegister } from "./auth.ts";
import { apiError, json, type Env } from "./env.ts";
import { handleMessages } from "./messages.ts";
import { handleTrends } from "./trends.ts";

const MAX_BODY_BYTES = 25 * 1024 * 1024;

export default {
  async fetch(req: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(req.url);
    const route = `${req.method} ${url.pathname}`;

    const len = Number(req.headers.get("content-length") ?? "0");
    if (len > MAX_BODY_BYTES) return apiError(413, "request_too_large", "Request body is too large.");

    if (route === "GET /health") {
      return json({
        ok: true,
        models: env.ALLOWED_MODELS.split(",").map((s) => s.trim()),
        trends: Boolean(env.YOUTUBE_API_KEY),
      });
    }
    if (route === "POST /v1/register") return handleRegister(req, env);

    if (route === "POST /v1/messages" || route === "GET /v1/trends") {
      const auth = await authenticate(req, env);
      if (auth instanceof Response) return auth;
      return route === "POST /v1/messages" ? handleMessages(req, env, ctx, auth.iid) : handleTrends(req, env);
    }
    return apiError(404, "not_found_error", "Not found.");
  },
} satisfies ExportedHandler<Env>;
