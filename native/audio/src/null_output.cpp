// SPDX-License-Identifier: MPL-2.0
#include "null_output.h"

namespace ax::audio {

bool NullOutput::open(const OutputConfig& config, AudioSource*, std::string*) {
    sampleRate_ = config.preferredSampleRate > 0 ? config.preferredSampleRate : 48000;
    return true;
}

}  // namespace ax::audio
