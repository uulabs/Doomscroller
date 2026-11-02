#!/usr/bin/env python3
"""Tenth diagnostic: verify and extend the three openings.

TikTok   /embed/tag/<subject>  -> video ids   (NEW)
         /sitemap.xml          -> video ids   (bulk)
X        syndication timeline-profile          -> status ids (needs a username)
Instagram still unresolved - retried with app-id headers.
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
CHROME = ("Mozilla/5.0 (Linux; Android 15; Blade A3 Plus) AppleWebKit/537.36 "
          "(KHTML, like Gecko) Chrome/155.0.0.0 Mobile Safari/537.36")

TT = r"tiktok\.com/@[\w.\-]+/video/(\d{15,25})"
XS = r"(?:x|twitter)\.com/[A-Za-z0-9_]{1,30}/status/(\d{15,25})"


def go(url, headers=None, timeout=25, ua=CHROME):
    h = {"User-Agent": ua, "Accept": "*/*", "Accept-Language": "en-AU,en;q=0.9",
         "Accept-Encoding": "gzip"}
    if headers:
        h.update(headers)
    req = urllib.request.Request(url, headers=h)
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
print("A. TIKTOK /embed/tag/<subject> across subjects and scripts")
subjects = ["cake", "蛋糕", "futebol", "adventure", "ラーメン"]
for subj in subjects:
    s, body = go("https://www.tiktok.com/embed/tag/" + urllib.parse.quote(subj))
    ids = list(dict.fromkeys(re.findall(TT, body)))
    print(f"  {subj:<12} status={s} bytes={len(body):>7} ids={len(ids)} {ids[:3]}")
    time.sleep(2.5)

print()
print("=" * 78)
print("B. Are those ids real, portrait and playable? (oEmbed)")
sample = None
s, body = go("https://www.tiktok.com/embed/tag/" + urllib.parse.quote("cake"))
ids = list(dict.fromkeys(re.findall(TT, body)))
print(f"  ids from 'cake': {len(ids)}")
for vid in ids[:3]:
    s, o = go("https://www.tiktok.com/oembed?url="
              + urllib.parse.quote(f"https://www.tiktok.com/@x/video/{vid}", safe=""))
    w = h = None
    try:
        d = json.loads(o)
        w, h = d.get("width"), d.get("height")
        title = (d.get("title") or "")[:40]
        author = d.get("author_unique_id")
    except Exception:
        title = author = None
    portrait = bool(w and h and int(h) > int(w))
    print(f"  {vid} status={s} {w}x{h} portrait={portrait} author={author!r} title={title!r}")
    time.sleep(2)

print()
print("=" * 78)
print("C. X: can syndication discover without a known username?")
for label, url in [
    ("timeline-profile screen-name", "https://syndication.twitter.com/srv/timeline-profile/screen-name/elonmusk"),
    ("timeline-profile hashtag", "https://syndication.twitter.com/srv/timeline-profile/hashtag/cake"),
    ("srv/timeline/hashtag", "https://syndication.twitter.com/srv/timeline/hashtag/cake"),
    ("srv/search", "https://syndication.twitter.com/srv/search?q=cake"),
    ("srv/timeline-profile (no arg)", "https://syndication.twitter.com/srv/timeline-profile"),
]:
    s, body = go(url)
    hits = list(dict.fromkeys(re.findall(XS, body)))
    print(f"  {label:<30} status={str(s):<5} bytes={len(body):>7} x_ids={len(hits)}")
    time.sleep(2)

print()
print("=" * 78)
print("D. Instagram: retry with the web app-id headers")
for label, url, hdrs in [
    ("tags/web_info", "https://www.instagram.com/api/v1/tags/web_info/?tag_name=cake",
     {"X-IG-App-ID": "936619743392459", "X-Requested-With": "XMLHttpRequest",
      "Referer": "https://www.instagram.com/explore/tags/cake/"}),
    ("users/web_profile_info", "https://www.instagram.com/api/v1/users/web_profile_info/?username=instagram",
     {"X-IG-App-ID": "936619743392459", "X-Requested-With": "XMLHttpRequest",
      "Referer": "https://www.instagram.com/instagram/"}),
]:
    s, body = go(url, headers=hdrs,
                 ua=("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
                     "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"))
    codes = list(dict.fromkeys(re.findall(r'"shortcode":"([A-Za-z0-9_-]{9,14})"', body)))
    print(f"  {label:<26} status={s} bytes={len(body):>7} shortcodes={len(codes)} "
          f"{'LOGIN' if 'login' in body.lower() else ''}")
    if codes:
        print("      ", codes[:3])
    time.sleep(2)
