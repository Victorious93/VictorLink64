cmake_minimum_required(VERSION 3.18)
project(vl64snes C)
add_library(vl64snes SHARED snesfront.c)
target_link_libraries(vl64snes android log dl)
# Victorious Framework: GL ES libretro frontend for the PSP (PPSSPP) and GameCube (Dolphin) cores.
add_library(vl64hw SHARED hwfront.c)
target_link_libraries(vl64hw android log dl EGL GLESv3)
