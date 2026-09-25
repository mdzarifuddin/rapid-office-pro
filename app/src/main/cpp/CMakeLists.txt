cmake_minimum_required(VERSION 3.22.1)
project(pdfiumextra)

# No prebuilt pdfium is needed here: pdfium_extra.cpp resolves the few entry points it uses with
# dlsym at runtime, against the libpdfium.so that io.legere:pdfiumandroid already loads.
add_library(pdfiumextra SHARED pdfium_extra.cpp)

find_library(log-lib log)
target_link_libraries(pdfiumextra ${log-lib} dl)
