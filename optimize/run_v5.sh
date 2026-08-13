#!/bin/bash
# Real (on-device) test of the joint-memory hypothesis: does GA's crossover
# (explicit recombination of contiguous block combinations) beat QIEA's
# independent-per-block probability model on the real fitness landscape?
# Matched 16-trial budget each, same as the v3 QPSO-vs-QIEA test.
set -x
echo "V5 START: $(date)"
./.venv/Scripts/python.exe baselines/ga.py --population 4 --generations 4 --n-prompt 128 --n-gen 32 --reps 2 --log results/v5_ga.jsonl
echo "GA DONE: $(date)"
./.venv/Scripts/python.exe baselines/qiea.py --individuals 4 --generations 4 --n-prompt 128 --n-gen 32 --reps 2 --seed 2 --log results/v5_qiea.jsonl
echo "QIEA DONE: $(date)"
echo "ALL_DONE"
