"""Genetic algorithm baseline: tournament selection, single-point crossover,
per-gene mutation, elitism -- classical, not quantum-inspired, and included
specifically to test a hypothesis from the QIEA-vs-QPSO comparison (see
chat/commit history): QIEA's per-block-independent probability model has no
way to remember a specific *combination* of blocks that scored well together,
only marginal per-block tendencies, which looks like the reason it lost to
QPSO's point-based personal-best/global-best memory. A GA's crossover
operator explicitly preserves and recombines contiguous chunks of a parent
candidate's genome -- if GA beats QIEA, that's evidence for the diagnosis;
if it doesn't, that's useful too.

Single-point (not uniform) crossover on purpose: transformer blocks are
positionally ordered (block 0 near input, block 25 near output), so there
may be real structure to preserve in a contiguous run of blocks -- uniform
crossover would shred that structure on every child.

Evaluation budget matches the other scripts: population * generations must
equal --trials elsewhere for a fair comparison. Every generation
re-evaluates the full population, including carried-over elites -- real
device fitness is noisy, so a second look at the elite is a feature, not
wasted budget, and it keeps the trials-per-generation accounting identical
to qpso.py/qiea.py's particles/individuals * generations.

Usage:
    python baselines/ga.py --population 4 --generations 4 --n-prompt 128 --n-gen 32 --reps 2
"""
from __future__ import annotations

import argparse
import json
import logging
import random
import sys
import time
from pathlib import Path
from typing import Callable

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from fitness import combined_score, score_candidate  # noqa: E402
from search_space import N_BLOCKS, QUANT_CHOICES, Candidate  # noqa: E402

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
log = logging.getLogger("ga")


def random_candidate(rng: random.Random) -> Candidate:
    return Candidate(tuple(rng.choice(QUANT_CHOICES) for _ in range(N_BLOCKS)))


def tournament_select(population: list[Candidate], scores: list[float], rng: random.Random, k: int) -> Candidate:
    """k-way tournament: sample k individuals, keep the best. Robust to a
    single noisy device measurement in a way that pure fitness-proportionate
    selection isn't (one lucky/unlucky score can't dominate the pool)."""
    idxs = rng.sample(range(len(population)), min(k, len(population)))
    best_idx = max(idxs, key=lambda i: scores[i])
    return population[best_idx]


def crossover(parent_a: Candidate, parent_b: Candidate, rng: random.Random) -> Candidate:
    point = rng.randint(1, N_BLOCKS - 1)
    child_types = parent_a.block_types[:point] + parent_b.block_types[point:]
    return Candidate(child_types)


def mutate(candidate: Candidate, rng: random.Random, mutation_rate: float) -> Candidate:
    new_types = list(candidate.block_types)
    for i in range(N_BLOCKS):
        if rng.random() < mutation_rate:
            choices = [c for c in QUANT_CHOICES if c != new_types[i]]
            new_types[i] = rng.choice(choices)
    return Candidate(tuple(new_types))


def search(
    population_size: int,
    n_generations: int,
    evaluate_fn: Callable[[Candidate], float],
    seed: int = 0,
    crossover_rate: float = 0.8,
    mutation_rate: float = 0.05,
    tournament_k: int = 3,
    elitism: int = 1,
    on_trial: Callable[[dict], None] | None = None,
) -> tuple[float, Candidate | None]:
    """Core GA loop, decoupled from device I/O -- see test_ga_offline.py for
    a no-phone correctness check against a synthetic objective."""
    rng = random.Random(seed)
    population = [random_candidate(rng) for _ in range(population_size)]
    best_score = float("-inf")
    best_candidate: Candidate | None = None
    trial = 0

    for gen in range(n_generations):
        scores: list[float] = []
        for i, candidate in enumerate(population):
            started = time.monotonic()
            try:
                score = evaluate_fn(candidate)
            except Exception as e:
                elapsed = time.monotonic() - started
                scores.append(float("-inf"))  # never selected as a parent, but keeps indices aligned
                if on_trial:
                    on_trial({"trial": trial, "generation": gen, "individual": i,
                              "error": str(e)[:500], "elapsed_s": elapsed,
                              "block_types": candidate.block_types})
                log.error("gen %d individual %d FAILED (%.1fs): %s", gen, i, elapsed, str(e)[:200])
                trial += 1
                continue
            elapsed = time.monotonic() - started
            scores.append(score)
            if score > best_score:
                best_score, best_candidate = score, candidate
            if on_trial:
                on_trial({"trial": trial, "generation": gen, "individual": i,
                          "score": score, "elapsed_s": elapsed,
                          "block_types": candidate.block_types})
            log.info("gen %d individual %d: score=%.4f (%.1fs)", gen, i, score, elapsed)
            trial += 1

        if gen == n_generations - 1:
            break  # no next generation will be evaluated -- don't bother breeding one

        ranked = sorted(range(population_size), key=lambda i: scores[i], reverse=True)
        next_population = [population[i] for i in ranked[:elitism]]
        while len(next_population) < population_size:
            parent_a = tournament_select(population, scores, rng, tournament_k)
            parent_b = tournament_select(population, scores, rng, tournament_k)
            child = crossover(parent_a, parent_b, rng) if rng.random() < crossover_rate else parent_a
            child = mutate(child, rng, mutation_rate)
            next_population.append(child)
        population = next_population

    return best_score, best_candidate


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--population", type=int, default=4)
    parser.add_argument("--generations", type=int, default=4)
    parser.add_argument("--n-prompt", type=int, default=128)
    parser.add_argument("--n-gen", type=int, default=32)
    parser.add_argument("--reps", type=int, default=2)
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--crossover-rate", type=float, default=0.8)
    parser.add_argument("--mutation-rate", type=float, default=0.05)
    parser.add_argument("--tournament-k", type=int, default=3)
    parser.add_argument("--elitism", type=int, default=1)
    parser.add_argument("--log", type=str, default="results/ga_trials.jsonl")
    args = parser.parse_args()

    log_path = Path(args.log)
    log_path.parent.mkdir(parents=True, exist_ok=True)

    def evaluate(candidate: Candidate) -> float:
        obj = score_candidate(candidate, n_prompt=args.n_prompt, n_gen=args.n_gen, reps=args.reps)
        return combined_score(obj)

    def on_trial(record: dict) -> None:
        with log_path.open("a") as f:
            f.write(json.dumps(record) + "\n")

    best_score, best_candidate = search(
        args.population, args.generations, evaluate, seed=args.seed,
        crossover_rate=args.crossover_rate, mutation_rate=args.mutation_rate,
        tournament_k=args.tournament_k, elitism=args.elitism, on_trial=on_trial,
    )

    log.info("best score: %.4f", best_score)
    log.info("best config: %s", best_candidate.block_types if best_candidate else None)


if __name__ == "__main__":
    main()
