cmake_minimum_required(VERSION 3.22.1)

project(pocketcasts_vocalboost)

add_library(vocalboost SHARED vocal_boost_engine.cpp)

target_compile_features(vocalboost PRIVATE cxx_std_17)
target_compile_options(vocalboost PRIVATE -Wall -Wextra -Werror)

find_library(log-lib log)
target_link_libraries(vocalboost ${log-lib})
