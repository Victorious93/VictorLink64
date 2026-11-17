/*
 * VictorLink64 shared GL post-processing pass (original code, GPLv3) - Victorious Framework.
 * Draws a texture to the current window framebuffer through one of a few full-screen effects. Used by the PSP and
 * GameCube frontend (core FBO texture) and by the SNES frontend (uploaded picture). GLES 3.0.
 */
#ifndef VL64_GLPASS_H
#define VL64_GLPASS_H

#include <stdbool.h>
#include <GLES3/gl3.h>

/* Keep in sync with PostFx.id in GraphicsModel.kt. */
enum {
    VL_FX_NONE = 0,    /* plain scaling (filter chosen by `nearest`) */
    VL_FX_SHARPEN = 1,
    VL_FX_FXAA = 2,
    VL_FX_CRT = 3,
    VL_FX_SMOOTH = 4,  /* xBRZ-style edge smoothing for pixel art (EPX corner rules + sharp bilinear) */
    VL_FX_COUNT
};

struct vl_pass_in {
    GLuint tex;
    int tex_w, tex_h;   /* full texture size */
    int src_w, src_h;   /* valid picture region, anchored at texel (0,0) */
    int flip_y;         /* 1 when texel row 0 is the TOP of the picture */
    int out_w, out_h;   /* window framebuffer size */
    int mode;           /* VL_FX_* */
    bool nearest;       /* nearest-neighbour sampling for VL_FX_NONE */
};

/* Draws into the default framebuffer. Returns false if no usable program could be built. GL context must be current. */
bool vl_pass_draw(const struct vl_pass_in *in);

/* Frees GL objects; the GL context must still be current. */
void vl_pass_release(void);

#endif
