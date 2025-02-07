
# **********************************************************
# Copyright (c) 2014-2017 Google, Inc.    All rights reserved.
# **********************************************************

# Redistribution and use in source and binary forms, with or without
# modification, are permitted provided that the following conditions are met:
#
# * Redistributions of source code must retain the above copyright notice,
#   this list of conditions and the following disclaimer.
#
# * Redistributions in binary form must reproduce the above copyright notice,
#   this list of conditions and the following disclaimer in the documentation
#   and/or other materials provided with the distribution.
#
# * Neither the name of Google, Inc. nor the names of its contributors may be
#   used to endorse or promote products derived from this software without
#   specific prior written permission.
#
# THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
# AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
# IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
# ARE DISCLAIMED. IN NO EVENT SHALL GOOGLE, INC. OR CONTRIBUTORS BE LIABLE
# FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
# DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
# SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
# CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
# LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
# OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH
# DAMAGE.

# For cross-compiling on arm64 Linux using gcc-x86_64-linux-gnu package:
# - install x86_64 tool chain:
#   $ sudo apt-get install g++-x86_64-linux-gnu
# - cross-compiling config
#   $ cmake -DCMAKE_TOOLCHAIN_FILE=../dynamorio/make/toolchain-arm64.cmake ../dynamorio
# You may have to set CMAKE_FIND_ROOT_PATH to point to the target enviroment, e.g.
# by passing -DCMAKE_FIND_ROOT_PATH=/usr/x86_64-linux-gnu on Debian-like systems.
set(STATIC 1)
set(CMAKE_SYSTEM_NAME Windows)
set(CMAKE_SYSTEM_PROCESSOR x86_64)
set(TARGET_ABI "pc-cygwin")
# specify the cross compiler
SET(CMAKE_C_COMPILER   gcc)
SET(CMAKE_CXX_COMPILER g++)

# To build the tests, we need to set where the target environment containing
# the required library is. On Debian-like systems, this is
# /usr/x86_64-linux-gnu.
SET(CMAKE_FIND_ROOT_PATH "/usr/x86_64-${TARGET_ABI}")
# search for programs in the build host directories
SET(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
# for libraries and headers in the target directories
SET(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
SET(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)

# Set additional variables.
# If we don't set some of these, CMake will end up using the host version.
# We want the full path, however, so we can pass EXISTS and other checks in
# the our CMake code.
find_program(GCC_FULL_PATH gcc)
if (NOT GCC_FULL_PATH)
  message(FATAL_ERROR "Cross-compiler gcc not found")
endif ()
get_filename_component(GCC_DIR ${GCC_FULL_PATH} PATH)
SET(CMAKE_LINKER       ld      CACHE FILEPATH "linker")
SET(CMAKE_ASM_COMPILER as      CACHE FILEPATH "assembler")
SET(CMAKE_OBJCOPY      objcopy CACHE FILEPATH "objcopy")
SET(CMAKE_STRIP        strip   CACHE FILEPATH "strip")
SET(CMAKE_CPP          cpp     CACHE FILEPATH "cpp")

# avoid gcc dependency
SET(CMAKE_SHARED_LINKER_FLAGS
    "-fdata-sections -ffunction-sections -Wl,--enable-stdcall-fixup -static-libgcc -static" CACHE STRING "" FORCE)
SET(CMAKE_EXE_LINKER_FLAGS
    "-fdata-sections -ffunction-sections -Wl,--enable-stdcall-fixup -static-libgcc -static" CACHE STRING "" FORCE)
link_libraries(-static-libgcc -static-libstdc++)