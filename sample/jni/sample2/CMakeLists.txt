cmake_minimum_required(VERSION 3.4.1)

add_library(
        native-lib2
        SHARED
        src/androidMain/c/native-lib2.c
)

find_library(
        log-lib
        log
)

target_link_libraries(
        native-lib2
        ${log-lib}
)
