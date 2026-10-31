/*
 * VictorLink64 hardware-rendered libretro frontend (original code, GPLv3) — Victorious Framework.
 * Drives OpenGL ES libretro cores (PPSSPP for PSP, Dolphin for GameCube). The core renders into an
 * offscreen FBO that is blitted to the window surface; audio and input mirror snesfront.c.
 */
#include <jni.h>
#include <dlfcn.h>
#include <string.h>
#include <stdlib.h>
#include <pthread.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include "libretro.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "VL64Hw", __VA_ARGS__)
#ifndef RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER
#define RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER 56
#endif
#define JNI(name) Java_org_victoriousframework_victorlink64_hw_HwCore_##name

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
static struct retro_hw_render_callback hw;
static bool hw_requested;
static unsigned fb_w = 1, fb_h = 1;
static GLuint fbo, fbo_tex, fbo_depth;

static EGLDisplay dpy = EGL_NO_DISPLAY;
static EGLContext ctx = EGL_NO_CONTEXT;
static EGLSurface surf = EGL_NO_SURFACE;
static EGLConfig cfg;
static ANativeWindow *window, *pending;
static bool window_dirty;
static pthread_mutex_t win_lock = PTHREAD_MUTEX_INITIALIZER;

static volatile int buttons;
static volatile int16_t ana_x, ana_y;

#define AUDIO_CAP 65536
static int16_t audio_buf[AUDIO_CAP];
static size_t audio_len;

static void log_cb(enum retro_log_level level, const char *fmt, ...) {
    va_list ap; va_start(ap, fmt); __android_log_vprint(ANDROID_LOG_INFO, "VL64HwCore", fmt, ap); va_end(ap);
}
static uintptr_t get_fb(void) { return fbo; }
static retro_proc_address_t get_proc(const char *sym) { return (retro_proc_address_t)eglGetProcAddress(sym); }

static bool env_cb(unsigned cmd, void *data) {
    switch (cmd & ~RETRO_ENVIRONMENT_EXPERIMENTAL) {
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: *(const char **)data = sys_dir; return true;
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: *(const char **)data = save_dir; return true;
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: return true;
    case RETRO_ENVIRONMENT_GET_CAN_DUPE: *(bool *)data = true; return true;
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: ((struct retro_log_callback *)data)->log = log_cb; return true;
    case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER: *(unsigned *)data = RETRO_HW_CONTEXT_OPENGLES3; return true;
    case RETRO_ENVIRONMENT_SET_HW_RENDER: {
        struct retro_hw_render_callback *cb = data;
        if (cb->context_type != RETRO_HW_CONTEXT_OPENGLES2 && cb->context_type != RETRO_HW_CONTEXT_OPENGLES3 &&
            cb->context_type != RETRO_HW_CONTEXT_OPENGLES_VERSION) { LOG("unsupported hw context %d", cb->context_type); return false; }
        cb->get_current_framebuffer = get_fb;
        cb->get_proc_address = get_proc;
        hw = *cb; hw_requested = true;
        return true;
    }
    case 51: /* GET_INPUT_BITMASKS */ return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE: return false;
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: *(bool *)data = false; return true;
    case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
    case RETRO_ENVIRONMENT_SET_GEOMETRY:
    case RETRO_ENVIRONMENT_SET_VARIABLES:
    case RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO:
    case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
    case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
    case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
    case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
        return true;
    default: return false;
    }
}

static void make_fbo(unsigned w, unsigned h) {
    if (fbo) { glDeleteFramebuffers(1, &fbo); glDeleteTextures(1, &fbo_tex); glDeleteRenderbuffers(1, &fbo_depth); }
    fb_w = w ? w : 640; fb_h = h ? h : 480;
    glGenTextures(1, &fbo_tex); glBindTexture(GL_TEXTURE_2D, fbo_tex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, fb_w, fb_h, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glGenRenderbuffers(1, &fbo_depth); glBindRenderbuffer(GL_RENDERBUFFER, fbo_depth);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, fb_w, fb_h);
    glGenFramebuffers(1, &fbo); glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, fbo_tex, 0);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, fbo_depth);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) LOG("FBO incomplete");
}

