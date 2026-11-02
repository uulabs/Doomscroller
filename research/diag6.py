#!/usr/bin/env python3
"""Sixth diagnostic: is any general index usable as a discovery backbone
without a key and without rate-limiting a naive client out of existence?

DDG throttled us after a handful of queries, so the question is whether a
machine-tolerant index exists at all."""

import gzip
import json
import re
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request

UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
CTX = ssl.create_default_context()


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


PATTERNS = {
    "tiktok ids": r'tiktok\.com/@[\w.\-]+/video/(\d{15,25})',
    "ig codes": r'instagram\.com/(?:reel|p|tv)/([A-Za-z0-9_-]{9,14})',
    "x ids": r'(?:x|twitter)\.com/[A-Za-z0-9_]{1,30}/status/(\d{15,25})',
    "yt ids": r'youtube\.com/shorts/([A-Za-z0-9_-]{11})',
}

TESTS = [
    ("Mojeek tiktok",
     "https://www.mojeek.com/search?q=" + urllib.parse.quote("site:tiktok.com cake")),
    ("Mojeek instagram",
     "https://www.mojeek.com/search?q=" + urllib.parse.quote("site:instagram.com reel")),
    ("Marginalia",
     "https://search.marginalia.nu/search?query=" + urllib.parse.quote("cooking video")),
    ("Startpage",
     "https://www.startpage.com/sp/search?query=" + urllib.parse.quote("site:tiktok.com cake")),
    ("Mojeek api-ish",
     "https://www.mojeek.com/search?q=" + urllib.parse.quote("tiktok cake recipe")),
]

for label, url in TESTS:
    s, raw = go(url)
    body = raw.decode("utf-8", "ignore")
    found = {k: len(list(dict.fromkeys(re.findall(p, body)))) for k, p in PATTERNS.items()}
    found = {k: v for k, v in found.items() if v}
    print(f"{label:<18} status={s} bytes={len(raw):>7} found={found}")
    time.sleep(3)

print()
print("=" * 72)
print("COMMON CRAWL INDEX (public, built for programmatic access)")
for label, url in [
    ("CC instagram reels",
     "https://index.commoncrawl.org/CC-MAIN-2024-33-index?url=instagram.com/reel/*&output=json&limit=5"),
    ("CC tiktok videos",
     "https://index.commoncrawl.org/CC-MAIN-2024-33-index?url=tiktok.com/@*/video/*&output=json&limit=5"),
]:
    s, raw = go(url, timeout=45)
    body = raw.decode("utf-8", "ignore")
    lines = [l for l in body.strip().splitlines()[:5] if l.startswith("{")]
    print(f"{label:<20} status={s} bytes={len(raw)} records={len(lines)}")
    for l in lines[:3]:
        try:
            print("    ", json.loads(l).get("url", "")[:110])
        except Exception:
            pass
    time.sleep(2)
