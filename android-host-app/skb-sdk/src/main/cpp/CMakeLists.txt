cmake_minimum_required(VERSION 3.10.2)

project(skb_serial)

add_library(skb_serial SHARED native_serial.cpp)

find_library(log-lib log)

target_link_libraries(skb_serial ${log-lib})
