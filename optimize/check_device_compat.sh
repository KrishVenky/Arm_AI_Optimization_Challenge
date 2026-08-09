#!/bin/bash
# Run this BEFORE building llama.cpp for a new phone. Checks whether the
# connected device's CPU actually supports the ARM extensions KleidiAI's
# accelerated int8 kernels need (dotprod, i8mm). Building with
# armv8.7-a+dotprod+i8mm and running on a chip missing either one crashes
# with an illegal-instruction signal, not a slow-but-valid benchmark.
set -e

echo "Checking connected device CPU features..."
FEATURES=$(adb shell cat /proc/cpuinfo | grep -m1 "Features")
echo "$FEATURES"
echo ""

HAS_DOTPROD=false
HAS_I8MM=false
echo "$FEATURES" | grep -qw asimddp && HAS_DOTPROD=true
echo "$FEATURES" | grep -qw i8mm && HAS_I8MM=true

echo "dotprod (asimddp): $HAS_DOTPROD"
echo "i8mm:               $HAS_I8MM"
echo ""

if $HAS_DOTPROD && $HAS_I8MM; then
    echo "Build with: -DGGML_CPU_ARM_ARCH=armv8.7-a+dotprod+i8mm  (same as the Pixel 9a reference run)"
elif $HAS_DOTPROD; then
    echo "i8mm missing. Build with: -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod"
    echo "You will NOT get KleidiAI's int8 matmul acceleration -- numbers won't be"
    echo "comparable to the Pixel 9a reference run, but the binary will run correctly."
else
    echo "Neither extension found. This chip predates the KleidiAI-accelerated path"
    echo "entirely. Build with a generic -DGGML_CPU_ARM_ARCH=armv8-a target, or don't"
    echo "set the flag at all and let llama.cpp pick a safe default. Expect much lower"
    echo "throughput and no meaningful comparison to the reference numbers."
fi
