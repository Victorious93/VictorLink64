# PC Games (Windows games on the phone), phase 1

Status: **work in progress, nothing has run on an ARM phone yet.**

## Progress note (for restarts)
- [x] Branch `pc-games-winlator` from main `7aa029e`.
- [x] Research: Winlator app source (`brunodev85/winlator-app`, LGPL-2.1) pinned at commit
      `f8a211ce7fd776de9a2642b45d166016876eaa86`; payload assets downloaded and hashed.
- [ ] Scan payload for hard-coded `/data/data/com.winlator` paths (decides helper package name / rootfs path).
- [ ] `pcgames/` helper app module (targetSdk 28, arm64-v8a) with vendored Winlator X server.
- [ ] Main app: PC Games card + screen.
- [ ] CI step + docs + NOTICE.

— Victorious (Victorious Framework)
