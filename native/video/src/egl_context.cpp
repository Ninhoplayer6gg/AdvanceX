// SPDX-License-Identifier: MPL-2.0
#include "ax/video/egl_context.h"

#include <EGL/eglext.h>

#include <cstdio>
#include <cstring>

#include "ax/common/log.h"

#ifndef EGL_OPENGL_ES3_BIT_KHR
#define EGL_OPENGL_ES3_BIT_KHR 0x00000040
#endif
#ifndef EGL_PLATFORM_SURFACELESS_MESA
#define EGL_PLATFORM_SURFACELESS_MESA 0x31DD
#endif

namespace ax::video {
namespace {
constexpr const char* kTag = "EglContext";

std::string eglErrorString(const char* what) {
    char buf[96];
    std::snprintf(buf, sizeof(buf), "%s failed (EGL error 0x%04x)", what, eglGetError());
    return buf;
}
}  // namespace

EglContext::~EglContext() { destroy(); }

bool EglContext::initialize(bool offscreen, std::string* error) {
    destroy();
    offscreen_ = offscreen;

#if !defined(__ANDROID__)
    if (offscreen) {
        // Headless hosts (CI, containers) have no window system; Mesa's
        // surfaceless platform still provides full GL ES via llvmpipe.
        auto getPlatformDisplay =
            reinterpret_cast<PFNEGLGETPLATFORMDISPLAYEXTPROC>(eglGetProcAddress("eglGetPlatformDisplayEXT"));
        if (getPlatformDisplay) {
            display_ = getPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, nullptr);
        }
    }
#endif
    if (display_ == EGL_NO_DISPLAY) {
        display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    }
    if (display_ == EGL_NO_DISPLAY || !eglInitialize(display_, nullptr, nullptr)) {
        if (error) *error = eglErrorString("eglInitialize");
        display_ = EGL_NO_DISPLAY;
        return false;
    }

    for (int version : {3, 2}) {
        EGLint renderable = version == 3 ? EGL_OPENGL_ES3_BIT_KHR : EGL_OPENGL_ES2_BIT;
        EGLint surfaceType = offscreen ? EGL_PBUFFER_BIT : EGL_WINDOW_BIT;
        const EGLint attribs[] = {EGL_RENDERABLE_TYPE, renderable, EGL_SURFACE_TYPE, surfaceType,
                                  EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
                                  EGL_DEPTH_SIZE, 0, EGL_STENCIL_SIZE, 0, EGL_NONE};
        EGLint count = 0;
        if (!eglChooseConfig(display_, attribs, &config_, 1, &count) || count < 1) {
            // Some offscreen implementations expose no pbuffer configs;
            // surfaceless contexts don't need one.
            const EGLint relaxed[] = {EGL_RENDERABLE_TYPE, renderable, EGL_NONE};
            if (!offscreen || !eglChooseConfig(display_, relaxed, &config_, 1, &count) || count < 1) {
                continue;
            }
        }
        const EGLint ctxAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, version, EGL_NONE};
        context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, ctxAttribs);
        if (context_ != EGL_NO_CONTEXT) {
            glesVersion_ = version;
            break;
        }
    }
    if (context_ == EGL_NO_CONTEXT) {
        if (error) *error = eglErrorString("eglCreateContext (ES3/ES2)");
        destroy();
        return false;
    }
    if (offscreen && !eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, context_)) {
        if (error) *error = eglErrorString("eglMakeCurrent (surfaceless)");
        destroy();
        return false;
    }
    AX_LOGI(kTag, "EGL context ready (OpenGL ES %d)", glesVersion_);
    return true;
}

void EglContext::destroy() {
    if (display_ != EGL_NO_DISPLAY) {
        eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (surface_ != EGL_NO_SURFACE) eglDestroySurface(display_, surface_);
        if (context_ != EGL_NO_CONTEXT) eglDestroyContext(display_, context_);
        eglTerminate(display_);
    }
    display_ = EGL_NO_DISPLAY;
    context_ = EGL_NO_CONTEXT;
    surface_ = EGL_NO_SURFACE;
    config_ = nullptr;
    glesVersion_ = 0;
}

bool EglContext::attachWindow(EGLNativeWindowType window, std::string* error) {
    detachSurface();
    if (display_ == EGL_NO_DISPLAY || context_ == EGL_NO_CONTEXT) {
        if (error) *error = "EGL not initialized";
        return false;
    }
    surface_ = eglCreateWindowSurface(display_, config_, window, nullptr);
    if (surface_ == EGL_NO_SURFACE) {
        if (error) *error = eglErrorString("eglCreateWindowSurface");
        return false;
    }
    if (!eglMakeCurrent(display_, surface_, surface_, context_)) {
        if (error) *error = eglErrorString("eglMakeCurrent");
        eglDestroySurface(display_, surface_);
        surface_ = EGL_NO_SURFACE;
        return false;
    }
    return true;
}

void EglContext::detachSurface() {
    if (display_ == EGL_NO_DISPLAY) return;
    if (surface_ != EGL_NO_SURFACE) {
        // Keep the context current without a surface so GL objects survive.
        if (!eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, context_)) {
            eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        }
        eglDestroySurface(display_, surface_);
        surface_ = EGL_NO_SURFACE;
    }
}

bool EglContext::makeCurrent() {
    if (display_ == EGL_NO_DISPLAY || context_ == EGL_NO_CONTEXT) return false;
    return eglMakeCurrent(display_, surface_, surface_, context_) == EGL_TRUE;
}

bool EglContext::swapBuffers() {
    if (surface_ == EGL_NO_SURFACE) return false;
    if (eglSwapBuffers(display_, surface_)) return true;
    EGLint err = eglGetError();
    AX_LOGW(kTag, "eglSwapBuffers failed: 0x%04x", err);
    return false;
}

void EglContext::setSwapInterval(int interval) {
    if (display_ != EGL_NO_DISPLAY) eglSwapInterval(display_, interval);
}

void EglContext::surfaceSize(int* width, int* height) const {
    EGLint w = 0, h = 0;
    if (surface_ != EGL_NO_SURFACE) {
        eglQuerySurface(display_, surface_, EGL_WIDTH, &w);
        eglQuerySurface(display_, surface_, EGL_HEIGHT, &h);
    }
    if (width) *width = w;
    if (height) *height = h;
}

}  // namespace ax::video
