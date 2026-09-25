cmake_minimum_required(VERSION 3.24)

project(jamal_render_engine LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_executable(jamal-render-engine src/main.cpp)

if(APPLE AND EXISTS "/opt/homebrew/opt/ffmpeg")
    set(FFMPEG_ROOT "/opt/homebrew/opt/ffmpeg")
endif()

find_path(FFMPEG_INCLUDE_DIR libavformat/avformat.h HINTS "${FFMPEG_ROOT}/include")
find_library(AVFORMAT_LIBRARY avformat HINTS "${FFMPEG_ROOT}/lib")
find_library(AVCODEC_LIBRARY avcodec HINTS "${FFMPEG_ROOT}/lib")
find_library(AVUTIL_LIBRARY avutil HINTS "${FFMPEG_ROOT}/lib")

if(NOT FFMPEG_INCLUDE_DIR OR NOT AVFORMAT_LIBRARY OR NOT AVCODEC_LIBRARY OR NOT AVUTIL_LIBRARY)
    message(FATAL_ERROR "FFmpeg development libraries were not found. Install FFmpeg before building.")
endif()

target_include_directories(jamal-render-engine PRIVATE "${FFMPEG_INCLUDE_DIR}")
target_link_libraries(jamal-render-engine PRIVATE ${AVFORMAT_LIBRARY} ${AVCODEC_LIBRARY} ${AVUTIL_LIBRARY})

find_package(OpenCV REQUIRED COMPONENTS core imgproc videoio dnn)
target_link_libraries(jamal-render-engine PRIVATE ${OpenCV_LIBS})

install(TARGETS jamal-render-engine RUNTIME DESTINATION bin)

include(CTest)
if(BUILD_TESTING)
    add_test(
        NAME render-smoke
        COMMAND ${CMAKE_CURRENT_SOURCE_DIR}/../tests/render-smoke.sh $<TARGET_FILE:jamal-render-engine>
    )
endif()