/* Recreates the window surface when the SurfaceView changed. Runs on the emulation (GL) thread. */
static void sync_window(void) {
    pthread_mutex_lock(&win_lock);
    if (window_dirty) {
        eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, ctx);
        if (surf != EGL_NO_SURFACE) { eglDestroySurface(dpy, surf); surf = EGL_NO_SURFACE; }
        if (window) ANativeWindow_release(window);
        window = pending; pending = NULL; window_dirty = false;
        if (window) surf = eglCreateWindowSurface(dpy, cfg, window, NULL);
        /* Surfaceless current context keeps GL objects valid while the app is backgrounded. */
        eglMakeCurrent(dpy, surf, surf, ctx);
    }
    pthread_mutex_unlock(&win_lock);
}

static bool egl_init(int major) {
    dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (!eglInitialize(dpy, NULL, NULL)) return false;
    const EGLint attrs[] = { EGL_RENDERABLE_TYPE, major >= 3 ? 0x40 /* EGL_OPENGL_ES3_BIT */ : EGL_OPENGL_ES2_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
        EGL_DEPTH_SIZE, 0, EGL_NONE };
    EGLint n = 0;
    if (!eglChooseConfig(dpy, attrs, &cfg, 1, &n) || n < 1) return false;
    const EGLint cattrs[] = { EGL_CONTEXT_CLIENT_VERSION, major, EGL_NONE };
    ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, cattrs);
    if (ctx == EGL_NO_CONTEXT) return false;
    window_dirty = true; sync_window();
    return true;
}

static void video_cb(const void *data, unsigned w, unsigned h, size_t pitch) {
    if (data != RETRO_HW_FRAME_BUFFER_VALID || surf == EGL_NO_SURFACE) return; /* dupe or software frame */
    EGLint ww = 0, wh = 0;
    eglQuerySurface(dpy, surf, EGL_WIDTH, &ww); eglQuerySurface(dpy, surf, EGL_HEIGHT, &wh);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
    glDisable(GL_SCISSOR_TEST);
    glViewport(0, 0, ww, wh);
    glClearColor(0, 0, 0, 1); glClear(GL_COLOR_BUFFER_BIT);
    if (hw.bottom_left_origin) glBlitFramebuffer(0, 0, w, h, 0, 0, ww, wh, GL_COLOR_BUFFER_BIT, GL_LINEAR);
    else glBlitFramebuffer(0, 0, w, h, 0, wh, ww, 0, GL_COLOR_BUFFER_BIT, GL_LINEAR);
    eglSwapBuffers(dpy, surf);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
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
    if (port != 0) return 0;
    if (device == RETRO_DEVICE_JOYPAD) {
        if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t)buttons;
        return (buttons >> id) & 1;
    }
    if (device == RETRO_DEVICE_ANALOG && index == RETRO_DEVICE_INDEX_ANALOG_LEFT)
        return id == RETRO_DEVICE_ID_ANALOG_X ? ana_x : ana_y;
    return 0;
}

#define SYM(v, n) do { *(void **)&v = dlsym(core, n); if (!v) { LOG("missing %s", n); return JNI_FALSE; } } while (0)

/** Must be called on the emulation thread, after nativeSetSurface supplied a surface. */
JNIEXPORT jboolean JNICALL
JNI(nativeLoad)(JNIEnv *env, jclass cls, jstring jcore, jstring jrom, jstring jsys, jstring jsave) {
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

    hw_requested = false;
    p_set_env(env_cb); p_init();
    p_set_video(video_cb); p_set_audio(audio_cb); p_set_audio_batch(audio_batch_cb);
    p_set_poll(poll_cb); p_set_state(state_cb);

    /* GL context must exist before retro_load_game: some cores touch GL while loading. */
    if (!egl_init(3) && !egl_init(2)) { LOG("EGL init failed 0x%x", eglGetError()); return JNI_FALSE; }

    const char *rom = (*env)->GetStringUTFChars(env, jrom, 0);
    struct retro_game_info gi = { rom, NULL, 0, NULL };
    bool ok = p_load(&gi);
    (*env)->ReleaseStringUTFChars(env, jrom, rom);
    if (!ok) { LOG("retro_load_game failed"); return JNI_FALSE; }
    if (!hw_requested) { LOG("core did not request a GL context"); return JNI_FALSE; }
    struct retro_system_av_info av; p_av(&av);
    make_fbo(av.geometry.max_width, av.geometry.max_height);
    if (hw.context_reset) hw.context_reset();
    p_set_cport(0, RETRO_DEVICE_JOYPAD);
    return JNI_TRUE;
}

