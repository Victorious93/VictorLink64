cmake_minimum_required(VERSION 3.18)
project(vl64snes C)
# Victorious Framework: the SNES frontend can draw through the shared GL pass (glpass.c), so it links EGL/GLES too.
add_library(vl64snes SHARED snesfront.c glpass.c gfxopts.c)
target_link_libraries(vl64snes android log dl EGL GLESv3)
# Victorious Framework: GL ES libretro frontend for the PSP (PPSSPP) and GameCube (Dolphin) cores.
add_library(vl64hw SHARED hwfront.c glpass.c gfxopts.c)
target_link_libraries(vl64hw android log dl EGL GLESv3)

# Classic Android check: tiny 32-bit proof library, built for armeabi-v7a only.
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    add_library(vl64classic32 SHARED classic32.c)
endif()
