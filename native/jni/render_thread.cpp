// SPDX-License-Identifier: MPL-2.0
#include "render_thread.h"

#include <android/api-level.h>
#include <dlfcn.h>
#include <pthread.h>
#include <sys/resource.h>
#include <unistd.h>

#include <chrono>

#include "ax/common/log.h"

namespace ax::jni {
namespace {
constexpr const char* kTag = "RenderThread";

// ANativeWindow_setFrameRate is API 30+; resolve it dynamically so the
// library still loads on Android 8-10.
using SetFrameRateFn = int32_t (*)(ANativeWindow*, float, int8_t);

void hintFrameRate(ANativeWindow* window) {
    static SetFrameRateFn fn = reinterpret_cast<SetFrameRateFn>(dlsym(RTLD_DEFAULT, "ANativeWindow_setFrameRate"));
    if (fn && window) {
        // 1 = ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_FIXED_SOURCE: lets
        // 90/120 Hz panels drop to 60 Hz while playing (smoother, cheaper).
        fn(window, 59.7275f, 1);
    }
}
}  // namespace

RenderThread::RenderThread(runtime::FrameMailbox* mailbox) : mailbox_(mailbox) {}

RenderThread::~RenderThread() { stop(); }

void RenderThread::start() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (thread_.joinable()) return;
    stop_ = false;
    thread_ = std::thread([this] { run(); });
}

void RenderThread::stop() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        stop_ = true;
    }
    cv_.notify_all();
    mailbox_->wakeAll();
    if (thread_.joinable()) thread_.join();
    std::lock_guard<std::mutex> lock(mutex_);
    if (pendingWindow_) {
        ANativeWindow_release(pendingWindow_);
        pendingWindow_ = nullptr;
    }
}

void RenderThread::setWindow(ANativeWindow* window) {
    std::unique_lock<std::mutex> lock(mutex_);
    if (!thread_.joinable()) {
        if (window) ANativeWindow_release(window);
        return;
    }
    if (pendingWindow_) ANativeWindow_release(pendingWindow_);
    pendingWindow_ = window;
    windowChangeRequested_ = true;
    windowChangeDone_ = false;
    cv_.notify_all();
    mailbox_->wakeAll();
    // Wait (bounded) for the render thread to switch surfaces.
    cv_.wait_for(lock, std::chrono::seconds(2), [this] { return windowChangeDone_ || stop_; });
}

void RenderThread::setVideoConfig(const video::VideoConfig& config) {
    std::lock_guard<std::mutex> lock(mutex_);
    pendingConfig_ = config;
    configChanged_ = true;
    redrawRequested_ = true;
    cv_.notify_all();
    mailbox_->wakeAll();
}

void RenderThread::requestRedraw() {
    std::lock_guard<std::mutex> lock(mutex_);
    redrawRequested_ = true;
    mailbox_->wakeAll();
}

void RenderThread::addReplacementTexture(uint64_t key, std::vector<uint32_t> rgba, int width, int height) {
    std::lock_guard<std::mutex> lock(mutex_);
    pendingTextures_.push_back(PendingTexture{key, std::move(rgba), width, height});
}

void RenderThread::clearReplacementTextures() {
    std::lock_guard<std::mutex> lock(mutex_);
    pendingTextures_.clear();
    clearTexturesRequested_ = true;
}

video::Rect RenderThread::lastViewport() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return viewport_;
}

bool RenderThread::ensureGl() {
    if (glReady_) return true;
    if (glFailed_) return false;
    std::string error;
    if (!egl_.initialize(false, &error)) {
        AX_LOGE(kTag, "EGL initialisation failed: %s", error.c_str());
        glFailed_ = true;
        return false;
    }
    glReady_ = true;
    return true;
}

void RenderThread::releaseGl() {
    if (glReady_) {
        egl_.makeCurrent();
        for (auto& t : textures_) renderer_.deleteTexture(t.second.id);
        textures_.clear();
        renderer_.shutdown();
        egl_.destroy();
    }
    glReady_ = false;
}

