#!/usr/bin/env python3
"""Diagnostic pass: dump what the controls actually return, so a failure is
a finding rather than a guess."""

import gzip
import json
import ssl
import urllib.error
import urllib.parse
import urllib.request

UA_MOBILE = (
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
)
UA_WEB = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)
CTX = ssl.create_default_context()


def go(url, headers=None, data=None, method=None):
    h = {
        "User-Agent": UA_WEB,
        "Accept": "*/*",
        "Accept-Language": "en-AU,en;q=0.9",
        "Accept-Encoding": "gzip",
    }
    if headers:
        h.update(headers)
    req = urllib.request.Request(url, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=25, context=CTX) as r:
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
            elif "br" in ((e.headers or {}).get("Content-Encoding") or ""):
                raw = b"<brotli>"
        except Exception:
            pass
        return e.code, dict(e.headers or {}), raw
    except Exception as e:  # noqa: BLE001
        return None, {}, repr(e).encode()


def show(label, status, headers, raw):
    txt = raw.decode("utf-8", "ignore")
    print(f"\n--- {label} ---")
    print(f"status={status} bytes={len(raw)} ctype={headers.get('Content-Type','')}")
    for probe in ("ytInitialData", "ytInitialPlayerResponse", "consent", "before you continue",
                  "__UNIVERSAL_DATA_FOR_REHYDRATION__", "login", "captcha", "shortcode", "videoId"):
        if probe.lower() in txt.lower():
            print(f"   contains: {probe}")
    print("   head:", txt[:280].replace("\n", " "))


q = urllib.parse.quote("cake")

# 1. YouTube search, desktop, with consent cookie
s, h, r = go(
    f"https://www.youtube.com/results?search_query={q}&sp=EgIYAQ%3D%3D",
    headers={"Cookie": "SOCS=CAI; CONSENT=YES+cb", "Accept": "text/html"},
)
show("YouTube search (desktop + consent)", s, h, r)

# 2. YouTube search, no filter, mobile UA
s, h, r = go(
    f"https://www.youtube.com/results?search_query={q}",
    headers={"User-Agent": UA_MOBILE, "Accept": "text/html"},
)
show("YouTube search (mobile UA, no filter)", s, h, r)

# 3. YouTube InnerTube player, ANDROID client
yt_key = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
payload = json.dumps({
    "context": {"client": {"clientName": "ANDROID", "clientVersion": "19.09.37",
                           "androidSdkVersion": 30, "hl": "en", "gl": "AU"}},
    "videoId": "dQw4w9WgXcQ",
}).encode()
s, h, r = go(f"https://www.youtube.com/youtubei/v1/player?key={yt_key}",
             headers={"Content-Type": "application/json"}, data=payload, method="POST")
show("YouTube InnerTube player (ANDROID)", s, h, r)

# 4. X guest token
xb = ("AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D"
      "1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA")
s, h, r = go("https://api.x.com/1.1/guest/activate.json",
             headers={"Authorization": f"Bearer {xb}", "Content-Type": "application/json",
                      "x-twitter-active-user": "yes"}, data=b"", method="POST")
show("X guest activate", s, h, r)
tok = None
try:
    tok = json.loads(r.decode()).get("guest_token")
except Exception:
    pass

if tok:
    qq = urllib.parse.quote("cake filter:native_video")
    s, h, r = go(
        f"https://api.x.com/1.1/search/tweets.json?q={qq}&count=20&result_type=recent&tweet_mode=extended",
        headers={"Authorization": f"Bearer {xb}", "x-guest-token": tok,
                 "x-twitter-active-user": "yes"},
    )
    show("X v1.1 search", s, h, r)

    s, h, r = go(
        f"https://api.x.com/2/search/adaptive.json?q={qq}&count=20&tweet_search_mode=live",
        headers={"Authorization": f"Bearer {xb}", "x-guest-token": tok,
                 "x-twitter-active-user": "yes"},
    )
    show("X v2 adaptive search", s, h, r)
