#!/usr/bin/env python3
"""Off-device audit of the discovery pipeline.

Simulates what the app does when it needs a clip: draw a subject from the topic
catalogue, ask the source, skip into the results and take one. Reports how many
distinct videos that produces and how often it repeats, which is the part that is
tedious to judge by scrolling a phone.

Usage: python3 tools/discovery-audit.py --draws 120 [--lang en] [--seed 1]
"""
import argparse, collections, concurrent.futures, pathlib, random, re, sys, time, urllib.parse, urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
TOPICS = ROOT / "app/src/main/assets/topics"
UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")


def topics(lang):
    rows = [l for l in (TOPICS / f"{lang}.tsv").read_text().splitlines()
            if l.strip() and not l.startswith("#")]
    out = []
    for row in rows:
        parts = row.split("\t")
        if len(parts) >= 3:
            out.append(parts[2])
    return list(dict.fromkeys(out))


def youtube_ids(query, timeout=20):
    url = ("https://www.youtube.com/results?search_query="
           + urllib.parse.quote(query) + "&sp=EgIYAQ%3D%3D")
    req = urllib.request.Request(url, headers={"User-Agent": UA,
                                               "Cookie": "SOCS=CAI; CONSENT=YES+cb"})
    html = urllib.request.urlopen(req, timeout=timeout).read().decode("utf-8", "ignore")
    return list(dict.fromkeys(re.findall(r'"videoId":"([A-Za-z0-9_-]{11})"', html)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--draws", type=int, default=40)
    ap.add_argument("--lang", default="en")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--concurrency", type=int, default=4)
    ap.add_argument("--progress", type=int, default=200)
    args = ap.parse_args()

    rng = random.Random(args.seed)
    words = topics(args.lang)
    rng.shuffle(words)
    bag = list(words)
    taken = collections.Counter()
    lengths = []
    failures = 0

    # words are consumed in bag order, exactly as the app does, and fetched a
    # few at a time so a long audit finishes in minutes rather than an hour
    def draw(_):
        nonlocal bag
        if not bag:
            bag = list(words)
            rng.shuffle(bag)
        return bag.pop()

    words_for_run = [draw(None) for _ in range(args.draws)]
    started = time.time()
    answered = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = {pool.submit(youtube_ids, w): w for w in words_for_run}
        for done, fut in enumerate(concurrent.futures.as_completed(futures), 1):
            word = futures[fut]
            try:
                ids = fut.result()
            except Exception:
                failures += 1
                ids = []
            if ids:
                lengths.append(len(ids))
                skip = 8 + rng.randrange(40)
                picked = ids[skip % len(ids)]
                taken[picked] += 1
                answered += 1
            if args.progress and done % args.progress == 0:
                print(f"  {done}/{args.draws} draws, "
                      f"{len(taken)} distinct, "
                      f"{time.time() - started:.0f}s", flush=True)

    distinct = len(taken)
    repeats = sum(1 for v in taken.values() if v > 1)
    total = sum(taken.values())
    print(f"language          {args.lang}")
    print(f"catalogue words   {len(words)}")
    print(f"draws requested   {args.draws}")
    print(f"draws answered    {total}  (failures {failures})")
    print(f"elapsed           {time.time() - started:.0f}s")
    print(f"result-list sizes min {min(lengths) if lengths else 0} "
          f"max {max(lengths) if lengths else 0} "
          f"median {sorted(lengths)[len(lengths)//2] if lengths else 0}")
    print(f"distinct clips    {distinct}")
    print(f"clips repeated    {repeats}")
    if distinct:
        print(f"repeat rate       {100.0 * repeats / distinct:.1f}% of distinct clips")
    worst = [v for v, c in taken.most_common(5) if c > 1]
    if worst:
        print(f"worst repeats     {[(v, taken[v]) for v in worst]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
