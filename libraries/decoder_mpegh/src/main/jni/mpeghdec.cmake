# Build mpeghdecJNI.
add_library(
    mpeghdecJNI
    SHARED
    ${CMAKE_CURRENT_SOURCE_DIR}/mpeghdec_jni.cpp)

target_include_directories(
    mpeghdecJNI
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/mpeghdec/include/
)

# Link mpeghdecJNI against used libraries.
target_link_libraries(
    mpeghdecJNI
    PRIVATE
    mpeghdec
    ${android_log_lib}
)
