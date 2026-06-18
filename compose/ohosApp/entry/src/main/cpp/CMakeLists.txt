# the minimum version of CMake.
cmake_minimum_required(VERSION 3.4.1)
project(HarmonyDemo)

set(NATIVERENDER_ROOT_PATH ${CMAKE_CURRENT_SOURCE_DIR})

if (DEFINED PACKAGE_FIND_FILE)
    include(${PACKAGE_FIND_FILE})
endif ()

include_directories(${NATIVERENDER_ROOT_PATH}
        ${NATIVERENDER_ROOT_PATH}/include)

set(KUIKLY_RENDER_ROOT ${NATIVERENDER_ROOT_PATH}/../../../../oh_modules/.ohpm/oh_modules/@kuikly-open/render)
if (NOT EXISTS ${KUIKLY_RENDER_ROOT}/include)
    file(GLOB KUIKLY_RENDER_ROOT_CANDIDATES
            ${NATIVERENDER_ROOT_PATH}/../../../../oh_modules/.ohpm/@kuikly-open+render@*/oh_modules/@kuikly-open/render)
    if (KUIKLY_RENDER_ROOT_CANDIDATES)
        list(GET KUIKLY_RENDER_ROOT_CANDIDATES 0 KUIKLY_RENDER_ROOT)
    endif ()
endif ()

add_library(kuikly_render SHARED IMPORTED)
set_target_properties(kuikly_render
        PROPERTIES
        IMPORTED_LOCATION ${KUIKLY_RENDER_ROOT}/libs/${OHOS_ARCH}/libkuikly.so)
target_include_directories(kuikly_render
        INTERFACE
        ${KUIKLY_RENDER_ROOT}/include)

add_library(kuikly_shared SHARED IMPORTED)
set_target_properties(kuikly_shared
        PROPERTIES
        IMPORTED_LOCATION ${NATIVERENDER_ROOT_PATH}/../../../libs/${OHOS_ARCH}/libshared.so)

add_library(entry SHARED napi_init.cpp)
target_link_libraries(entry PUBLIC libace_napi.z.so libhilog_ndk.z.so kuikly_shared kuikly_render)
