// SPDX-License-Identifier: MPL-2.0
#include <algorithm>
#include <cmath>

#include "ax/video/video_config.h"

namespace ax::video {

Rect computeViewport(ScaleMode mode, int surfaceWidth, int surfaceHeight, int sourceWidth, int sourceHeight) {
    Rect r;
    if (surfaceWidth <= 0 || surfaceHeight <= 0 || sourceWidth <= 0 || sourceHeight <= 0) {
        return r;
    }
    int w = surfaceWidth;
    int h = surfaceHeight;
    switch (mode) {
        case ScaleMode::Stretch:
            break;
        case ScaleMode::Integer: {
            int scale = std::min(surfaceWidth / sourceWidth, surfaceHeight / sourceHeight);
            if (scale >= 1) {
                w = sourceWidth * scale;
                h = sourceHeight * scale;
                break;
            }
            // Surface smaller than 1x: fall back to aspect-correct fit.
            [[fallthrough]];
        }
        case ScaleMode::Fit: {
            double scale = std::min(static_cast<double>(surfaceWidth) / sourceWidth,
                                    static_cast<double>(surfaceHeight) / sourceHeight);
            w = std::max(1, static_cast<int>(std::lround(sourceWidth * scale)));
            h = std::max(1, static_cast<int>(std::lround(sourceHeight * scale)));
            break;
        }
    }
    r.width = w;
    r.height = h;
    r.x = (surfaceWidth - w) / 2;
    r.y = (surfaceHeight - h) / 2;
    return r;
}

}  // namespace ax::video
