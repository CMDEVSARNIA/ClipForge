import Anthropic from "@anthropic-ai/sdk";
import { apiError, json, type Env } from "./env.ts";
import { costUsd } from "./pricing.ts";

/** Top-level request fields the app may send. Anything else is rejected rather than forwarded blindly. */
const ALLOWED_KEYS = new Set([
  "model",
  "max_tokens",
  "messages",
  "system",
  "output_config",
  "thinking",
  "tools",
  "tool_choice",
  "stop_sequences",
  "metadata",
  "cache_control",
]);

/** Models that get server-side refusal fallbacks (routes to the server-defined default on a policy decline). */
const FALLBACK_MODELS = new Set(["claude-sonnet-5-5", "claude-opus-5-5"]);
const FALLBACK_BETA = "server-side-fallback-2026-07-01";

export type Validation = { ok: true; body: Record<string, unknown> } | { ok: false; message: string };

export function validateMessagesBody(raw: unknown, allowedModels: string[], maxTokensCap: number): Validation {
  if (typeof raw !== "object" || raw === null || Array.isArray(raw)) return { ok: false, message: "Body must be a JSON object." };
  const body = raw as Record<string, unknown>;
  for (const k of Object.keys(body)) {
    if (!ALLOWED_KEYS.has(k)) return { ok: false, message: `Field "${k}" is not allowed through this proxy.` };
  }
  if (typeof body.model !== "string" || !allowedModels.includes(body.model)) {
    return { ok: false, message: `Model must be one of: ${allowedModels.join(", ")}.` };
  }
  if (typeof body.max_tokens !== "number" || !Number.isInteger(body.max_tokens) || body.max_tokens < 1 || body.max_tokens > maxTokensCap) {
    return { ok: false, message: `max_tokens must be an integer between 1 and ${maxTokensCap}.` };
  }
  if (!Array.isArray(body.messages) || body.messages.length === 0) {
    return { ok: false, message: "messages must be a non-empty array." };
  }
  if (body.tools !== undefined) {
    if (!Array.isArray(body.tools)) return { ok: false, message: "tools must be an array." };
    for (const t of body.tools) {
      const type = (t as { type?: unknown })?.type;
      // Only client-defined tools: server tools (web search, code execution...) bill extra and aren't needed.
      if (type !== undefined && type !== "custom") return { ok: false, message: `Server tool "${String(type)}" is not allowed.` };
    }
  }
  return { ok: true, body };
}

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

export async function handleMessages(req: Request, env: Env, ctx: ExecutionContext, iid: string): Promise<Response> {
  let raw: unknown;
  try {
    raw = await req.json();
  } catch {
    return apiError(400, "invalid_request_error", "Body must be JSON.");
  }
  const allowed = env.ALLOWED_MODELS.split(",").map((s) => s.trim()).filter(Boolean);
  const v = validateMessagesBody(raw, allowed, Number(env.MAX_TOKENS_CAP) || 16000);
  if (!v.ok) return apiError(400, "invalid_request_error", v.message);

  const spendKey = `spend:${iid}:${today()}`;
  const spent = Number((await env.KV.get(spendKey)) ?? "0");
  const budget = Number(env.DAILY_BUDGET_USD) || 2;
  if (spent >= budget) {
    return apiError(429, "budget_exceeded", `Daily budget of $${budget.toFixed(2)} reached for this install. It resets at 00:00 UTC.`);
  }

  const client = new Anthropic({ apiKey: env.ANTHROPIC_API_KEY, maxRetries: 2, timeout: 180_000 });
  const model = v.body.model as string;
  try {
    let message: { usage: Parameters<typeof costUsd>[1] };
    if (FALLBACK_MODELS.has(model)) {
      message = await client.beta.messages.create({
        ...(v.body as unknown as Anthropic.Beta.Messages.MessageCreateParamsNonStreaming),
        betas: [FALLBACK_BETA],
        fallbacks: "default",
      });
    } else {
      message = await client.messages.create(v.body as unknown as Anthropic.MessageCreateParamsNonStreaming);
    }
    const cost = costUsd(model, message.usage);
    // KV is eventually consistent, so the budget is approximate (good enough to stop runaway spend).
    ctx.waitUntil(env.KV.put(spendKey, String(spent + cost), { expirationTtl: 60 * 60 * 48 }));
    return json(message, 200, { "x-clipforge-cost-usd": cost.toFixed(6) });
  } catch (e) {
    if (e instanceof Anthropic.APIError) {
      const status = typeof e.status === "number" ? e.status : 502;
      const errBody = e.error ?? { type: "error", error: { type: "api_error", message: e.message } };
      const headers: Record<string, string> = {};
      const retryAfter = e.headers?.get("retry-after");
      if (retryAfter) headers["retry-after"] = retryAfter;
      return json(errBody, status, headers);
    }
    return apiError(502, "api_error", "Upstream request failed.");
  }
}
