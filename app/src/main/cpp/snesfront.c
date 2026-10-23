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

static void video_cb(const void *data, unsigned w, unsigned h, size_t pitch) {
    if (!data) return; /* dupe */
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
    pthread_mutex_unlock(&win_lock);
}

JNIEXPORT void JNICALL
Java_org_victoriousframework_victorlink64_snes_SnesCore_nativeUnload(JNIEnv *env, jclass cls) {
    if (!core) return;
    p_unload(); p_deinit();
    dlclose(core); core = NULL;
}
