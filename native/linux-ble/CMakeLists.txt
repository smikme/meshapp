cmake_minimum_required(VERSION 3.16)
project(meshapp-ble LANGUAGES C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_library(meshapp-ble SHARED
    meshapp_ble.c
    meshapp_ble.h
)

target_compile_definitions(meshapp-ble PRIVATE MESHBLE_EXPORTS)
target_compile_options(meshapp-ble PRIVATE -Wall -Wextra -fvisibility=hidden)

# Link sd-bus from libsystemd
find_package(PkgConfig REQUIRED)
pkg_check_modules(SYSTEMD REQUIRED libsystemd)
target_include_directories(meshapp-ble PRIVATE ${SYSTEMD_INCLUDE_DIRS})
target_link_libraries(meshapp-ble PRIVATE ${SYSTEMD_LIBRARIES} pthread)

install(TARGETS meshapp-ble LIBRARY DESTINATION lib)
