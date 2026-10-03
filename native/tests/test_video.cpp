// SPDX-License-Identifier: MPL-2.0
// Video pipeline tests. Viewport math runs everywhere; the GL tests render
// through the real renderer in a headless EGL context (Mesa llvmpipe on CI)
// and inspect the pixels.
#include <GLES2/gl2.h>

#include <cmath>
#include <cstdio>
#include <memory>

#include "ax/video/egl_context.h"
#include "ax/video/gl_renderer.h"
#include "ax/video/shader_builder.h"
#include "test_support.h"

using namespace ax::video;
using namespace axtest;

AX_TEST(viewport_integer_scaling) {
    Rect r = computeViewport(ScaleMode::Integer, 1080, 2400);
    AX_EXPECT_EQ(r.width, 960);
    AX_EXPECT_EQ(r.height, 640);
    AX_EXPECT_EQ(r.x, 60);
    AX_EXPECT_EQ(r.y, 880);
    // Landscape 1080p: 6x (1440x960) is the largest whole multiple.
    r = computeViewport(ScaleMode::Integer, 1920, 1080);
    AX_EXPECT_EQ(r.width, 1440);
    AX_EXPECT_EQ(r.height, 960);
    AX_EXPECT_EQ(r.x, 240);
    AX_EXPECT_EQ(r.y, 60);
    // Smaller than 1x falls back to an aspect-correct fit.
    r = computeViewport(ScaleMode::Integer, 120, 200);
    AX_EXPECT_EQ(r.width, 120);
    AX_EXPECT_EQ(r.height, 80);
}

AX_TEST(viewport_fit_keeps_aspect_and_stretch_fills) {
    Rect r = computeViewport(ScaleMode::Fit, 1920, 1080);
    AX_EXPECT_EQ(r.width, 1620);
    AX_EXPECT_EQ(r.height, 1080);
    AX_EXPECT_EQ(r.x, 150);
    AX_EXPECT_EQ(r.y, 0);
    r = computeViewport(ScaleMode::Stretch, 1920, 1080);
    AX_EXPECT_EQ(r.width, 1920);
    AX_EXPECT_EQ(r.height, 1080);
    r = computeViewport(ScaleMode::Fit, 0, 100);
    AX_EXPECT_EQ(r.width, 0);
}

namespace {

/// Headless GL fixture: EGL surfaceless context + an RGBA FBO target.
struct GlFixture {
    EglContext egl;
    GlRenderer renderer;
    GLuint fbo = 0;
    GLuint color = 0;
    int width = 0;
    int height = 0;

    GlFixture(int w, int h) : width(w), height(h) {
        std::string error;
        if (!egl.initialize(true, &error)) throw Failure{"EGL: " + error};
        if (!renderer.initialize(&error)) throw Failure{"renderer: " + error};
        glGenTextures(1, &color);
        glBindTexture(GL_TEXTURE_2D, color);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glGenFramebuffers(1, &fbo);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) throw Failure{"FBO incomplete"};
    }
    ~GlFixture() {
        renderer.shutdown();
        glDeleteFramebuffers(1, &fbo);
        glDeleteTextures(1, &color);
    }

    std::vector<uint32_t> renderAndRead() {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        renderer.render(width, height);
        std::vector<uint32_t> px(static_cast<size_t>(width) * height);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
        // GL rows are bottom-up; flip to top-down like the source frame.
        std::vector<uint32_t> flipped(px.size());
        for (int y = 0; y < height; ++y) {
            std::copy_n(px.begin() + static_cast<long>(y) * width, width,
                        flipped.begin() + static_cast<long>(height - 1 - y) * width);
        }
        return flipped;
    }
};

void writePpm(const std::string& name, const std::vector<uint32_t>& px, int w, int h) {
    if (outputDir().empty()) return;
    ax::fileio::makeDirs(outputDir());
    std::string path = outputDir() + "/" + name + ".ppm";
    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) return;
    std::fprintf(f, "P6 %d %d 255\n", w, h);
    for (uint32_t p : px) {
        unsigned char rgb[3] = {static_cast<unsigned char>(p), static_cast<unsigned char>(p >> 8),
                                static_cast<unsigned char>(p >> 16)};
        std::fwrite(rgb, 1, 3, f);
    }
    std::fclose(f);
}

