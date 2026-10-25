/**
 * Ring Partner API front door for the CleanYourTV doorbell add-on.
 *
 * Ring calls four HTTPS endpoints of a partner app. This Worker answers them and relays everything to the TV over
 * one WebSocket held in a Durable Object; the TV, on the home connection, makes every call to Ring itself (Ring's
 * API may refuse datacenter IPs). The Worker keeps no Ring tokens.
 *
 *   POST /ring/webhook   Webhook URL: checks X-Signature (hex HMAC-SHA256 of the raw body), relays the event.
 *   POST /ring/token     Token Exchange URL: hands the one-time code to the TV, answers 200 once the TV has used it.
 *   GET  /ring/link      Account Link URL: a sign-in page (Ring requires one); POST relays the nonce to the TV.
 *   GET  /ring/home      App Homepage URL: whether the TV is connected.
 *   GET  /tv             The TV's WebSocket, with Authorization: Bearer <TV_TOKEN>.
 */

export interface Env {
  HUB: DurableObjectNamespace;
  RING_HMAC_KEY: string;
  TV_TOKEN: string;
  LINK_PASSWORD: string;
}

const KEEP_EVENTS = 30;
const REPLAY_MS = 6 * 60 * 60 * 1000;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    // A missing or short secret would make the checks below pass for anyone: refuse to serve instead.
    if ([env.RING_HMAC_KEY, env.TV_TOKEN, env.LINK_PASSWORD].some((s) => !s || s.length < 12)) {
      return new Response("not configured", { status: 503 });
    }
    const url = new URL(request.url);
    const hub = env.HUB.get(env.HUB.idFromName("home"));
    if (url.pathname === "/ring/webhook" && request.method === "POST") {
      const body = await request.arrayBuffer();
      if (!(await signed(env.RING_HMAC_KEY, body, request.headers.get("X-Signature")))) {
        return new Response("bad signature", { status: 401 });
      }
      return hub.fetch("https://hub/event", { method: "POST", body });
    }
    if (url.pathname === "/ring/token" && request.method === "POST") {
      const form = await request.formData();
      const code = String(form.get("code") ?? "");
      if (!code) return new Response("no code", { status: 400 });
      return hub.fetch("https://hub/ask", { method: "POST", body: JSON.stringify({ t: "code", code }) });
    }
    if (url.pathname === "/ring/link") return link(request, url, env, hub);
    if (url.pathname === "/" || url.pathname === "/ring/home") {
      const state = await (await hub.fetch("https://hub/state")).json<{ tv: boolean; last: number }>();
      return page("Technabit TV OS", `<p>The TV is ${state.tv ? "connected" : "not connected right now"}.</p>` +
        (state.last ? `<p>Last doorbell event: ${new Date(state.last).toISOString()}</p>` : ""));
    }
    if (url.pathname === "/tv") {
      if (request.headers.get("Upgrade") !== "websocket") return new Response("websocket only", { status: 426 });
      if (!equal(request.headers.get("Authorization") ?? "", `Bearer ${env.TV_TOKEN}`)) {
        return new Response("no", { status: 401 });
      }
      return hub.fetch(request);
    }
    return new Response("not found", { status: 404 });
  },
};

/** The Account Link URL: Ring sends the user here with ?nonce&time; they sign in, the TV completes the link. */
async function link(request: Request, url: URL, env: Env, hub: DurableObjectStub): Promise<Response> {
  if (request.method === "GET") {
    const nonce = url.searchParams.get("nonce") ?? "", time = url.searchParams.get("time") ?? "";
    return page("Link Ring to your TV", `
      <form method="post">
        <input type="hidden" name="nonce" value="${escape(nonce)}">
        <input type="hidden" name="time" value="${escape(time)}">
        <label>Passphrase<br><input type="password" name="password" autofocus required></label>
        <button>Sign in and link</button>
      </form>`);
  }
  const form = await request.formData();
  const nonce = String(form.get("nonce") ?? ""), time = Number(form.get("time") ?? 0);
  if (!equal(String(form.get("password") ?? ""), env.LINK_PASSWORD)) {
    return page("Link Ring to your TV", "<p>Wrong passphrase. Go back and try again.</p>", 401);
  }
  const age = Date.now() - time;
  if (!nonce || !(age >= 0 && age <= 600_000)) {
    return page("Link Ring to your TV", "<p>This link has expired. Start again from the Ring app.</p>", 400);
  }
  const answer = await hub.fetch("https://hub/ask", {
    method: "POST", body: JSON.stringify({ t: "link", nonce, time: String(time) }),
  });
  return answer.ok
    ? page("Linked", "<p>Your Ring account is linked to the TV. You can close this page.</p>")
    : page("Not linked", `<p>The TV could not complete the link: ${escape(await answer.text())}</p>`, 502);
}

/** One instance per home: the TV's socket, the questions waiting for its answer, and recent events to replay. */
export class Hub {
  private waiting = new Map<string, (answer: { ok: boolean; error?: string }) => void>();

  constructor(private state: DurableObjectState, private env: Env) {
    state.setWebSocketAutoResponse(new WebSocketRequestResponsePair('{"t":"ping"}', '{"t":"pong"}'));
  }

