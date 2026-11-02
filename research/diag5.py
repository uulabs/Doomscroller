#!/usr/bin/env python3
"""Fifth diagnostic: find the actual stream URL inside TikTok's public embed,
and re-test X syndication with the token parameter that worked earlier."""

import gzip
import json
import os
import re
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
CTX = ssl.create_default_context()
OUT = Path(os.environ.get("UULABS_SCRATCH_DIR", ".")) / "spike"
OUT.mkdir(parents=True, exist_ok=True)


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
            return r.status, dict(r.headers), raw
    except urllib.error.HTTPError as e:
        raw = b""
        try:
            raw = e.read()
            if "gzip" in ((e.headers or {}).get("Content-Encoding") or ""):
                raw = gzip.decompress(raw)
        except Exception:
            pass
        return e.code, dict(e.headers or {}), raw
    except Exception as e:  # noqa: BLE001
        return None, {}, repr(e).encode()


VID = "7373098322015391018"
print("=" * 72)
print(f"TIKTOK EMBED {VID}")
s, h, raw = go(f"https://www.tiktok.com/embed/v2/{VID}",
               headers={"Accept": "text/html", "Referer": "https://www.tiktok.com/"})
body = raw.decode("utf-8", "ignore")
(OUT / f"tiktok-embed-{VID}.html").write_text(body)
print(f"status={s} bytes={len(raw)} saved to {OUT}")

urls = re.findall(r'https?://[^\s"\'<>\\)]{20,300}', body)
interesting = [u for u in urls if re.search(
    r'\.mp4|video|play|bytevc|tiktokcdn|muscdn|v\d+-web|aweme', u, re.I)]
print(f"total urls={len(urls)} provider-ish={len(interesting)}")
seen = set()
for u in interesting:
    key = u.split("?")[0][-60:]
    if key in seen:
        continue
    seen.add(key)
    print("   ", u[:150])
    if len(seen) >= 12:
        break

for name, pat in {
    "<video tag": r"<video[^>]{0,200}",
    "playAddr": r'playAddr[^,}]{0,120}',
    "play_addr": r'play_addr[^,}]{0,120}',
    "source": r'<source[^>]{0,160}',
    "mp4 mention": r'[^"]{0,40}video/mp4[^"]{0,60}',
}.items():
    hits = re.findall(pat, body)
    print(f"   {name:<14} {len(hits)} {str(hits[:2])[:160]}")

print()
print("=" * 72)
print("TIKTOK: oEmbed error body")
s, h, raw = go("https://www.tiktok.com/oembed?url="
               + urllib.parse.quote(f"https://www.tiktok.com/@a/video/{VID}", safe=""))
print(f"status={s} body={raw[:300]!r}")

print()
print("=" * 72)
print("X SYNDICATION (token param)")
for tok in ["abc", ""]:
    url = f"https://cdn.syndication.twimg.com/tweet-result?id=20&lang=en"
    if tok:
        url += f"&token={tok}"
    s, h, raw = go(url)
    print(f"   token={tok!r:<6} status={s} bytes={len(raw)} head={raw[:90]!r}")

print()
print("=" * 72)
print("DDG retry for X and Instagram ids (after a pause)")
time.sleep(5)
for label, q in [
    ("X", "site:x.com /status/ video"),
    ("IG", "instagram reel video"),
    ("IG alt", "site:instagram.com reel"),
]:
    time.sleep(4)
    s, h, raw = go("https://html.duckduckgo.com/html/?q=" + urllib.parse.quote(q))
    body = raw.decode("utf-8", "ignore")
    xs = list(dict.fromkeys(re.findall(
        r'(?:x|twitter)\.com/([A-Za-z0-9_]{1,30})/status/(\d{15,25})', body)))
    igs = list(dict.fromkeys(re.findall(
        r'instagram\.com/(?:reel|p)/([A-Za-z0-9_-]{9,14})', body)))
    print(f"   {label:<8} status={s} bytes={len(raw)} x_ids={len(xs)} ig_codes={len(igs)} {str(xs[:2]+igs[:2])[:80]}")
