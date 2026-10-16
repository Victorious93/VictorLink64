// VictorLink64 relay: Cloudflare Worker + Durable Object "Room".
// Clients connect to wss://<host>/room/<CODE>?name=<nick>; every text message is relayed to all other peers.
// GET /new returns a fresh 6-char room code. Messages are opaque JSON strings (max 16 KB).
const ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O/1/I
const MAX_PEERS = 8;
// Types only the relay may originate; clients treat them as control messages, so peers must not be able to forge them.
const RESERVED = new Set(["welcome", "peer_join", "peer_leave"]);

function isReserved(msg) {
  if (!msg.includes('"t"')) return false;
  try {
    const o = JSON.parse(msg);
    return o !== null && typeof o === "object" && RESERVED.has(o.t);
  } catch (_) {
    return false;
  }
}

function newCode() {
  const b = new Uint8Array(6);
  crypto.getRandomValues(b);
  return Array.from(b, (x) => ALPHABET[x % ALPHABET.length]).join("");
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const cors = { "Access-Control-Allow-Origin": "*" };
    if (url.pathname === "/" || url.pathname === "/health")
      return new Response(JSON.stringify({ ok: true, service: "victorlink64-relay" }), { headers: { "content-type": "application/json", ...cors } });
    if (url.pathname === "/new")
      return new Response(JSON.stringify({ code: newCode() }), { headers: { "content-type": "application/json", ...cors } });
    const m = url.pathname.match(/^\/room\/([A-Z0-9]{6})$/i);
    if (!m) return new Response("not found", { status: 404 });
    if (request.headers.get("Upgrade") !== "websocket") return new Response("expected websocket", { status: 426 });
    const code = m[1].toUpperCase();
    const stub = env.ROOMS.get(env.ROOMS.idFromName(code));
    return stub.fetch(request);
  },
};

export class Room {
  constructor(state) {
    this.state = state;
  }

  peers() {
    return this.state.getWebSockets();
  }

  broadcast(msg, except) {
    for (const ws of this.peers()) {
      if (ws !== except) {
        try { ws.send(msg); } catch (_) {}
      }
    }
  }

  roster() {
    return this.peers().map((ws) => ws.deserializeAttachment()).filter(Boolean);
  }

  async fetch(request) {
    if (this.peers().length >= MAX_PEERS) return new Response("room full", { status: 403 });
    const url = new URL(request.url);
    const name = (url.searchParams.get("name") || "Player").slice(0, 24);
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    const id = crypto.randomUUID().slice(0, 8);
    const others = this.roster();
    const isHost = others.length === 0;
    this.state.acceptWebSocket(server);
    server.serializeAttachment({ id, name, host: isHost });
    server.send(JSON.stringify({ t: "welcome", id, host: isHost, peers: others }));
    this.broadcast(JSON.stringify({ t: "peer_join", id, name, host: isHost, count: this.peers().length }), server);
    return new Response(null, { status: 101, webSocket: client });
  }

  webSocketMessage(ws, msg) {
    if (typeof msg !== "string" || msg.length > 16384) return;
    if (msg === "ping") { ws.send("pong"); return; }
    if (isReserved(msg)) return;
    this.broadcast(msg, ws);
  }

  webSocketClose(ws, code, reason) {
    const a = ws.deserializeAttachment() || {};
    try { ws.close(code >= 1000 && code <= 4999 && code !== 1005 && code !== 1006 ? code : 1000, reason || "closing"); } catch (_) {}
    // Count peers other than the one leaving; the closing socket may or may not still be listed.
    const count = this.peers().filter((w) => w !== ws).length;
    this.broadcast(JSON.stringify({ t: "peer_leave", id: a.id, name: a.name, count }), ws);
  }

  webSocketError(ws) { this.webSocketClose(ws, 1011, "error"); }
}
