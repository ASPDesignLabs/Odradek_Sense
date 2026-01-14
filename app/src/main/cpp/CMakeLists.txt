cmake_minimum_required(VERSION 3.22.1)

# Name of our native library
project("neonflux")

# Import Oboe via Prefab
find_package(oboe REQUIRED CONFIG)

# Define our library and source files
add_library(neonflux SHARED
        neon_engine.cpp)

# Link Oboe and Android Log to our library
target_link_libraries(neonflux
        oboe::oboe
        log
	android)
