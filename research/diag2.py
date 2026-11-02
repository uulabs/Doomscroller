#!/usr/bin/env python3
"""Second diagnostic: fix YouTube extraction, and find out what X can still do."""

import gzip
import json
import re
import ssl
import urllib.error
import urllib.parse
import urllib.request

UA_WEB = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
          "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
CTX = ssl.create_default_context()


def go(url, headers=None, data=None, method=None):
    h = {"User-Agent": UA_WEB, "Accept": "*/*", "Accept-Language": "en-AU,en;q=0.9",
         "Accept-Encoding": "gzip"}
    if headers:
        h.update(headers)
    req = urllib.request.Request(url, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=25, context=CTX) as r:
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


# ---------------------------------------------------------------- YouTube
print("=" * 70)
print("YOUTUBE DISCOVERY")
q = urllib.parse.quote("cake")
status, raw = go(f"https://www.youtube.com/results?search_query={q}&sp=EgIYAQ%3D%3D",
                 headers={"Cookie": "SOCS=CAI; CONSENT=YES+cb", "Accept": "text/html"})
body = raw.decode("utf-8", "ignore")
print(f"status={status} bytes={len(raw)}")

patterns = {
    'plain          "videoId":"X"': r'"videoId":"([A-Za-z0-9_-]{11})"',
    'escaped    \\"videoId\\":\\"X\\"': r'\\"videoId\\":\\"([A-Za-z0-9_-]{11})\\"',
    'url-encoded  videoId%22%3A%22X': r'videoId%22%3A%22([A-Za-z0-9_-]{11})',
    '/shorts/ links': r'/shorts/([A-Za-z0-9_-]{11})',
    'watch?v= links': r'watch\?v=([A-Za-z0-9_-]{11})',
}
for name, pat in patterns.items():
    hits = re.findall(pat, body)
    uniq = list(dict.fromkeys(hits))
    print(f"  {name:<34} {len(hits):>4} hits / {len(uniq):>4} unique")
    if uniq:
        print(f"      sample: {uniq[:3]}")

i = body.find("videoId")
if i >= 0:
    print("  context:", body[i - 60:i + 90].replace("\n", " "))

# ---------------------------------------------------------- YouTube player
print()
print("=" * 70)
print("YOUTUBE PLAYER (InnerTube)")
payload = json.dumps({
    "context": {"client": {"clientName": "WEB", "clientVersion": "2.20240726.00.00",
                           "hl": "en", "gl": "AU"}},
    "videoId": "dQw4w9WgXcQ",
}).encode()
key = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
s, r = go(f"https://www.youtube.com/youtubei/v1/player?key={key}",
          headers={"Content-Type": "application/json", "Origin": "https://www.youtube.com",
                   "Referer": "https://www.youtube.com/"}, data=payload, method="POST")
print(f"status={s} bytes={len(r)}")
try:
    root = json.loads(r.decode())
    print("  keys:", list(root.keys())[:8])
    print("  playability:", (root.get("playabilityStatus") or {}).get("status"),
          (root.get("playabilityStatus") or {}).get("reason"))
    sd = root.get("streamingData") or {}
    fmts = (sd.get("formats") or []) + (sd.get("adaptiveFormats") or [])
    print(f"  formats={len(fmts)} with_url={sum(1 for f in fmts if f.get('url'))}")
    for f in fmts[:4]:
        print(f"    {f.get('mimeType','?')[:40]:<42} {f.get('width')}x{f.get('height')}")
except Exception as e:  # noqa: BLE001
    print("  not json:", r[:200])

# ---------------------------------------------------------------------- X
print()
print("=" * 70)
print("X SURFACES")
xb = ("AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D"
      "1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA")

for label, url in [
    ("web search page", "https://x.com/search?q=cake%20filter%3Avideos&f=live"),
    ("web search page (twitter.com)", "https://twitter.com/search?q=cake&f=live"),
]:
    s, r = go(url, headers={"Accept": "text/html"})
    t = r.decode("utf-8", "ignore")
    print(f"  {label:<32} status={s} bytes={len(r)} "
          f"has_tweet_text={'data-testid=\"tweet\"' in t} login_wall={'login' in t.lower() and 'sign' in t.lower()}")

# syndication: works for a KNOWN id, but is not a discovery surface
s, r = go("https://cdn.syndication.twimg.com/tweet-result?id=20&lang=en&token=abc")
print(f"  {'syndication (known id)':<32} status={s} bytes={len(r)} head={r[:120]!r}")

# trends: historically public
s, r = go("https://api.x.com/2/trends/place.json?id=23424748")
print(f"  {'v2 trends/place (anon)':<32} status={s} bytes={len(r)} head={r[:160]!r}")

s, r = go("https://trends24.in/australia/")
print(f"  {'trends24 (3rd party)':<32} status={s} bytes={len(r)}")
