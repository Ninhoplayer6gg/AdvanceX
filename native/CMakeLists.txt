# SPDX-License-Identifier: MPL-2.0
#
# AdvanceX native code.
#
# Library layering (each arrow is the only allowed dependency direction):
#
#   ax_jni (Android only) ──► ax_runtime ──► ax_engine ──► ax_core ──► mgba
#                                │              │
#                                ├──► ax_video  └──► ax_common
#                                └──► ax_audio
#
# ax_core never links ax_engine: the emulator works without the Advance Engine.
cmake_minimum_required(VERSION 3.22)
project(AdvanceXNative VERSION 0.1.0 LANGUAGES C CXX ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(ANDROID)
    # Only JNI_OnLoad (marked JNIEXPORT) is exported from libadvancex.so.
    set(CMAKE_C_VISIBILITY_PRESET hidden)
    set(CMAKE_CXX_VISIBILITY_PRESET hidden)
    set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
endif()

option(AX_BUILD_TESTS "Build host unit/integration tests" OFF)
option(AX_WITH_GLES "Build the OpenGL ES video pipeline" ON)

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

set(AX_WARNINGS -Wall -Wextra -Wno-unused-parameter)

# ---------------------------------------------------------------------------
# mGBA (MPL-2.0) — built as a minimal static library: GBA core only, no
# frontends, no optional dependencies, no debugger or scripting.
# ---------------------------------------------------------------------------
set(MGBA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/mgba")
if(NOT EXISTS "${MGBA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "mGBA sources not found in ${MGBA_DIR}. Run: git submodule update --init --recursive")
endif()

set(LIBMGBA_ONLY ON CACHE BOOL "" FORCE)
set(M_CORE_GBA ON CACHE BOOL "" FORCE)
set(M_CORE_GB OFF CACHE BOOL "" FORCE)
foreach(opt USE_DEBUGGERS USE_GDB_STUB USE_EDITLINE ENABLE_SCRIPTING USE_ELF USE_ZLIB USE_PNG USE_LZMA
            USE_SQLITE3 USE_LIBZIP USE_MINIZIP USE_LUA USE_FFMPEG USE_EPOXY USE_JSON_C USE_DISCORD_RPC
            BUILD_GL BUILD_GLES2 BUILD_GLES3 BUILD_LTO)
    set(${opt} OFF CACHE BOOL "" FORCE)
endforeach()
add_subdirectory("${MGBA_DIR}" mgba EXCLUDE_FROM_ALL)
# mGBA is third-party code: keep its own warning policy out of our logs.
target_compile_options(mgba PRIVATE -w)

find_package(ZLIB REQUIRED)

# ---------------------------------------------------------------------------
# AdvanceX libraries
# ---------------------------------------------------------------------------
add_library(ax_common STATIC
    common/src/log.cpp
    common/src/file_io.cpp
    common/src/hash.cpp
)
target_include_directories(ax_common PUBLIC common/include)
target_link_libraries(ax_common PUBLIC ZLIB::ZLIB)
target_compile_options(ax_common PRIVATE ${AX_WARNINGS})
if(ANDROID)
    target_link_libraries(ax_common PUBLIC log)
endif()

add_library(ax_core STATIC
    core/src/memory_map.cpp
    core/src/mgba_core.cpp
)
target_include_directories(ax_core PUBLIC core/include)
target_link_libraries(ax_core PUBLIC ax_common PRIVATE mgba)
target_compile_options(ax_core PRIVATE ${AX_WARNINGS})

add_library(ax_video STATIC
    video/src/viewport.cpp
    video/src/shader_builder.cpp
    video/src/gl_renderer.cpp
    video/src/egl_context.cpp
)
target_include_directories(ax_video PUBLIC video/include)
target_link_libraries(ax_video PUBLIC ax_common)
target_compile_options(ax_video PRIVATE ${AX_WARNINGS})
if(ANDROID)
    target_link_libraries(ax_video PUBLIC EGL GLESv2)
else()
    find_library(AX_EGL_LIB EGL REQUIRED)
    find_library(AX_GLES2_LIB GLESv2 REQUIRED)
    target_link_libraries(ax_video PUBLIC ${AX_EGL_LIB} ${AX_GLES2_LIB})
endif()

add_library(ax_audio STATIC
    audio/src/audio_mixer.cpp
    audio/src/null_output.cpp
    audio/src/aaudio_output.cpp
    audio/src/opensl_output.cpp
    audio/src/output_factory.cpp
)
target_include_directories(ax_audio PUBLIC audio/include)
target_link_libraries(ax_audio PUBLIC ax_common)
target_compile_options(ax_audio PRIVATE ${AX_WARNINGS})
if(ANDROID)
    target_link_libraries(ax_audio PUBLIC aaudio OpenSLES)
endif()

add_library(ax_engine STATIC
    engine/src/patch_engine.cpp
    engine/src/rewind_buffer.cpp
    engine/src/sprite_detector.cpp
)
target_include_directories(ax_engine PUBLIC engine/include)
# The engine only sees the core *interfaces* (MemoryBus); it never links mGBA.
target_include_directories(ax_engine PUBLIC core/include)
target_link_libraries(ax_engine PUBLIC ax_common)
target_compile_options(ax_engine PRIVATE ${AX_WARNINGS})

add_library(ax_runtime STATIC
    runtime/src/session.cpp
    runtime/src/state_file.cpp
    runtime/src/background_writer.cpp
    runtime/src/frame_mailbox.cpp
    runtime/src/crash_handler.cpp
    runtime/src/patch_blob.cpp
)
target_include_directories(ax_runtime PUBLIC runtime/include)
target_link_libraries(ax_runtime PUBLIC ax_core ax_engine ax_audio ax_common)
find_package(Threads REQUIRED)
target_link_libraries(ax_runtime PUBLIC Threads::Threads)
target_compile_options(ax_runtime PRIVATE ${AX_WARNINGS})

# ---------------------------------------------------------------------------
# Android shared library loaded by the app (libadvancex.so)
# ---------------------------------------------------------------------------
if(ANDROID)
    add_library(advancex SHARED
        jni/advancex_jni.cpp
        jni/render_thread.cpp
    )
    target_link_libraries(advancex PRIVATE ax_runtime ax_video android log)
    target_compile_definitions(advancex PRIVATE AX_VERSION="${PROJECT_VERSION}")
    target_compile_options(advancex PRIVATE ${AX_WARNINGS})
    # Keep only JNI_OnLoad exported; everything else is reached via
    # RegisterNatives. Smaller binary, fewer symbol clashes.
    target_link_options(advancex PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
endif()

option(AX_BUILD_TOOLS "Build developer tools (ax_tool)" OFF)
if(AX_BUILD_TOOLS OR AX_BUILD_TESTS)
    if(NOT ANDROID)
        add_executable(ax_tool tools/ax_tool.cpp)
        target_link_libraries(ax_tool PRIVATE ax_runtime)
        target_compile_options(ax_tool PRIVATE ${AX_WARNINGS})
    endif()
endif()

if(AX_BUILD_TESTS)
    enable_testing()
    add_subdirectory(tests)
endif()
