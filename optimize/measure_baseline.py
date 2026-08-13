"""Measure this device's own F16-ish (actually BF16) prompt-processing
baseline directly -- fitness.py's combined_score() needs a per-device speed
number to normalize against. Model size needs no measurement: the source
GGUF is bit-identical on every device (same HuggingFace re-upload), only
speed varies by chip.

Run once per new device before trusting combined_score()'s absolute
magnitude across devices. A run that never calls this falls back to the
Pixel 9a numbers from docs/BENCHMARKS.md -- fine for ranking search
algorithms against each other within one run on one device (a constant
scale factor doesn't change relative order), not for comparing absolute
scores across different devices, which is what this file fixes.

Usage: python measure_baseline.py
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from device import ON_DEVICE_ROOT, remote, shell
from quantize_and_bench import BenchError, SOURCE_MODEL


def _bench_pp_only(model_name: str, n_prompt: int, reps: int):
    """Prompt-processing-only bench (n_gen=0 skips llama-bench's generation
    test entirely). combined_score's speed_term only ever reads
    prompt_processing_tok_s, and on this device the unquantized BF16 source
    model's token-generation phase reliably crashed llama-bench (likely
    memory pressure from the doubled f16 KV-cache at this context length) --
    skip a measurement we don't need instead of working around the crash.
    """
    result = shell(
        f"{ON_DEVICE_ROOT}/llama-bench",
        "-m", remote(model_name),
        "-p", str(n_prompt),
        "-n", "0",
        "-r", str(reps),
        "-o", "json",
        timeout=600,
    )
    if result.returncode != 0:
        raise BenchError(result.stdout[-2000:] + result.stderr[-2000:])
    records = json.loads(result.stdout)
    pp = next(r for r in records if r["n_prompt"] > 0)
    return pp["avg_ts"], pp["model_size"]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--n-prompt", type=int, default=128)
    parser.add_argument("--reps", type=int, default=3)
    parser.add_argument("--out", type=str, default="device_baseline.json")
    args = parser.parse_args()

    pp_tok_s, model_size_bytes = _bench_pp_only(SOURCE_MODEL, args.n_prompt, args.reps)
    baseline = {
        "f16_pp_baseline": pp_tok_s,
        "f16_size_baseline": model_size_bytes,
        "measured_n_prompt": args.n_prompt,
        "measured_reps": args.reps,
    }
    Path(args.out).write_text(json.dumps(baseline, indent=2) + "\n")
    print(f"wrote {args.out}: {baseline}")


if __name__ == "__main__":
    main()
