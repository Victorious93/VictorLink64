/*
 * VictorLink64 graphics options shared by the libretro frontends (original code, GPLv3) - Victorious Framework.
 * Holds libretro core variables set from Kotlin and the shared GL-pass selection. Thread safe: the UI thread writes,
 * the emulation thread reads.
 */
#ifndef VL64_GFXOPTS_H
#define VL64_GFXOPTS_H

#include <stdbool.h>

/* Sets (or replaces) a core variable and marks variables as changed. */
void gfx_set_var(const char *key, const char *value);

/* Value for a core variable, or NULL when never set. The pointer stays valid for the next 15 lookups. */
const char *gfx_get_var(const char *key);

/* True once after any variable changed; backs RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE. */
bool gfx_take_var_update(void);

/* Selects the GL pass effect (glpass.h VL_FX_*) and whether scaling is nearest-neighbour. */
void gfx_set_post(int mode, bool nearest);
void gfx_get_post(int *mode, bool *nearest);

/* Forgets every variable and effect; call before configuring a new game. */
void gfx_reset(void);

#endif
