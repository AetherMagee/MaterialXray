cmake_minimum_required(VERSION 3.22.1)

project(xray_launcher)

add_library(xray_launcher SHARED xray_launcher.c)

target_link_libraries(xray_launcher log)

# Root mode execs this from nativeLibraryDir, where the installer only extracts lib*.so files.
add_executable(xraytun xray_tun_exec.c)
set_target_properties(xraytun PROPERTIES OUTPUT_NAME "libxraytun.so")
