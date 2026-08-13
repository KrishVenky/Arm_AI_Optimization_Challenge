"""NSGA-II multi-objective search: instead of collapsing accuracy/speed/size
into one scalar the way random_search.py/qpso.py/qiea.py/baselines/ga.py/
study_tpe.py all do via fitness.py's combined_score, this searches for the
actual Pareto front directly -- non-dominated sorting genetic algorithm II
(Deb et al. 2002), via Optuna's built-in NSGAIISampler. study_tpe.py's own
docstring already flagged this as the natural next step "once the accuracy
eval harness exists, so the Pareto front actually includes accuracy instead
of a placeholder weighting" -- that harness (accuracy.py + eval_data.py) now
exists.

Three objectives: maximize accuracy, maximize prompt-processing tok/s,
minimize model size. No fixed weighting -- the useful output isn't a single
"best" candidate but a set of Pareto-optimal ones (nothing else in the set
beats a given candidate on all three axes at once), so a write-up can pick a
tradeoff point after the fact (fastest candidate above some accuracy floor,
say) instead of committing to combined_score's 0.6/0.25/0.15 weights
upfront.

population_size is set explicitly small (default 6) -- Optuna's NSGAIISampler
default (50) assumes a budget of hundreds of trials, wildly mismatched with
what a ~3-minute-per-real-evaluation search can afford here.

Usage:
    python study_nsga2.py --trials 24 --population-size 6 --n-prompt 128 --n-gen 32 --reps 2
"""
from __future__ import annotations

import argparse
import json
import logging
import time
from pathlib import Path

import optuna

from fitness import score_candidate
from search_space import N_BLOCKS, QUANT_CHOICES, Candidate

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
log = logging.getLogger("study_nsga2")


def suggest_candidate(trial: optuna.Trial) -> Candidate:
    block_types = tuple(
        trial.suggest_categorical(f"blk_{i}", QUANT_CHOICES) for i in range(N_BLOCKS)
    )
    return Candidate(block_types)


def make_objective(n_prompt: int, n_gen: int, reps: int, log_path: Path):
    def objective(trial: optuna.Trial) -> tuple[float, float, float]:
        candidate = suggest_candidate(trial)
        started = time.monotonic()
        obj = score_candidate(candidate, n_prompt=n_prompt, n_gen=n_gen, reps=reps)
        elapsed = time.monotonic() - started

        record = {
            "trial": trial.number,
            "accuracy": obj.accuracy,
            "pp_tok_s": obj.prompt_processing_tok_s,
            "tg_tok_s": obj.token_generation_tok_s,
            "model_size_bytes": obj.model_size_bytes,
            "elapsed_s": elapsed,
            "block_types": candidate.block_types,
        }
        with log_path.open("a") as f:
            f.write(json.dumps(record) + "\n")
        log.info(
            "trial %d: accuracy=%.4f pp=%.1f tok/s size=%.0fMiB (%.1fs)",
            trial.number, obj.accuracy, obj.prompt_processing_tok_s,
            obj.model_size_bytes / 2**20, elapsed,
        )
        return obj.accuracy, obj.prompt_processing_tok_s, obj.model_size_bytes

    return objective


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--trials", type=int, default=24)
    parser.add_argument("--population-size", type=int, default=6)
    parser.add_argument("--n-prompt", type=int, default=128)
    parser.add_argument("--n-gen", type=int, default=32)
    parser.add_argument("--reps", type=int, default=2)
    parser.add_argument("--log", type=str, default="results/nsga2_trials.jsonl")
    args = parser.parse_args()

    log_path = Path(args.log)
    log_path.parent.mkdir(parents=True, exist_ok=True)

    study = optuna.create_study(
        directions=["maximize", "maximize", "minimize"],  # accuracy, pp tok/s, size
        sampler=optuna.samplers.NSGAIISampler(seed=0, population_size=args.population_size),
    )
    study.optimize(
        make_objective(args.n_prompt, args.n_gen, args.reps, log_path),
        n_trials=args.trials,
        # A candidate can fail for reasons unrelated to the search (adb bridge
        # torn down mid-command, etc. -- see fitness.py's retry docstring).
        catch=(Exception,),
    )

    log.info("Pareto front: %d non-dominated trials out of %d", len(study.best_trials), len(study.trials))
    for t in study.best_trials:
        log.info(
            "  trial %d: accuracy=%.4f pp=%.1f tok/s size=%.0fMiB",
            t.number, t.values[0], t.values[1], t.values[2] / 2**20,
        )


if __name__ == "__main__":
    main()
