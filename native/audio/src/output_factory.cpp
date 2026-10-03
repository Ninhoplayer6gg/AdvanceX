// SPDX-License-Identifier: MPL-2.0
#include "ax/audio/audio_output.h"
#include "null_output.h"

#if defined(__ANDROID__)
#include <android/api-level.h>
#endif

namespace ax::audio {

#if defined(__ANDROID__)
std::unique_ptr<AudioOutput> createAAudioOutput();
std::unique_ptr<AudioOutput> createOpenSlOutput();
#endif

std::unique_ptr<AudioOutput> createAudioOutput(Backend backend) {
#if defined(__ANDROID__)
    switch (backend) {
        case Backend::AAudio:
            return createAAudioOutput();
        case Backend::OpenSLES:
            return createOpenSlOutput();
        case Backend::Null:
            return std::make_unique<NullOutput>();
        case Backend::Auto:
            // AAudio on 8.0 (API 26) had known issues; prefer OpenSL ES there.
            return android_get_device_api_level() >= 27 ? createAAudioOutput() : createOpenSlOutput();
    }
    return std::make_unique<NullOutput>();
#else
    (void) backend;
    return std::make_unique<NullOutput>();
#endif
}

}  // namespace ax::audio
