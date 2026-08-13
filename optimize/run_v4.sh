#!/bin/bash
# Targeted test: does TPE overtake random search as budget grows? Run 1
# (24 trials, 16-example eval, different device/day) had TPE beat random.
# Run 2/v2/v3 (9-16 trials, 32-example eval) had random beat everything.
# This isolates budget as the variable, on this device, with the now-fixed
# per-device baseline (device_baseline.json) and 32-example eval set held
# constant -- QPSO/QIEA skipped this round to afford a bigger budget on the
# two methods with actually conflicting evidence.
set -x
echo "V4 START: $(date)"
./.venv/Scripts/python.exe baselines/random_search.py --trials 16 --n-prompt 128 --n-gen 32 --reps 2 --log results/v4_random.jsonl
echo "RANDOM DONE: $(date)"
./.venv/Scripts/python.exe study_tpe.py --trials 16 --n-prompt 128 --n-gen 32 --reps 2 --log results/v4_tpe.jsonl
echo "TPE DONE: $(date)"
echo "ALL_DONE"
