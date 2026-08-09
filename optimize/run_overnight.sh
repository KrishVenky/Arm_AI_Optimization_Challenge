#!/bin/bash
set -x
echo "START: $(date)"
./.venv/Scripts/python.exe baselines/random_search.py --trials 24 --n-prompt 128 --n-gen 32 --reps 2 --log results/overnight_random.jsonl
echo "RANDOM DONE: $(date)"
./.venv/Scripts/python.exe baselines/qpso.py --particles 4 --generations 6 --n-prompt 128 --n-gen 32 --reps 2 --log results/overnight_qpso.jsonl
echo "QPSO DONE: $(date)"
./.venv/Scripts/python.exe baselines/qiea.py --individuals 4 --generations 6 --n-prompt 128 --n-gen 32 --reps 2 --log results/overnight_qiea.jsonl
echo "QIEA DONE: $(date)"
./.venv/Scripts/python.exe study_tpe.py --trials 24 --n-prompt 128 --n-gen 32 --reps 2 --log results/overnight_tpe.jsonl
echo "TPE DONE: $(date)"
echo "ALL_DONE"
