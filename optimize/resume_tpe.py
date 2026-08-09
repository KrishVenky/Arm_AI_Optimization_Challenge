"""Give TPE back the evaluation slots it lost to device-flake failures, informed
by everything it already learned -- not a blind rerun of the same failed configs
(those were arbitrary unlucky draws, not specifically interesting candidates).

Reconstructs the study's posterior from the successful trials in an existing
results/*.jsonl log, then lets the sampler propose N new candidates on top of
that history.

Usage: python resume_tpe.py --log results/overnight_tpe.jsonl --n-new 4
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import optuna
from optuna.distributions import CategoricalDistribution
from optuna.trial import TrialState, create_trial

from fitness import combined_score, score_candidate
from search_space import N_BLOCKS, QUANT_CHOICES, Candidate

DISTRIBUTIONS = {f"blk_{i}": CategoricalDistribution(QUANT_CHOICES) for i in range(N_BLOCKS)}


def load_completed_trials(log_path: Path) -> list:
    trials = []
    for line in log_path.read_text().splitlines():
        rec = json.loads(line)
        if "error" in rec or "block_types" not in rec:
            continue
        params = {f"blk_{i}": t for i, t in enumerate(rec["block_types"])}
        trials.append(create_trial(
            state=TrialState.COMPLETE, value=rec["score"],
            params=params, distributions=DISTRIBUTIONS,
        ))
    return trials


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--log", type=str, default="results/overnight_tpe.jsonl")
    parser.add_argument("--n-new", type=int, default=4)
    parser.add_argument("--n-prompt", type=int, default=128)
    parser.add_argument("--n-gen", type=int, default=32)
    parser.add_argument("--reps", type=int, default=2)
    parser.add_argument("--resume-log", type=str, default="results/overnight_tpe_resume.jsonl")
    args = parser.parse_args()

    history = load_completed_trials(Path(args.log))
    study = optuna.create_study(direction="maximize", sampler=optuna.samplers.TPESampler(seed=0))
    study.add_trials(history)
    print(f"Seeded with {len(history)} historical trials. Best so far: {study.best_value:.4f}")

    resume_log = Path(args.resume_log)
    resume_log.parent.mkdir(parents=True, exist_ok=True)

    def objective(trial: optuna.Trial) -> float:
        block_types = tuple(trial.suggest_categorical(f"blk_{i}", QUANT_CHOICES) for i in range(N_BLOCKS))
        candidate = Candidate(block_types)
        obj = score_candidate(candidate, n_prompt=args.n_prompt, n_gen=args.n_gen, reps=args.reps)
        score = combined_score(obj)
        record = {"score": score, "pp_tok_s": obj.prompt_processing_tok_s,
                   "model_size_bytes": obj.model_size_bytes, "block_types": candidate.block_types}
        with resume_log.open("a") as f:
            f.write(json.dumps(record) + "\n")
        print(f"resume trial: score={score:.4f} pp={obj.prompt_processing_tok_s:.1f} tok/s")
        return score

    study.optimize(objective, n_trials=args.n_new, catch=(Exception,))
    print(f"\nFinal best (20 original + {args.n_new} new): {study.best_value:.4f}")
    print(f"Final best config: {study.best_params}")


if __name__ == "__main__":
    main()
