cmake_minimum_required(VERSION 3.22)
project(visiomata_aribcaption LANGUAGES C CXX)

set(FT_DISABLE_ZLIB ON CACHE BOOL "Disable zlib" FORCE)
set(FT_DISABLE_BZIP2 ON CACHE BOOL "Disable bzip2" FORCE)
set(FT_DISABLE_PNG ON CACHE BOOL "Disable libpng" FORCE)
set(FT_DISABLE_HARFBUZZ ON CACHE BOOL "Disable harfbuzz" FORCE)
set(FT_DISABLE_BROTLI ON CACHE BOOL "Disable brotli" FORCE)
set(BUILD_SHARED_LIBS OFF)
add_subdirectory(freetype EXCLUDE_FROM_ALL)
set(FREETYPE_FOUND TRUE)
set(FREETYPE_INCLUDE_DIRS
    "${CMAKE_CURRENT_SOURCE_DIR}/freetype/include"
    "${CMAKE_CURRENT_BINARY_DIR}/freetype/include")
set(FREETYPE_LIBRARIES freetype)

set(ARIBCC_NO_RENDERER OFF CACHE BOOL "Use libaribcaption renderer" FORCE)
set(ARIBCC_USE_FREETYPE ON CACHE BOOL "Use embedded FreeType" FORCE)
set(ARIBCC_BUILD_TESTS OFF CACHE BOOL "Disable libaribcaption tests" FORCE)

# Keep the submodule pristine and apply the Android integration changes to a
# generated copy. This avoids a permanently dirty submodule checkout.
set(ARIBCAPTION_GENERATED_SOURCE "${CMAKE_CURRENT_BINARY_DIR}/third_party/libaribcaption")
file(REMOVE_RECURSE "${ARIBCAPTION_GENERATED_SOURCE}")
file(COPY "${CMAKE_CURRENT_SOURCE_DIR}/libaribcaption/"
    DESTINATION "${ARIBCAPTION_GENERATED_SOURCE}")

file(READ "${ARIBCAPTION_GENERATED_SOURCE}/CMakeLists.txt" ARIBCAPTION_CMAKE)
string(REPLACE
    "cmake_minimum_required(VERSION 3.28)"
    "cmake_minimum_required(VERSION 3.22)"
    ARIBCAPTION_CMAKE "${ARIBCAPTION_CMAKE}")
string(REPLACE
    "if(ARIBCC_USE_FREETYPE AND (NOT ARIBCC_USE_EMBEDDED_FREETYPE))"
    "if(ARIBCC_USE_FREETYPE AND TARGET freetype)\n    set(FREETYPE_FOUND TRUE)\n    message(STATUS \"Use parent project's FreeType target\")\nelseif(ARIBCC_USE_FREETYPE AND (NOT ARIBCC_USE_EMBEDDED_FREETYPE))"
    ARIBCAPTION_CMAKE "${ARIBCAPTION_CMAKE}")
file(WRITE "${ARIBCAPTION_GENERATED_SOURCE}/CMakeLists.txt" "${ARIBCAPTION_CMAKE}")

file(READ
    "${ARIBCAPTION_GENERATED_SOURCE}/src/renderer/font_provider_android.cpp"
    ARIBCAPTION_ANDROID_FONT_PROVIDER)
string(REPLACE
    "auto FontProviderAndroid::GetFontFace(const std::string &font_name,\n                                      std::optional<uint32_t> ucs4) -> Result<FontfaceInfo, FontProviderError> {"
    "auto FontProviderAndroid::GetFontFace(const std::string &font_name,\n                                      std::optional<uint32_t> ucs4) -> Result<FontfaceInfo, FontProviderError> {\n    // Allow the embedding app to provide an app-private font file.\n    if (!font_name.empty() && font_name.front() == '/' && access(font_name.c_str(), R_OK) == 0) {\n        FontfaceInfo info;\n        info.family_name = font_name;\n        info.filename = font_name;\n        info.face_index = 0;\n        info.provider_type = FontProviderType::kAndroid;\n        return Ok(std::move(info));\n    }"
    ARIBCAPTION_ANDROID_FONT_PROVIDER "${ARIBCAPTION_ANDROID_FONT_PROVIDER}")
file(WRITE
    "${ARIBCAPTION_GENERATED_SOURCE}/src/renderer/font_provider_android.cpp"
    "${ARIBCAPTION_ANDROID_FONT_PROVIDER}")

add_subdirectory("${ARIBCAPTION_GENERATED_SOURCE}" libaribcaption)

if(ANDROID_ABI STREQUAL "arm64-v8a")
  set(ANDROID_RUST_TARGET "aarch64-linux-android")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
  set(ANDROID_RUST_TARGET "armv7-linux-androideabi")
elseif(ANDROID_ABI STREQUAL "x86")
  set(ANDROID_RUST_TARGET "i686-linux-android")
elseif(ANDROID_ABI STREQUAL "x86_64")
  set(ANDROID_RUST_TARGET "x86_64-linux-android")
else()
  message(FATAL_ERROR "Unsupported Android ABI for Rust: ${ANDROID_ABI}")
endif()

add_library(visiomata_mpeg2toh264 STATIC IMPORTED)
set_target_properties(visiomata_mpeg2toh264 PROPERTIES
    IMPORTED_LOCATION
    "${CMAKE_CURRENT_SOURCE_DIR}/../rust/target/${ANDROID_RUST_TARGET}/release/libvisiomata_mpeg2toh264.a")

add_library(visiomata_aribcaption SHARED aribcaption_jni.cpp mpeg2toh264_jni.cpp)
target_compile_features(visiomata_aribcaption PRIVATE cxx_std_17)
target_link_libraries(visiomata_aribcaption PRIVATE aribcaption visiomata_mpeg2toh264 log)
