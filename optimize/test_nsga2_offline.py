"""Offline smoke test for study_nsga2.py -- no phone needed. NSGA-II itself
is Optuna's well-tested library code, not something being reimplemented
here, so this isn't validating the algorithm -- it's checking that the
objective/logging/Pareto-front wiring in study_nsga2.py actually runs
end-to-end and produces a sane non-dominated set, by monkeypatching
score_candidate to a fast synthetic function instead of a real device call.

Usage: python test_nsga2_offline.py
"""
from __future__ import annotations

import random
from dataclasses import dataclass
from pathlib import Path

import optuna

import study_nsga2
from fitness import Objectives
from search_space import N_BLOCKS, QUANT_CHOICES


def fake_score_candidate(candidate, n_prompt, n_gen, reps) -> Objectives:
    """Deterministic synthetic objectives with a genuine speed/accuracy
    tradeoff (more Q8_0 -> higher accuracy, lower speed; more Q4_0 -> lower
    accuracy, higher speed), so a real Pareto front should emerge instead of
    a single dominant point."""
    rng = random.Random(hash(candidate.block_types) & 0xFFFFFFFF)
    rank = {"Q4_0": 0, "Q5_K": 1, "Q6_K": 2, "Q8_0": 3}
    avg_rank = sum(rank[t] for t in candidate.block_types) / N_BLOCKS
    accuracy = 0.5 + 0.4 * (avg_rank / 3) + rng.uniform(-0.02, 0.02)
    pp_tok_s = 200 - 40 * avg_rank + rng.uniform(-5, 5)
    size_bytes = 700_000_000 + int(150_000_000 * avg_rank)
    return Objectives(
        prompt_processing_tok_s=pp_tok_s,
        token_generation_tok_s=3.0,
        model_size_bytes=size_bytes,
        accuracy=min(1.0, max(0.0, accuracy)),
    )


def main() -> None:
    study_nsga2.score_candidate = fake_score_candidate  # patch before make_objective closes over it

    log_path = Path("results/_test_nsga2_offline.jsonl")
    if log_path.exists():
        log_path.unlink()

    study = optuna.create_study(
        directions=["maximize", "maximize", "minimize"],
        sampler=optuna.samplers.NSGAIISampler(seed=0, population_size=4),
    )
    study.optimize(
        study_nsga2.make_objective(n_prompt=1, n_gen=1, reps=1, log_path=log_path),
        n_trials=16,
    )

    print(f"total trials: {len(study.trials)}")
    print(f"Pareto front: {len(study.best_trials)} non-dominated trials")
    for t in study.best_trials:
        print(f"  accuracy={t.values[0]:.3f} pp={t.values[1]:.1f} size={t.values[2]/2**20:.0f}MiB")

    assert len(study.trials) == 16, "not all trials completed"
    assert len(study.best_trials) >= 2, (
        "expected a real Pareto front (>=2 non-dominated points) given the synthetic "
        "accuracy/speed tradeoff -- got a single dominant point, which would suggest "
        "the multi-objective wiring collapsed to single-objective behavior"
    )
    assert log_path.exists() and len(log_path.read_text().splitlines()) == 16, "JSONL logging didn't capture all trials"
    log_path.unlink()

    print("\nOK: NSGA-II wiring runs end-to-end and finds a real (>=2-point) Pareto front.")


if __name__ == "__main__":
    main()
