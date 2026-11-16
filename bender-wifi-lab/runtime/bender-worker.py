#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Private supervised process group. No arbitrary command or radio primitive."""
import os
from pathlib import Path
import sys

def main():
    kind, job_name = sys.argv[1:]
    job = Path(job_name)
    if kind not in {"analyze", "capture-analysis", "lab", "identity", "prepare", "privacy_restore", "privacy_status", "session_prepare", "session_restore", "wps", "wps_restore"}:
        raise ValueError("Unknown supervised worker")
    # chroot exec preserves the background PID; establish a private session so
    # the root supervisor can stop both Python and decoder subprocesses.
    if os.getsid(0) != os.getpid():
        os.setsid()
    (job / "worker.pid").write_text(str(os.getpid()))
    tools = Path(__file__).resolve().parent
    if kind in {"wps", "wps_restore"}:
        command = [sys.executable, str(tools / "live_wps.py"), kind, str(job)]
    elif kind in {"session_prepare", "session_restore"}:
        command = [sys.executable, str(tools / "privacy-session.py"), kind, str(job)]
    elif kind in {"lab", "identity", "prepare", "privacy_restore", "privacy_status"}:
        command = [sys.executable, str(tools / "qca-backend.py"), kind, str(job)]
    else:
        source = "input.pcap" if kind == "analyze" else "capture-01.cap"
        command = [sys.executable, str(tools / "bender-engine.py"), "analyze", "--pcap", str(job / source), "--output", str(job / "observations-v1.json"), "--cancel-file", str(job / "cancel")]
    os.execv(sys.executable, command)

if __name__ == "__main__":
    main()
