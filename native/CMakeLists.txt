cmake_minimum_required(VERSION 3.22)
project(media_wave LANGUAGES CXX)
add_library(media_wave SHARED wave.cpp jni.cpp)
target_compile_features(media_wave PRIVATE cxx_std_17)
