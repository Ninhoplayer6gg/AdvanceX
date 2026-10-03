// SPDX-License-Identifier: MPL-2.0
#include "ax/video/gl_renderer.h"

#include <GLES2/gl2.h>

#include <algorithm>

#include "ax/common/log.h"
#include "ax/video/shader_builder.h"

namespace ax::video {
namespace {

constexpr const char* kTag = "GlRenderer";
constexpr int kAmbientWidth = 60;
constexpr int kAmbientHeight = 40;

// Full-target quad, triangle strip. Texture row 0 is the top of the frame.
const float kQuadPositions[] = {-1.f, -1.f, 1.f, -1.f, -1.f, 1.f, 1.f, 1.f};
const float kQuadTexCoords[] = {0.f, 1.f, 1.f, 1.f, 0.f, 0.f, 1.f, 0.f};

uint32_t compileShader(GLenum type, const std::string& source, std::string* error) {
    GLuint shader = glCreateShader(type);
    const char* src = source.c_str();
    glShaderSource(shader, 1, &src, nullptr);
    glCompileShader(shader);
    GLint ok = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[1024] = {0};
        glGetShaderInfoLog(shader, sizeof(log) - 1, nullptr, log);
        if (error) *error = std::string(type == GL_VERTEX_SHADER ? "vertex: " : "fragment: ") + log;
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

void setTextureParams(GLint filter) {
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
}

}  // namespace

GlRenderer::~GlRenderer() {
    // GL objects can only be released with a current context; the owner must
    // call shutdown() explicitly. Here we only drop bookkeeping.
}

bool GlRenderer::buildProgram(const std::string& vs, const std::string& fs, Program* out, std::string* error) {
    GLuint v = compileShader(GL_VERTEX_SHADER, vs, error);
    if (!v) return false;
    GLuint f = compileShader(GL_FRAGMENT_SHADER, fs, error);
    if (!f) {
        glDeleteShader(v);
        return false;
    }
    GLuint program = glCreateProgram();
    glAttachShader(program, v);
    glAttachShader(program, f);
    glBindAttribLocation(program, 0, "aPosition");
    glBindAttribLocation(program, 1, "aTexCoord");
    glLinkProgram(program);
    glDeleteShader(v);
    glDeleteShader(f);
    GLint ok = GL_FALSE;
    glGetProgramiv(program, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[1024] = {0};
        glGetProgramInfoLog(program, sizeof(log) - 1, nullptr, log);
        if (error) *error = std::string("link: ") + log;
        glDeleteProgram(program);
        return false;
    }
    Program p;
    p.id = program;
    p.aPosition = 0;
    p.aTexCoord = 1;
    p.uFrame = glGetUniformLocation(program, "uFrame");
    p.uPrevFrame = glGetUniformLocation(program, "uPrevFrame");
    p.uSourceSize = glGetUniformLocation(program, "uSourceSize");
    p.uOutputSize = glGetUniformLocation(program, "uOutputSize");
    p.uStrength = glGetUniformLocation(program, "uStrength");
    p.uTexture = glGetUniformLocation(program, "uTexture");
    p.uOpacity = glGetUniformLocation(program, "uOpacity");
    *out = p;
    return true;
}

bool GlRenderer::initialize(std::string* error) {
    if (initialized_) return true;
    std::string err;
    if (!buildProgram(mainVertexShader(), fallbackFragmentShader(), &fallback_, &err) ||
        !buildProgram(mainVertexShader(), blitFragmentShader(), &blit_, &err) ||
        !buildProgram(mainVertexShader(), ambientFragmentShader(), &ambient_, &err)) {
        if (error) *error = "base shaders failed: " + err;
        AX_LOGE(kTag, "Base shader compilation failed: %s", err.c_str());
        shutdown();
        return false;
    }
    glGenTextures(2, frameTex_);
    for (uint32_t tex : frameTex_) {
        glBindTexture(GL_TEXTURE_2D, tex);
        setTextureParams(GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, frameWidth_, frameHeight_, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    }
    appliedFilter_ = GL_NEAREST;
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    initialized_ = true;
    hasFrame_ = false;
    const char* renderer = reinterpret_cast<const char*>(glGetString(GL_RENDERER));
    const char* version = reinterpret_cast<const char*>(glGetString(GL_VERSION));
    AX_LOGI(kTag, "GL ready: %s / %s", renderer ? renderer : "?", version ? version : "?");
    return true;
}

void GlRenderer::shutdown() {
    for (auto& entry : mainPrograms_) {
        if (entry.second.id) glDeleteProgram(entry.second.id);
    }
    mainPrograms_.clear();
    for (Program* p : {&fallback_, &blit_, &ambient_}) {
        if (p->id) glDeleteProgram(p->id);
        *p = Program{};
    }
    if (frameTex_[0]) glDeleteTextures(2, frameTex_);
    frameTex_[0] = frameTex_[1] = 0;
    if (ambientFbo_) glDeleteFramebuffers(1, &ambientFbo_);
    if (ambientTex_) glDeleteTextures(1, &ambientTex_);
    ambientFbo_ = ambientTex_ = 0;
    initialized_ = false;
    hasFrame_ = false;
    appliedFilter_ = -1;
}

void GlRenderer::setConfig(const VideoConfig& config) {
    config_ = config;
    config_.scanlineStrength = std::clamp(config_.scanlineStrength, 0.0f, 1.0f);
    config_.gridStrength = std::clamp(config_.gridStrength, 0.0f, 1.0f);
    config_.sharpenStrength = std::clamp(config_.sharpenStrength, 0.0f, 2.0f);
    config_.frameBlendAmount = std::clamp(config_.frameBlendAmount, 0.0f, 1.0f);
}

void GlRenderer::uploadFrame(const uint32_t* pixels, int width, int height, int stridePixels) {
    if (!initialized_ || !pixels || width <= 0 || height <= 0) return;
    if (config_.frameBlending) {
        current_ ^= 1;  // previous frame stays in the other texture
    }
    if (width != frameWidth_ || height != frameHeight_) {
        frameWidth_ = width;
        frameHeight_ = height;
        for (uint32_t tex : frameTex_) {
            glBindTexture(GL_TEXTURE_2D, tex);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        }
        hasFrame_ = false;
    }
    auto uploadInto = [&](uint32_t tex) {
        glBindTexture(GL_TEXTURE_2D, tex);
        if (stridePixels == width) {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            return;
        }
        for (int y = 0; y < height; ++y) {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, y, width, 1, GL_RGBA, GL_UNSIGNED_BYTE,
                            pixels + static_cast<size_t>(y) * stridePixels);
        }
    };
    uploadInto(frameTex_[current_]);
    if (!hasFrame_) {
        // First frame: make the "previous" texture identical so blending
        // does not fade in from black.
        uploadInto(frameTex_[current_ ^ 1]);
    }
    hasFrame_ = true;
}

const GlRenderer::Program* GlRenderer::mainProgram() {
    uint32_t key = shaderFeatureKey(config_);
    auto it = mainPrograms_.find(key);
    if (it != mainPrograms_.end()) {
        return it->second.id ? &it->second : &fallback_;
    }
    Program p;
    std::string err;
    if (!buildProgram(mainVertexShader(), mainFragmentShader(key), &p, &err)) {
        AX_LOGE(kTag, "Feature shader 0x%x failed, using plain output: %s", key, err.c_str());
        degraded_ = true;
        mainPrograms_[key] = Program{};  // remember the failure
        return &fallback_;
    }
    mainPrograms_[key] = p;
    return &mainPrograms_[key];
}

void GlRenderer::applyFrameFilter() {
    GLint filter = config_.filter == Filter::Nearest ? GL_NEAREST : GL_LINEAR;
    if (filter == appliedFilter_) return;
    for (uint32_t tex : frameTex_) {
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
    }
    appliedFilter_ = filter;
}

void GlRenderer::drawQuad(const Program& program, const float* positions, const float* texCoords) {
    glEnableVertexAttribArray(static_cast<GLuint>(program.aPosition));
    glEnableVertexAttribArray(static_cast<GLuint>(program.aTexCoord));
    glVertexAttribPointer(static_cast<GLuint>(program.aPosition), 2, GL_FLOAT, GL_FALSE, 0, positions);
    glVertexAttribPointer(static_cast<GLuint>(program.aTexCoord), 2, GL_FLOAT, GL_FALSE, 0, texCoords);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

void GlRenderer::ensureAmbientTarget() {
    if (ambientFbo_) return;
    glGenTextures(1, &ambientTex_);
    glBindTexture(GL_TEXTURE_2D, ambientTex_);
    setTextureParams(GL_LINEAR);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, kAmbientWidth, kAmbientHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glGenFramebuffers(1, &ambientFbo_);
    glBindFramebuffer(GL_FRAMEBUFFER, ambientFbo_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, ambientTex_, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        AX_LOGW(kTag, "Ambient framebuffer incomplete; ambient background disabled");
        glDeleteFramebuffers(1, &ambientFbo_);
        glDeleteTextures(1, &ambientTex_);
        ambientFbo_ = ambientTex_ = 0;
    }
}

void GlRenderer::renderAmbient(int targetWidth, int targetHeight, int boundFramebuffer) {
    ensureAmbientTarget();
    if (!ambientFbo_) return;

    // 1) Blur the frame into a tiny texture.
    glBindFramebuffer(GL_FRAMEBUFFER, ambientFbo_);
    glViewport(0, 0, kAmbientWidth, kAmbientHeight);
    glUseProgram(ambient_.id);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, frameTex_[current_]);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glUniform1i(ambient_.uFrame, 0);
    glUniform2f(ambient_.uSourceSize, static_cast<float>(frameWidth_), static_cast<float>(frameHeight_));
    drawQuad(ambient_, kQuadPositions, kQuadTexCoords);
    appliedFilter_ = -1;  // frame filter was changed above

    // 2) Stretch it over the whole target ("cover": crop, never distort).
    glBindFramebuffer(GL_FRAMEBUFFER, static_cast<GLuint>(boundFramebuffer));
    glViewport(0, 0, targetWidth, targetHeight);
    float targetAspect = static_cast<float>(targetWidth) / static_cast<float>(targetHeight);
    float sourceAspect = static_cast<float>(kAmbientWidth) / static_cast<float>(kAmbientHeight);
    float u0 = 0.f, u1 = 1.f, v0 = 0.f, v1 = 1.f;
    if (targetAspect > sourceAspect) {
        float visible = sourceAspect / targetAspect;  // fraction of height shown
        v0 = 0.5f - visible * 0.5f;
        v1 = 0.5f + visible * 0.5f;
    } else {
        float visible = targetAspect / sourceAspect;
        u0 = 0.5f - visible * 0.5f;
        u1 = 0.5f + visible * 0.5f;
    }
    // The ambient texture was rendered upright in GL orientation.
    const float tex[] = {u0, v0, u1, v0, u0, v1, u1, v1};
    glUseProgram(blit_.id);
    glBindTexture(GL_TEXTURE_2D, ambientTex_);
    glUniform1i(blit_.uTexture, 0);
    glUniform1f(blit_.uOpacity, 1.0f);
    drawQuad(blit_, kQuadPositions, tex);
}

void GlRenderer::render(int targetWidth, int targetHeight) {
    if (!initialized_ || targetWidth <= 0 || targetHeight <= 0) return;
    GLint bound = 0;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &bound);

    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_BLEND);
    glViewport(0, 0, targetWidth, targetHeight);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
    if (!hasFrame_) return;

