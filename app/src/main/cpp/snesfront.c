/* VictorLink64 minimal libretro frontend (original code, GPLv3). */
#include <jni.h>
#include <dlfcn.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <pthread.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include "libretro.h"
#include "snes_color.h"
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include "glpass.h"
#include "gfxopts.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "VL64Snes", __VA_ARGS__)

static void *core;
static void (*p_init)(void), (*p_deinit)(void), (*p_run)(void), (*p_unload)(void), (*p_reset)(void);
static bool (*p_load)(const struct retro_game_info *);
static void (*p_av)(struct retro_system_av_info *);
static void (*p_set_env)(retro_environment_t), (*p_set_video)(retro_video_refresh_t),
    (*p_set_audio)(retro_audio_sample_t), (*p_set_audio_batch)(retro_audio_sample_batch_t),
    (*p_set_poll)(retro_input_poll_t), (*p_set_state)(retro_input_state_t);
static void (*p_set_cport)(unsigned, unsigned);
static size_t (*p_ser_size)(void);
static bool (*p_ser)(void *, size_t), (*p_unser)(const void *, size_t);

static char sys_dir[1024], save_dir[1024];
static enum retro_pixel_format pixfmt = RETRO_PIXEL_FORMAT_0RGB1555;
static ANativeWindow *window;
static pthread_mutex_t win_lock = PTHREAD_MUTEX_INITIALIZER;
/*
 * GL path (Victorious Framework): with an effect or sharp scaling selected the picture is uploaded as a texture and
 * drawn through the shared GL pass. Chosen once at the first frame; EGL failure falls back to the software blit.
 */
static unsigned window_gen;
static bool gl_decided, gl_wanted, gl_failed;
static EGLDisplay gl_dpy = EGL_NO_DISPLAY;
static EGLContext gl_ctx = EGL_NO_CONTEXT;
static EGLSurface gl_surf = EGL_NO_SURFACE;
static EGLConfig gl_cfg;
static unsigned gl_surf_gen;
static GLuint gl_tex;
static unsigned gl_tex_w, gl_tex_h;
static uint32_t *gl_buf;
static size_t gl_buf_cap;
static volatile int buttons;

#define AUDIO_CAP 32768
static int16_t audio_buf[AUDIO_CAP];
static size_t audio_len;

static void log_cb(enum retro_log_level level, const char *fmt, ...) {
    va_list ap; va_start(ap, fmt); __android_log_vprint(ANDROID_LOG_INFO, "VL64Core", fmt, ap); va_end(ap);
}

static bool env_cb(unsigned cmd, void *data) {
    switch (cmd) {
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: *(const char **)data = sys_dir; return true;
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: *(const char **)data = save_dir; return true;
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
        enum retro_pixel_format f = *(enum retro_pixel_format *)data;
        if (f == RETRO_PIXEL_FORMAT_0RGB1555 || f == RETRO_PIXEL_FORMAT_RGB565 || f == RETRO_PIXEL_FORMAT_XRGB8888) { pixfmt = f; return true; }
        return false;
    }
    case RETRO_ENVIRONMENT_GET_CAN_DUPE: *(bool *)data = true; return true;
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: ((struct retro_log_callback *)data)->log = log_cb; return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE: return false;
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: *(bool *)data = false; return true;
    case RETRO_ENVIRONMENT_SET_GEOMETRY:
    case RETRO_ENVIRONMENT_SET_VARIABLES:
    case RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO:
    case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
    case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
    case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
        return true;
    default: return false;
    }
}

static void gl_drop_surface(void) {
    if (gl_dpy != EGL_NO_DISPLAY) {
        eglMakeCurrent(gl_dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, gl_ctx);
        if (gl_surf != EGL_NO_SURFACE) eglDestroySurface(gl_dpy, gl_surf);
    }
    gl_surf = EGL_NO_SURFACE;
}

