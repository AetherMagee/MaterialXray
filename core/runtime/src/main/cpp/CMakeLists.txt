cmake_minimum_required(VERSION 3.22.1)

project(xray_launcher)

add_library(xray_launcher SHARED xray_launcher.c)

target_link_libraries(xray_launcher log)

# Root mode execs this from nativeLibraryDir, where the installer only extracts lib*.so files.
add_executable(xrayroot xray_root_exec.c)
set_target_properties(xrayroot PROPERTIES OUTPUT_NAME "libxrayroot.so")
