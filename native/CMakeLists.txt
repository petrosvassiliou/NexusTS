cmake_minimum_required(VERSION 3.22.1)

project(nexusts_native LANGUAGES C CXX VERSION 1.1.0)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(NEXUSTS_ENABLE_FREERDP3 "Link against prebuilt FreeRDP 3.x libraries when staged" ON)
option(NEXUSTS_ENABLE_H264_MEDIACODEC "Enable hardware H.264/AVC444 decoding (AMediaCodec / VideoToolbox)" ON)

if(ANDROID)
    # Enforce 16KB ELF segment alignment for Android 15/16+ & Googlebook OS kernels
    add_link_options(
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
        "-Wl,--build-id=sha1"
    )

    find_library(ANDROID_LIB android REQUIRED)
    find_library(JNIGRAPHICS_LIB jnigraphics REQUIRED)
    find_library(LOG_LIB log REQUIRED)
    find_library(MEDIANDK_LIB mediandk REQUIRED)
    find_library(AAUDIO_LIB aaudio REQUIRED)

    set(FREERDP3_PREBUILT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/third_party/freerdp3/${ANDROID_ABI}")
    if(NEXUSTS_ENABLE_FREERDP3 AND EXISTS "${FREERDP3_PREBUILT_ROOT}/lib/libfreerdp3.so")
        message(STATUS "NexusTS: Found prebuilt FreeRDP 3.x SDK for ${ANDROID_ABI}")
        add_library(freerdp3 SHARED IMPORTED)
        set_target_properties(freerdp3 PROPERTIES IMPORTED_LOCATION "${FREERDP3_PREBUILT_ROOT}/lib/libfreerdp3.so")

        add_library(freerdp_client3 SHARED IMPORTED)
        set_target_properties(freerdp_client3 PROPERTIES IMPORTED_LOCATION "${FREERDP3_PREBUILT_ROOT}/lib/libfreerdp-client3.so")

        add_library(winpr3 SHARED IMPORTED)
        set_target_properties(winpr3 PROPERTIES IMPORTED_LOCATION "${FREERDP3_PREBUILT_ROOT}/lib/libwinpr3.so")

        set(NEXUSTS_HAS_FREERDP3_BINARY 1)
    else()
        message(STATUS "NexusTS: Compiling FreeRDP 3.x protocol framing & DISP_DVC engine with integrated NDK transport")
        set(NEXUSTS_HAS_FREERDP3_BINARY 0)
    endif()

    add_library(
        nexusts_rdp_jni
        SHARED
        cpp/rdp/rdp_session_context.cpp
        cpp/rdp/rdp_gdi_surface.cpp
        cpp/rdp/rdp_channels.cpp
        cpp/rdp/rdp_h264_decoder.cpp
        cpp/rdp/nexusts_rdp_jni.cpp
    )

    target_include_directories(
        nexusts_rdp_jni
        PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/cpp/include
        $<$<BOOL:${NEXUSTS_HAS_FREERDP3_BINARY}>:${FREERDP3_PREBUILT_ROOT}/include/freerdp3>
        $<$<BOOL:${NEXUSTS_HAS_FREERDP3_BINARY}>:${FREERDP3_PREBUILT_ROOT}/include/winpr3>
    )

    target_compile_definitions(
        nexusts_rdp_jni
        PRIVATE
        NEXUSTS_HAS_FREERDP3_BINARY=${NEXUSTS_HAS_FREERDP3_BINARY}
        NEXUSTS_ENABLE_H264_MEDIACODEC=$<BOOL:${NEXUSTS_ENABLE_H264_MEDIACODEC}>
    )

    target_link_libraries(
        nexusts_rdp_jni
        PRIVATE
        ${ANDROID_LIB}
        ${JNIGRAPHICS_LIB}
        ${LOG_LIB}
        ${MEDIANDK_LIB}
        ${AAUDIO_LIB}
        $<$<BOOL:${NEXUSTS_HAS_FREERDP3_BINARY}>:freerdp3>
        $<$<BOOL:${NEXUSTS_HAS_FREERDP3_BINARY}>:freerdp_client3>
        $<$<BOOL:${NEXUSTS_HAS_FREERDP3_BINARY}>:winpr3>
    )
else()
    # macOS Universal2 (arm64 + x86_64) & POSIX Desktop Static C-ABI Target
    set(FREERDP3_MACOS_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/third_party/freerdp3/macos")
    if(NEXUSTS_ENABLE_FREERDP3 AND (EXISTS "${FREERDP3_MACOS_ROOT}/lib/libfreerdp3.dylib" OR EXISTS "${FREERDP3_MACOS_ROOT}/lib/libfreerdp3.a"))
        message(STATUS "NexusTS: Found prebuilt FreeRDP 3.x macOS Universal2 SDK at ${FREERDP3_MACOS_ROOT}")
        set(NEXUSTS_HAS_FREERDP3_BINARY 1)
    else()
        message(STATUS "NexusTS: Building FreeRDP 3.x macOS C-ABI session & DISP_DVC engine")
        set(NEXUSTS_HAS_FREERDP3_BINARY 0)
    endif()

    add_library(
        nexusts_rdp_macos
        STATIC
        cpp/rdp/rdp_session_context.cpp
        cpp/rdp/rdp_gdi_surface.cpp
        cpp/rdp/rdp_channels.cpp
        cpp/rdp/rdp_h264_decoder.cpp
        cpp/rdp/nexusts_rdp_macos.cpp
    )

    target_include_directories(
        nexusts_rdp_macos
        PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/cpp/include
        PRIVATE
        $<$<BOOL:${NEXUSTS_HAS_FREERDP3_BINARY}>:${FREERDP3_MACOS_ROOT}/include/freerdp3>
        $<$<BOOL:${NEXUSTS_HAS_FREERDP3_BINARY}>:${FREERDP3_MACOS_ROOT}/include/winpr3>
    )

    target_compile_definitions(
        nexusts_rdp_macos
        PRIVATE
        NEXUSTS_HAS_FREERDP3_BINARY=${NEXUSTS_HAS_FREERDP3_BINARY}
        NEXUSTS_ENABLE_H264_MEDIACODEC=$<BOOL:${NEXUSTS_ENABLE_H264_MEDIACODEC}>
    )
endif()