/* Called with win_lock held and a valid window. */
static bool gl_setup(void) {
    if (gl_dpy == EGL_NO_DISPLAY) {
        gl_dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (!eglInitialize(gl_dpy, NULL, NULL)) { gl_dpy = EGL_NO_DISPLAY; return false; }
        const EGLint attrs[] = { EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT */, EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_DEPTH_SIZE, 0, EGL_NONE };
        EGLint n = 0;
        if (!eglChooseConfig(gl_dpy, attrs, &gl_cfg, 1, &n) || n < 1) return false;
        const EGLint cattrs[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
        gl_ctx = eglCreateContext(gl_dpy, gl_cfg, EGL_NO_CONTEXT, cattrs);
        if (gl_ctx == EGL_NO_CONTEXT) return false;
    }
    gl_drop_surface();
    gl_surf = eglCreateWindowSurface(gl_dpy, gl_cfg, window, NULL);
    if (gl_surf == EGL_NO_SURFACE) return false;
    if (!eglMakeCurrent(gl_dpy, gl_surf, gl_surf, gl_ctx)) return false;
    eglSwapInterval(gl_dpy, 0); /* pacing comes from the audio clock, not vsync */
    gl_surf_gen = window_gen;
    return true;
}

static void gl_teardown(void) {
    gl_decided = gl_wanted = gl_failed = false;
    if (gl_dpy == EGL_NO_DISPLAY) return;
    if (gl_surf != EGL_NO_SURFACE && gl_ctx != EGL_NO_CONTEXT) {
        eglMakeCurrent(gl_dpy, gl_surf, gl_surf, gl_ctx);
        vl_pass_release();
        if (gl_tex) glDeleteTextures(1, &gl_tex);
    }
    gl_tex = 0; gl_tex_w = gl_tex_h = 0;
    gl_drop_surface();
    if (gl_ctx != EGL_NO_CONTEXT) eglDestroyContext(gl_dpy, gl_ctx);
    eglTerminate(gl_dpy);
    gl_dpy = EGL_NO_DISPLAY; gl_ctx = EGL_NO_CONTEXT;
    free(gl_buf); gl_buf = NULL; gl_buf_cap = 0;
}

/* Returns true when the frame was handled (drawn or skipped for lack of a window); false asks for the software path. */
static bool gl_present(const void *data, unsigned w, unsigned h, size_t pitch, int mode, bool nearest) {
    bool ok = true;
    pthread_mutex_lock(&win_lock);
    if (!window) { gl_drop_surface(); goto done; }
    if (gl_surf == EGL_NO_SURFACE || gl_surf_gen != window_gen) {
        if (!gl_setup()) { gl_failed = true; ok = false; goto done; }
    }
    size_t need = (size_t)w * h;
    if (need > gl_buf_cap) {
        free(gl_buf);
        gl_buf = malloc(need * sizeof(uint32_t));
        gl_buf_cap = gl_buf ? need : 0;
        if (!gl_buf) { gl_failed = true; ok = false; goto done; }
    }
    for (unsigned y = 0; y < h; y++) {
        uint32_t *dst = gl_buf + (size_t)y * w;
        const uint8_t *row = (const uint8_t *)data + y * pitch;
        for (unsigned x = 0; x < w; x++) {
            if (pixfmt == RETRO_PIXEL_FORMAT_XRGB8888) dst[x] = vl_from_xrgb8888(((const uint32_t *)row)[x]);
            else if (pixfmt == RETRO_PIXEL_FORMAT_RGB565) dst[x] = vl_from_rgb565(((const uint16_t *)row)[x]);
            else dst[x] = vl_from_0rgb1555(((const uint16_t *)row)[x]);
        }
    }
    if (!gl_tex) glGenTextures(1, &gl_tex);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, gl_tex);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    if (gl_tex_w != w || gl_tex_h != h) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, gl_buf);
        gl_tex_w = w; gl_tex_h = h;
    } else {
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, gl_buf);
    }
    EGLint ww = 0, wh = 0;
    eglQuerySurface(gl_dpy, gl_surf, EGL_WIDTH, &ww); eglQuerySurface(gl_dpy, gl_surf, EGL_HEIGHT, &wh);
    struct vl_pass_in in = { gl_tex, (int)w, (int)h, (int)w, (int)h, 1, ww, wh, mode, nearest };
    if (!vl_pass_draw(&in)) { gl_failed = true; ok = false; goto done; }
    if (!eglSwapBuffers(gl_dpy, gl_surf)) gl_drop_surface(); /* surface lost; rebuilt when the next window arrives */
done:
    pthread_mutex_unlock(&win_lock);
    return ok;
}

