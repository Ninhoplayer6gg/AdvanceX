// SPDX-License-Identifier: MPL-2.0
// EGL display/context/surface management.
//
// Tries an OpenGL ES 3 context first and falls back to ES 2. The context can
// outlive window surfaces, so Android surface destruction (app switching,
// rotation) does not require recompiling shaders.
#pragma once

#include <EGL/egl.h>

#include <string>

namespace ax::video {

class EglContext {
public:
    EglContext() = default;
    ~EglContext();
    EglContext(const EglContext&) = delete;
    EglContext& operator=(const EglContext&) = delete;

    /// Creates the display and context. `offscreen` selects a surfaceless
    /// setup (used by host tests).
    bool initialize(bool offscreen, std::string* error);
    void destroy();

    bool attachWindow(EGLNativeWindowType window, std::string* error);
    void detachSurface();
    bool hasSurface() const { return surface_ != EGL_NO_SURFACE; }

    bool makeCurrent();
    bool swapBuffers();
    void setSwapInterval(int interval);
    void surfaceSize(int* width, int* height) const;

    int glesVersion() const { return glesVersion_; }

private:
    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLConfig config_ = nullptr;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLSurface surface_ = EGL_NO_SURFACE;
    bool offscreen_ = false;
    int glesVersion_ = 0;
};

}  // namespace ax::video
