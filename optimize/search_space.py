"""Per-layer mixed-precision search space for gemma-3-1b-it (the benchmark model
from docs/BENCHMARKS.md), matched to the tensor names llama-quantize reports for
this exact GGUF (confirmed via `llama-quantize --dry-run` on-device).

One knob per transformer block: all seven quantizable tensors in that block
(attn_q/k/v/output, ffn_gate/up/down) move together. This keeps the search
space at 26 categorical dimensions instead of 182 (26 blocks x 7 tensors),
which matters when each fitness evaluation costs real device minutes.
"""
from __future__ import annotations

from dataclasses import dataclass

N_BLOCKS = 26

BLOCK_TENSORS = (
    "attn_q",
    "attn_k",
    "attn_v",
    "attn_output",
    "ffn_gate",
    "ffn_up",
    "ffn_down",
)

# Ordered roughly cheapest/smallest to most expensive/largest. Kept small
# on purpose: every extra choice multiplies the search space and the number
# of trials needed to meaningfully cover it.
QUANT_CHOICES = ("Q4_0", "Q5_K", "Q6_K", "Q8_0")

# Tensors llama-quantize doesn't let --tensor-type touch usefully at this
# size (norms are f32 and tiny; token_embd/output fall back to their own
# rule). BASE_TYPE only affects the pruned/unmatched conv remainder.
BASE_TYPE = "Q8_0"


@dataclass(frozen=True)
class Candidate:
    """One point in the search space: a quant choice per transformer block."""

    block_types: tuple[str, ...]  # length N_BLOCKS, each in QUANT_CHOICES

    def tensor_type_args(self) -> list[str]:
        """Build the repeated --tensor-type flags for llama-quantize.

        Only safe for small candidates -- adb shell silently truncates
        commands around ~1KB, and a full 26-block candidate produces a
        ~2.7KB command line. Use tensor_type_file_lines() + --tensor-type-file
        for real evaluations (see quantize_and_bench.py).
        """
        args: list[str] = []
        for block_idx, quant_type in enumerate(self.block_types):
            pattern = (
                rf"^blk\.{block_idx}\.({'|'.join(BLOCK_TENSORS)})\.weight$"
            )
            args.extend(["--tensor-type", f"{pattern}={quant_type}"])
        return args

    def tensor_type_file_lines(self) -> str:
        """Same overrides as tensor_type_args(), as --tensor-type-file content
        (one `pattern=type` per line). This is the form actually used for
        evaluation, since it goes over adb push, not the adb shell command
        line, and has no length limit worth worrying about here."""
        lines = []
        for block_idx, quant_type in enumerate(self.block_types):
            pattern = (
                rf"^blk\.{block_idx}\.({'|'.join(BLOCK_TENSORS)})\.weight$"
            )
            lines.append(f"{pattern}={quant_type}")
        return "\n".join(lines) + "\n"

    def size_score(self) -> float:
        """Cheap proxy for on-disk size: bits-per-weight rank, summed. Lower is smaller."""
        rank = {t: i for i, t in enumerate(QUANT_CHOICES)}
        return sum(rank[t] for t in self.block_types)

    def to_dict(self) -> dict[str, str]:
        return {f"blk_{i}": t for i, t in enumerate(self.block_types)}

    @staticmethod
    def from_dict(d: dict[str, str]) -> "Candidate":
        return Candidate(tuple(d[f"blk_{i}"] for i in range(N_BLOCKS)))

    @staticmethod
    def uniform(quant_type: str) -> "Candidate":
        return Candidate((quant_type,) * N_BLOCKS)
