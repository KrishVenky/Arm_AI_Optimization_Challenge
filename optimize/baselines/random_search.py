"""Random-search control: same search space, same fitness, same trial budget
as study_tpe.py. This is the comparison that makes a TPE/QPSO result
defensible -- if TPE or QPSO can't beat this at equal evaluation count, they
aren't earning their complexity.

Usage:
    python baselines/random_search.py --trials 20 --n-prompt 128 --n-gen 32 --reps 2
"""
from __future__ import annotations

import argparse
import json
import logging
import random
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from fitness import combined_score, score_candidate  # noqa: E402
from search_space import N_BLOCKS, QUANT_CHOICES, Candidate  # noqa: E402

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
log = logging.getLogger("random_search")


def random_candidate(rng: random.Random) -> Candidate:
    return Candidate(tuple(rng.choice(QUANT_CHOICES) for _ in range(N_BLOCKS)))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--trials", type=int, default=20)
    parser.add_argument("--n-prompt", type=int, default=128)
    parser.add_argument("--n-gen", type=int, default=32)
    parser.add_argument("--reps", type=int, default=2)
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--log", type=str, default="results/random_trials.jsonl")
    args = parser.parse_args()

    log_path = Path(args.log)
    log_path.parent.mkdir(parents=True, exist_ok=True)
    rng = random.Random(args.seed)

    best_score = float("-inf")
    best_candidate = None

    for trial in range(args.trials):
        candidate = random_candidate(rng)
        started = time.monotonic()
        try:
            obj = score_candidate(candidate, n_prompt=args.n_prompt, n_gen=args.n_gen, reps=args.reps)
        except Exception as e:
            # fitness.score_candidate already retried once; a second failure
            # means this trial is a loss, not the whole run -- log and move on.
            elapsed = time.monotonic() - started
            record = {"trial": trial, "error": str(e)[:500], "elapsed_s": elapsed,
                       "block_types": candidate.block_types}
            with log_path.open("a") as f:
                f.write(json.dumps(record) + "\n")
            log.error("trial %d FAILED after retries (%.1fs): %s", trial, elapsed, str(e)[:200])
            continue
        score = combined_score(obj)
        elapsed = time.monotonic() - started

        if score > best_score:
            best_score, best_candidate = score, candidate

        record = {
            "trial": trial,
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
            trial, score, obj.prompt_processing_tok_s,
            obj.model_size_bytes / 2**20, elapsed,
        )

    log.info("best score: %.4f", best_score)
    log.info("best config: %s", best_candidate.block_types if best_candidate else None)


if __name__ == "__main__":
    main()
