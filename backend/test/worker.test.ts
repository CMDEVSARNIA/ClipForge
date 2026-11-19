import { test } from "node:test";
import assert from "node:assert/strict";
import { signToken, verifyToken } from "../src/auth.ts";
import { validateMessagesBody } from "../src/messages.ts";
import { isoDurationSeconds, slug } from "../src/trends.ts";
import { costUsd } from "../src/pricing.ts";

const models = ["claude-haiku-5-5", "claude-sonnet-5-5"];
const base = { model: "claude-haiku-5-5", max_tokens: 1000, messages: [{ role: "user", content: "hi" }] };

test("token round-trips and rejects tampering", async () => {
  const t = await signToken({ iid: "abc", iat: 1, v: 1 }, "secret");
  assert.deepEqual(await verifyToken(t, "secret"), { iid: "abc", iat: 1, v: 1 });
  assert.equal(await verifyToken(t, "other-secret"), null);
  const [body, sig] = t.split(".");
  const forged = Buffer.from(JSON.stringify({ iid: "evil", iat: 1, v: 1 })).toString("base64url");
  assert.equal(await verifyToken(`${forged}.${sig}`, "secret"), null);
  assert.equal(await verifyToken(`${body}`, "secret"), null);
});

test("validation accepts a normal request", () => {
  assert.equal(validateMessagesBody(base, models, 16000).ok, true);
});

test("validation rejects unknown models, fields, streaming, server tools, big max_tokens", () => {
  assert.equal(validateMessagesBody({ ...base, model: "claude-opus-5-5" }, models, 16000).ok, false);
  assert.equal(validateMessagesBody({ ...base, stream: true }, models, 16000).ok, false);
  assert.equal(validateMessagesBody({ ...base, max_tokens: 64000 }, models, 16000).ok, false);
  assert.equal(validateMessagesBody({ ...base, tools: [{ type: "web_search_20260209", name: "web_search" }] }, models, 16000).ok, false);
  assert.equal(validateMessagesBody({ ...base, tools: [{ name: "set_trim", input_schema: { type: "object" } }] }, models, 16000).ok, true);
  assert.equal(validateMessagesBody({ ...base, messages: [] }, models, 16000).ok, false);
  assert.equal(validateMessagesBody([], models, 16000).ok, false);
});

test("trend helpers", () => {
  assert.equal(isoDurationSeconds("PT1M5S"), 65);
  assert.equal(isoDurationSeconds("PT45S"), 45);
  assert.equal(isoDurationSeconds("PT1H"), 3600);
  assert.equal(isoDurationSeconds("garbage"), 0);
  assert.equal(slug("Grand Theft Auto V"), "grand-theft-auto-v");
});

test("cost estimate", () => {
  assert.equal(costUsd("claude-haiku-5-5", { input_tokens: 1_000_000, output_tokens: 0 }), 0.1);
  assert.equal(costUsd("claude-sonnet-5-5", { input_tokens: 0, output_tokens: 1_000_000 }), 10);
});
