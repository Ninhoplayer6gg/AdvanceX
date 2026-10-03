// SPDX-License-Identifier: MPL-2.0
// OpenGL ES 2.0+ renderer for the AdvanceX video pipeline.
//
// Requires a current GL context on the calling thread for every method.
// Renders into whatever framebuffer is bound when render() is called, so the
// same code draws to an Android window surface or to an offscreen FBO in
// host tests.
#pragma once

#include <cstdint>
#include <string>
#include <unordered_map>
#include <vector>

#include "ax/video/video_config.h"

namespace ax::video {

/// A textured quad positioned in GBA screen coordinates (240x160 space),
/// drawn on top of the game image. Used by the Advance Engine for asset
/// replacement overlays.
struct OverlayQuad {
    uint32_t texture = 0;
    float x = 0, y = 0, width = 0, height = 0;
    bool flipH = false;
    bool flipV = false;
    float opacity = 1.0f;
};

class GlRenderer {
public:
    GlRenderer() = default;
    ~GlRenderer();
    GlRenderer(const GlRenderer&) = delete;
    GlRenderer& operator=(const GlRenderer&) = delete;

    bool initialize(std::string* error);
    void shutdown();
    bool isInitialized() const { return initialized_; }

    void setConfig(const VideoConfig& config);
    const VideoConfig& config() const { return config_; }

    /// Uploads a new game frame (RGBX8888, R in the low byte).
    void uploadFrame(const uint32_t* pixels, int width, int height, int stridePixels);
    bool hasFrame() const { return hasFrame_; }

    /// Draws into the currently bound framebuffer of the given size.
    void render(int targetWidth, int targetHeight);

    /// Replacement overlays for the next render() calls.
    void setOverlays(std::vector<OverlayQuad> overlays) { overlays_ = std::move(overlays); }

    /// Texture management for overlays. Returns 0 on failure.
    uint32_t createTexture(const uint32_t* rgba, int width, int height, bool smooth);
    void deleteTexture(uint32_t texture);

    Rect lastViewport() const { return lastViewport_; }
    /// True when a feature shader failed to compile and the renderer fell
    /// back to plain output (the failure is logged once).
    bool degraded() const { return degraded_; }

private:
    struct Program {
        uint32_t id = 0;
        int aPosition = -1;
        int aTexCoord = -1;
        int uFrame = -1;
        int uPrevFrame = -1;
        int uSourceSize = -1;
        int uOutputSize = -1;
        int uStrength = -1;
        int uTexture = -1;
        int uOpacity = -1;
    };

    bool buildProgram(const std::string& vs, const std::string& fs, Program* out, std::string* error);
    const Program* mainProgram();
    void drawQuad(const Program& program, const float* positions, const float* texCoords);
    void ensureAmbientTarget();
    void renderAmbient(int targetWidth, int targetHeight, int boundFramebuffer);
    void applyFrameFilter();

    bool initialized_ = false;
    bool hasFrame_ = false;
    bool degraded_ = false;
    VideoConfig config_;
    std::vector<OverlayQuad> overlays_;
    Rect lastViewport_;

    uint32_t frameTex_[2] = {0, 0};
    int current_ = 0;
    int frameWidth_ = 240;
    int frameHeight_ = 160;
    int appliedFilter_ = -1;

    uint32_t ambientTex_ = 0;
    uint32_t ambientFbo_ = 0;

    std::unordered_map<uint32_t, Program> mainPrograms_;
    Program fallback_;
    Program blit_;
    Program ambient_;
};

}  // namespace ax::video
