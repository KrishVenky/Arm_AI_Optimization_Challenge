"""Optuna TPE study: the sample-efficient search over per-layer quant configs.

Single-objective for now (combined_score = speed + size, see fitness.py) --
switch to optuna.samplers.NSGAIISampler with three objectives once the
accuracy eval harness exists, so the Pareto front actually includes accuracy
instead of a placeholder weighting.

Usage:
    python study_tpe.py --trials 20 --n-prompt 128 --n-gen 32 --reps 2
"""
from __future__ import annotations

import argparse
import json
import logging
import time
from pathlib import Path

import optuna

from fitness import combined_score, score_candidate
from search_space import N_BLOCKS, QUANT_CHOICES, Candidate

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
log = logging.getLogger("study_tpe")


def suggest_candidate(trial: optuna.Trial) -> Candidate:
    block_types = tuple(
        trial.suggest_categorical(f"blk_{i}", QUANT_CHOICES) for i in range(N_BLOCKS)
    )
    return Candidate(block_types)


def make_objective(n_prompt: int, n_gen: int, reps: int, log_path: Path):
    def objective(trial: optuna.Trial) -> float:
        candidate = suggest_candidate(trial)
        started = time.monotonic()
        try:
            obj = score_candidate(candidate, n_prompt=n_prompt, n_gen=n_gen, reps=reps)
        except Exception as e:
            # Unlike random_search.py/qpso.py/qiea.py/ga.py, this used to let
            # optuna's catch=(Exception,) swallow the failure silently -- the
            # trial showed up as "[W ... Trial N failed with value None]" in
            # stderr but never got a JSONL record, so results/*.jsonl always
            # undercounted TPE's real failure rate (e.g. 8/16 lost silently
            # in one run -- see chat/commit history). Log it like everything
            # else does, then let it propagate so optuna still marks the
            # trial FAILED.
            elapsed = time.monotonic() - started
            record = {"trial": trial.number, "error": str(e)[:500], "elapsed_s": elapsed,
                       "block_types": candidate.block_types}
            with log_path.open("a") as f:
                f.write(json.dumps(record) + "\n")
            log.error("trial %d FAILED (%.1fs): %s", trial.number, elapsed, str(e)[:200])
            raise
        score = combined_score(obj)
        elapsed = time.monotonic() - started

        record = {
            "trial": trial.number,
            "score": score,
            "pp_tok_s": obj.prompt_processing_tok_s,
            "tg_tok_s": obj.token_generation_tok_s,
            "model_size_bytes": obj.model_size_bytes,
            "elapsed_s": elapsed,
            "block_types": candidate.block_types,
        }
        with log_path.open("a") as f:
            f.write(json.dumps(record) + "\n")
        log.info(
            "trial %d: score=%.4f pp=%.1f tok/s size=%.0fMiB (%.1fs)",
            trial.number, score, obj.prompt_processing_tok_s,
            obj.model_size_bytes / 2**20, elapsed,
        )
        return score

    return objective


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--trials", type=int, default=20)
    parser.add_argument("--n-prompt", type=int, default=128)
    parser.add_argument("--n-gen", type=int, default=32)
    parser.add_argument("--reps", type=int, default=2)
    parser.add_argument("--log", type=str, default="results/tpe_trials.jsonl")
    args = parser.parse_args()

    log_path = Path(args.log)
    log_path.parent.mkdir(parents=True, exist_ok=True)

    study = optuna.create_study(
        direction="maximize",
        sampler=optuna.samplers.TPESampler(seed=0),
    )
    study.optimize(
        make_objective(args.n_prompt, args.n_gen, args.reps, log_path),
        n_trials=args.trials,
        # A candidate can fail for reasons unrelated to the search (adb bridge
        # torn down mid-command, etc. -- see fitness.py's retry docstring).
        # fitness.score_candidate already retries once; if it still raises,
        # mark this one trial FAILED and keep going rather than losing the
        # whole study's data.
        catch=(Exception,),
    )

    log.info("best score: %.4f", study.best_value)
    log.info("best config: %s", study.best_params)


if __name__ == "__main__":
    main()
