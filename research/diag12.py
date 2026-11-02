#!/usr/bin/env python3
"""Twelfth diagnostic: follow the surfaces built FOR crawlers.

robots.txt declares sitemaps. Sitemaps exist so strangers can enumerate a site.
That is the one door a platform leaves open on purpose.
"""

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


def go(url, timeout=30):
    req = urllib.request.Request(url, headers={
        "User-Agent": DESKTOP, "Accept": "*/*",
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
print("A. robots.txt / sitemap declarations")
for host in ["https://www.tiktok.com", "https://www.instagram.com", "https://x.com"]:
    s, body = go(host + "/robots.txt")
    maps = re.findall(r"(?i)sitemap:\s*(\S+)", body)
    print(f"  {host:<28} status={s} bytes={len(body):>6} sitemaps={len(maps)}")
    for m in maps[:6]:
        print(f"      {m}")
    time.sleep(2)

print()
print("=" * 78)
print("B. TikTok sitemap structure - is it an index with children?")
s, body = go("https://www.tiktok.com/sitemap.xml")
print(f"  /sitemap.xml status={s} bytes={len(body)}")
print(f"  <sitemapindex>={body.count('<sitemapindex')} <sitemap>={body.count('<sitemap>')} "
      f"<url>={body.count('<url>')}")
locs = re.findall(r"<loc>([^<]+)</loc>", body)
print(f"  locs={len(locs)} first={locs[:3]}")
ids = list(dict.fromkeys(re.findall(TT, body)))
print(f"  tiktok video ids in it: {len(ids)} sample={ids[:3]}")

print()
print("=" * 78)
print("C. oEmbed on sitemap ids - are they real, portrait, playable?")
for vid in ids[:3]:
    s2, o = go("https://www.tiktok.com/oembed?url="
               + urllib.parse.quote(f"https://www.tiktok.com/@x/video/{vid}", safe=""))
    try:
        d = json.loads(o)
        w, h = d.get("width"), d.get("height")
        print(f"  {vid} status={s2} {w}x{h} portrait={bool(w and h and int(h) > int(w))} "
              f"author={d.get('author_unique_id')!r} title={(d.get('title') or '')[:35]!r}")
    except Exception:
        print(f"  {vid} status={s2} not-json {o[:70]!r}")
    time.sleep(5)

print()
print("=" * 78)
print("D. X username graph - can handles expand without a curated list?")
s, body = go("https://syndication.twitter.com/srv/timeline-profile/screen-name/cake")
handles = list(dict.fromkeys(re.findall(r'(?<![\w/])(?:x|twitter)\.com/([A-Za-z0-9_]{2,20})(?![\w/])', body)))
handles = [h for h in handles if h.lower() not in {"i", "intent", "search", "hashtag", "home"}]
print(f"  screen-name/cake status={s} bytes={len(body)} handles_found={len(handles)}")
print(f"  sample handles: {handles[:12]}")
time.sleep(3)
if handles:
    probe = handles[0]
    s2, b2 = go("https://syndication.twitter.com/srv/timeline-profile/screen-name/" + probe)
    xs = list(dict.fromkeys(re.findall(r'status/(\d{15,25})', b2)))
    print(f"  harvested handle '{probe}' -> status={s2} bytes={len(b2)} status_ids={len(xs)}")
