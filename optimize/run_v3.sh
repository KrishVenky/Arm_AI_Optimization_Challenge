#!/bin/bash
# Targeted test: is QIEA's underperformance in run_v2 caused by too small a
# population per generation (individuals=2 -> rank-based fitness shaping
# degenerates to "trust whichever of 2 noisy samples was better")? Same
# 16-trial budget as QPSO, reshaped toward more individuals/generation.
set -x
echo "V3 START: $(date)"
./.venv/Scripts/python.exe baselines/qpso.py --particles 4 --generations 4 --n-prompt 128 --n-gen 32 --reps 2 --log results/v3_qpso.jsonl
echo "QPSO DONE: $(date)"
./.venv/Scripts/python.exe baselines/qiea.py --individuals 4 --generations 4 --n-prompt 128 --n-gen 32 --reps 2 --log results/v3_qiea.jsonl
echo "QIEA DONE: $(date)"
echo "ALL_DONE"
