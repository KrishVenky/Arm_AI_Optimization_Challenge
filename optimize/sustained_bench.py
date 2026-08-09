"""Sustained-load measurement: the actual motivation for this search (see chat
history -- thermal throttling, not just peak throughput). llama-bench already
runs its repetitions back-to-back with no cooldown and returns each rep's
tok/s in samples_ts, so "sustained" is just: ask for more reps, read the
trend instead of the mean. No new benchmarking mechanism needed.
"""
from __future__ import annotations

import json
from dataclasses import dataclass

from device import ON_DEVICE_ROOT, remote, shell
from quantize_and_bench import BenchError


@dataclass
class SustainedResult:
    first_rep_tok_s: float
    last_rep_tok_s: float
    min_tok_s: float
    samples: list[float]

    @property
    def degradation_pct(self) -> float:
        """How far the last rep fell from the first, as a percent. 0 = no throttling."""
        if self.first_rep_tok_s == 0:
            return 0.0
        return 100.0 * (self.first_rep_tok_s - self.last_rep_tok_s) / self.first_rep_tok_s


def measure_sustained(model_name: str, n_prompt: int = 512, n_gen: int = 128, reps: int = 10) -> SustainedResult:
    """One llama-bench invocation, no cooldown between reps by design -- this
    is meant to reproduce the throttling docs/BENCHMARKS.md found, not avoid it.
    reps=10 is a starting point; raise it if 10 reps don't run long enough to
    show throttling on a given phone (BENCHMARKS.md's throttled run needed a
    ~50-minute tg128 phase to show it clearly on the Pixel 9a).
    """
    result = shell(
        f"{ON_DEVICE_ROOT}/llama-bench",
        "-m", remote(model_name),
        "-p", str(n_prompt),
        "-n", str(n_gen),
        "-r", str(reps),
        "-o", "json",
        timeout=1800,
    )
    if result.returncode != 0:
        raise BenchError(result.stdout[-2000:] + result.stderr[-2000:])
    records = json.loads(result.stdout)
    pp = next(r for r in records if r["n_prompt"] > 0)
    samples = pp["samples_ts"]
    return SustainedResult(
        first_rep_tok_s=samples[0],
        last_rep_tok_s=samples[-1],
        min_tok_s=min(samples),
        samples=samples,
    )
