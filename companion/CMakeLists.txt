cmake_minimum_required(VERSION 3.25)
project(midicomp_companion VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

option(MIDICOMP_FETCH_DEPS "Download dependencies that are not installed" ON)
set(MIDICOMP_DEFAULT_FONT "" CACHE FILEPATH "TTF font loaded at runtime when MIDICOMP_FONT is unset")
set(MIDICOMP_EMBED_FONT "" CACHE FILEPATH "TTF font compiled into the binary as the fallback")
# In the Nix dev shell MIDICOMP_FONT points at a font; keep it as the default so the binary also
# finds it when started outside the shell.
if(NOT MIDICOMP_DEFAULT_FONT AND DEFINED ENV{MIDICOMP_FONT})
  set(MIDICOMP_DEFAULT_FONT "$ENV{MIDICOMP_FONT}" CACHE FILEPATH
      "TTF font loaded at runtime when MIDICOMP_FONT is unset" FORCE)
endif()

include(FetchContent)
find_package(OpenGL REQUIRED)
find_package(Threads REQUIRED)

# --- Dependencies: installed packages first (Nix, distribution packages), else pinned downloads.

find_package(glfw3 3.3 QUIET)
if(NOT glfw3_FOUND)
  if(NOT MIDICOMP_FETCH_DEPS)
    message(FATAL_ERROR "GLFW not found and MIDICOMP_FETCH_DEPS is off")
  endif()
  set(GLFW_BUILD_DOCS OFF CACHE BOOL "" FORCE)
  set(GLFW_BUILD_TESTS OFF CACHE BOOL "" FORCE)
  set(GLFW_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
  set(GLFW_INSTALL OFF CACHE BOOL "" FORCE)
  set(GLFW_BUILD_WAYLAND OFF CACHE BOOL "" FORCE)
  FetchContent_Declare(glfw
    URL https://github.com/glfw/glfw/archive/refs/tags/3.4.tar.gz
    URL_HASH SHA256=c038d34200234d071fae9345bc455e4a8f2f544ab60150765d7704e08f3dac01
    SYSTEM)
  FetchContent_MakeAvailable(glfw)
endif()

find_package(imgui CONFIG QUIET)
if(NOT imgui_FOUND)
  if(NOT MIDICOMP_FETCH_DEPS)
    message(FATAL_ERROR "Dear ImGui not found and MIDICOMP_FETCH_DEPS is off")
  endif()
  FetchContent_Declare(imgui
    URL https://github.com/ocornut/imgui/archive/refs/tags/v1.91.4.tar.gz
    URL_HASH SHA256=a455c28d987c78ddf56aab98ce0ff0fda791a23a2ec88ade46dd106b837f0923
    SYSTEM)
  FetchContent_MakeAvailable(imgui)
  add_library(imgui STATIC
    ${imgui_SOURCE_DIR}/imgui.cpp
    ${imgui_SOURCE_DIR}/imgui_demo.cpp
    ${imgui_SOURCE_DIR}/imgui_draw.cpp
    ${imgui_SOURCE_DIR}/imgui_tables.cpp
    ${imgui_SOURCE_DIR}/imgui_widgets.cpp
    ${imgui_SOURCE_DIR}/backends/imgui_impl_glfw.cpp
    ${imgui_SOURCE_DIR}/backends/imgui_impl_opengl3.cpp)
  target_include_directories(imgui SYSTEM PUBLIC ${imgui_SOURCE_DIR} ${imgui_SOURCE_DIR}/backends)
  target_link_libraries(imgui PUBLIC glfw)
  add_library(imgui::imgui ALIAS imgui)
  if(NOT MIDICOMP_EMBED_FONT)
    set(MIDICOMP_EMBED_FONT ${imgui_SOURCE_DIR}/misc/fonts/Roboto-Medium.ttf)
  endif()
endif()

find_package(nlohmann_json 3.11 QUIET)
if(NOT nlohmann_json_FOUND)
  if(NOT MIDICOMP_FETCH_DEPS)
    message(FATAL_ERROR "nlohmann_json not found and MIDICOMP_FETCH_DEPS is off")
  endif()
  FetchContent_Declare(nlohmann_json
    URL https://github.com/nlohmann/json/releases/download/v3.11.3/json.tar.xz
    URL_HASH SHA256=d6c65aca6b1ed68e7a182f4757257b107ae403032760ed6ef121c9d55e81757d
    SYSTEM)
  FetchContent_MakeAvailable(nlohmann_json)
endif()

# RtMidi's package (and its own build) needs the ALSA headers on Linux.
if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  find_package(ALSA REQUIRED)
endif()
find_package(RtMidi CONFIG QUIET)
if(NOT RtMidi_FOUND)
  if(NOT MIDICOMP_FETCH_DEPS)
    message(FATAL_ERROR "RtMidi not found and MIDICOMP_FETCH_DEPS is off")
  endif()
  # RtMidi enables a MIDI API only if it finds it, and otherwise builds without any; enable the
  # platform's API explicitly so that a missing dependency fails here.
  if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    set(RTMIDI_API_ALSA ON CACHE BOOL "" FORCE)
  endif()
  set(RTMIDI_BUILD_STATIC_LIBS ON CACHE BOOL "" FORCE)
  set(RTMIDI_BUILD_TESTING OFF CACHE BOOL "" FORCE)
  set(RTMIDI_API_JACK OFF CACHE BOOL "" FORCE)
  FetchContent_Declare(rtmidi
    URL https://github.com/thestk/rtmidi/archive/refs/tags/6.0.0.tar.gz
    URL_HASH SHA256=ef7bcda27fee6936b651c29ebe9544c74959d0b1583b716ce80a1c6fea7617f0
    SYSTEM)
  FetchContent_MakeAvailable(rtmidi)
  if(NOT TARGET RtMidi::rtmidi)
    add_library(RtMidi::rtmidi ALIAS rtmidi)
  endif()
endif()

# cpp-httplib is used header-only and without TLS, so its CMake package (which requires OpenSSL)
# is not needed.
find_path(HTTPLIB_INCLUDE_DIR httplib.h)
if(NOT HTTPLIB_INCLUDE_DIR)
  if(NOT MIDICOMP_FETCH_DEPS)
    message(FATAL_ERROR "cpp-httplib not found and MIDICOMP_FETCH_DEPS is off")
  endif()
  # SOURCE_SUBDIR points at a missing directory so only the header is downloaded.
  FetchContent_Declare(httplib
    URL https://github.com/yhirose/cpp-httplib/archive/refs/tags/v0.18.3.tar.gz
    URL_HASH SHA256=a0567bcd6c3fe5cef1b329b96245119047f876b49e06cc129a36a7a8dffe173e
    SOURCE_SUBDIR header-only)
  FetchContent_MakeAvailable(httplib)
  set(HTTPLIB_INCLUDE_DIR ${httplib_SOURCE_DIR} CACHE PATH "" FORCE)
endif()

# --- Targets

function(midicomp_warnings target)
  if(MSVC)
    target_compile_options(${target} PRIVATE /W4 /WX /permissive- /utf-8 /external:W0)
    target_compile_definitions(${target} PRIVATE _CRT_SECURE_NO_WARNINGS NOMINMAX
      WIN32_LEAN_AND_MEAN)
  else()
    target_compile_options(${target} PRIVATE -Wall -Wextra -Wpedantic -Wshadow -Werror)
  endif()
  if(APPLE)
    target_compile_definitions(${target} PRIVATE GL_SILENCE_DEPRECATION)
  endif()
endfunction()

add_library(midicomp_core STATIC
  src/api_client.cpp
  src/comp_store.cpp
  src/midi_input.cpp
  src/palette.cpp
  src/settings.cpp
  src/state.cpp
  src/theme.cpp
)
target_include_directories(midicomp_core PUBLIC src)
target_include_directories(midicomp_core SYSTEM PRIVATE ${HTTPLIB_INCLUDE_DIR})
target_link_libraries(midicomp_core PUBLIC imgui::imgui nlohmann_json::nlohmann_json Threads::Threads
  RtMidi::rtmidi)
if(WIN32)
  target_link_libraries(midicomp_core PUBLIC ws2_32)
endif()
midicomp_warnings(midicomp_core)

add_executable(midicomp-companion
  src/comp_view.cpp
  src/main.cpp
)
target_link_libraries(midicomp-companion PRIVATE midicomp_core glfw OpenGL::GL)
if(MIDICOMP_DEFAULT_FONT)
  target_compile_definitions(midicomp-companion PRIVATE
    MIDICOMP_DEFAULT_FONT="${MIDICOMP_DEFAULT_FONT}")
endif()
if(MIDICOMP_EMBED_FONT)
  # Turn the font file into a byte array.
  file(READ ${MIDICOMP_EMBED_FONT} font_hex HEX)
  string(REGEX REPLACE "([0-9a-f][0-9a-f])" "0x\\1," font_bytes "${font_hex}")
  file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/embedded_font.cpp
    "#include <cstddef>\n"
    "extern const unsigned char midicomp_font_data[];\n"
    "extern const std::size_t midicomp_font_size;\n"
    "const unsigned char midicomp_font_data[] = {${font_bytes}};\n"
    "const std::size_t midicomp_font_size = sizeof(midicomp_font_data);\n")
  target_sources(midicomp-companion PRIVATE ${CMAKE_CURRENT_BINARY_DIR}/embedded_font.cpp)
  target_compile_definitions(midicomp-companion PRIVATE MIDICOMP_EMBEDDED_FONT)
endif()
if(WIN32)
  # A GUI application: no console window.
  set_target_properties(midicomp-companion PROPERTIES WIN32_EXECUTABLE ON)
  target_link_options(midicomp-companion PRIVATE
    $<$<CXX_COMPILER_ID:MSVC>:/ENTRY:mainCRTStartup>)
endif()
midicomp_warnings(midicomp-companion)
install(TARGETS midicomp-companion)

include(CTest)
if(BUILD_TESTING)
  add_executable(companion-tests tests/companion_tests.cpp)
  target_link_libraries(companion-tests PRIVATE midicomp_core)
  midicomp_warnings(companion-tests)
  add_test(NAME companion-tests COMMAND companion-tests)
endif()