static void video_cb(const void *data, unsigned w, unsigned h, size_t pitch) {
    if (!data) return; /* dupe */
    if (!gl_decided) {
        int mode0 = 0; bool nearest0 = false;
        gfx_get_post(&mode0, &nearest0);
        gl_wanted = mode0 != VL_FX_NONE || nearest0;
        gl_decided = true;
    }
    if (gl_wanted && !gl_failed) {
        int mode = 0; bool nearest = false;
        gfx_get_post(&mode, &nearest);
        if (gl_present(data, w, h, pitch, mode, nearest)) return;
        LOG("GL path unavailable, using software blit");
    }
    pthread_mutex_lock(&win_lock);
    if (window) {
        if ((unsigned)ANativeWindow_getWidth(window) != w || (unsigned)ANativeWindow_getHeight(window) != h)
            ANativeWindow_setBuffersGeometry(window, w, h, AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM);
        ANativeWindow_Buffer b;
        if (ANativeWindow_lock(window, &b, NULL) == 0) {
            for (unsigned y = 0; y < h && y < (unsigned)b.height; y++) {
                uint32_t *dst = (uint32_t *)b.bits + (size_t)y * b.stride;
                const uint8_t *row = (const uint8_t *)data + y * pitch;
                for (unsigned x = 0; x < w && x < (unsigned)b.width; x++) {
                    if (pixfmt == RETRO_PIXEL_FORMAT_XRGB8888) dst[x] = vl_from_xrgb8888(((const uint32_t *)row)[x]);
                    else if (pixfmt == RETRO_PIXEL_FORMAT_RGB565) dst[x] = vl_from_rgb565(((const uint16_t *)row)[x]);
                    else dst[x] = vl_from_0rgb1555(((const uint16_t *)row)[x]);
                }
            }
            ANativeWindow_unlockAndPost(window);
        }
    }
    pthread_mutex_unlock(&win_lock);
}

static size_t audio_batch_cb(const int16_t *data, size_t frames) {
    size_t n = frames * 2;
    if (audio_len + n > AUDIO_CAP) n = AUDIO_CAP - audio_len;
    memcpy(audio_buf + audio_len, data, n * sizeof(int16_t));
    audio_len += n;
    return frames;
}
static void audio_cb(int16_t l, int16_t r) { int16_t s[2] = {l, r}; audio_batch_cb(s, 1); }
static void poll_cb(void) {}
static int16_t state_cb(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port != 0 || device != RETRO_DEVICE_JOYPAD) return 0;
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t)buttons;
    return (buttons >> id) & 1;
}

#define SYM(v, n) do { *(void **)&v = dlsym(core, n); if (!v) { LOG("missing %s", n); return JNI_FALSE; } } while (0)

JNIEXPORT jboolean JNICALL
Java_org_victoriousframework_victorlink64_snes_SnesCore_nativeLoad(JNIEnv *env, jclass cls, jstring jcore, jstring jrom, jstring jsys, jstring jsave) {
    const char *c = (*env)->GetStringUTFChars(env, jcore, 0);
    core = dlopen(c, RTLD_NOW | RTLD_LOCAL);
    (*env)->ReleaseStringUTFChars(env, jcore, c);
    if (!core) { LOG("dlopen: %s", dlerror()); return JNI_FALSE; }
    SYM(p_init, "retro_init"); SYM(p_deinit, "retro_deinit"); SYM(p_run, "retro_run");
    SYM(p_load, "retro_load_game"); SYM(p_unload, "retro_unload_game"); SYM(p_av, "retro_get_system_av_info");
    SYM(p_reset, "retro_reset");
    SYM(p_set_env, "retro_set_environment"); SYM(p_set_video, "retro_set_video_refresh");
    SYM(p_set_audio, "retro_set_audio_sample"); SYM(p_set_audio_batch, "retro_set_audio_sample_batch");
    SYM(p_set_poll, "retro_set_input_poll"); SYM(p_set_state, "retro_set_input_state");
    SYM(p_set_cport, "retro_set_controller_port_device");
    SYM(p_ser_size, "retro_serialize_size"); SYM(p_ser, "retro_serialize"); SYM(p_unser, "retro_unserialize");

    const char *s = (*env)->GetStringUTFChars(env, jsys, 0); strncpy(sys_dir, s, sizeof sys_dir - 1); (*env)->ReleaseStringUTFChars(env, jsys, s);
    s = (*env)->GetStringUTFChars(env, jsave, 0); strncpy(save_dir, s, sizeof save_dir - 1); (*env)->ReleaseStringUTFChars(env, jsave, s);

    p_set_env(env_cb); p_init();
    p_set_video(video_cb); p_set_audio(audio_cb); p_set_audio_batch(audio_batch_cb);
    p_set_poll(poll_cb); p_set_state(state_cb);

    const char *rom = (*env)->GetStringUTFChars(env, jrom, 0);
    struct retro_game_info gi = { rom, NULL, 0, NULL };
    bool ok = p_load(&gi);
    (*env)->ReleaseStringUTFChars(env, jrom, rom);
    if (!ok) { LOG("retro_load_game failed"); return JNI_FALSE; }
    p_set_cport(0, RETRO_DEVICE_JOYPAD);
    return JNI_TRUE;
}

