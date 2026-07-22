cmake_minimum_required(VERSION 3.4.1)

project(sample_jni_sample2 C)

add_library(
        native-lib2
        SHARED
        src/main/c/native-lib2.c
)

find_library(
        log-lib
        log
)

target_link_libraries(
        native-lib2
        ${log-lib}
)
