# On-device inference benchmark: Arm KleidiAI on Tensor G4

This is the full data and methodology behind the KleidiAI benchmark referenced in
[DESIGN.md](../DESIGN.md) and the README. It is a standalone hardware/toolchain validation, run with
llama.cpp, not a measurement of Audimus's own runtime (Audimus uses Google's LiteRT-LM, see
DESIGN.md). Every number below is real measured `llama-bench` output captured on-device over `adb`,
not an estimate.

## Hardware and software

- Device: Pixel 9a, Tensor G4. Tensor G4 does not support SME2, so nothing here reflects SME2-class
  acceleration, only standard KleidiAI/XNNPACK int8 (dotprod, i8mm) kernels.
- Android build: 17.
- Toolchain: Android NDK r29, CMake, Ninja, all cross-compiling for `arm64-v8a`.
- llama.cpp commit: `fa72aeccb23947074c12b5fec25f5b6ced28cbfe` (2026-07-24).
- Build flags: `-DGGML_CPU_KLEIDIAI=ON -DGGML_CPU_ARM_ARCH=armv8.7-a+dotprod+i8mm -DGGML_OPENMP=OFF
  -DGGML_LLAMAFILE=OFF -DBUILD_SHARED_LIBS=OFF -DCMAKE_BUILD_TYPE=Release`.