    if (config_.background == Background::Ambient && config_.scaleMode != ScaleMode::Stretch) {
        renderAmbient(targetWidth, targetHeight, bound);
    }

    Rect vp = computeViewport(config_.scaleMode, targetWidth, targetHeight, frameWidth_, frameHeight_);
    lastViewport_ = vp;
    int glY = targetHeight - vp.y - vp.height;
    glViewport(vp.x, glY, vp.width, vp.height);

    applyFrameFilter();
    const Program* program = mainProgram();
    glUseProgram(program->id);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, frameTex_[current_]);
    glUniform1i(program->uFrame, 0);
    if (program->uPrevFrame >= 0) {
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, frameTex_[current_ ^ 1]);
        glUniform1i(program->uPrevFrame, 1);
        glActiveTexture(GL_TEXTURE0);
    }
    if (program->uSourceSize >= 0) {
        glUniform2f(program->uSourceSize, static_cast<float>(frameWidth_), static_cast<float>(frameHeight_));
    }
    if (program->uOutputSize >= 0) {
        glUniform2f(program->uOutputSize, static_cast<float>(vp.width), static_cast<float>(vp.height));
    }
    if (program->uStrength >= 0) {
        glUniform4f(program->uStrength, config_.scanlineStrength, config_.gridStrength, config_.sharpenStrength,
                    config_.frameBlendAmount);
    }
    drawQuad(*program, kQuadPositions, kQuadTexCoords);

    if (!overlays_.empty()) {
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glUseProgram(blit_.id);
        glUniform1i(blit_.uTexture, 0);
        float sx = 2.0f / static_cast<float>(frameWidth_);
        float sy = 2.0f / static_cast<float>(frameHeight_);
        for (const OverlayQuad& q : overlays_) {
            if (!q.texture) continue;
            float x0 = -1.f + q.x * sx;
            float x1 = -1.f + (q.x + q.width) * sx;
            float y0 = 1.f - (q.y + q.height) * sy;  // bottom
            float y1 = 1.f - q.y * sy;               // top
            const float pos[] = {x0, y0, x1, y0, x0, y1, x1, y1};
            float u0 = q.flipH ? 1.f : 0.f, u1 = q.flipH ? 0.f : 1.f;
            float vTop = q.flipV ? 1.f : 0.f, vBottom = q.flipV ? 0.f : 1.f;
            const float tex[] = {u0, vBottom, u1, vBottom, u0, vTop, u1, vTop};
            glBindTexture(GL_TEXTURE_2D, q.texture);
            glUniform1f(blit_.uOpacity, q.opacity);
            drawQuad(blit_, pos, tex);
        }
        glDisable(GL_BLEND);
    }
}

uint32_t GlRenderer::createTexture(const uint32_t* rgba, int width, int height, bool smooth) {
    if (!initialized_ || !rgba || width <= 0 || height <= 0) return 0;
    GLuint tex = 0;
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    setTextureParams(smooth ? GL_LINEAR : GL_NEAREST);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    return tex;
}

void GlRenderer::deleteTexture(uint32_t texture) {
    if (texture) {
        GLuint t = texture;
        glDeleteTextures(1, &t);
    }
}

}  // namespace ax::video