JNIEXPORT jdouble JNICALL
Java_org_victoriousframework_victorlink64_snes_SnesCore_nativeSampleRate(JNIEnv *env, jclass cls) {
    struct retro_system_av_info av; p_av(&av); return av.timing.sample_rate;
}
JNIEXPORT jdouble JNICALL
Java_org_victoriousframework_victorlink64_snes_SnesCore_nativeFps(JNIEnv *env, jclass cls) {
    struct retro_system_av_info av; p_av(&av); return av.timing.fps;
}

/** Runs one frame; copies produced audio into out[] and returns sample count (interleaved). */
JNIEXPORT jint JNICALL
Java_org_victoriousframework_victorlink64_snes_SnesCore_nativeRunFrame(JNIEnv *env, jclass cls, jshortArray out) {
    audio_len = 0;
    p_run();
    jsize cap = (*env)->GetArrayLength(env, out);
    jsize n = audio_len < (size_t)cap ? (jsize)audio_len : cap;
    (*env)->SetShortArrayRegion(env, out, 0, n, audio_buf);
    return n;
}

JNIEXPORT void JNICALL
Java_org_victoriousframework_victorlink64_snes_SnesCore_nativeSetButtons(JNIEnv *env, jclass cls, jint b) { buttons = b; }

JNIEXPORT void JNICALL
Java_org_victoriousframework_victorlink64_snes_SnesCore_nativeReset(JNIEnv *env, jclass cls) { if (p_reset) p_reset(); }

JNIEXPORT jbyteArray JNICALL
Java_org_victoriousframework_victorlink64_snes_SnesCore_nativeSaveState(JNIEnv *env, jclass cls) {
    size_t n = p_ser_size(); if (!n) return NULL;
    void *buf = malloc(n);
    jbyteArray arr = NULL;
    if (p_ser(buf, n)) { arr = (*env)->NewByteArray(env, (jsize)n); (*env)->SetByteArrayRegion(env, arr, 0, (jsize)n, buf); }
    free(buf); return arr;
}
JNIEXPORT jboolean JNICALL
Java_org_victoriousframework_victorlink64_snes_SnesCore_nativeLoadState(JNIEnv *env, jclass cls, jbyteArray a) {
    jsize n = (*env)->GetArrayLength(env, a);
    jbyte *b = (*env)->GetByteArrayElements(env, a, 0);
    bool ok = p_unser(b, (size_t)n);
    (*env)->ReleaseByteArrayElements(env, a, b, JNI_ABORT);
    return ok;
}

JNIEXPORT void JNICALL
Java_org_victoriousframework_victorlink64_snes_SnesCore_nativeSetSurface(JNIEnv *env, jclass cls, jobject surface) {
    pthread_mutex_lock(&win_lock);
    if (window) { ANativeWindow_release(window); window = NULL; }
    if (surface) window = ANativeWindow_fromSurface(env, surface);
    window_gen++;
    pthread_mutex_unlock(&win_lock);
}

JNIEXPORT void JNICALL
Java_org_victoriousframework_victorlink64_snes_SnesCore_nativeUnload(JNIEnv *env, jclass cls) {
    if (!core) return;
    p_unload(); p_deinit();
    pthread_mutex_lock(&win_lock);
    gl_teardown();
    pthread_mutex_unlock(&win_lock);
    dlclose(core); core = NULL;
}

/* Victorious Framework graphics options (see graphics/GfxNative.kt). Safe from any thread. */
JNIEXPORT void JNICALL
Java_org_victoriousframework_victorlink64_graphics_SnesGfxNative_nativeSetPost(JNIEnv *env, jclass cls, jint mode, jboolean nearest) {
    gfx_set_post(mode, nearest != JNI_FALSE);
}

JNIEXPORT void JNICALL
Java_org_victoriousframework_victorlink64_graphics_SnesGfxNative_nativeClear(JNIEnv *env, jclass cls) {
    gfx_reset();
}
