"""QIEA (the real quantum contender): a quantum-inspired estimation-of-
distribution algorithm. One block's categorical quant-type choice is encoded
as BITS_PER_GENE qubits; unlike the textbook Han & Kim (2002) rotation-gate
table this started as, the update here is a derived policy gradient, not a
lookup table -- see "The math" below.

This differs from baselines/qpso.py in more than name: QPSO moves a
continuous position in R^N_BLOCKS and rounds to the nearest discrete choice
after the fact -- a mismatch for a genuinely categorical space, which is why
the README treats it as a weak control rather than a real contender against
TPE. QIEA represents each choice as qubits and never leaves the discrete
space to begin with.

Architecture: ONE shared qubit register (a single probability distribution
over the 26-block x 2-bit space), not one per individual. Each generation
samples n_individuals classical candidates from it, evaluates them, and
folds all of their scores into a single distribution update. Real device
evaluations are the scarce resource here (~3 minutes each) -- splitting a
~20-30-trial budget across N independent per-individual registers (the
original version of this file did that) means each register only ever sees
a handful of its own samples to learn from. A shared distribution spends
every trial on the same set of parameters.

The math:

Each qubit's P(observe=1) = sin^2(theta) (the Born rule). We want to do
gradient ascent on expected fitness J(theta) = E_{x~p_theta}[f(x)] using the
score-function/REINFORCE estimator:

    d/dtheta_j J = E[ (f(x) - b) * d/dtheta_j log p(x_j; theta_j) ]

with a baseline b for variance reduction. Two choices make this more than a
generic policy gradient:

1. Fitness shaping (score_fn's caller, nes_weights): raw device scores are
   noisy and can have outlier scale (a failed/thermal-throttled trial should
   not swing the update as hard as a clean one). Rank-transforming the
   generation's scores into fixed, zero-sum weights before using them as the
   advantage is the same fitness-shaping trick CMA-ES and Natural Evolution
   Strategies use (Hansen; Wierstra et al. 2014) specifically because it's
   invariant to monotonic transforms of the fitness and bounded regardless
   of how noisy raw scores get.

2. The angle parameterization is not just "for the quantum flavor" -- it's
   the natural coordinates for this problem. The Fisher information of a
   single qubit under this parameterization is
       I(theta) = E[score(x;theta)^2] = 4  for every theta,
   constant regardless of how close p is to 0, 0.5, or 1 (short calculation:
   score = 2cot(theta) w.p. sin^2(theta), -2tan(theta) w.p. cos^2(theta);
   E[score^2] = 4cos^2(theta) + 4sin^2(theta) = 4). A plain gradient step in
   theta is therefore already a natural-gradient step (constant step size in
   KL-divergence terms) -- no Fisher-matrix inversion needed. This is the
   same reason statisticians use the arcsine/angular transform
   theta = arcsin(sqrt(p)) to stabilize the variance of a binomial
   proportion (Fisher 1922; Anscombe 1948). PBIL-style algorithms that
   update p directly slow to a crawl as p approaches 0 or 1; updating theta
   does not have that problem.

Evaluation budget is matched to study_tpe.py / random_search.py / qpso.py:
n_individuals * n_generations must equal --trials in the other scripts for
the four-way comparison to be fair.

Usage:
    python baselines/qiea.py --individuals 5 --generations 4 --n-prompt 128 --n-gen 32 --reps 2
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
from typing import Callable

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from fitness import combined_score, score_candidate  # noqa: E402
from search_space import N_BLOCKS, QUANT_CHOICES, Candidate  # noqa: E402

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
log = logging.getLogger("qiea")

N_CHOICES = len(QUANT_CHOICES)
BITS_PER_GENE = max(1, math.ceil(math.log2(N_CHOICES)))
N_QUBITS = N_BLOCKS * BITS_PER_GENE

THETA_EPS = 1e-3
THETA_INIT = math.pi / 4  # p=0.5: equal superposition, no prior


def decode(bits: list[int]) -> Candidate:
    idxs = []
    for g in range(N_BLOCKS):
        chunk = bits[g * BITS_PER_GENE:(g + 1) * BITS_PER_GENE]
        val = 0
        for b in chunk:
            val = (val << 1) | b
        idxs.append(min(N_CHOICES - 1, val))
    return Candidate(tuple(QUANT_CHOICES[i] for i in idxs))


def observe(theta: list[float], rng: random.Random) -> list[int]:
    """Sample one classical bit string from the shared qubit distribution."""
    bits = []
    for t in theta:
        p1 = math.sin(t) ** 2
        bits.append(1 if rng.random() < p1 else 0)
    return bits


def score_fn(theta: float, bit: int) -> float:
    """d/dtheta log P(bit; theta) under P(1) = sin^2(theta). See module
    docstring for why this parameterization is also the natural gradient."""
    if bit == 1:
        return 2.0 / math.tan(theta)
    return -2.0 * math.tan(theta)


def nes_weights(n: int) -> list[float]:
    """Rank-based fitness-shaping weights (Hansen / Wierstra et al. 2014),
    best-to-worst, summing to zero -- a bounded, scale-invariant advantage
    signal in place of raw (noisy, unbounded-scale) device scores."""
    raw = [max(0.0, math.log(n / 2 + 1) - math.log(k)) for k in range(1, n + 1)]
    total = sum(raw)
    if total == 0:
        return [0.0] * n
    return [w / total - 1.0 / n for w in raw]


def distribution_entropy(theta: list[float]) -> float:
    """Total Shannon entropy (bits) of the qubit distribution -- 0 when every
    qubit has collapsed to a point mass, N_QUBITS at the initial uniform
    superposition. A convergence diagnostic, logged but not acted on."""
    h = 0.0
    for t in theta:
        p = min(1.0 - 1e-9, max(1e-9, math.sin(t) ** 2))
        h += -(p * math.log2(p) + (1 - p) * math.log2(1 - p))
    return h


def search(
    n_individuals: int,
    n_generations: int,
    evaluate_fn: Callable[[Candidate], float],
    seed: int = 0,
    alpha: float = 0.2,
    mutation_rate: float = 0.0,
    on_trial: Callable[[dict], None] | None = None,
) -> tuple[float, Candidate | None]:
    """Core QIEA loop, decoupled from device I/O so it can run against any
    evaluate_fn -- the real on-device one in main() below, or a synthetic one
    in test_qiea_offline.py for a correctness check with no phone attached.
    """
    rng = random.Random(seed)
    theta = [THETA_INIT] * N_QUBITS
    best_score = float("-inf")
    best_candidate: Candidate | None = None
    trial = 0

    for gen in range(n_generations):
        gen_bits: list[list[int]] = []
        gen_scores: list[float] = []

        for i in range(n_individuals):
            bits = observe(theta, rng)
            candidate = decode(bits)
            started = time.monotonic()
            try:
                score = evaluate_fn(candidate)
            except Exception as e:
                elapsed = time.monotonic() - started
                if on_trial:
                    on_trial({"trial": trial, "generation": gen, "individual": i,
                              "error": str(e)[:500], "elapsed_s": elapsed,
                              "block_types": candidate.block_types})
                log.error("gen %d individual %d FAILED (%.1fs): %s", gen, i, elapsed, str(e)[:200])
                trial += 1
                continue
            elapsed = time.monotonic() - started
            gen_bits.append(bits)
            gen_scores.append(score)
            if score > best_score:
                best_score, best_candidate = score, candidate
            if on_trial:
                on_trial({"trial": trial, "generation": gen, "individual": i,
                          "score": score, "elapsed_s": elapsed,
                          "block_types": candidate.block_types})
            log.info("gen %d individual %d: score=%.4f (%.1fs)", gen, i, score, elapsed)
            trial += 1

        if len(gen_scores) < 2:
            log.error("gen %d: fewer than 2 successful evals, skipping distribution update", gen)
            continue

        ranked = sorted(zip(gen_bits, gen_scores), key=lambda t: t[1], reverse=True)
        weights = nes_weights(len(ranked))

        grad = [0.0] * N_QUBITS
        for (bits, _score), w in zip(ranked, weights):
            for j in range(N_QUBITS):
                grad[j] += w * score_fn(theta[j], bits[j])

        theta = [
            min(math.pi / 2 - THETA_EPS, max(THETA_EPS, t + alpha * g))
            for t, g in zip(theta, grad)
        ]

        if mutation_rate > 0:
            theta = [THETA_INIT if rng.random() < mutation_rate else t for t in theta]

        log.info("gen %d: distribution entropy=%.1f / %d bits", gen, distribution_entropy(theta), N_QUBITS)

    return best_score, best_candidate


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--individuals", type=int, default=5)
    parser.add_argument("--generations", type=int, default=4)
    parser.add_argument("--n-prompt", type=int, default=128)
    parser.add_argument("--n-gen", type=int, default=32)
    parser.add_argument("--reps", type=int, default=2)
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--alpha", type=float, default=0.2,
                         help="gradient-ascent step size on the qubit angles (natural-gradient units)")
    parser.add_argument("--mutation-rate", type=float, default=0.0,
                         help="per-qubit probability of resetting to equal superposition each generation")
    parser.add_argument("--log", type=str, default="results/qiea_trials.jsonl")
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
        args.individuals, args.generations, evaluate, seed=args.seed,
        alpha=args.alpha, mutation_rate=args.mutation_rate, on_trial=on_trial,
    )

    log.info("best score: %.4f", best_score)
    log.info("best config: %s", best_candidate.block_types if best_candidate else None)


if __name__ == "__main__":
    main()
