# the minimum version of CMake.
cmake_minimum_required(VERSION 3.5.0)
project(mmkv_c_wrapper)

set(NATIVERENDER_ROOT_PATH ${CMAKE_CURRENT_SOURCE_DIR})

include_directories(${NATIVERENDER_ROOT_PATH}
                    ${NATIVERENDER_ROOT_PATH}/include)

add_library(mmkv_c_wrapper SHARED
    mmkv_c_wrapper.cpp
    include/mmkv_c_wrapper.h)

# Add these lines
set(MODULE_ROOT_PATH ${CMAKE_CURRENT_SOURCE_DIR}/../../..)

# project/entry/oh_modules/@tencent/mmkv
set(MMKV_MODULE_PATH ${MODULE_ROOT_PATH}/oh_modules/@tencent/mmkv)
# project/entry/oh_modules/@tencent/mmkv/include
set(MMKV_INCLUDE_PATH ${MMKV_MODULE_PATH}/include)
# project/entry/oh_modules/@tencent/mmkv/libs/arm64-v8a
set(MMKV_LIB_PATH ${MMKV_MODULE_PATH}/libs/${OHOS_ARCH})

# setup mmkv include path
target_include_directories(mmkv_c_wrapper PRIVATE ${MMKV_INCLUDE_PATH})

# setup mmkv lib path
add_library(mmkv SHARED IMPORTED)
if (EXISTS ${MMKV_LIB_PATH}/libmmkv.so)
    set_target_properties(mmkv PROPERTIES IMPORTED_LOCATION ${MMKV_LIB_PATH}/libmmkv.so)
else()
    message(FATAL_ERROR "Can't find libmmkv.so in @tencent/mmkv module:${MMKV_MODULE_PATH}, check if \"ohpm install @tencent/mmkv\" fail!")
endif()
target_link_libraries(mmkv_c_wrapper PRIVATE mmkv)