std::vector<uint32_t> testCartFrame() {
    auto core = bootTestRom();
    runFrames(*core, 60);
    auto f = core->frame();
    return std::vector<uint32_t>(f.pixels, f.pixels + 240 * 160);
}

int luminance(uint32_t p) { return static_cast<int>((p & 0xFF) + ((p >> 8) & 0xFF) + ((p >> 16) & 0xFF)); }

}  // namespace

AX_TEST(gl_all_feature_shaders_compile) {
    GlFixture gl(240, 160);
    auto frame = testCartFrame();
    gl.renderer.uploadFrame(frame.data(), 240, 160, 240);
    // Exercise every combination of shader features.
    for (int mask = 0; mask < 64; ++mask) {
        VideoConfig c;
        c.filter = (mask & 1) ? Filter::SharpBilinear : Filter::Nearest;
        c.colorCorrection = mask & 2;
        c.frameBlending = mask & 4;
        c.scanlines = mask & 8;
        c.lcdGrid = mask & 16;
        c.sharpen = mask & 32;
        gl.renderer.setConfig(c);
        gl.renderAndRead();
        AX_EXPECT(!gl.renderer.degraded());
    }
    AX_EXPECT_EQ(static_cast<int>(glGetError()), static_cast<int>(GL_NO_ERROR));
}

AX_TEST(gl_nearest_integer_scaling_is_pixel_exact) {
    GlFixture gl(960, 640);
    auto frame = testCartFrame();
    VideoConfig c;
    c.scaleMode = ScaleMode::Integer;
    c.filter = Filter::Nearest;
    gl.renderer.setConfig(c);
    gl.renderer.uploadFrame(frame.data(), 240, 160, 240);
    auto out = gl.renderAndRead();
    writePpm("nearest_4x", out, 960, 640);
    int mismatches = 0;
    for (int y = 0; y < 640; ++y) {
        for (int x = 0; x < 960; ++x) {
            uint32_t src = frame[(y / 4) * 240 + (x / 4)] & 0x00FFFFFFu;
            if ((out[y * 960 + x] & 0x00FFFFFFu) != src) ++mismatches;
        }
    }
    AX_EXPECT_EQ(mismatches, 0);
}

AX_TEST(gl_sharp_bilinear_is_flat_inside_texels) {
    GlFixture gl(600, 400);  // 2.5x: non-integer scale
    auto frame = testCartFrame();
    VideoConfig c;
    c.scaleMode = ScaleMode::Fit;
    c.filter = Filter::SharpBilinear;
    gl.renderer.setConfig(c);
    gl.renderer.uploadFrame(frame.data(), 240, 160, 240);
    auto out = gl.renderAndRead();
    writePpm("sharp_bilinear_2_5x", out, 600, 400);
    // Sample the centre of every source texel: it must equal the source.
    int mismatches = 0;
    for (int sy = 0; sy < 160; ++sy) {
        for (int sx = 0; sx < 240; ++sx) {
            int ox = static_cast<int>((sx + 0.5) * 2.5);
            int oy = static_cast<int>((sy + 0.5) * 2.5);
            uint32_t a = out[oy * 600 + ox];
            uint32_t b = frame[sy * 240 + sx];
            for (int ch = 0; ch < 3; ++ch) {
                int d = static_cast<int>((a >> (ch * 8)) & 0xFF) - static_cast<int>((b >> (ch * 8)) & 0xFF);
                if (std::abs(d) > 2) {
                    ++mismatches;
                    break;
                }
            }
        }
    }
    AX_EXPECT(mismatches < 240 * 160 / 100);  // allow edge rounding on <1% of texels
}

