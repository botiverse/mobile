# the minimum version of CMake.
cmake_minimum_required(VERSION 3.5.0)
project(mmkv_c_wrapper)

set(NATIVERENDER_ROOT_PATH ${CMAKE_CURRENT_SOURCE_DIR})

include_directories(${NATIVERENDER_ROOT_PATH}
                    ${NATIVERENDER_ROOT_PATH}/include)

add_library(mmkv_c_wrapper SHARED
    mmkv_c_wrapper.cpp
    include/mmkv_c_wrapper.h)

set(MODULE_ROOT_PATH ${CMAKE_CURRENT_SOURCE_DIR}/../../..)
set(PROJECT_ROOT_PATH ${MODULE_ROOT_PATH}/..)

set(MMKV_CANDIDATE_PATHS
    ${MODULE_ROOT_PATH}/oh_modules/@tencent/mmkv
    ${MODULE_ROOT_PATH}/oh_modules/.ohpm/oh_modules/@tencent/mmkv
    ${PROJECT_ROOT_PATH}/oh_modules/@tencent/mmkv
    ${PROJECT_ROOT_PATH}/oh_modules/.ohpm/oh_modules/@tencent/mmkv)

foreach(CANDIDATE_PATH ${MMKV_CANDIDATE_PATHS})
    if (EXISTS ${CANDIDATE_PATH}/libs/${OHOS_ARCH}/libmmkv.so)
        set(MMKV_MODULE_PATH ${CANDIDATE_PATH})
        break()
    endif()
endforeach()

if (NOT MMKV_MODULE_PATH)
    message(FATAL_ERROR "Can't find @tencent/mmkv module. Checked: ${MMKV_CANDIDATE_PATHS}")
endif()

set(MMKV_INCLUDE_PATH ${MMKV_MODULE_PATH}/include)
set(MMKV_LIB_PATH ${MMKV_MODULE_PATH}/libs/${OHOS_ARCH})

target_include_directories(mmkv_c_wrapper PRIVATE ${MMKV_INCLUDE_PATH})

add_library(mmkv SHARED IMPORTED)
set_target_properties(mmkv PROPERTIES IMPORTED_LOCATION ${MMKV_LIB_PATH}/libmmkv.so)
target_link_libraries(mmkv_c_wrapper PRIVATE mmkv)
