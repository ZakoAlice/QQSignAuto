#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Re-generate the probe's copies of the shipping module classes.

The auto module (work/checkin/auto) holds the real code. The capture probe
(work/checkin/module) is the only LSPosed entry the user has confirmed enabled, so it
carries copies of those classes under different names to run the same logic live and
log it. Hand-copying those files is how they went stale once already, so this script
derives them from the module sources every time instead.

    cd work/checkin && python sync_probe.py
"""
import io
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "auto", "src", "com", "hou33", "qqsignauto")
DST = os.path.join(HERE, "module", "src", "com", "hou33", "qqsignlog")

# (module source file, probe file, [identifier renames])
JOBS = [
    ("Card.java", "AutoCard.java", [("Card", "AutoCard")]),
    ("Session.java", "AutoSession.java", [("Session", "AutoSession")]),
    ("SignInClient.java", "AutoClient.java", [("SignInClient", "AutoClient"),
                                              ("Card", "AutoCard"),
                                              ("Session", "AutoSession")]),
    ("Notifier.java", "AutoNotifier.java", [("Notifier", "AutoNotifier")]),
]

HEADER = (
    "// GENERATED FILE - DO NOT EDIT.\n"
    "// Derived from work/checkin/auto/src/com/hou33/qqsignauto/%s by work/checkin/sync_probe.py\n"
    "// It lets the capture probe run the shipping module's logic and log the result.\n"
)

OLD_PKG = "package com.hou33.qqsignauto;"
NEW_PKG = "package com.hou33.qqsignlog;"


def convert(text, basename, renames):
    if OLD_PKG not in text:
        raise SystemExit("no package line in " + basename)
    out = text.replace(OLD_PKG, HEADER % basename + NEW_PKG, 1)
    for old, new in renames:
        out = re.sub(r"\b%s\b" % re.escape(old), new, out)
    return out


def main():
    if not os.path.isdir(SRC) or not os.path.isdir(DST):
        raise SystemExit("unexpected layout: %s / %s" % (SRC, DST))
    for src, dst, renames in JOBS:
        with io.open(os.path.join(SRC, src), encoding="utf-8") as f:
            text = f.read()
        out = convert(text, src, renames)
        path = os.path.join(DST, dst)
        with io.open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(out)
        # what the transform produced, minus comments, is the real signal
        body = re.sub(r"//.*", "", re.sub(r"/\*.*?\*/", "", out, flags=re.S))
        for old, _ in renames:
            if re.search(r"\b%s\b" % re.escape(old), body):
                raise SystemExit("rename missed %s in %s" % (old, dst))
        print("wrote %s (%d bytes, %d lines)" %
              (os.path.relpath(path, HERE), len(out.encode("utf-8")), out.count("\n") + 1))
    print("probe copies are in sync with the module")


if __name__ == "__main__":
    sys.exit(main())
