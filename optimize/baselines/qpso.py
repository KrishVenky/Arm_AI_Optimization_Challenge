"""QPSO baseline (the bio-inspired control): quantum-behaved particle swarm
optimization, standard Sun et al. formulation, with each block's discrete
quant-type choice represented as a continuous position in [0, n_choices)
that gets rounded to the nearest choice for fitness evaluation.

Evaluation budget is matched to study_tpe.py and random_search.py:
n_particles * n_generations must equal --trials in the other two scripts for
the three-way comparison to be fair.

Usage:
    python baselines/qpso.py --particles 5 --generations 4 --n-prompt 128 --n-gen 32 --reps 2
"""
from __future__ import annotations

import argparse
import json
import logging
import math
import random
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from fitness import combined_score, score_candidate  # noqa: E402
from search_space import N_BLOCKS, QUANT_CHOICES, Candidate  # noqa: E402

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
log = logging.getLogger("qpso")

N_CHOICES = len(QUANT_CHOICES)


def decode(position: list[float]) -> Candidate:
    idxs = [min(N_CHOICES - 1, max(0, round(x))) for x in position]
    return Candidate(tuple(QUANT_CHOICES[i] for i in idxs))


def evaluate_position(position: list[float], n_prompt: int, n_gen: int, reps: int) -> float:
    candidate = decode(position)
    obj = score_candidate(candidate, n_prompt=n_prompt, n_gen=n_gen, reps=reps)
    return combined_score(obj)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--particles", type=int, default=5)
    parser.add_argument("--generations", type=int, default=4)
    parser.add_argument("--n-prompt", type=int, default=128)
    parser.add_argument("--n-gen", type=int, default=32)
    parser.add_argument("--reps", type=int, default=2)
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--beta-start", type=float, default=1.0)
    parser.add_argument("--beta-end", type=float, default=0.5)
    parser.add_argument("--log", type=str, default="results/qpso_trials.jsonl")
    args = parser.parse_args()

    log_path = Path(args.log)
    log_path.parent.mkdir(parents=True, exist_ok=True)
    rng = random.Random(args.seed)

    positions = [
        [rng.uniform(0, N_CHOICES - 1) for _ in range(N_BLOCKS)]
        for _ in range(args.particles)
    ]
    pbest = [list(p) for p in positions]
    pbest_score = [float("-inf")] * args.particles
    gbest: list[float] | None = None
    gbest_score = float("-inf")

    trial = 0
    for gen in range(args.generations):
        beta = args.beta_start - (args.beta_start - args.beta_end) * gen / max(1, args.generations - 1)

        for i, pos in enumerate(positions):
            started = time.monotonic()
            try:
                score = evaluate_position(pos, args.n_prompt, args.n_gen, args.reps)
            except Exception as e:
                # fitness.score_candidate already retried once. Don't let one
                # bad particle kill the swarm: skip updating pbest/gbest for
                # it this generation (position still moves via the QPSO update
                # below, so it gets another chance next generation).
                elapsed = time.monotonic() - started
                record = {"trial": trial, "generation": gen, "particle": i,
                           "error": str(e)[:500], "elapsed_s": elapsed,
                           "block_types": decode(pos).block_types}
                with log_path.open("a") as f:
                    f.write(json.dumps(record) + "\n")
                log.error("gen %d particle %d FAILED after retries (%.1fs): %s",
                          gen, i, elapsed, str(e)[:200])
                trial += 1
                continue
            elapsed = time.monotonic() - started

            if score > pbest_score[i]:
                pbest_score[i], pbest[i] = score, list(pos)
            if score > gbest_score:
                gbest_score, gbest = score, list(pos)

            record = {
                "trial": trial, "generation": gen, "particle": i,
                "score": score, "elapsed_s": elapsed,
                "block_types": decode(pos).block_types,
            }
            with log_path.open("a") as f:
                f.write(json.dumps(record) + "\n")
            log.info("gen %d particle %d: score=%.4f (%.1fs)", gen, i, score, elapsed)
            trial += 1

        if gbest is None:
            # every particle failed this generation (after their own retries) --
            # nothing to update toward, leave positions as-is for next generation.
            log.error("gen %d: all particles failed, skipping position update", gen)
            continue

        # mean personal-best position across the swarm (QPSO's mBest)
        mbest = [sum(pbest[i][d] for i in range(args.particles)) / args.particles for d in range(N_BLOCKS)]

        for i in range(args.particles):
            for d in range(N_BLOCKS):
                phi = rng.random()
                p = phi * pbest[i][d] + (1 - phi) * gbest[d]
                u = max(1e-9, rng.random())
                sign = 1.0 if rng.random() < 0.5 else -1.0
                new_x = p + sign * beta * abs(mbest[d] - positions[i][d]) * math.log(1.0 / u)
                positions[i][d] = min(N_CHOICES - 1, max(0, new_x))

    log.info("best score: %.4f", gbest_score)
    log.info("best config: %s", decode(gbest).block_types if gbest else None)


if __name__ == "__main__":
    main()
