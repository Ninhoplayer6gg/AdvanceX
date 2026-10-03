// SPDX-License-Identifier: MPL-2.0
#include "ax/video/shader_builder.h"

namespace ax::video {
namespace {

enum Feature : uint32_t {
    kSharpBilinear = 1u << 0,
    kColorCorrection = 1u << 1,
    kFrameBlend = 1u << 2,
    kScanlines = 1u << 3,
    kLcdGrid = 1u << 4,
    kSharpen = 1u << 5,
};

const char* kPrecision = R"(#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
)";

}  // namespace

uint32_t shaderFeatureKey(const VideoConfig& c) {
    uint32_t key = 0;
    if (c.filter == Filter::SharpBilinear) key |= kSharpBilinear;
    if (c.colorCorrection) key |= kColorCorrection;
    if (c.frameBlending) key |= kFrameBlend;
    if (c.scanlines) key |= kScanlines;
    if (c.lcdGrid) key |= kLcdGrid;
    if (c.sharpen) key |= kSharpen;
    return key;
}

std::string mainVertexShader() {
    return R"(attribute vec2 aPosition;
attribute vec2 aTexCoord;
varying vec2 vTexCoord;
void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
    vTexCoord = aTexCoord;
}
)";
}

std::string mainFragmentShader(uint32_t key) {
    std::string s = kPrecision;
    if (key & kSharpBilinear) s += "#define AX_SHARP_BILINEAR 1\n";
    if (key & kColorCorrection) s += "#define AX_COLOR_CORRECTION 1\n";
    if (key & kFrameBlend) s += "#define AX_FRAME_BLEND 1\n";
    if (key & kScanlines) s += "#define AX_SCANLINES 1\n";
    if (key & kLcdGrid) s += "#define AX_LCD_GRID 1\n";
    if (key & kSharpen) s += "#define AX_SHARPEN 1\n";
    s += R"(
uniform sampler2D uFrame;
uniform sampler2D uPrevFrame;
uniform vec2 uSourceSize;
uniform vec2 uOutputSize;
// x: scanline strength, y: grid strength, z: sharpen strength, w: frame blend
uniform vec4 uStrength;
varying vec2 vTexCoord;

vec2 sourceCoord(vec2 uv) {
#ifdef AX_SHARP_BILINEAR
    // Behave like a nearest-neighbour integer prescale followed by a bilinear
    // resize: inside each texel the colour is flat, and only a band of
    // 1/scale texels around each edge is interpolated.
    vec2 texel = uv * uSourceSize;
    vec2 scale = max(floor(uOutputSize / uSourceSize), vec2(1.0));
    vec2 base = floor(texel);
    vec2 fromCentre = texel - base - 0.5;
    vec2 flatHalfWidth = 0.5 - 0.5 / scale;
    vec2 blend = (fromCentre - clamp(fromCentre, -flatHalfWidth, flatHalfWidth)) * scale + 0.5;
    return (base + blend) / uSourceSize;
#else
    return uv;
#endif
}

vec3 fetch(vec2 uv) {
    vec3 c = texture2D(uFrame, uv).rgb;
#ifdef AX_FRAME_BLEND
    c = mix(c, texture2D(uPrevFrame, uv).rgb, uStrength.w);
#endif
    return c;
}

#ifdef AX_COLOR_CORRECTION
// Approximates the unlit reflective GBA screen, for which games were tuned:
// darker mid-tones and some colour bleed between neighbouring sub-pixels.
// Each matrix row sums to 1.0 so neutral greys stay neutral.
vec3 lcdResponse(vec3 c) {
    vec3 lin = pow(c, vec3(2.6));
    mat3 bleed = mat3(0.80, 0.12, 0.06,
                      0.14, 0.74, 0.18,
                      0.06, 0.14, 0.76);
    return pow(clamp(bleed * lin, 0.0, 1.0), vec3(1.0 / 2.2));
}
#endif

void main() {
    vec2 uv = sourceCoord(vTexCoord);
    vec3 c = fetch(uv);

#ifdef AX_SHARPEN
    vec2 px = 1.0 / uSourceSize;
    vec3 around = fetch(uv + vec2(px.x, 0.0)) + fetch(uv - vec2(px.x, 0.0)) +
                  fetch(uv + vec2(0.0, px.y)) + fetch(uv - vec2(0.0, px.y));
    c = clamp(c + (c - around * 0.25) * uStrength.z, 0.0, 1.0);
#endif

#ifdef AX_COLOR_CORRECTION
    c = lcdResponse(c);
#endif

#ifdef AX_SCANLINES
    float row = fract(vTexCoord.y * uSourceSize.y);
    float beam = abs(row - 0.5) * 2.0;
    c *= 1.0 - uStrength.x * beam * beam;
#endif

#ifdef AX_LCD_GRID
    vec2 cell = fract(vTexCoord * uSourceSize);
    vec2 edge = min(cell, 1.0 - cell);
    float gap = smoothstep(0.0, 0.16, min(edge.x, edge.y));
    c *= mix(1.0 - uStrength.y, 1.0, gap);
#endif

    gl_FragColor = vec4(c, 1.0);
}
)";
    return s;
}

std::string blitFragmentShader() {
    return std::string(kPrecision) + R"(
uniform sampler2D uTexture;
uniform float uOpacity;
varying vec2 vTexCoord;
void main() {
    vec4 c = texture2D(uTexture, vTexCoord);
    gl_FragColor = vec4(c.rgb, c.a * uOpacity);
}
)";
}

std::string ambientFragmentShader() {
    return std::string(kPrecision) + R"(
uniform sampler2D uFrame;
uniform vec2 uSourceSize;
varying vec2 vTexCoord;
void main() {
    vec3 acc = vec3(0.0);
    vec2 stepUv = 6.0 / uSourceSize;
    for (int y = -2; y <= 2; ++y) {
        for (int x = -2; x <= 2; ++x) {
            acc += texture2D(uFrame, vTexCoord + vec2(float(x), float(y)) * stepUv).rgb;
        }
    }
    gl_FragColor = vec4(acc * (0.40 / 25.0), 1.0);
}
)";
}

std::string fallbackFragmentShader() {
    return R"(precision mediump float;
uniform sampler2D uFrame;
varying vec2 vTexCoord;
void main() {
    gl_FragColor = vec4(texture2D(uFrame, vTexCoord).rgb, 1.0);
}
)";
}

}  // namespace ax::video