void RenderThread::applyPendingLocked() {
    if (windowChangeRequested_) {
        windowChangeRequested_ = false;
        egl_.detachSurface();
        if (window_) {
            ANativeWindow_release(window_);
            window_ = nullptr;
        }
        window_ = pendingWindow_;
        pendingWindow_ = nullptr;
        if (window_ && ensureGl()) {
            std::string error;
            if (egl_.attachWindow(window_, &error)) {
                egl_.setSwapInterval(1);
                hintFrameRate(window_);
                if (!renderer_.isInitialized() && !renderer_.initialize(&error)) {
                    AX_LOGE(kTag, "Renderer initialisation failed: %s", error.c_str());
                }
                renderer_.setConfig(pendingConfig_);
                if (!packet_.pixels.empty()) renderer_.uploadFrame(packet_.pixels.data(), 240, 160, 240);
            } else {
                AX_LOGE(kTag, "Cannot attach window: %s", error.c_str());
            }
        }
        redrawRequested_ = true;
        windowChangeDone_ = true;
        cv_.notify_all();
    }
    if (configChanged_) {
        configChanged_ = false;
        if (renderer_.isInitialized()) renderer_.setConfig(pendingConfig_);
    }
    if (renderer_.isInitialized() && egl_.hasSurface()) {
        if (clearTexturesRequested_) {
            clearTexturesRequested_ = false;
            for (auto& t : textures_) renderer_.deleteTexture(t.second.id);
            textures_.clear();
        }
        // Upload a few textures per frame to avoid long stalls.
        int budget = 8;
        while (!pendingTextures_.empty() && budget-- > 0) {
            PendingTexture t = std::move(pendingTextures_.back());
            pendingTextures_.pop_back();
            uint32_t id = renderer_.createTexture(t.rgba.data(), t.width, t.height, true);
            if (id) {
                auto old = textures_.find(t.key);
                if (old != textures_.end()) renderer_.deleteTexture(old->second.id);
                textures_[t.key] = TextureInfo{id, t.width, t.height};
            }
        }
    }
}

void RenderThread::run() {
    pthread_setname_np(pthread_self(), "ax-render");
    setpriority(PRIO_PROCESS, static_cast<id_t>(gettid()), -4);
    std::vector<video::OverlayQuad> overlays;

    for (;;) {
        bool redraw = false;
        {
            std::unique_lock<std::mutex> lock(mutex_);
            if (stop_) break;
            applyPendingLocked();
            redraw = redrawRequested_;
            redrawRequested_ = false;
        }

        bool fresh = false;
        if (!redraw) {
            mailbox_->waitForNewer(packet_.sequence, std::chrono::milliseconds(50));
        }
        fresh = mailbox_->fetchIfNewer(&packet_);

        if (!glReady_ || !egl_.hasSurface() || !renderer_.isInitialized()) continue;
        if (!fresh && !redraw) continue;

        if (fresh) {
            renderer_.uploadFrame(packet_.pixels.data(), 240, 160, 240);
            overlays.clear();
            for (const auto& d : packet_.overlays) {
                auto it = textures_.find(d.key);
                if (it == textures_.end()) continue;
                video::OverlayQuad q;
                q.texture = it->second.id;
                q.x = d.x;
                q.y = d.y;
                q.width = d.width;
                q.height = d.height;
                q.flipH = d.hflip;
                q.flipV = d.vflip;
                overlays.push_back(q);
            }
            renderer_.setOverlays(overlays);
        }
        int w = 0, h = 0;
        egl_.surfaceSize(&w, &h);
        renderer_.render(w, h);
        egl_.swapBuffers();
        {
            std::lock_guard<std::mutex> lock(mutex_);
            viewport_ = renderer_.lastViewport();
            degraded_ = renderer_.degraded();
        }
    }

    egl_.detachSurface();
    if (window_) {
        ANativeWindow_release(window_);
        window_ = nullptr;
    }
    releaseGl();
    std::lock_guard<std::mutex> lock(mutex_);
    windowChangeDone_ = true;
    cv_.notify_all();
}

}  // namespace ax::jni
