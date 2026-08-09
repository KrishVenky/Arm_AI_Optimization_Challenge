"""Offline correctness check for qiea.py's rotation-gate update -- no phone
needed. Uses a synthetic combinatorial fitness (fraction of blocks matching a
hidden target pattern) instead of score_candidate/quantize_and_bench, so all
this proves is that the *algorithm* converges on a discrete needle-in-haystack
problem it has no business solving by chance alone.

It says nothing about whether QIEA beats TPE on the real quantize+bench
objective -- that comparison still needs an actual device run (see README.md).
Do not cite these numbers as search results.

Usage: python baselines/test_qiea_offline.py
"""
from __future__ import annotations

import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from qiea import search  # noqa: E402
from search_space import N_BLOCKS, QUANT_CHOICES, Candidate  # noqa: E402


def make_target(seed: int) -> Candidate:
    rng = random.Random(seed)
    return Candidate(tuple(rng.choice(QUANT_CHOICES) for _ in range(N_BLOCKS)))


def make_fitness(target: Candidate):
    def fitness(candidate: Candidate) -> float:
        matches = sum(a == b for a, b in zip(candidate.block_types, target.block_types))
        return matches / N_BLOCKS
    return fitness


def random_baseline(fitness, n_trials: int, seed: int) -> float:
    rng = random.Random(seed)
    best = float("-inf")
    for _ in range(n_trials):
        candidate = Candidate(tuple(rng.choice(QUANT_CHOICES) for _ in range(N_BLOCKS)))
        best = max(best, fitness(candidate))
    return best


def main() -> None:
    target = make_target(seed=42)
    fitness = make_fitness(target)

    individuals, generations = 6, 15
    n_trials = individuals * generations

    qiea_best_score, qiea_best_candidate = search(
        individuals, generations, fitness, seed=1,
    )
    random_best_score = random_baseline(fitness, n_trials, seed=1)

    print(f"target:          {target.block_types}")
    print(f"QIEA best:       {qiea_best_score:.4f}  {qiea_best_candidate.block_types}")
    print(f"random baseline: {random_best_score:.4f}  (same {n_trials}-eval budget)")

    random_expected = 1.0 / len(QUANT_CHOICES)  # expected match fraction of one random guess
    assert qiea_best_score > random_expected * 1.5, (
        f"QIEA ({qiea_best_score:.4f}) didn't clearly beat chance ({random_expected:.4f}) "
        "-- rotation-gate update is likely broken, not just unlucky"
    )
    assert qiea_best_score >= random_best_score, (
        f"QIEA ({qiea_best_score:.4f}) didn't beat the random-search baseline "
        f"({random_best_score:.4f}) at equal eval budget -- it isn't earning its complexity"
    )
    print("\nOK: QIEA converges toward the hidden target and beats a random baseline "
          "at equal eval budget on this synthetic objective.")


if __name__ == "__main__":
    main()
