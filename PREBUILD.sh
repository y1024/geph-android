#!/usr/bin/env bash
export PATH=$HOME/.cargo/bin:/usr/bin/:/bin:$PATH
export ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/27.0.12077973

# Make sure output directories exist
mkdir -p prebuild/arm64-v8a prebuild/armeabi-v7a prebuild/x86 prebuild/x86_64

(
  cd geph5/binaries/geph5-client
  export CARGO_TARGET_DIR=../../target/
  # Build for arm64-v8a
  ~/.cargo/bin/cargo ndk -t arm64-v8a --platform 21 build --release --features aws_lambda

  # Build for armeabi-v7a
  ~/.cargo/bin/cargo ndk -t armeabi-v7a --platform 21 build --release --features aws_lambda
)

# Copy the resulting binaries to the correct folders
cp geph5/target/aarch64-linux-android/release/geph5-client       prebuild/arm64-v8a/libgeph.so
cp geph5/target/armv7-linux-androideabi/release/geph5-client     prebuild/armeabi-v7a/libgeph.so

