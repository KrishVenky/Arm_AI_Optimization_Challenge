"""Thin adb wrapper for driving the Pixel 9a from the search loop.

Git Bash/MSYS auto-converts args that look like POSIX absolute paths before
they reach adb.exe, so every on-device path passed through here is
double-slash-prefixed ("//data/..."), which defeats that conversion.
"""
from __future__ import annotations

import os
import shlex
import subprocess
import time

ON_DEVICE_ROOT = "//data/local/tmp/bench"

_ENV = dict(os.environ, MSYS_NO_PATHCONV="1")

# adb's USB connection drops intermittently during long unattended runs (seen
# in practice: device went "offline" mid-search, fixed by kill-server +
# start-server). A multi-hour search shouldn't die on one USB hiccup.
_ADB_FLAKE_MARKERS = ("no devices/emulators found", "device offline", "device unauthorized")
_MAX_RETRIES = 4
_RETRY_DELAY_S = 10


class PushError(RuntimeError):
    pass


def _restart_adb_server() -> None:
    subprocess.run(["adb", "kill-server"], capture_output=True, env=_ENV)
    time.sleep(2)
    subprocess.run(["adb", "start-server"], capture_output=True, env=_ENV)
    try:
        subprocess.run(["adb", "wait-for-device"], capture_output=True, env=_ENV, timeout=60)
    except subprocess.TimeoutExpired:
        # A slow reconnect isn't fatal on its own -- _run_with_retry's caller
        # still has retry attempts left. Letting this raise uncaught (as it
        # did before) killed the whole candidate on the very first restart,
        # burning through _MAX_RETRIES=4 in a single ~34s shot instead of
        # actually using them -- the cause of the high failure rate seen in
        # practice (QIEA lost 21/24 trials to exactly this).
        pass


def _run_with_retry(argv: list[str], timeout: float | None) -> subprocess.CompletedProcess:
    """Shared retry loop for any adb subcommand. Retries through transient
    USB/adb-daemon drops (seen in practice on this device repeatedly, not
    hypothetical) by restarting the adb server between attempts.
    """
    last_result = None
    for attempt in range(_MAX_RETRIES):
        result = subprocess.run(argv, capture_output=True, text=True, env=_ENV, timeout=timeout)
        combined = result.stdout + result.stderr
        if result.returncode == 0 or not any(marker in combined for marker in _ADB_FLAKE_MARKERS):
            return result
        last_result = result
        if attempt < _MAX_RETRIES - 1:
            _restart_adb_server()
    return last_result


def remote(path: str) -> str:
    """Build a device-side path under ON_DEVICE_ROOT, MSYS-safe."""
    return f"{ON_DEVICE_ROOT}/{path}"


def push(local_path: str, remote_name: str) -> None:
    """adb push a local file to ON_DEVICE_ROOT/remote_name. Same retry-through-
    flake behavior as shell() -- a plain check=True here was the gap that let
    a mid-run adb disconnect kill a whole QPSO run (fitness.py's retry only
    caught QuantizeError/BenchError, not adb push's CalledProcessError)."""
    result = _run_with_retry(["adb", "push", local_path, remote(remote_name)], timeout=60)
    if result.returncode != 0:
        raise PushError(result.stdout[-1000:] + result.stderr[-1000:])


def shell(*args: str, timeout: float | None = None) -> subprocess.CompletedProcess:
    """Run `adb shell <args>`, return the completed process (stdout/stderr captured).

    adb shell concatenates argv with spaces and re-parses the result with
    /system/bin/sh on the device, so any arg containing shell metacharacters
    (regex parens/pipes in --tensor-type patterns, for instance) must be
    quoted here -- adb does not do this for you. Retries through transient
    USB/adb-daemon drops rather than surfacing them as candidate failures.
    """
    command = " ".join(shlex.quote(a) for a in args)
    return _run_with_retry(["adb", "shell", command], timeout=timeout)


def rm(path: str) -> None:
    shell("rm", "-f", remote(path))


def exists(path: str) -> bool:
    result = shell("test", "-e", remote(path))
    return result.returncode == 0
