#!/usr/bin/env python3
"""Per-source duplicate audit, off-device.

For one source, asks it about many different subjects exactly as the app does and
records the candidate ids. The question it answers is the one that matters for
repeats: does asking about a *different* subject keep returning the *same* items?

Usage: python3 tools/source-audit.py --source tiktok --words 80
"""
import argparse, collections, json, pathlib, random, re, sys, time, urllib.parse, urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
TOPICS = ROOT / "app/src/main/assets/topics"
DESKTOP = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
           "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
MOBILE = ("Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 "
          "(KHTML, like Gecko) Chrome/155.0.0.0 Mobile Safari/537.36")


def fetch(url, ua=DESKTOP, timeout=25):
    req = urllib.request.Request(url, headers={
        "User-Agent": ua, "Accept": "text/html,application/json",
        "Accept-Language": "en-AU,en;q=0.9"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode("utf-8", "ignore")


def words(lang, count, seed):
    rows = [l for l in (TOPICS / f"{lang}.tsv").read_text().splitlines()
            if l.strip() and not l.startswith("#")]
    pool = [r.split("\t")[2] for r in rows if len(r.split("\t")) >= 3]
    rng = random.Random(seed)
    rng.shuffle(pool)
    return pool[:count]


def collect(source, word):
    if source == "tiktok":
        html = fetch("https://www.tiktok.com/embed/tag/" + urllib.parse.quote(word),
                     ua=MOBILE)
        ids = re.findall(r"tiktok\.com/@[\w.\-]+/video/(\d{15,25})", html)
        return list(dict.fromkeys(ids))[:12]
    if source == "instagram":
        html = fetch("https://search.brave.com/search?q=" + urllib.parse.quote(
            "site:instagram.com/reel " + word))
        return list(dict.fromkeys(re.findall(
            r"instagram\.com/reel/([A-Za-z0-9_-]{5,})", html)))[:20]
    if source == "facebook":
        html = fetch("https://search.brave.com/search?q=" + urllib.parse.quote(
            "site:facebook.com/reel " + word))
        return list(dict.fromkeys(re.findall(
            r"facebook\.com/reel/(\d{6,})", html)))[:20]
    if source == "x":
        html = fetch("https://syndication.twitter.com/srv/timeline-profile/"
                     "screen-name/" + urllib.parse.quote(word))
        urls = re.findall(r"https://video\.twimg\.com/[^\"\\]+?\.mp4[^\"\\]*", html)
        return list(dict.fromkeys(urls))[:12]
    if source == "peertube":
        js = fetch("https://sepiasearch.org/api/v1/search/videos?search="
                   + urllib.parse.quote(word) + "&count=12")
        data = json.loads(js).get("data", [])
        return [d.get("uuid") for d in data if d.get("uuid")][:12]
    if source == "youtube":
        html = fetch("https://www.youtube.com/results?search_query="
                     + urllib.parse.quote(word) + "&sp=EgIYAQ%3D%3D")
        return list(dict.fromkeys(
            re.findall(r'"videoId":"([A-Za-z0-9_-]{11})"', html)))[:30]
    raise SystemExit("unknown source " + source)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", required=True)
    ap.add_argument("--words", type=int, default=60)
    ap.add_argument("--lang", default="en")
    ap.add_argument("--seed", type=int, default=5)
    ap.add_argument("--pause", type=float, default=0.6)
    args = ap.parse_args()

    seen = collections.Counter()
    failures = 0
    empties = 0
    fetched = 0
    started = time.time()
    for word in words(args.lang, args.words, args.seed):
        try:
            ids = collect(args.source, word)
        except Exception:
            failures += 1
            time.sleep(1.0)
            continue
        fetched += 1
        if not ids:
            empties += 1
        for one in ids:
            seen[one] += 1
        time.sleep(args.pause)

    shared = {k: v for k, v in seen.items() if v > 1}
    print(f"source            {args.source}")
    print(f"subjects asked    {fetched}  (failed {failures}, empty {empties})")
    print(f"distinct items    {len(seen)}")
    print(f"items seen again  {len(shared)}")
    if seen:
        print(f"shared rate       {100.0 * len(shared) / len(seen):.1f}% of items")
    worst = sorted(shared.items(), key=lambda kv: -kv[1])[:5]
    if worst:
        print(f"worst             {worst}")
    print(f"elapsed           {time.time() - started:.0f}s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
