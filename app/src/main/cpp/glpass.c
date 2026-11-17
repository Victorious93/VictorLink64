/* VictorLink64 shared GL post-processing pass (original code, GPLv3) - Victorious Framework. */
#include "glpass.h"
#include "glpass_shaders.h"
#include <stdio.h>
#include <android/log.h>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "VL64Pass", __VA_ARGS__)

enum { U_TEX, U_TEXSIZE, U_SRC, U_OUT, U_FLIP, U_COUNT };

static GLuint prog[VL_FX_COUNT];
static GLint uloc[VL_FX_COUNT][U_COUNT];
static bool failed[VL_FX_COUNT];
static GLuint vao;

static GLuint compile(GLenum type, const char *define_line, const char *body) {
    GLuint s = glCreateShader(type);
    const char *src[3] = { VL_GLSL_VERSION, define_line, body };
    glShaderSource(s, 3, src, NULL);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512] = {0};
        glGetShaderInfoLog(s, sizeof log - 1, NULL, log);
        LOG("shader compile failed: %s", log);
        glDeleteShader(s);
        return 0;
    }
    return s;
}

static bool build(int mode) {
    char define_line[32];
    snprintf(define_line, sizeof define_line, "#define FX %d\n", mode);
    GLuint vs = compile(GL_VERTEX_SHADER, "", VL_VS_BODY);
    GLuint fs = compile(GL_FRAGMENT_SHADER, define_line, VL_FS_BODY);
    if (!vs || !fs) { if (vs) glDeleteShader(vs); if (fs) glDeleteShader(fs); return false; }
    GLuint p = glCreateProgram();
    glAttachShader(p, vs); glAttachShader(p, fs);
    glLinkProgram(p);
    glDeleteShader(vs); glDeleteShader(fs);
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[512] = {0};
        glGetProgramInfoLog(p, sizeof log - 1, NULL, log);
        LOG("program link failed: %s", log);
        glDeleteProgram(p);
        return false;
    }
    prog[mode] = p;
    uloc[mode][U_TEX] = glGetUniformLocation(p, "uTex");
    uloc[mode][U_TEXSIZE] = glGetUniformLocation(p, "uTexSize");
    uloc[mode][U_SRC] = glGetUniformLocation(p, "uSrc");
    uloc[mode][U_OUT] = glGetUniformLocation(p, "uOut");
    uloc[mode][U_FLIP] = glGetUniformLocation(p, "uFlip");
    return true;
}

/* Returns the mode that can actually be drawn: the requested one, else plain scaling, else -1. */
static int usable_mode(int mode) {
    if (mode < 0 || mode >= VL_FX_COUNT) mode = VL_FX_NONE;
    if (!prog[mode] && !failed[mode] && !build(mode)) failed[mode] = true;
    if (prog[mode]) return mode;
    if (!prog[VL_FX_NONE] && !failed[VL_FX_NONE] && !build(VL_FX_NONE)) failed[VL_FX_NONE] = true;
    return prog[VL_FX_NONE] ? VL_FX_NONE : -1;
}

bool vl_pass_draw(const struct vl_pass_in *in) {
    int m = usable_mode(in->mode);
    if (m < 0) return false;
    if (!vao) glGenVertexArrays(1, &vao);

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, in->out_w, in->out_h);
    glDisable(GL_SCISSOR_TEST); glDisable(GL_DEPTH_TEST); glDisable(GL_BLEND);
    glDisable(GL_CULL_FACE); glDisable(GL_STENCIL_TEST);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);

    glUseProgram(prog[m]);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, in->tex);
    GLint filter = (in->nearest && m == VL_FX_NONE) ? GL_NEAREST : GL_LINEAR;
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glUniform1i(uloc[m][U_TEX], 0);
    glUniform2f(uloc[m][U_TEXSIZE], (float)in->tex_w, (float)in->tex_h);
    glUniform2f(uloc[m][U_SRC], (float)in->src_w, (float)in->src_h);
    glUniform2f(uloc[m][U_OUT], (float)in->out_w, (float)in->out_h);
    glUniform1f(uloc[m][U_FLIP], in->flip_y ? 1.0f : 0.0f);

    glBindVertexArray(vao);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glBindVertexArray(0);
    glUseProgram(0);
    glBindTexture(GL_TEXTURE_2D, 0);
    return true;
}

void vl_pass_release(void) {
    for (int i = 0; i < VL_FX_COUNT; i++) {
        if (prog[i]) glDeleteProgram(prog[i]);
        prog[i] = 0; failed[i] = false;
    }
    if (vao) { glDeleteVertexArrays(1, &vao); vao = 0; }
}
