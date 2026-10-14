cmake_minimum_required(VERSION 3.18)
project(vl64snes C)
add_library(vl64snes SHARED snesfront.c)
target_link_libraries(vl64snes android log dl)
