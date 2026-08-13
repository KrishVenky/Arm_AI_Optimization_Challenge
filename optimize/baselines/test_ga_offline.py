"""Offline correctness check for ga.py -- no phone needed. Same synthetic
needle-in-haystack objective as test_qiea_offline.py (fraction of blocks
matching a hidden target pattern), so results from the two are directly
comparable at equal eval budget.

Says nothing about real device performance -- see README.md.

Usage: python baselines/test_ga_offline.py
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from ga import search  # noqa: E402
from test_qiea_offline import make_fitness, make_target, random_baseline  # noqa: E402
from search_space import N_BLOCKS, QUANT_CHOICES  # noqa: E402


def main() -> None:
    target = make_target(seed=42)
    fitness = make_fitness(target)

    population, generations = 6, 15
    n_trials = population * generations

    ga_best_score, ga_best_candidate = search(population, generations, fitness, seed=1)
    random_best_score = random_baseline(fitness, n_trials, seed=1)

    print(f"target:          {target.block_types}")
    print(f"GA best:         {ga_best_score:.4f}  {ga_best_candidate.block_types}")
    print(f"random baseline: {random_best_score:.4f}  (same {n_trials}-eval budget)")

    random_expected = 1.0 / len(QUANT_CHOICES)
    assert ga_best_score > random_expected * 1.5, (
        f"GA ({ga_best_score:.4f}) didn't clearly beat chance ({random_expected:.4f}) "
        "-- crossover/mutation/selection is likely broken, not just unlucky"
    )
    assert ga_best_score >= random_best_score, (
        f"GA ({ga_best_score:.4f}) didn't beat the random-search baseline "
        f"({random_best_score:.4f}) at equal eval budget -- it isn't earning its complexity"
    )
    print("\nOK: GA converges toward the hidden target and beats a random baseline "
          "at equal eval budget on this synthetic objective.")


if __name__ == "__main__":
    main()