  async fetch(request: Request): Promise<Response> {
    const path = new URL(request.url).pathname;
    if (path === "/tv") {
      const [client, server] = Object.values(new WebSocketPair());
      for (const old of this.state.getWebSockets()) old.close(4000, "replaced");
      this.state.acceptWebSocket(server);
      return new Response(null, { status: 101, webSocket: client });
    }
    if (path === "/state") {
      const last = (await this.state.storage.get<number>("last")) ?? 0;
      return Response.json({ tv: this.state.getWebSockets().length > 0, last });
    }
    if (path === "/event") return this.event(await request.text());
    if (path === "/ask") return this.ask(await request.json());
    return new Response("not found", { status: 404 });
  }

  /** Stores the event (by request_id, so Ring's retries are dropped) and sends it to the TV if it is there. */
  private async event(body: string): Promise<Response> {
    let id = "";
    try { id = JSON.parse(body)?.meta?.request_id ?? ""; } catch { return new Response("not json", { status: 400 }); }
    const events = (await this.state.storage.get<{ id: string; at: number; body: string }[]>("events")) ?? [];
    if (id && events.some((e) => e.id === id)) return new Response("seen");
    const at = Date.now();
    events.push({ id, at, body });
    await this.state.storage.put("events", events.slice(-KEEP_EVENTS));
    await this.state.storage.put("last", at);
    this.send({ t: "event", at, body });
    return new Response("ok");
  }

  /** A question for the TV (a code to exchange, a nonce to confirm); waits up to 50 s for its answer. */
  private async ask(question: Record<string, string>): Promise<Response> {
    if (this.state.getWebSockets().length === 0) return new Response("the TV is not connected", { status: 503 });
    const id = crypto.randomUUID();
    const answer = await new Promise<{ ok: boolean; error?: string }>((resolve) => {
      this.waiting.set(id, resolve);
      setTimeout(() => resolve({ ok: false, error: "the TV did not answer" }), 50_000);
      this.send({ ...question, id });
    });
    this.waiting.delete(id);
    return answer.ok ? new Response("ok") : new Response(answer.error ?? "failed", { status: 502 });
  }

  async webSocketMessage(socket: WebSocket, message: string | ArrayBuffer): Promise<void> {
    let m: { t?: string; id?: string; ok?: boolean; error?: string; since?: number };
    try { m = JSON.parse(typeof message === "string" ? message : new TextDecoder().decode(message)); } catch { return; }
    if (m.t === "answer" && m.id) this.waiting.get(m.id)?.({ ok: !!m.ok, error: m.error });
    if (m.t === "hello") {
      // The TV was away: send what it missed, newest last.
      const since = Math.max(m.since ?? 0, Date.now() - REPLAY_MS);
      const events = (await this.state.storage.get<{ at: number; body: string }[]>("events")) ?? [];
      for (const e of events) if (e.at > since) socket.send(JSON.stringify({ t: "event", at: e.at, body: e.body }));
    }
  }

  async webSocketClose(socket: WebSocket, code: number): Promise<void> {
    try { socket.close(code, "bye"); } catch { /* already closed */ }
  }

  private send(message: object): void {
    const text = JSON.stringify(message);
    for (const socket of this.state.getWebSockets()) {
      try { socket.send(text); } catch { /* gone; the TV reconnects */ }
    }
  }
}

async function signed(key: string, body: ArrayBuffer, header: string | null): Promise<boolean> {
  if (!header?.startsWith("sha256=")) return false;
  const hmac = await crypto.subtle.importKey("raw", new TextEncoder().encode(key), { name: "HMAC", hash: "SHA-256" },
    false, ["sign"]);
  const digest = new Uint8Array(await crypto.subtle.sign("HMAC", hmac, body));
  const hex = [...digest].map((b) => b.toString(16).padStart(2, "0")).join("");
  return equal(hex, header.slice(7).toLowerCase());
}

/** Constant-time string comparison. */
function equal(a: string, b: string): boolean {
  const x = new TextEncoder().encode(a), y = new TextEncoder().encode(b);
  if (x.length !== y.length) return false;
  let diff = 0;
  for (let i = 0; i < x.length; i++) diff |= x[i] ^ y[i];
  return diff === 0;
}

function escape(text: string): string {
  return text.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
}

function page(title: string, body: string, status = 200): Response {
  return new Response(`<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>${escape(title)}</title>
<style>body{font:16px system-ui;background:#0b0d12;color:#eee;display:grid;place-items:center;min-height:100vh;margin:0}
main{max-width:420px;padding:32px;background:#141821;border-radius:20px}input{width:100%;padding:12px;margin:8px 0 16px;
border-radius:10px;border:1px solid #333;background:#0b0d12;color:#eee}button{padding:12px 20px;border:0;border-radius:22px;
background:#ff8a3d;color:#000;font-weight:600}</style></head><body><main><h1>${escape(title)}</h1>${body}</main></body></html>`,
    { status, headers: { "Content-Type": "text/html;charset=utf-8" } });
}
