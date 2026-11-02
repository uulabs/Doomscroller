#!/usr/bin/env python3
"""Fourth diagnostic: given an item id found via a web index, can an anonymous
client resolve it to a playable, portrait video?

This is the half of the pipeline that decides whether index-based discovery
is enough to ship.
"""

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


TIKTOK_IDS = ["7642516492377935135", "7373098322015391018", "7292838888756858154"]

print("=" * 72)
print("TIKTOK: id -> playable?")
for vid in TIKTOK_IDS[:2]:
    print(f"\n-- id {vid}")

    # 1. oEmbed: public, documented, no auth. Gives canonical url + author.
    canonical = f"https://www.tiktok.com/@placeholder/video/{vid}"
    s, h, raw = go("https://www.tiktok.com/oembed?url=" + urllib.parse.quote(canonical, safe=""))
    print(f"   oembed   status={s} bytes={len(raw)}")
    title = author = None
    if s == 200:
        try:
            o = json.loads(raw)
            author = o.get("author_unique_id") or o.get("author_name")
            title = (o.get("title") or "")[:60]
            print(f"      author={author!r} title={title!r}")
            print(f"      thumb={str(o.get('thumbnail_url'))[:70]}")
        except Exception as e:  # noqa: BLE001
            print("      not json", e)

    # 2. embed/v2: the public player page. Usually contains playAddr.
    s, h, raw = go(f"https://www.tiktok.com/embed/v2/{vid}",
                   headers={"Accept": "text/html", "Referer": "https://www.tiktok.com/"})
    body = raw.decode("utf-8", "ignore")
    print(f"   embed/v2 status={s} bytes={len(raw)}")
    if body:
        for name, pat in {
            "playAddr": r'"playAddr":"([^"]+)"',
            "play address (alt)": r'"play_addr".{0,200}?"url_list":\["([^"]+)"',
            "mimeType mp4": r'"mimeType":"video/mp4"',
            "dimensions": r'"width":(\d{2,5}),"height":(\d{2,5})',
            "webapp.video-detail": r'webapp\.video-detail',
        }.items():
            hits = re.findall(pat, body)
            if hits:
                print(f"      {name:<22} {len(hits)} -> {str(hits[:2])[:110]}")
    time.sleep(3)

print()
print("=" * 72)
print("INSTAGRAM: is any by-id surface public?")
# We need a real code. Try the popular surface a tag page redirected to,
# then the embed endpoint with whatever code it yields.
for tag in ["cake", "futebol"]:
    s, h, raw = go(f"https://www.instagram.com/popular/{tag}/", headers={"Accept": "text/html"})
    body = raw.decode("utf-8", "ignore")
    codes = list(dict.fromkeys(re.findall(r'/(?:reel|p)/([A-Za-z0-9_-]{9,14})', body)))
    print(f"   /popular/{tag}/ status={s} bytes={len(raw)} codes={len(codes)} {codes[:3]}")
    time.sleep(3)

print()
print("=" * 72)
print("X: syndication by id (we have no id yet, but confirm the surface)")
s, h, raw = go("https://cdn.syndication.twimg.com/tweet-result?id=20&lang=en")
print(f"   status={s} bytes={len(raw)}")
try:
    o = json.loads(raw)
    print(f"   keys={list(o.keys())[:10]}")
except Exception:
    print("   ", raw[:150])
