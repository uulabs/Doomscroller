#!/usr/bin/env python3
"""Seventh diagnostic: keyless supply via open public timelines.

If no search key is acceptable, the remaining honest source is a public
firehose - places where strangers post links to these platforms in the open,
with no account, no key and no scraping of the platforms themselves.
"""

import gzip
import json
import re
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter

UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
CTX = ssl.create_default_context()

PLATFORM = {
    "tiktok": r"tiktok\.com/@[\w.\-]+/video/(\d{15,25})",
    "instagram": r"instagram\.com/(?:reel|p|tv)/([A-Za-z0-9_-]{9,14})",
    "x": r"(?:x|twitter)\.com/[A-Za-z0-9_]{1,30}/status/(\d{15,25})",
    "youtube": r"(?:youtube\.com/shorts/|youtu\.be/|youtube\.com/watch\?v=)([A-Za-z0-9_-]{11})",
    "facebook": r"facebook\.com/reel/(\d{6,25})",
}


def go(url, headers=None, timeout=30):
    h = {"User-Agent": UA, "Accept": "*/*", "Accept-Language": "en-AU,en;q=0.9",
         "Accept-Encoding": "gzip"}
    if headers:
        h.update(headers)
    req = urllib.request.Request(url, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
            raw = r.read()
            if "gzip" in (r.headers.get("Content-Encoding") or ""):
                raw = gzip.decompress(raw)
            return r.status, raw
    except urllib.error.HTTPError as e:
        raw = b""
        try:
            raw = e.read()
            if "gzip" in ((e.headers or {}).get("Content-Encoding") or ""):
                raw = gzip.decompress(raw)
        except Exception:
            pass
        return e.code, raw
    except Exception as e:  # noqa: BLE001
        return None, repr(e).encode()


def count_links(text):
    found = Counter()
    samples = {}
    for name, pat in PLATFORM.items():
        for m in re.findall(pat, text):
            found[name] += 1
            samples.setdefault(name, []).append(m)
    return found, samples


print("=" * 72)
print("MASTODON PUBLIC TIMELINES (keyless, no auth)")
for inst in ["https://mastodon.social", "https://mstdn.jp", "https://fosstodon.org"]:
    for label, path in [("local", "/api/v1/timelines/public?local=true&limit=40"),
                        ("federated", "/api/v1/timelines/public?limit=40")]:
        s, raw = go(inst + path)
        if s != 200:
            print(f"  {inst:<26} {label:<10} status={s}")
            time.sleep(1)
            continue
        try:
            posts = json.loads(raw)
        except Exception:
            print(f"  {inst:<26} {label:<10} not json")
            continue
        blob = " ".join((p.get("content") or "") for p in posts)
        found, samples = count_links(blob)
        print(f"  {inst:<26} {label:<10} status=200 posts={len(posts)} links={dict(found)}")
        for k, v in samples.items():
            print(f"      {k}: {v[:2]}")
        time.sleep(1)

print()
print("=" * 72)
print("BLUESKY public search (keyless AppView)")
for q in ["tiktok cake", "instagram reel", "youtube shorts"]:
    url = ("https://public.api.bsky.app/xrpc/app.bsky.feed.searchPosts"
           f"?q={urllib.parse.quote(q)}&limit=50")
    s, raw = go(url)
    if s != 200:
        print(f"  {q!r:<20} status={s} head={raw[:120]!r}")
        continue
    try:
        d = json.loads(raw)
    except Exception:
        print(f"  {q!r:<20} not json")
        continue
    posts = d.get("posts") or []
    blob = json.dumps(d)
    found, samples = count_links(blob)
    external = sum(1 for p in posts
                   if ((p.get("embed") or {}).get("external") or {}).get("uri"))
    print(f"  {q!r:<20} status=200 posts={len(posts)} link_cards={external} links={dict(found)}")
    for k, v in samples.items():
        print(f"      {k}: {v[:2]}")
    time.sleep(2)

print()
print("=" * 72)
print("REDDIT RSS (keyless, often survives where .json 403s)")
for sub in ["TikTokCringe", "InstagramReels"]:
    s, raw = go(f"https://www.reddit.com/r/{sub}/new/.rss",
                headers={"Accept": "application/rss+xml"})
    text = raw.decode("utf-8", "ignore")
    entries = text.count("<entry>")
    found, samples = count_links(text)
    print(f"  r/{sub:<16} status={s} bytes={len(raw)} entries={entries} links={dict(found)}")
    for k, v in samples.items():
        print(f"      {k}: {v[:2]}")
    time.sleep(2)
