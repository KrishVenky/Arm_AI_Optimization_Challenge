"""Fitness function wiring measured device numbers into what the search
algorithms optimize. Accuracy comes from accuracy.py's real on-device eval
(16-example smoke-test-sized set, see eval_data.py -- expand before citing
accuracy numbers in a write-up).
"""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass

from accuracy import score_accuracy
from device import PushError
from quantize_and_bench import BenchError, Measurement, QuantizeError, cleanup, evaluate
from search_space import Candidate

log = logging.getLogger("fitness")

# A candidate can fail for reasons that have nothing to do with the candidate
# itself -- e.g. the USB/adb bridge got torn down mid-command because someone
# toggled Developer Options while a quantize was in flight (seen in practice:
# QPSO's first trial died this way, output just stopped mid-tensor-list with
# no error text, classic signature of the process getting killed rather than
# erroring). One retry after a short pause covers that class of flake without
# masking a candidate that's genuinely broken (e.g. an invalid regex) -- those
# fail the same way twice.
_MAX_CANDIDATE_RETRIES = 2
_RETRY_DELAY_S = 5


@dataclass
class Objectives:
    prompt_processing_tok_s: float  # higher is better
    token_generation_tok_s: float  # higher is better
    model_size_bytes: int  # lower is better
    accuracy: float  # higher is better, fraction correct on eval_data.EXAMPLES


def score_candidate(
    candidate: Candidate,
    n_prompt: int = 512,
    n_gen: int = 128,
    reps: int = 5,
    out_name: str | None = None,
) -> Objectives:
    last_error: Exception | None = None
    for attempt in range(_MAX_CANDIDATE_RETRIES):
        try:
            m: Measurement = evaluate(candidate, n_prompt=n_prompt, n_gen=n_gen, reps=reps, out_name=out_name)
            try:
                accuracy = score_accuracy(m.model_name)
            finally:
                cleanup(m.model_name)
            return Objectives(
                prompt_processing_tok_s=m.prompt_processing_tok_s,
                token_generation_tok_s=m.token_generation_tok_s,
                model_size_bytes=m.model_size_bytes,
                accuracy=accuracy,
            )
        except (QuantizeError, BenchError, PushError) as e:
            last_error = e
            log.warning("candidate evaluation failed (attempt %d/%d): %s",
                        attempt + 1, _MAX_CANDIDATE_RETRIES, str(e)[:200])
            if attempt < _MAX_CANDIDATE_RETRIES - 1:
                time.sleep(_RETRY_DELAY_S)
    raise last_error  # same failure twice -- genuinely broken, not a flake


def combined_score(obj: Objectives) -> float:
    """Single scalar for algorithms that need one (random search, QPSO baseline).

    Accuracy dominates (0.6): a fast, small, wrong model is useless for a scam
    detector. Speed and size split the remainder, normalized against the F16
    baseline from docs/BENCHMARKS.md.
    """
    f16_pp_baseline = 19.87  # docs/BENCHMARKS.md clean-run F16 baseline
    f16_size_baseline = 2_006_573_344  # bytes, same source

    speed_term = obj.prompt_processing_tok_s / f16_pp_baseline
    size_term = 1.0 - (obj.model_size_bytes / f16_size_baseline)
    return 0.6 * obj.accuracy + 0.25 * speed_term + 0.15 * size_term
