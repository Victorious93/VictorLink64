cmake_minimum_required(VERSION 3.18)
project(vl64snes C)
add_library(vl64snes SHARED snesfront.c)
target_link_libraries(vl64snes android log dl)

# Classic Android check: tiny 32-bit proof library, built for armeabi-v7a only.
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    add_library(vl64classic32 SHARED classic32.c)
endif()
