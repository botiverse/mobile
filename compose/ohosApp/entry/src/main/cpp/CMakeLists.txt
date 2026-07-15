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

# NetworkKMM OHOS runtime (task #6): libcurl wrapper + openssl consumed by the
# shared KMP transport. Synced into libs/ via ./gradlew copyNetworkOhosRuntimeLibs.
add_library(pbcurlwrapper SHARED IMPORTED)
set_target_properties(pbcurlwrapper
        PROPERTIES
        IMPORTED_LOCATION ${NATIVERENDER_ROOT_PATH}/../../../libs/${OHOS_ARCH}/libpbcurlwrapper.so)

add_library(openssl SHARED IMPORTED)
set_target_properties(openssl
        PROPERTIES
        IMPORTED_LOCATION ${NATIVERENDER_ROOT_PATH}/../../../libs/${OHOS_ARCH}/libopenssl.so)

# KmmResource native bridge (task #19): implements the ohos_* resource C API
# that libshared.so's resource-core KLIB declares (DT_NEEDED). Committed
# binary from KuiklyBase-components KmmResource@928fdd2 (same commit as its
# source resource_compose.cpp), sha256 99680ee047ec56dae7ae3646b4f6bfbbf1bbaa74951dbcb3bc9aea9409a32865.
add_library(resource_compose SHARED IMPORTED)
set_target_properties(resource_compose
        PROPERTIES
        IMPORTED_LOCATION ${NATIVERENDER_ROOT_PATH}/../../../libs/${OHOS_ARCH}/libresource_compose.so)

add_library(entry SHARED
        napi_init.cpp
        SlockOhosImageAdapter.cpp)
target_link_libraries(entry PUBLIC
        libace_napi.z.so
        libace_ndk.z.so
        libhilog_ndk.z.so
        libpixelmap.so
        libimage_source.so
        kuikly_shared
        kuikly_render
        pbcurlwrapper
        openssl
        resource_compose)