- Model: Gemma 3 1B-it, sourced as BF16 GGUF from `unsloth/gemma-3-1b-it-GGUF` (a public re-upload of
  Google's official weights). This is a different model from the Gemma 4 E2B that Audimus itself runs;
  see "Why a different model" below.
- F16, Q8_0, and Q4_0 variants were all derived from that same BF16 source with `llama-quantize`, so
  all three benchmark numbers trace back to identical source weights.

## Headline results (clean run)

Measured with the phone cooled to an idle baseline (CPU frequencies back to idle, battery temperature
stable) before each of the three runs, so no run benefits or suffers from heat carried over from a
previous one. `llama-bench` default settings: `pp512` (512-token prompt processing), `tg128` (128-token
generation), 5 repetitions each, values reported as mean plus or minus standard deviation.

| Format | Size on disk | Prompt processing (tok/s) | Token generation (tok/s) |
|---|---|---|---|
| F16 (baseline) | 2,006,573,344 bytes (1.87 GiB) | 19.87 ± 1.57 | 4.39 ± 0.67 |
| Q8_0 | 1,069,306,144 bytes (1.00 GiB) | 251.10 ± 35.78 | 4.13 ± 1.33 |
| Q4_0 | 720,425,248 bytes (687 MiB) | 174.52 ± 3.31 | 3.87 ± 0.56 |

**Size reduction versus F16:** Q8_0 is 46.7% smaller, Q4_0 is 64.1% smaller.

**Speedup versus F16, prompt processing:** Q8_0 is 12.6x faster, Q4_0 is 8.8x faster. This is the
real, defensible headline number: KleidiAI's quantized int8 kernels give a large, measured speedup on
compute-bound prompt processing on this exact chip.

**Token generation:** Q8_0 and Q4_0's numbers are statistically indistinguishable from F16's (the
error bars overlap). We are not claiming a generation speedup. Single-token generation is
memory-bandwidth-bound rather than compute-bound, and on a model this size, per-token overhead appears
to dominate over the raw bandwidth savings from quantization. If your pitch or write-up cites a
speedup number, scope it to prompt processing specifically; a blanket "quantization made everything
faster" claim would not survive a judge rerunning this benchmark.

## Why a different model than Audimus itself runs

Audimus's own model ships as a `.litertlm` file for Google's LiteRT-LM runtime. llama.cpp (and
therefore KleidiAI, as wired up here) only reads GGUF-format models, so a like-for-like benchmark of
the literal file Audimus loads is not possible with this tool. Gemma 3 1B-it was chosen instead
because it is small enough for fast iteration, from the same model family, and because its
classification-style usage (long prompt in, short structured answer out) matches the shape of
Audimus's own per-call analysis pass. This benchmark validates that KleidiAI's kernels are a real,
usable win on this hardware for that workload shape. It does not mean Audimus's own runtime is 12.6x
faster; Audimus does not use llama.cpp or KleidiAI at all.

## Why there is also a throttled run in this repo's history

The first pass through all three quant levels was run back-to-back with no cooldown between them.
F16 ran first (coolest state) and still took roughly 50 minutes for its `tg128` phase alone (no
native fp16 SIMD in this build's arch flags, see below), generating enough heat that later runs were
measurably compromised:

| Format | Prompt processing (tok/s) | Token generation (tok/s) |
|---|---|---|
| F16 | 12.55 ± 2.20 | 2.01 ± 0.79 |
| Q8_0 | 210.05 ± 54.34 | 3.80 ± 0.84 |
| Q4_0 | 166.66 ± 6.39 | 3.26 ± 1.64 |

Comparing the two tables shows exactly why the cooldown-separated numbers above are the ones to cite:
in the throttled run, F16 itself was measured under compromised conditions (its own long run generated
sustained heat), which made quantization's generation speedup look larger than it actually is. CPU
frequency reads taken during the Q4_0 run confirmed thermal throttling directly: cores that should
differ significantly in max clock speed were both reading approximately 1.2 GHz, well below Tensor
G4's rated clocks. We are keeping this data in the repo rather than deleting it, since the discrepancy
between the two runs is itself a real, useful finding about sustained on-device benchmarking.

## A note on the F16 baseline specifically

The build flags above (`armv8.7-a+dotprod+i8mm`) were chosen to enable KleidiAI's int8 kernels for the
quantized paths. They do not include `+fp16`, so this build has no native half-precision vector
arithmetic; F16 matmul runs through a slower fallback path. This is a real, honest characteristic of
this specific build configuration, and it is part of why F16's prompt-processing number is as low as
it is: some of the quantized paths' apparent advantage reflects "the quantized path has a fast SIMD
kernel and the fp16 path does not" as well as quantization itself. Both build configurations (this one,
and one that also enables `+fp16`) reflect real, legitimate ways to build llama.cpp for Android; this
repo used the one that matches llama.cpp's own documented Android cross-compile instructions.

## Reproducing this benchmark

```bash
# Cross-compile llama.cpp for arm64-v8a with KleidiAI
cmake -B build-android \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-28 \
  -DGGML_CPU_ARM_ARCH=armv8.7-a+dotprod+i8mm \
  -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF -DGGML_CPU_KLEIDIAI=ON \
  -DBUILD_SHARED_LIBS=OFF -DCMAKE_BUILD_TYPE=Release -G Ninja
cmake --build build-android --target llama-bench llama-quantize

# Push the binaries and a BF16 base model, quantize on-device, then benchmark each variant
adb push build-android/bin/llama-bench build-android/bin/llama-quantize /data/local/tmp/bench/
adb push gemma-3-1b-it-BF16.gguf /data/local/tmp/bench/
adb shell "cd /data/local/tmp/bench && ./llama-quantize gemma-3-1b-it-BF16.gguf gemma-3-1b-it-F16.gguf F16"
adb shell "cd /data/local/tmp/bench && ./llama-quantize gemma-3-1b-it-BF16.gguf gemma-3-1b-it-Q8_0.gguf Q8_0"
adb shell "cd /data/local/tmp/bench && ./llama-quantize gemma-3-1b-it-BF16.gguf gemma-3-1b-it-Q4_0.gguf Q4_0"
adb shell "cd /data/local/tmp/bench && ./llama-bench -m gemma-3-1b-it-F16.gguf"
# Let the phone cool to idle CPU frequencies between runs, then repeat for Q8_0 and Q4_0.
```
