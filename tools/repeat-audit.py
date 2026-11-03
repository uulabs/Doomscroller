#!/usr/bin/env python3
"""Repeats from a device log.

Reads the play log the debug build writes (source, id, title key per played clip)
and reports repeats by id and by title. Run it on a log pulled from a phone:

  adb shell run-as au.com.uulabs.doomscroller cat files/play.log > play.log
  python3 tools/repeat-audit.py play.log
"""
import collections, pathlib, sys


def main():
    path = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "play.log")
    rows = []
    for line in path.read_text(errors="ignore").splitlines():
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        rows.append((parts[0], parts[1], parts[2] if len(parts) > 2 else ""))

    total = len(rows)
    ids = collections.Counter(f"{s}:{i}" for s, i, _ in rows)
    titles = collections.Counter(t for _, _, t in rows if t)
    by_source = collections.Counter(s for s, _, _ in rows)

    repeat_ids = {k: v for k, v in ids.items() if v > 1}
    repeat_titles = {k: v for k, v in titles.items() if v > 1}

    print(f"clips played        {total}")
    if total:
        print(f"distinct clips      {len(ids)}")
        print(f"repeated by id      {len(repeat_ids)}  "
              f"({100.0 * len(repeat_ids) / len(ids):.2f}%)")
        print(f"repeated by title   {len(repeat_titles)}  "
              f"({100.0 * len(repeat_titles) / max(1, len(titles)):.2f}% of titled clips)")
        print(f"by source           {dict(by_source)}")
    for label, group in (("id", repeat_ids), ("title", repeat_titles)):
        worst = sorted(group.items(), key=lambda kv: -kv[1])[:5]
        if worst:
            print(f"worst {label} repeats  {worst}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
