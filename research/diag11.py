#!/usr/bin/env python3
"""Eleventh diagnostic: does TikTok discovery hold up, with a desktop UA and
polite spacing? And can X be discovered without knowing a username?"""

import gzip
import json
import re
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request

CTX = ssl.create_default_context()
DESKTOP = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
           "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
TT = r"tiktok\.com/@[\w.\-]+/video/(\d{15,25})"
XS = r"(?:x|twitter)\.com/[A-Za-z0-9_]{1,30}/status/(\d{15,25})"


def go(url, timeout=25):
    req = urllib.request.Request(url, headers={
        "User-Agent": DESKTOP, "Accept": "text/html,application/xhtml+xml",
        "Accept-Language": "en-AU,en;q=0.9", "Accept-Encoding": "gzip"})
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
            raw = r.read()
            if "gzip" in (r.headers.get("Content-Encoding") or ""):
                raw = gzip.decompress(raw)
            return r.status, raw.decode("utf-8", "ignore")
    except urllib.error.HTTPError as e:
        raw = b""
        try:
            raw = e.read()
            if "gzip" in ((e.headers or {}).get("Content-Encoding") or ""):
                raw = gzip.decompress(raw)
        except Exception:
            pass
        return e.code, raw.decode("utf-8", "ignore")
    except Exception as e:  # noqa: BLE001
        return None, repr(e)


print("=" * 78)
print("A. TIKTOK /embed/tag/<subject> - desktop UA, 6s spacing")
total = 0
for subj in ["cake", "蛋糕", "futebol", "adventure"]:
    s, body = go("https://www.tiktok.com/embed/tag/" + urllib.parse.quote(subj))
    ids = list(dict.fromkeys(re.findall(TT, body)))
    total += len(ids)
    print(f"  {subj:<12} status={s} bytes={len(body):>7} ids={len(ids)} {ids[:2]}")
    time.sleep(6)

print()
print("=" * 78)
print("B. TIKTOK sitemap - desktop UA")
s, body = go("https://www.tiktok.com/sitemap.xml")
ids = list(dict.fromkeys(re.findall(TT, body)))
print(f"  sitemap status={s} bytes={len(body)} ids={len(ids)}")
if "sitemapindex" in body or "<sitemap>" in body:
    locs = re.findall(r"<loc>([^<]+)</loc>", body)
    print(f"  sitemap index with {len(locs)} children: {locs[:4]}")

print()
print("=" * 78)
print("C. TIKTOK oEmbed on a discovered id (portrait + playable?)")
s, body = go("https://www.tiktok.com/embed/tag/" + urllib.parse.quote("cake"))
ids = list(dict.fromkeys(re.findall(TT, body)))
print(f"  ids to test: {len(ids)}")
for vid in ids[:2]:
    s2, o = go("https://www.tiktok.com/oembed?url="
               + urllib.parse.quote(f"https://www.tiktok.com/@x/video/{vid}", safe=""))
    try:
        d = json.loads(o)
        w, h = d.get("width"), d.get("height")
        print(f"  {vid} status={s2} {w}x{h} "
              f"portrait={bool(w and h and int(h) > int(w))} "
              f"author={d.get('author_unique_id')!r}")
    except Exception:
        print(f"  {vid} status={s2} not-json {o[:80]!r}")
    time.sleep(4)

print()
print("=" * 78)
print("D. X: random word used as a username (no list, no key)")
for word in ["cake", "futebol", "adventure"]:
    s, body = go("https://syndication.twitter.com/srv/timeline-profile/screen-name/" + word)
    ids = list(dict.fromkeys(re.findall(XS, body)))
    mp4 = body.count("video/mp4")
    print(f"  /screen-name/{word:<10} status={s} bytes={len(body):>7} "
          f"status_ids={len(ids)} mp4_refs={mp4}")
    time.sleep(5)
