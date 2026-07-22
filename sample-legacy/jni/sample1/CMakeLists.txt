cmake_minimum_required(VERSION 3.4.1)

project(sample_legacy_jni_sample1 C)

add_library(
        native-lib1
        SHARED
        src/androidMain/c/native-lib1.c
)

find_library(
        log-lib
        log
)

target_link_libraries(
        native-lib1
        ${log-lib}
)
