// SPDX-License-Identifier: MPL-2.0
// Android render thread: presents frames from a Session's FrameMailbox on an
// ANativeWindow using the AdvanceX GL pipeline.
//
// Rendering only happens when a new frame arrives or the surface changed, so
// a paused game costs no GPU time. The GL context survives surface loss
// (app switch, rotation); only the window surface is recreated.
#pragma once

#include <android/native_window.h>

#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <thread>
#include <unordered_map>
#include <vector>

#include "ax/runtime/frame_mailbox.h"
#include "ax/video/egl_context.h"
#include "ax/video/gl_renderer.h"
#include "ax/video/video_config.h"

namespace ax::jni {

class RenderThread {
public:
    explicit RenderThread(runtime::FrameMailbox* mailbox);
    ~RenderThread();

    void start();
    void stop();

    /// Attaches a window (takes a reference) or detaches with nullptr.
    /// Blocks until the render thread has released the previous window, as
    /// required by SurfaceHolder.Callback.surfaceDestroyed.
    void setWindow(ANativeWindow* window);
    void setVideoConfig(const video::VideoConfig& config);
    void requestRedraw();

    /// Replacement artwork (RGBA8888). Uploaded lazily on the GL thread.
    void addReplacementTexture(uint64_t key, std::vector<uint32_t> rgba, int width, int height);
    void clearReplacementTextures();

    bool degraded() const { return degraded_; }
    video::Rect lastViewport() const;

private:
    struct PendingTexture {
        uint64_t key;
        std::vector<uint32_t> rgba;
        int width;
        int height;
    };

    void run();
    bool ensureGl();
    void applyPendingLocked();
    void releaseGl();

    runtime::FrameMailbox* mailbox_;
    std::thread thread_;
    mutable std::mutex mutex_;
    std::condition_variable cv_;
    bool stop_ = false;

    // Requests from the UI thread (guarded by mutex_)
    ANativeWindow* pendingWindow_ = nullptr;
    bool windowChangeRequested_ = false;
    bool windowChangeDone_ = true;
    bool configChanged_ = false;
    bool redrawRequested_ = false;
    bool clearTexturesRequested_ = false;
    video::VideoConfig pendingConfig_;
    std::vector<PendingTexture> pendingTextures_;
    video::Rect viewport_;
    bool degraded_ = false;

    // Render-thread state
    ANativeWindow* window_ = nullptr;
    video::EglContext egl_;
    video::GlRenderer renderer_;
    bool glReady_ = false;
    bool glFailed_ = false;
    runtime::FramePacket packet_;
    struct TextureInfo {
        uint32_t id;
        int width;
        int height;
    };
    std::unordered_map<uint64_t, TextureInfo> textures_;
};

}  // namespace ax::jni
