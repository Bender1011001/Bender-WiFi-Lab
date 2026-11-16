#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Packaged entrypoint: equivalent to python -m bender_engine."""
import runpy
if __name__ == "__main__":
    runpy.run_module("bender_engine", run_name="__main__")
