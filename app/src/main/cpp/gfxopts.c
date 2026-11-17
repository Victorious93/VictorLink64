/* VictorLink64 graphics options (original code, GPLv3) - Victorious Framework. */
#include "gfxopts.h"
#include <pthread.h>
#include <string.h>

#define MAX_VARS 96
#define KEY_LEN 64
#define VAL_LEN 64
#define RING 16

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static char keys[MAX_VARS][KEY_LEN];
static char vals[MAX_VARS][VAL_LEN];
static int nvars;
static bool var_updated;
static int post_mode;
static bool post_nearest;
static char ring[RING][VAL_LEN];
static unsigned ring_pos;

static void copy(char *dst, size_t cap, const char *src) {
    strncpy(dst, src, cap - 1);
    dst[cap - 1] = 0;
}

void gfx_set_var(const char *key, const char *value) {
    if (!key || !value) return;
    pthread_mutex_lock(&lock);
    int i;
    for (i = 0; i < nvars; i++) if (strcmp(keys[i], key) == 0) break;
    bool added = i == nvars && nvars < MAX_VARS;
    if (added) { copy(keys[i], KEY_LEN, key); vals[i][0] = 0; nvars++; }
    if (i < nvars) {
        if (added || strcmp(vals[i], value) != 0) var_updated = true;
        copy(vals[i], VAL_LEN, value);
    }
    pthread_mutex_unlock(&lock);
}

const char *gfx_get_var(const char *key) {
    const char *out = NULL;
    if (!key) return NULL;
    pthread_mutex_lock(&lock);
    for (int i = 0; i < nvars; i++) {
        if (strcmp(keys[i], key) == 0) {
            copy(ring[ring_pos % RING], VAL_LEN, vals[i]);
            out = ring[ring_pos % RING];
            ring_pos++;
            break;
        }
    }
    pthread_mutex_unlock(&lock);
    return out;
}

bool gfx_take_var_update(void) {
    pthread_mutex_lock(&lock);
    bool u = var_updated;
    var_updated = false;
    pthread_mutex_unlock(&lock);
    return u;
}

void gfx_set_post(int mode, bool nearest) {
    pthread_mutex_lock(&lock);
    post_mode = mode;
    post_nearest = nearest;
    pthread_mutex_unlock(&lock);
}

void gfx_get_post(int *mode, bool *nearest) {
    pthread_mutex_lock(&lock);
    if (mode) *mode = post_mode;
    if (nearest) *nearest = post_nearest;
    pthread_mutex_unlock(&lock);
}

void gfx_reset(void) {
    pthread_mutex_lock(&lock);
    nvars = 0;
    var_updated = false;
    post_mode = 0;
    post_nearest = false;
    pthread_mutex_unlock(&lock);
}
