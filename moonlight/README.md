# moonlight (vendored)

Victorious Framework: the streaming core behind VictorLink64's **PC Streaming** screen.

This module is a trimmed copy of [Moonlight for Android](https://github.com/moonlight-stream/moonlight-android)
(GPL-3.0, © Cameron Gutman and the Moonlight contributors), taken at upstream commit
`b48494cb96bff23d8886c4775cc4f39a1075495d` (2026-09-12). Its native code includes
[moonlight-common-c](https://github.com/moonlight-stream/moonlight-common-c) `874ac9548f1b`
(GPL-3.0), [ENet](https://github.com/cgutman/enet) `aca87840b57f` (MIT) and
[nanors](https://github.com/sleepybishop/nanors) `b1e3c22ca0cd` (MIT). The prebuilt
OpenSSL `libcrypto.a` (Apache-2.0) and Opus `libopus.a` (BSD) static libraries are Moonlight's own,
built from https://github.com/cgutman/moonlight-mobile-deps.

Kept from upstream, unchanged: `com.limelight.nvstream.**` (protocol, HTTP, pairing, mDNS),
`binding/audio`, `binding/crypto`, `binding/video`, `LimeLog`, `jni/moonlight-core`.

Changed: `nvstream/http/NvHTTP.java` drops the `.fastFallback(false)` call because the app builds against OkHttp 4.12 (that option arrived in OkHttp 5; 4.x already connects to addresses one at a time). `preferences/PreferenceConfiguration.java` is cut down to the fields the decoder reads, and
`jni/moonlight-core/Android.mk` adds a 16 KB page-size linker flag because VictorLink64 builds with NDK r26.
Moonlight's activities, preference screens, grids and controller drivers are not included;
VictorLink64 has its own UI in `app/src/main/java/org/victoriousframework/victorlink64/pc`.

`LICENSE.txt` is Moonlight's GPL-3.0 licence text.

— Victorious
