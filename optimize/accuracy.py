"""Real accuracy scoring: run llama-simple on-device against eval_data.EXAMPLES,
parse the generated label, compare to ground truth. This is what fitness.py was
missing -- see optimize/README.md's "Status" section before this existed.

Prompt shape matches docs/BENCHMARKS.md's rationale for the benchmark model
choice (long context in, short structured answer out) and mirrors the app's
own classification task (GemmaCallAnalyzer), simplified to one label since
this is a search fitness signal, not the app's actual multi-field JSON output.
"""
from __future__ import annotations

from device import ON_DEVICE_ROOT, remote, shell
from eval_data import EXAMPLES

PROMPT_TEMPLATE = (
    "Classify the call transcript as SCAM or NOT_SCAM. "
    "Reply with exactly one word, either SCAM or NOT_SCAM.\n"
    "Transcript: {text}\n"
    "Answer:"
)

LABEL_MAP = {"SCAM": "SCAM", "SAFE": "NOT_SCAM"}  # eval_data label -> model's expected token


def _classify(model_name: str, text: str) -> str | None:
    prompt = PROMPT_TEMPLATE.format(text=text)
    result = shell(
        f"{ON_DEVICE_ROOT}/llama-simple",
        "-m", remote(model_name),
        "-n", "6",
        prompt,
        timeout=60,
    )
    output = result.stdout + result.stderr
    marker = output.rfind("Answer:")
    if marker == -1:
        return None
    tail = output[marker + len("Answer:"):].strip()
    first_word = tail.split()[0].strip(".,:;\"'") if tail.split() else ""
    if first_word.upper().startswith("NOT_SCAM") or first_word.upper() == "SAFE":
        return "NOT_SCAM"
    if first_word.upper().startswith("SCAM"):
        return "SCAM"
    return None


def score_accuracy(model_name: str) -> float:
    """Fraction of eval_data.EXAMPLES the given on-device model classifies correctly."""
    correct = 0
    for text, label in EXAMPLES:
        expected = LABEL_MAP[label]
        predicted = _classify(model_name, text)
        if predicted == expected:
            correct += 1
    return correct / len(EXAMPLES)
