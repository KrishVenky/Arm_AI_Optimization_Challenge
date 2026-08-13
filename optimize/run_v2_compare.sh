#!/bin/bash
set -x
echo "START: $(date)"
./.venv/Scripts/python.exe baselines/random_search.py --trials 9 --n-prompt 128 --n-gen 32 --reps 2 --log results/v2_random.jsonl
echo "RANDOM DONE: $(date)"
./.venv/Scripts/python.exe baselines/qpso.py --particles 3 --generations 3 --n-prompt 128 --n-gen 32 --reps 2 --log results/v2_qpso.jsonl
echo "QPSO DONE: $(date)"
./.venv/Scripts/python.exe study_tpe.py --trials 9 --n-prompt 128 --n-gen 32 --reps 2 --log results/v2_tpe.jsonl
echo "TPE DONE: $(date)"
echo "ALL_DONE"
