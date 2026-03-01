cmake_minimum_required(VERSION 3.20)
project(meshapp-ble LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Static CRT — makes DLL self-contained, no VCRUNTIME dependency
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

add_library(meshapp-ble SHARED
    meshapp_ble.cpp
    meshapp_ble.h
)

target_compile_definitions(meshapp-ble PRIVATE
    MESHBLE_EXPORTS
    WINRT_LEAN_AND_MEAN
    WIN32_LEAN_AND_MEAN
    NOMINMAX
    _CRT_SECURE_NO_WARNINGS
)

# Link WinRT umbrella library
target_link_libraries(meshapp-ble PRIVATE
    windowsapp
    runtimeobject
)

if(MSVC)
    target_compile_options(meshapp-ble PRIVATE /EHsc /W3)
endif()

install(TARGETS meshapp-ble RUNTIME DESTINATION bin)
