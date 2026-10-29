cmake_minimum_required(VERSION 3.22)
project(amusic_airplay C CXX)

# OpenSSL 1.1.1 from Google's prefab package (com.android.ndk.thirdparty:openssl).
find_package(openssl REQUIRED CONFIG)

# ---------- libplist (LGPL-2.1): binary/XML property lists used by the AirPlay protocol ----------
file(GLOB PLIST_SRC libplist/src/*.c)
add_library(plist STATIC ${PLIST_SRC} libplist/libcnary/node.c libplist/libcnary/node_list.c)
target_include_directories(plist
        PUBLIC libplist/include
        PRIVATE libplist/src libplist/libcnary/include)
target_compile_definitions(plist
        PUBLIC LIBPLIST_STATIC
        PRIVATE HAVE_STRNDUP HAVE_MEMMEM HAVE_TM_TM_GMTOFF HAVE_TM_TM_ZONE
                HAVE_LOCALTIME_R HAVE_GMTIME_R PACKAGE_VERSION="2.7.0")
target_compile_options(plist PRIVATE -w)

# ---------- Apple ALAC decoder (Apache-2.0): AirPlay music arrives as Apple Lossless ----------
add_library(alac STATIC
        alac/ALACDecoder.cpp alac/ALACBitUtilities.c alac/EndianPortable.c
        alac/ag_dec.c alac/dp_dec.c alac/matrix_dec.c)
target_include_directories(alac PUBLIC alac)
target_compile_options(alac PRIVATE -w)

# ---------- UxPlay AirPlay server core (GPL-3.0) with its built-in mDNS responder ----------
file(GLOB UXPLAY_SRC
        uxplay/*.c uxplay/llhttp/*.c uxplay/playfair/*.c uxplay/mdnsd/*.c)
add_library(uxplay STATIC ${UXPLAY_SRC})
target_include_directories(uxplay PUBLIC uxplay PRIVATE uxplay/llhttp uxplay/playfair)
target_compile_definitions(uxplay PRIVATE
        NOHOLD PLIST_210 PLIST_230 OPENSSL_API_COMPAT=0x10101000L
        _REENTRANT _LARGEFILE64_SOURCE _FILE_OFFSET_BITS=64)
target_compile_options(uxplay PRIVATE -O2 -Wno-unused-variable -Wno-unused-function
        -Wno-unused-but-set-variable -Wno-deprecated-declarations)
target_link_libraries(uxplay PUBLIC plist openssl::crypto)

# ---------- JNI bridge ----------
add_library(airplay SHARED airplay_jni.cpp)
target_compile_options(airplay PRIVATE -Wall -fvisibility=hidden)
target_link_libraries(airplay uxplay alac log)
