#!/bin/bash
# Fresh 4-way comparison under the doubled (32-example) eval_data.py -- the
# overnight_*.jsonl results predate this fitness-function change and are not
# comparable to these. Smaller budget (8/leg instead of 24) given how long
# the larger eval set makes each trial and tonight's device fatigue history.
set -x
echo "V2 START: $(date)"
./.venv/Scripts/python.exe baselines/random_search.py --trials 8 --n-prompt 128 --n-gen 32 --reps 2 --log results/v2_random.jsonl
echo "RANDOM DONE: $(date)"
./.venv/Scripts/python.exe baselines/qpso.py --particles 2 --generations 4 --n-prompt 128 --n-gen 32 --reps 2 --log results/v2_qpso.jsonl
echo "QPSO DONE: $(date)"
./.venv/Scripts/python.exe baselines/qiea.py --individuals 2 --generations 4 --n-prompt 128 --n-gen 32 --reps 2 --log results/v2_qiea.jsonl
echo "QIEA DONE: $(date)"
./.venv/Scripts/python.exe study_tpe.py --trials 8 --n-prompt 128 --n-gen 32 --reps 2 --log results/v2_tpe.jsonl
echo "TPE DONE: $(date)"
echo "ALL_DONE"
