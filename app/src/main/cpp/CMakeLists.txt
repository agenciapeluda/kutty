cmake_minimum_required(VERSION 3.22.1)
project(kutty LANGUAGES CXX)

find_package(oboe REQUIRED CONFIG)

add_library(kutty SHARED engine.cpp)
target_link_libraries(kutty oboe::oboe log android)
