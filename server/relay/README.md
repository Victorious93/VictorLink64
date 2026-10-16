# VictorLink64 relay

Cloudflare Worker + Durable Object (`Room`) that relays JSON messages between 2–8 peers sharing a 6-character room code.

- `GET /new` → `{"code":"ABC234"}`
- `GET /health`
- `wss://<host>/room/<CODE>?name=<nick>` → server sends `welcome` (with `host` flag), then relays every text frame to the other peers and emits `peer_join` / `peer_leave`.

Deploy: `npx wrangler deploy` (needs a Cloudflare account; free plan supports SQLite-backed Durable Objects). Then set `RELAY_URL` in `app/build.gradle` (`buildConfigField`).

Messages of type `welcome`, `peer_join` and `peer_leave` are reserved for the relay: if a peer sends one it is dropped, so a peer cannot forge host/roster control messages. All other text frames (≤ 16 KB) are relayed verbatim.

Tests (no dependencies; uses in-memory fakes of the Cloudflare runtime, so they check the Room logic, **not** behaviour on real Cloudflare): `cd server/relay && npm test`.

— Victorious (Victorious Framework)
