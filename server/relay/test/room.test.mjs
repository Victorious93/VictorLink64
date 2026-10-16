// Victorious Framework — unit tests for the VictorLink64 relay Room logic, using in-memory fakes
// for the Cloudflare runtime (WebSocketPair, Durable Object state). Run: `npm test` in server/relay.
import test from "node:test";
import assert from "node:assert/strict";

class FakeSocket {
  constructor() { this.sent = []; this.attachment = null; this.closed = null; }
  send(m) { this.sent.push(m); }
  close(code, reason) { this.closed = { code, reason }; }
  serializeAttachment(a) { this.attachment = a; }
  deserializeAttachment() { return this.attachment; }
  json() { return this.sent.map((m) => { try { return JSON.parse(m); } catch { return m; } }); }
}

globalThis.WebSocketPair = class { constructor() { this[0] = new FakeSocket(); this[1] = new FakeSocket(); } };
// Node's Response rejects status 101, so stub just enough for Room.fetch.
const RealResponse = globalThis.Response;
globalThis.Response = class extends RealResponse {
  constructor(body, init = {}) {
    if (init.status === 101) { super(null, { status: 200 }); Object.defineProperty(this, "status", { value: 101 }); this.webSocket = init.webSocket; }
    else super(body, init);
  }
};

const { Room } = await import("../src/index.js");

function makeRoom() {
  const accepted = [];
  const state = { getWebSockets: () => accepted, acceptWebSocket: (ws) => accepted.push(ws) };
  return { room: new Room(state), accepted };
}
async function join(room, name = "P") {
  const res = await room.fetch({ url: `https://x/room/ABCDEF?name=${name}` });
  return res.webSocket ? { client: res.webSocket, res } : { res };
}
const serverSide = (accepted) => accepted[accepted.length - 1];

test("first peer is host, second is guest and sees the roster", async () => {
  const { room, accepted } = makeRoom();
  await join(room, "Ann");
  const a = serverSide(accepted);
  assert.equal(a.json()[0].t, "welcome");
  assert.equal(a.json()[0].host, true);
  await join(room, "Bob");
  const b = serverSide(accepted);
  assert.equal(b.json()[0].host, false);
  assert.equal(b.json()[0].peers[0].name, "Ann");
  assert.equal(a.json()[1].t, "peer_join");
  assert.equal(a.json()[1].name, "Bob");
});

test("room rejects the 9th peer", async () => {
  const { room } = makeRoom();
  for (let i = 0; i < 8; i++) await join(room, `p${i}`);
  const { res } = await join(room, "late");
  assert.equal(res.status, 403);
});

test("messages are relayed to everyone except the sender; ping gets pong", async () => {
  const { room, accepted } = makeRoom();
  await join(room, "A"); await join(room, "B");
  const [a, b] = accepted;
  const before = a.sent.length;
  room.webSocketMessage(b, JSON.stringify({ t: "move", uci: "e2e4" }));
  assert.equal(a.sent.length, before + 1);
  assert.equal(JSON.parse(a.sent.at(-1)).uci, "e2e4");
  const bBefore = b.sent.length;
  room.webSocketMessage(b, "ping");
  assert.equal(b.sent.at(-1), "pong");
  assert.equal(b.sent.length, bBefore + 1);
});

test("peers cannot forge relay control messages", async () => {
  const { room, accepted } = makeRoom();
  await join(room, "A"); await join(room, "B");
  const [a, b] = accepted;
  const before = a.sent.length;
  for (const t of ["welcome", "peer_join", "peer_leave"])
    room.webSocketMessage(b, JSON.stringify({ t, host: false, name: "evil" }));
  assert.equal(a.sent.length, before, "forged control messages must be dropped");
  room.webSocketMessage(b, JSON.stringify({ t: "welcome_back" })); // similar but not reserved
  assert.equal(a.sent.length, before + 1);
  room.webSocketMessage(b, "not json at all \"t\"");
  assert.equal(a.sent.length, before + 2, "non-JSON text is still opaque and relayed");
});

test("oversized and non-string messages are dropped", async () => {
  const { room, accepted } = makeRoom();
  await join(room, "A"); await join(room, "B");
  const [a, b] = accepted;
  const before = a.sent.length;
  room.webSocketMessage(b, "x".repeat(16385));
  room.webSocketMessage(b, new ArrayBuffer(4));
  assert.equal(a.sent.length, before);
});

test("close replies to the closing socket and reports the right peer count", async () => {
  const { room, accepted } = makeRoom();
  await join(room, "A"); await join(room, "B"); await join(room, "C");
  const [a, b, c] = accepted;
  // Case 1: runtime still lists the closing socket.
  room.webSocketClose(c, 1001, "bye");
  assert.deepEqual(c.closed, { code: 1001, reason: "bye" });
  let leave = JSON.parse(a.sent.at(-1));
  assert.equal(leave.t, "peer_leave"); assert.equal(leave.name, "C"); assert.equal(leave.count, 2);
  // Case 2: runtime already dropped it from the list -> same count, no off-by-one.
  accepted.splice(accepted.indexOf(c), 1); // c is gone for real now
  accepted.splice(accepted.indexOf(b), 1);
  room.webSocketClose(b, 1005, "");
  leave = JSON.parse(a.sent.at(-1));
  assert.equal(leave.name, "B"); assert.equal(leave.count, 1);
  assert.equal(b.closed.code, 1000, "reserved/no-status codes are replaced with 1000");
});

test("webSocketError behaves like a close", async () => {
  const { room, accepted } = makeRoom();
  await join(room, "A"); await join(room, "B");
  const [a, b] = accepted;
  room.webSocketError(b);
  assert.equal(JSON.parse(a.sent.at(-1)).t, "peer_leave");
});
