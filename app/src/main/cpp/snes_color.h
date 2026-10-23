/* VictorLink64 SNES frontend: libretro pixel formats -> Android R8G8B8X8 (little-endian bytes R,G,B,X). Original code, GPLv3. */
#ifndef VL64_SNES_COLOR_H
#define VL64_SNES_COLOR_H
#include <stdint.h>

/* Widen an n-bit channel to 8 bits by bit replication so the maximum maps to 255, not 248 (shift-only loses ~3% brightness). */
static inline uint32_t vl_expand5(uint32_t v) { return (v << 3) | (v >> 2); }
static inline uint32_t vl_expand6(uint32_t v) { return (v << 2) | (v >> 4); }

static inline uint32_t vl_pack(uint32_t r, uint32_t g, uint32_t b) { return 0xff000000u | (b << 16) | (g << 8) | r; }

static inline uint32_t vl_from_xrgb8888(uint32_t p) { return vl_pack((p >> 16) & 0xff, (p >> 8) & 0xff, p & 0xff); }
static inline uint32_t vl_from_rgb565(uint16_t p) { return vl_pack(vl_expand5((p >> 11) & 0x1f), vl_expand6((p >> 5) & 0x3f), vl_expand5(p & 0x1f)); }
static inline uint32_t vl_from_0rgb1555(uint16_t p) { return vl_pack(vl_expand5((p >> 10) & 0x1f), vl_expand5((p >> 5) & 0x1f), vl_expand5(p & 0x1f)); }

#endif