AX_TEST(gl_scanlines_darken_row_edges) {
    GlFixture gl(960, 640);
    std::vector<uint32_t> white(240 * 160, 0xFFFFFFFFu);
    VideoConfig c;
    c.scaleMode = ScaleMode::Integer;
    c.filter = Filter::Nearest;
    c.scanlines = true;
    c.scanlineStrength = 0.5f;
    gl.renderer.setConfig(c);
    gl.renderer.uploadFrame(white.data(), 240, 160, 240);
    auto out = gl.renderAndRead();
    writePpm("scanlines", out, 960, 640);
    int edge = luminance(out[0 * 960 + 100]);    // first output row of source row 0
    int centre = luminance(out[2 * 960 + 100]);  // middle of source row 0
    AX_EXPECT(centre > edge + 60);
    AX_EXPECT(centre > 700);
}

AX_TEST(gl_color_correction_desaturates_but_keeps_white) {
    GlFixture gl(240, 160);
    std::vector<uint32_t> frame(240 * 160, 0xFF0000FFu);  // pure red
    for (int i = 0; i < 240 * 80; ++i) frame[i] = 0xFFFFFFFFu;  // top half white
    VideoConfig c;
    c.scaleMode = ScaleMode::Integer;
    c.filter = Filter::Nearest;
    c.colorCorrection = true;
    gl.renderer.setConfig(c);
    gl.renderer.uploadFrame(frame.data(), 240, 160, 240);
    auto out = gl.renderAndRead();
    uint32_t white = out[10 * 240 + 10];
    uint32_t red = out[120 * 240 + 10];
    AX_EXPECT((white & 0xFF) > 245 && ((white >> 8) & 0xFF) > 245 && ((white >> 16) & 0xFF) > 245);
    AX_EXPECT((red & 0xFF) < 250);           // less intense
    AX_EXPECT(((red >> 8) & 0xFF) > 10);     // picked up green bleed
    AX_EXPECT(((red >> 16) & 0xFF) > 10);    // and blue bleed
}

AX_TEST(gl_ambient_background_fills_margins) {
    GlFixture gl(1920, 1080);
    auto frame = testCartFrame();
    VideoConfig c;
    c.scaleMode = ScaleMode::Fit;
    c.background = Background::Black;
    gl.renderer.setConfig(c);
    gl.renderer.uploadFrame(frame.data(), 240, 160, 240);
    auto black = gl.renderAndRead();
    AX_EXPECT_EQ(luminance(black[540 * 1920 + 20]), 0);

    c.background = Background::Ambient;
    gl.renderer.setConfig(c);
    auto ambient = gl.renderAndRead();
    writePpm("ambient_1080p", ambient, 1920, 1080);
    AX_EXPECT(luminance(ambient[540 * 1920 + 20]) > 15);
    // The game image itself is unchanged by the background.
    AX_EXPECT(ambient[540 * 1920 + 960] == black[540 * 1920 + 960]);
}

AX_TEST(gl_overlay_quads_are_drawn_in_game_coordinates) {
    GlFixture gl(480, 320);  // 2x
    std::vector<uint32_t> frame(240 * 160, 0xFF000000u);
    VideoConfig c;
    c.scaleMode = ScaleMode::Integer;
    c.filter = Filter::Nearest;
    gl.renderer.setConfig(c);
    gl.renderer.uploadFrame(frame.data(), 240, 160, 240);
    std::vector<uint32_t> red(8 * 8, 0xFF0000FFu);
    uint32_t tex = gl.renderer.createTexture(red.data(), 8, 8, false);
    AX_EXPECT(tex != 0);
    OverlayQuad q;
    q.texture = tex;
    q.x = 100;
    q.y = 50;
    q.width = 16;
    q.height = 16;
    gl.renderer.setOverlays({q});
    auto out = gl.renderAndRead();
    // Inside: GBA (108, 58) -> output (216, 116).
    AX_EXPECT_EQ(out[116 * 480 + 216] & 0x00FFFFFFu, 0x000000FFu);
    // Outside: GBA (90, 58) is untouched black.
    AX_EXPECT_EQ(out[116 * 480 + 180] & 0x00FFFFFFu, 0u);
    gl.renderer.deleteTexture(tex);
}
