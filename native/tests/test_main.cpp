// SPDX-License-Identifier: MPL-2.0
#include <cstring>
#include <exception>

#include "ax/common/log.h"
#include "test_framework.h"

int main(int argc, char** argv) {
    const char* filter = nullptr;
    for (int i = 1; i < argc; ++i) {
        if (std::strcmp(argv[i], "--assets") == 0 && i + 1 < argc) {
            axtest::assetsDir() = argv[++i];
        } else if (std::strcmp(argv[i], "--out") == 0 && i + 1 < argc) {
            axtest::outputDir() = argv[++i];
        } else {
            filter = argv[i];
        }
    }
    ax::log::setMinLevel(ax::log::Level::Warn);

    int passed = 0;
    int failed = 0;
    for (const auto& test : axtest::registry()) {
        if (filter && !std::strstr(test.name, filter)) {
            continue;
        }
        try {
            test.fn();
            std::printf("[ PASS ] %s\n", test.name);
            ++passed;
        } catch (const axtest::Failure& f) {
            std::printf("[ FAIL ] %s\n         %s\n", test.name, f.message.c_str());
            ++failed;
        } catch (const std::exception& e) {
            std::printf("[ FAIL ] %s\n         exception: %s\n", test.name, e.what());
            ++failed;
        }
    }
    std::printf("\n%d passed, %d failed\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
