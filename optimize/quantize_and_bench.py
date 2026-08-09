"""Apply one search candidate on-device: quantize it with llama-quantize, measure
it with llama-bench, clean up, return real numbers. No estimates, no simulation.

Every call here costs real device minutes -- see optimize/README.md for the
two-fidelity plan (this module is the expensive/full-fidelity path; a fast
low-fidelity path with fewer reps and no cooldown is the -p/-n/-r knobs below).
"""
from __future__ import annotations

import json
import tempfile
import time
import uuid
from dataclasses import dataclass
from pathlib import Path

from device import ON_DEVICE_ROOT, push, remote, rm, shell
from search_space import BASE_TYPE, Candidate

SOURCE_MODEL = "gemma-3-1b-it-BF16.gguf"


class QuantizeError(RuntimeError):
    pass


class BenchError(RuntimeError):
    pass


@dataclass
class Measurement:
    model_name: str  # on-device filename -- still present, caller must cleanup()
    model_size_bytes: int
    prompt_processing_tok_s: float
    token_generation_tok_s: float
    wall_seconds: float  # quantize + bench wall time for this evaluation


def cleanup(model_name: str) -> None:
    rm(model_name)


def _quantize(candidate: Candidate, out_name: str) -> None:
    # A full 26-block candidate produces ~2.7KB of --tensor-type flags,
    # which adb shell silently truncates (its effective command-line limit
    # is close to 1KB). Push the overrides as a file instead: no such limit
    # over adb push, and it's what --tensor-type-file is for.
    type_file_name = f"types_{uuid.uuid4().hex[:8]}.txt"
    with tempfile.TemporaryDirectory() as tmp:
        local_path = Path(tmp) / type_file_name
        local_path.write_text(candidate.tensor_type_file_lines())
        push(str(local_path), type_file_name)

    try:
        result = shell(
            f"{ON_DEVICE_ROOT}/llama-quantize",
            "--tensor-type-file", remote(type_file_name),
            remote(SOURCE_MODEL),
            remote(out_name),
            BASE_TYPE,
            timeout=180,
        )
    finally:
        rm(type_file_name)

    if result.returncode != 0:
        raise QuantizeError(result.stdout[-2000:] + result.stderr[-2000:])


def _bench(model_name: str, n_prompt: int, n_gen: int, reps: int) -> Measurement:
    result = shell(
        f"{ON_DEVICE_ROOT}/llama-bench",
        "-m", remote(model_name),
        "-p", str(n_prompt),
        "-n", str(n_gen),
        "-r", str(reps),
        "-o", "json",
        timeout=600,
    )
    if result.returncode != 0:
        raise BenchError(result.stdout[-2000:] + result.stderr[-2000:])
    records = json.loads(result.stdout)
    pp = next(r for r in records if r["n_prompt"] > 0)
    tg = next(r for r in records if r["n_gen"] > 0)
    return Measurement(
        model_name=model_name,
        model_size_bytes=pp["model_size"],
        prompt_processing_tok_s=pp["avg_ts"],
        token_generation_tok_s=tg["avg_ts"],
        wall_seconds=0.0,  # filled in by evaluate()
    )


def evaluate(
    candidate: Candidate,
    n_prompt: int = 512,
    n_gen: int = 128,
    reps: int = 5,
    out_name: str | None = None,
) -> Measurement:
    """Quantize a candidate on-device and bench it. Does NOT delete the quantized
    file (accuracy.py needs it too) -- caller must call cleanup(measurement.model_name)
    when done with it.

    Defaults (n_prompt=512, n_gen=128, reps=5) match docs/BENCHMARKS.md's
    llama-bench defaults for comparability. Pass smaller values for the cheap
    low-fidelity screening pass in a multi-fidelity search.
    """
    started = time.monotonic()
    out_name = out_name or f"cand_{uuid.uuid4().hex[:8]}.gguf"
    _quantize(candidate, out_name)
    measurement = _bench(out_name, n_prompt, n_gen, reps)
    measurement.wall_seconds = time.monotonic() - started
    return measurement
