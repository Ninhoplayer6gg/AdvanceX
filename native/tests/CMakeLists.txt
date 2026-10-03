# SPDX-License-Identifier: MPL-2.0
add_executable(ax_tests
    test_main.cpp
    test_core.cpp
)
target_link_libraries(ax_tests PRIVATE ax_core)
target_compile_options(ax_tests PRIVATE ${AX_WARNINGS})

set(AX_TEST_ASSETS "${CMAKE_CURRENT_SOURCE_DIR}/../../tools/testrom/dist")
add_test(NAME ax_tests COMMAND ax_tests --assets "${AX_TEST_ASSETS}" --out "${CMAKE_CURRENT_BINARY_DIR}/out")
