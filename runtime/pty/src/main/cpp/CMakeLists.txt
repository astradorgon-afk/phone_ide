cmake_minimum_required(VERSION 3.22.1)

project(mobileforge-pty C)

# 16 KB page alignment (RISK-004).
#
# Android devices shipping from 2025 use 16 KB memory pages, and a shared library linked for
# 4 KB alignment will not load on them at all. For libmfexec that is not a degraded experience
# but a dead one: it is LD_PRELOADed into every process we start, so failing to load means no
# installed binary can be executed from a shell.
#
# This was missed until Android lint reported it — `tools/build-bundle.sh` passed the flag for
# `mf-doctor`, and `tools/package-termux-bundle.sh` refuses bundles that lack the alignment, so
# the project was checking everyone else's binaries while shipping unaligned ones of its own.
#
# NDK r28 makes this the default; the flag is set explicitly so the alignment does not depend on
# which NDK happens to be installed. It is harmless on 4 KB devices.
add_link_options(-Wl,-z,max-page-size=16384)

# Bionic has openpty/forkpty/login_tty from API 23; minSdk is 30, so no guards are needed.
add_library(mfpty SHARED pty.c)

target_compile_options(mfpty PRIVATE
    -Wall
    -Wextra
    -Werror
    -fvisibility=hidden
)

# JNI entry points must stay visible despite -fvisibility=hidden.
target_compile_definitions(mfpty PRIVATE
    JNIEXPORT=__attribute__\(\(visibility\("default"\)\)\)
)

target_link_libraries(mfpty log)

# libmfexec: an LD_PRELOAD execve() interposer.
#
# Separate from mfpty because it is loaded into EVERY child process, not into the app. It must
# stay tiny and depend on nothing beyond libc and libdl — anything it pulls in is pulled into
# every command the user runs.
add_library(mfexec SHARED mfexec.c)

target_compile_options(mfexec PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)

target_link_libraries(mfexec dl)
