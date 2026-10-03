// SPDX-License-Identifier: MPL-2.0
// Tiny self-contained test framework for the native host tests.
// (Avoids pulling a test dependency into the build.)
#pragma once

#include <cstdio>
#include <functional>
#include <string>
#include <vector>

namespace axtest {

struct TestCase {
    const char* name;
    std::function<void()> fn;
};

inline std::vector<TestCase>& registry() {
    static std::vector<TestCase> tests;
    return tests;
}

struct Registrar {
    Registrar(const char* name, std::function<void()> fn) { registry().push_back({name, std::move(fn)}); }
};

struct Failure {
    std::string message;
};

inline std::string& assetsDir() {
    static std::string dir;
    return dir;
}

inline std::string& outputDir() {
    static std::string dir;
    return dir;
}

}  // namespace axtest

#define AX_CONCAT_INNER(a, b) a##b
#define AX_CONCAT(a, b) AX_CONCAT_INNER(a, b)

#define AX_TEST(name)                                                              \
    static void name();                                                            \
    static ::axtest::Registrar AX_CONCAT(registrar_, name)(#name, name);           \
    static void name()

#define AX_EXPECT(cond)                                                                       \
    do {                                                                                      \
        if (!(cond)) {                                                                        \
            char buf[512];                                                                    \
            std::snprintf(buf, sizeof(buf), "%s:%d: expectation failed: %s", __FILE__, __LINE__, #cond); \
            throw ::axtest::Failure{buf};                                                     \
        }                                                                                     \
    } while (0)

#define AX_EXPECT_EQ(a, b)                                                                        \
    do {                                                                                          \
        auto _va = (a);                                                                           \
        auto _vb = (b);                                                                           \
        if (!(_va == _vb)) {                                                                      \
            char buf[512];                                                                        \
            std::snprintf(buf, sizeof(buf), "%s:%d: expected %s == %s (%lld vs %lld)", __FILE__, __LINE__, #a, #b, \
                          static_cast<long long>(_va), static_cast<long long>(_vb));              \
            throw ::axtest::Failure{buf};                                                         \
        }                                                                                         \
    } while (0)