JNIEXPORT jdouble JNICALL JNI(nativeSampleRate)(JNIEnv *env, jclass cls) { struct retro_system_av_info av; p_av(&av); return av.timing.sample_rate; }
JNIEXPORT jdouble JNICALL JNI(nativeFps)(JNIEnv *env, jclass cls) { struct retro_system_av_info av; p_av(&av); return av.timing.fps; }
JNIEXPORT jfloat JNICALL JNI(nativeAspect)(JNIEnv *env, jclass cls) {
    struct retro_system_av_info av; p_av(&av);
    if (av.geometry.aspect_ratio > 0) return av.geometry.aspect_ratio;
    return av.geometry.base_height ? (float)av.geometry.base_width / av.geometry.base_height : 4.f / 3.f;
}

JNIEXPORT jint JNICALL JNI(nativeRunFrame)(JNIEnv *env, jclass cls, jshortArray out) {
    sync_window();
    audio_len = 0;
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    p_run();
    jsize cap = (*env)->GetArrayLength(env, out);
    jsize n = audio_len < (size_t)cap ? (jsize)audio_len : cap;
    (*env)->SetShortArrayRegion(env, out, 0, n, audio_buf);
    return n;
}

JNIEXPORT void JNICALL JNI(nativeSetButtons)(JNIEnv *env, jclass cls, jint b) { buttons = b; }
JNIEXPORT void JNICALL JNI(nativeSetAnalog)(JNIEnv *env, jclass cls, jint x, jint y) { ana_x = (int16_t)x; ana_y = (int16_t)y; }
JNIEXPORT void JNICALL JNI(nativeReset)(JNIEnv *env, jclass cls) { if (p_reset) p_reset(); }

JNIEXPORT jbyteArray JNICALL JNI(nativeSaveState)(JNIEnv *env, jclass cls) {
    size_t n = p_ser_size(); if (!n) return NULL;
    void *buf = malloc(n); if (!buf) return NULL;
    jbyteArray arr = NULL;
    if (p_ser(buf, n)) { arr = (*env)->NewByteArray(env, (jsize)n); (*env)->SetByteArrayRegion(env, arr, 0, (jsize)n, buf); }
    free(buf); return arr;
}
JNIEXPORT jboolean JNICALL JNI(nativeLoadState)(JNIEnv *env, jclass cls, jbyteArray a) {
    jsize n = (*env)->GetArrayLength(env, a);
    jbyte *b = (*env)->GetByteArrayElements(env, a, 0);
    bool ok = p_unser(b, (size_t)n);
    (*env)->ReleaseByteArrayElements(env, a, b, JNI_ABORT);
    return ok;
}

/** Called from the UI thread; the GL thread picks the new window up before its next frame. */
JNIEXPORT void JNICALL JNI(nativeSetSurface)(JNIEnv *env, jclass cls, jobject surface) {
    pthread_mutex_lock(&win_lock);
    if (pending) ANativeWindow_release(pending);
    pending = surface ? ANativeWindow_fromSurface(env, surface) : NULL;
    window_dirty = true;
    pthread_mutex_unlock(&win_lock);
}

JNIEXPORT void JNICALL JNI(nativeUnload)(JNIEnv *env, jclass cls) {
    if (!core) return;
    if (hw.context_destroy) hw.context_destroy();
    p_unload(); p_deinit();
    if (fbo) { glDeleteFramebuffers(1, &fbo); glDeleteTextures(1, &fbo_tex); glDeleteRenderbuffers(1, &fbo_depth); fbo = 0; }
    if (dpy != EGL_NO_DISPLAY) {
        eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (surf != EGL_NO_SURFACE) eglDestroySurface(dpy, surf);
        if (ctx != EGL_NO_CONTEXT) eglDestroyContext(dpy, ctx);
        eglTerminate(dpy);
    }
    dpy = EGL_NO_DISPLAY; ctx = EGL_NO_CONTEXT; surf = EGL_NO_SURFACE;
    pthread_mutex_lock(&win_lock);
    if (window) { ANativeWindow_release(window); window = NULL; }
    pthread_mutex_unlock(&win_lock);
    memset(&hw, 0, sizeof hw);
    dlclose(core); core = NULL;
}
