#!/usr/bin/env python3
"""Decide whether a freshly recorded baseline profile has actually drifted.

Used by .github/workflows/baseline-profile.yml, which re-records the profile
monthly and has to answer one question: has the app's startup path moved, or is
this just the recording being a measurement?

WHY THIS IS NOT `git diff --exit-code`.

Two back-to-back recordings on the SAME emulator are not identical. Measured on
this app, run 2 against run 1: 28 rules added, 9 removed, 22,687 unchanged --
0.163% churn, every one of it in timing-dependent background work (Sentry queue
draining, a Compose paint helper). An exact-match gate would fail every single
month, and a check that always fails is a check nobody reads: it would be
switched off, and then the profile would go stale silently, which is the failure
this whole mechanism exists to prevent.

Only that same-machine figure is measured. Host-to-host churn -- this repository
records on a Linux runner in CI and profiles have also been recorded on Windows
-- is NOT measured, so the default threshold is deliberately an order of
magnitude looser than the observed noise. It is sized to catch a startup path
that MOVED (a new library on the Application path, a different first screen;
changes of that kind shift tens of percent), not to police jitter.

Comparison is on the SET of rules, not on line order or line endings, because
neither carries meaning: AGP writes the file with the recording host's line
endings, and ordering is an artifact of the profile writer.
"""

from __future__ import annotations

import argparse
import os
import sys


def read_rules(path: str) -> set[str]:
    with open(path, encoding="utf-8") as handle:
        return {line.strip() for line in handle if line.strip()}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("committed", help="the profile in the repository")
    parser.add_argument("fresh", help="the profile just recorded")
    parser.add_argument(
        "--max-churn-percent",
        type=float,
        default=5.0,
        help="percentage of rules that may differ before this is drift (default: 5)",
    )
    parser.add_argument(
        "--summary",
        default=os.environ.get("GITHUB_STEP_SUMMARY"),
        help="file to append a Markdown report to (default: $GITHUB_STEP_SUMMARY)",
    )
    args = parser.parse_args()

    before = read_rules(args.committed)
    after = read_rules(args.fresh)

    # An empty recording is not "0% churn from an empty file", it is a broken
    # run. Checked before the ratio, which would otherwise divide sensibly and
    # report success on two empty files.
    if not after:
        print(
            f"::error::{args.fresh} contains no rules. The recording produced "
            "nothing; do not commit it and do not hand-write a replacement.",
            file=sys.stderr,
        )
        return 1

    added = after - before
    removed = before - after
    churn = 100.0 * len(added | removed) / max(len(before), len(after))
    drifted = churn > args.max_churn_percent

    report = [
        "### Baseline profile",
        "",
        f"- committed: {len(before)} rules",
        f"- fresh recording: {len(after)} rules",
        (
            f"- {len(added)} added, {len(removed)} removed = {churn:.3f}% churn "
            f"(threshold {args.max_churn_percent}%)"
        ),
        "",
    ]
    if drifted:
        report += [
            (
                "**The startup path has moved and the committed profile is stale.** "
                "Download the `baseline-profile` artifact from this run and commit it, "
                "or re-record locally with `scripts/generate-baseline-profile.sh`. "
                "Do not hand-edit the file."
            ),
            "",
        ]
        for label, entries in (("added", added), ("removed", removed)):
            shown = sorted(entries)[:200]
            report += [
                f"<details><summary>{len(entries)} {label}</summary>",
                "",
                "```",
                *shown,
                "```",
                "</details>",
                "",
            ]
    else:
        report.append(
            f"{churn:.3f}% churn is within the {args.max_churn_percent}% noise "
            "threshold; the committed profile still describes this startup path."
        )

    text = "\n".join(report) + "\n"
    if args.summary:
        with open(args.summary, "a", encoding="utf-8") as handle:
            handle.write(text)
    print(text)

    if drifted:
        print(
            f"::error::The committed baseline profile differs from a fresh recording "
            f"by {churn:.3f}% of rules (threshold {args.max_churn_percent}%).",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
