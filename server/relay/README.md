# VictorLink64 relay

Cloudflare Worker + Durable Object (`Room`) that relays JSON messages between 2–8 peers sharing a 6-character room code.

- `GET /new` → `{"code":"ABC234"}`
- `GET /health`
- `wss://<host>/room/<CODE>?name=<nick>` → server sends `welcome` (with `host` flag), then relays every text frame to the other peers and emits `peer_join` / `peer_leave`.

Deploy: `npx wrangler deploy` (needs a Cloudflare account; free plan supports SQLite-backed Durable Objects). Then set `RELAY_URL` in `app/build.gradle` (`buildConfigField`).
