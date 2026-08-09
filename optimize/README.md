# Per-layer quantization search (Pixel 9a, Arm KleidiAI)

Real, on-device search over per-transformer-block quantization choices, using the
same llama-quantize/llama-bench toolchain as [docs/BENCHMARKS.md](../docs/BENCHMARKS.md).
Every number this produces comes from an actual quantize + bench cycle on the
connected phone. Nothing here is estimated or simulated.

## Status

**Working end to end, verified against the real device, including accuracy and
sustained-load measurement:**

- `quantize_and_bench.py` -- per-block quantize + on-device burst latency (pp/tg).
- `accuracy.py` + `eval_data.py` -- real on-device classification accuracy via
  `llama-simple` (built fresh, wasn't in the original toolchain) against a 16-example
  labeled scam/not-scam set. 16 examples is smoke-test-sized, not a rigor claim --
  expand `eval_data.py` before citing accuracy numbers in a write-up.
- `fitness.py` -- `combined_score()` is now real: 0.6 x accuracy + 0.25 x speed + 0.15 x size.
- `sustained_bench.py` -- reads llama-bench's per-repetition samples from one
  higher-rep invocation (no cooldown between reps, by design) to see throughput
  trend under sustained load, not just the averaged peak number. A short run
  (~8 reps) did not show degradation in testing -- consistent with
  docs/BENCHMARKS.md, which needed a ~50-minute tg128 phase before throttling
  was visible. Use enough reps/duration before trusting a "no throttling" result.
- `study_tpe.py`, `baselines/random_search.py`, `baselines/qpso.py`,
  `baselines/qiea.py` -- all four searches, real.

## Setup

```bash
py -3 -m venv .venv
./.venv/Scripts/python.exe -m pip install -r requirements.txt
```

Requires: phone connected via adb, with `llama-quantize`, `llama-bench`, and
`gemma-3-1b-it-BF16.gguf` already on-device at `/data/local/tmp/bench/` (already
true if you ran the docs/BENCHMARKS.md reproduction steps).

## Usage

```bash
# Bayesian search (recommended -- see chat history for why over pure QPSO/GA)
./.venv/Scripts/python.exe study_tpe.py --trials 20 --n-prompt 128 --n-gen 32 --reps 2

# Random-search control (must match --trials for a fair comparison)
./.venv/Scripts/python.exe baselines/random_search.py --trials 20 --n-prompt 128 --n-gen 32 --reps 2

# QPSO control (particles * generations must equal --trials above)
./.venv/Scripts/python.exe baselines/qpso.py --particles 5 --generations 4 --n-prompt 128 --n-gen 32 --reps 2

# QIEA -- the real quantum contender (individuals * generations must equal --trials above)
./.venv/Scripts/python.exe baselines/qiea.py --individuals 5 --generations 4 --n-prompt 128 --n-gen 32 --reps 2
```

### QPSO vs. QIEA

Both are "quantum" in name, but only one of them treats the search space as
what it actually is. `baselines/qpso.py` is quantum-behaved *particle swarm*
optimization: it moves a continuous position in R^26 and rounds to the
nearest of the 4 quant choices per block after the fact. That continuous-to-
discrete mismatch is exactly why it's a control, not a real contender --
see the TPE recommendation above.

`baselines/qiea.py` is a quantum-inspired estimation-of-distribution
algorithm: each block's choice is encoded as 2 qubits (4 choices = 2^2), all
26 blocks sharing one probability distribution that every generation's
candidates are sampled from. The update is a derived policy-gradient
(REINFORCE) ascent on expected fitness, with CMA-ES/Natural-Evolution-
Strategies-style rank-based fitness shaping standing in for the raw (noisy)
device scores -- not a lookup table. See the module docstring for the full
derivation, including why the qubit-angle parameterization is already the
natural-gradient coordinates (constant Fisher information -- the same reason
statisticians use the arcsine variance-stabilizing transform for a binomial
proportion). `baselines/test_qiea_offline.py` sanity-checks convergence on a
synthetic (no-device) needle-in-haystack objective before spending real
device time on it; run it with `python baselines/test_qiea_offline.py`.

Each run appends one JSON line per trial to `results/*.jsonl` (gitignored --
these are raw search logs, not curated results; promote a real comparison run's
output to a docs/ page the way BENCHMARKS.md does, once accuracy is real).

`--n-prompt`/`--n-gen`/`--reps` control fidelity vs. cost: the defaults above
(128/32/2) are a fast low-fidelity screening pass, good for search-loop iteration.
Use `--n-prompt 512 --n-gen 128 --reps 5` (matches BENCHMARKS.md) for a slow,
thermal-safe confirmation run on a shortlisted candidate, ideally with a cooldown
beforehand -- ~140 real evaluations at those settings is not a budget this search
should spend live; reserve full-fidelity runs for the handful of finalists.

## How a candidate becomes a quantized model

`search_space.py` encodes one quant-type choice per transformer block (26 blocks
in gemma-3-1b-it, confirmed via `llama-quantize --dry-run`), covering
`attn_q/k/v/output` and `ffn_gate/up/down` together. `quantize_and_bench.py` writes
that as a `--tensor-type-file` (pattern=type per line) and pushes it to the device
-- **not** as inline `--tensor-type` flags, because a full 26-block candidate
produces a ~2.7KB command line and `adb shell` silently truncates around ~1KB.
This one cost about an hour of debugging to find; don't reintroduce it.

## Known rough edges

- The phone's adb connection drops intermittently during longer runs (seen in
  practice, not hypothetical). `device.py`'s `shell()` retries through
  `kill-server`/`start-server` automatically; if a run still dies, check
  `adb devices` for `offline` first.
- Windows/Git Bash path handling: on-device paths go through `device.remote()`
  (double-slash-prefixed, MSYS-safe); local paths (temp files for
  `--tensor-type-file`) are plain Windows paths and don't need that treatment.
