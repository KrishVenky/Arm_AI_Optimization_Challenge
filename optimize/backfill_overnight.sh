#!/bin/bash
# Tops up the three legs that lost trials to the adb wait-for-device bug
# (see device.py fix) up to a real 24 successful evaluations each. Appends to
# the same overnight_*.jsonl logs -- run_comparison analysis should count only
# successful (non-"error") records, not just "most recent 24 lines".
set -x
echo "BACKFILL START: $(date)"
./.venv/Scripts/python.exe baselines/qpso.py --particles 4 --generations 4 --n-prompt 128 --n-gen 32 --reps 2 --seed 1 --log results/overnight_qpso.jsonl
echo "QPSO BACKFILL DONE: $(date)"
./.venv/Scripts/python.exe study_tpe.py --trials 16 --n-prompt 128 --n-gen 32 --reps 2 --log results/overnight_tpe.jsonl
echo "TPE BACKFILL DONE: $(date)"
./.venv/Scripts/python.exe baselines/qiea.py --individuals 4 --generations 6 --n-prompt 128 --n-gen 32 --reps 2 --seed 1 --log results/overnight_qiea.jsonl
echo "QIEA BACKFILL DONE: $(date)"
echo "ALL_DONE"
