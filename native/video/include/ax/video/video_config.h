// SPDX-License-Identifier: MPL-2.0
// Configuration of the AdvanceX video pipeline.
//
//   GBA framebuffer (240x160)
//     -> scaling (integer / fit / stretch)
//     -> colour correction (GBA LCD response)
//     -> shader effects (scanlines, LCD grid, sharpen, frame blending)
//     -> optional replacements (Advance Engine overlays)
//     -> display
//
// The renderer compiles the active combination into a single fragment
// shader, so enabling features costs one draw call, not one pass each.
#pragma once

#include <cstdint>

namespace ax::video {

enum class ScaleMode : int {
    Integer = 0,  // largest whole-number multiple that fits (pixel-perfect)
    Fit = 1,      // largest size that fits while keeping the 3:2 aspect ratio
    Stretch = 2,  // fill the whole surface (distorts; opt-in only)
};

enum class Filter : int {
    Nearest = 0,        // hard pixels
    Bilinear = 1,       // smooth
    SharpBilinear = 2,  // crisp pixels without shimmer at non-integer scales
};

enum class Background : int {
    Black = 0,
    Ambient = 1,  // blurred, darkened copy of the game behind the image
};

struct VideoConfig {
    ScaleMode scaleMode = ScaleMode::Fit;
    Filter filter = Filter::SharpBilinear;
    Background background = Background::Black;

    bool colorCorrection = false;  // emulate the GBA LCD colour response
    bool frameBlending = false;    // LCD persistence (some games rely on it)
    bool scanlines = false;
    bool lcdGrid = false;
    bool sharpen = false;

    float scanlineStrength = 0.30f;  // 0..1
    float gridStrength = 0.35f;      // 0..1
    float sharpenStrength = 0.50f;   // 0..1
    float frameBlendAmount = 0.50f;  // 0..1, weight of the previous frame

    bool operator==(const VideoConfig& o) const {
        return scaleMode == o.scaleMode && filter == o.filter && background == o.background &&
               colorCorrection == o.colorCorrection && frameBlending == o.frameBlending &&
               scanlines == o.scanlines && lcdGrid == o.lcdGrid && sharpen == o.sharpen &&
               scanlineStrength == o.scanlineStrength && gridStrength == o.gridStrength &&
               sharpenStrength == o.sharpenStrength && frameBlendAmount == o.frameBlendAmount;
    }
    bool operator!=(const VideoConfig& o) const { return !(*this == o); }
};

struct Rect {
    int x = 0;
    int y = 0;
    int width = 0;
    int height = 0;
};

/// Where the game image goes inside a surface of the given size.
Rect computeViewport(ScaleMode mode, int surfaceWidth, int surfaceHeight, int sourceWidth = 240,
                     int sourceHeight = 160);

}  // namespace ax::video
