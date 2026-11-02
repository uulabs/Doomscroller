#!/usr/bin/env python3
"""Third diagnostic: can a general web index supply provider item ids?
If the platforms' own discovery surfaces are closed, an open index that
merely *points at* them may not be."""

import gzip
import json
import re
import ssl
import urllib.error
import urllib.parse
import urllib.request

UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
CTX = ssl.create_default_context()


def go(url, headers=None, data=None, method=None, timeout=25):
    h = {"User-Agent": UA, "Accept": "*/*", "Accept-Language": "en-AU,en;q=0.9",
         "Accept-Encoding": "gzip"}
    if headers:
        h.update(headers)
    req = urllib.request.Request(url, data=data, headers=h, method=method)
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


def probe_index(label, url, patterns):
    s, raw = go(url)
    body = raw.decode("utf-8", "ignore")
    print(f"\n{label}: status={s} bytes={len(raw)}")
    for name, pat in patterns.items():
        hits = list(dict.fromkeys(re.findall(pat, body)))
        print(f"   {name:<26} {len(hits):>3} unique {hits[:3]}")

    return body


print("=" * 70)
print("WEB INDEX -> PROVIDER ITEM IDS")

ddg = "https://html.duckduckgo.com/html/?q="
probe_index(
    "DDG: site:tiktok.com cake",
    ddg + urllib.parse.quote("site:tiktok.com /video/ cake"),
    {"tiktok video urls": r'tiktok\.com/@[\w.\-]+/video/(\d{15,25})',
     "tiktok any urls": r'tiktok\.com/(?:@[\w.\-]+/video|v)/(\d{15,25})'},
)

probe_index(
    "DDG: site:instagram.com reel cake",
    ddg + urllib.parse.quote("site:instagram.com /reel/ cake"),
    {"ig reel codes": r'instagram\.com/(?:reel|p)/([A-Za-z0-9_-]{9,14})'},
)

probe_index(
    "DDG: site:x.com cake",
    ddg + urllib.parse.quote("site:x.com cake video"),
    {"x status urls": r'(?:x|twitter)\.com/[A-Za-z0-9_]{1,30}/status/(\d{15,25})'},
)

print()
print("=" * 70)
print("BING (same queries)")
bing = "https://www.bing.com/search?q="
probe_index(
    "BING: site:tiktok.com cake",
    bing + urllib.parse.quote("site:tiktok.com /video/ cake"),
    {"tiktok video urls": r'tiktok\.com/@[\w.\-]+/video/(\d{15,25})'},
)
probe_index(
    "BING: site:instagram.com reel",
    bing + urllib.parse.quote("site:instagram.com /reel/ cake"),
    {"ig reel codes": r'instagram\.com/(?:reel|p)/([A-Za-z0-9_-]{9,14})'},
)

print()
print("=" * 70)
print("REDDIT (why it returned nothing)")
s, raw = go("https://www.reddit.com/r/interestingasfuck/top.json?t=day&limit=25",
            headers={"Accept": "application/json"})
print(f"status={s} bytes={len(raw)} head={raw[:200]!r}")

s, raw = go("https://old.reddit.com/r/interestingasfuck/top.json?t=day&limit=25",
            headers={"Accept": "application/json"})
print(f"old.reddit status={s} bytes={len(raw)}")
if raw[:1] == b"{":
    try:
        d = json.loads(raw)
        kids = d.get("data", {}).get("children", [])
        vids = [c["data"] for c in kids if c.get("data", {}).get("is_video")]
        print(f"   posts={len(kids)} videos={len(vids)}")
        if vids:
            rv = (vids[0].get("secure_media") or {}).get("reddit_video") or {}
            print(f"   sample {vids[0]['id']} {rv.get('width')}x{rv.get('height')}")
    except Exception as e:  # noqa: BLE001
        print("   parse fail", e)
