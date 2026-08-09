#!/bin/bash
set -x
./.venv/Scripts/python.exe baselines/random_search.py --trials 6 --n-prompt 64 --n-gen 16 --reps 1 --log results/live_random.jsonl
./.venv/Scripts/python.exe baselines/qpso.py --particles 2 --generations 3 --n-prompt 64 --n-gen 16 --reps 1 --log results/live_qpso.jsonl
./.venv/Scripts/python.exe study_tpe.py --trials 6 --n-prompt 64 --n-gen 16 --reps 1 --log results/live_tpe.jsonl
echo "ALL_DONE"
