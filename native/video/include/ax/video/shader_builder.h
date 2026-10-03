// SPDX-License-Identifier: MPL-2.0
// Generates the GLSL ES 1.00 programs used by the video pipeline.
//
// All shaders target GLSL ES 1.00 so they run on every OpenGL ES 2.0+ GPU,
// including the low-end Mali/Adreno/PowerVR parts found in budget phones.
#pragma once

#include <cstdint>
#include <string>

#include "ax/video/video_config.h"

namespace ax::video {

/// Bitmask describing which features are compiled into the main shader.
uint32_t shaderFeatureKey(const VideoConfig& config);

std::string mainVertexShader();
std::string mainFragmentShader(uint32_t featureKey);

/// Simple textured quad with opacity (overlays, ambient upscale).
std::string blitFragmentShader();
/// 5x5 wide box blur used to build the ambient background.
std::string ambientFragmentShader();

/// Minimal last-resort shader used if a feature shader fails to compile.
std::string fallbackFragmentShader();

}  // namespace ax::video
