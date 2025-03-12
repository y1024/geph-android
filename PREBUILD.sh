#!/usr/bin/env bash

# Make sure output directories exist
mkdir -p prebuild/arm64-v8a prebuild/armeabi-v7a prebuild/x86 prebuild/x86_64

(
  cd geph5/binaries/geph5-client

  # Build for arm64-v8a
  ~/.cargo/bin/cargo ndk -t arm64-v8a -p 21 build --profile release-small

  # Build for armeabi-v7a
  ~/.cargo/bin/cargo ndk -t armeabi-v7a -p 21 build --profile release-small
)

# Copy the resulting binaries to the correct folders
cp geph5/target/aarch64-linux-android/release-small/geph5-client       prebuild/arm64-v8a/libgeph.so
cp geph5/target/armv7-linux-androideabi/release-small/geph5-client     prebuild/armeabi-v7a/libgeph.so

