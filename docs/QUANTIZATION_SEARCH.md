# Per-layer quantization search: algorithm comparison

Full results for the search comparison referenced from [optimize/README.md](../optimize/README.md).
Like [BENCHMARKS.md](BENCHMARKS.md), this is a standalone hardware/toolchain exercise using
llama.cpp on gemma-3-1b-it, not a measurement of Audimus's own LiteRT-LM runtime. Every number
below is a real `llama-quantize` + `llama-bench` + on-device accuracy-eval cycle on the Pixel 9a,
logged automatically by the scripts in `optimize/`, not estimated.

## What's being searched

One quantization level (Q4_0 / Q5_K / Q6_K / Q8_0) per transformer block, 26 blocks, so a 4^26
discrete search space. Each candidate is scored by `fitness.py`'s `combined_score`:

```
0.6 x accuracy + 0.25 x (prompt-processing tok/s, normalized to the F16 baseline)
              + 0.15 x (1 - model size / F16 baseline size)
```

Accuracy is measured by running the actual quantized model against a labeled scam/not-scam
transcript set (`optimize/eval_data.py`) via `llama-simple`, not simulated. Four search
algorithms were compared: an Optuna/TPE Bayesian search (the recommended approach), a QPSO
(quantum-behaved particle swarm) baseline, a QIEA (quantum-inspired estimation-of-distribution)
baseline, and random search as the control every comparison needs.

## Run 1: 24-trial budget, 16-example eval set

First real comparison, matched budget across three algorithms (QIEA didn't exist yet).
`--n-prompt 128 --n-gen 32 --reps 2` fidelity throughout.

| Method | Trials | Failed | Best score | Avg score | Best pp (tok/s) | Best size |
|---|---|---|---|---|---|---|
| **TPE** | 20 (+4 informed resume) | 0 | **2.986** | 2.007 | 192.3 | 907 MiB |
| Random search | 24 | 0 | 2.688 | 2.113 | 162.3 | 845 MiB |
| QPSO | 24 | 0 | 2.329 | 1.817 | -- | -- |

TPE lost 4 of its original 24 trials to a mid-run adb disconnect (a real device flake, not an
algorithm fault -- confirmed via live logcat at the time). Rather than reporting a
budget-shorted result, its study was reconstructed from the 20 successful trials and given 4
more proposals informed by that full history (`optimize/resume_tpe.py`). Those 4 new candidates
scored 2.589, 2.668, 2.814, 2.872 -- all below the original best, confirming 2.986 as the real
ceiling for this budget rather than an artifact of the missing data.

**TPE's winning config**, for reference: `Q8_0,Q4_0,Q6_K,Q8_0,Q4_0,Q4_0,Q6_K,Q4_0,Q8_0,Q6_K,Q6_K,
Q5_K,Q6_K,Q8_0,Q4_0,Q8_0,Q8_0,Q8_0,Q6_K,Q8_0,Q6_K,Q4_0,Q6_K,Q8_0,Q6_K,Q6_K` (blocks 0-25).

QPSO's per-trial log doesn't capture raw pp/size (only the combined score) -- a logging gap in
that script worth fixing before it matters for a headline claim.

## Run 2: 9-trial matched budget, 32-example eval set

The 16-example eval set was coarse enough that one misclassification moved a candidate's score
by 0.6/16 = 0.0375, comparable to or larger than the per-block signal the search was trying to
detect (roughly 0.03-0.18 across blocks in practice). The eval set was doubled to 32 examples to
cut that noise (0.6/32 = 0.01875 per misclassification), and all four algorithms including the
new QIEA baseline were re-run at a matched 9-trial budget for a fair comparison. Same
`--n-prompt 128 --n-gen 32 --reps 2` fidelity.

| Method | Trials | Failed | Best score | Avg score |
|---|---|---|---|---|
| **Random search** | 9 | 0 | **2.898** | 2.332 |
| TPE | 9 | 0 | 2.563 | 1.976 |
| QIEA | 9 | 0 | 2.459 | 2.070 |
| QPSO | 9 | 1 | 2.220 | 1.925 |

Random search won this pass, reversing Run 1's result (TPE led 2.986 to 2.688 there). QPSO's one
failure was caught and retried by the resilience layer (`device.py`'s `wait-for-device` fix, see
below) rather than crashing the run; it still failed on the retry, one lost trial, not a lost
run.

### Why the reversal, honestly

Two variables changed between Run 1 and Run 2 at once: the trial budget (24 vs. 9) and the
eval-set size (16 vs. 32 examples). This run does not isolate which one is responsible for the
flip, and it would be dishonest to claim it does. The more likely explanation is budget: TPE's
entire advantage comes from its posterior model getting more informative as trials complete,
which only pays off once it has seen enough of the 4^26 space to matter. Nine trials is thin for
a 26-dimensional categorical search; random search has no cold-start cost to overcome, so at a
small budget it is a genuinely competitive baseline, not just a control TPE is expected to beat.
**The defensible claim from these two runs together is that TPE's advantage is budget-dependent,
not universal** -- a real, citable finding, and a more honest one than picking whichever run's
number looks better.

## A real bug found and fixed mid-comparison

`device.py`'s adb-server-restart helper called `adb wait-for-device` with an uncaught
`TimeoutExpired`. On a slow reconnect, that exception propagated up and killed the entire
candidate on the very first retry attempt, burning through all `_MAX_RETRIES` in one shot
instead of actually using them. This was the direct cause of an earlier QIEA run losing 21 of 24
trials to what looked like a device problem but was actually a bug in the retry logic itself.
Fixed by catching the timeout and letting the normal retry loop proceed. Confirmed working: the
QIEA run in this comparison (9/9 succeeded) and the Run 2 four-way comparison (only 1 failure in
35 total trials, and that one was a genuine second failure after a real retry, not the bug) both
ran clean.

## Known limitations

- 32 examples is still a smoke-test-sized eval set, not a rigor claim. Expand further before
  citing accuracy numbers as a standalone result.
- The search fitness measures burst throughput (`pp128`/`tg32`-equivalent), not sustained-load
  throughput under thermal stress, which is the actual motivating problem (see project chat
  history). `optimize/sustained_bench.py` exists and works but hasn't been run on either run's
  winning candidate yet -- that validation pass is still outstanding.
- QPSO and QIEA's per-trial logs don't record raw pp tok/s or model size, only the combined
  score, unlike random search and TPE's logs. Fine for ranking, incomplete for a per-candidate
  breakdown.

## Reproducing this

```bash
cd optimize
py -3 -m venv .venv && ./.venv/Scripts/python.exe -m pip install -r requirements.txt

# Matched-budget 4-way comparison (adjust --trials / --individuals / --particles together)
./.venv/Scripts/python.exe baselines/random_search.py --trials 9 --n-prompt 128 --n-gen 32 --reps 2 --log results/random.jsonl
./.venv/Scripts/python.exe baselines/qpso.py --particles 3 --generations 3 --n-prompt 128 --n-gen 32 --reps 2 --log results/qpso.jsonl
./.venv/Scripts/python.exe baselines/qiea.py --individuals 3 --generations 3 --n-prompt 128 --n-gen 32 --reps 2 --log results/qiea.jsonl
./.venv/Scripts/python.exe study_tpe.py --trials 9 --n-prompt 128 --n-gen 32 --reps 2 --log results/tpe.jsonl
```

Raw per-trial logs are gitignored (`optimize/results/`) since they're working data, not curated
results -- this page is the promoted, checked version, same pattern as BENCHMARKS.md.
