#!/usr/bin/env python3
"""Eighth diagnostic: a search engine's own web page as an on-device,
keyless, serverless discovery surface.

Bing wraps results as /ck/a?...&u=a1<base64url>. The earlier test saw zero
provider URLs because it never decoded that. This does.
"""

import base64
import gzip
import re
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request

UA_MOBILE = ("Mozilla/5.0 (Linux; Android 15; Blade A3 Plus) AppleWebKit/537.36 "
             "(KHTML, like Gecko) Chrome/155.0.0.0 Mobile Safari/537.36")
UA_DESKTOP = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
CTX = ssl.create_default_context()

PATTERNS = {
    "tiktok": r"tiktok\.com/@[\w.\-]+/video/(\d{15,25})",
    "instagram": r"instagram\.com/(?:reel|p|tv)/([A-Za-z0-9_-]{9,14})",
    "x": r"(?:x|twitter)\.com/[A-Za-z0-9_]{1,30}/status/(\d{15,25})",
    "youtube": r"(?:youtube\.com/shorts/|youtu\.be/)([A-Za-z0-9_-]{11})",
}


def go(url, ua=UA_MOBILE, headers=None, timeout=30):
    h = {"User-Agent": ua, "Accept": "text/html,application/xhtml+xml",
         "Accept-Language": "en-AU,en;q=0.9", "Accept-Encoding": "gzip"}
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


def decode_bing(body):
    """Bing's /ck/a redirect carries the target base64url after 'u=a1'."""
    out = []
    for m in re.findall(r"u=a1([A-Za-z0-9_\-]+)", body):
        s = m.replace("-", "+").replace("_", "/")
        s += "=" * (-len(s) % 4)
        try:
            out.append(base64.b64decode(s).decode("utf-8", "ignore"))
        except Exception:
            pass
    return out


def report(label, status, body, decoded=None):
    blob = body + " " + " ".join(decoded or [])
    hits = {}
    for name, pat in PATTERNS.items():
        found = list(dict.fromkeys(re.findall(pat, blob)))
        if found:
            hits[name] = found
    print(f"  {label:<40} status={status} bytes={len(body):>7} "
          f"decoded={len(decoded or [])} found={ {k: len(v) for k, v in hits.items()} }")
    for k, v in hits.items():
        print(f"      {k}: {v[:3]}")
    return hits


print("=" * 74)
print("BING (mobile UA), with redirect decoding")
queries = [
    ("tiktok cake", "site:tiktok.com cake"),
    ("instagram reel", "site:instagram.com reel cooking"),
    ("x video", "site:x.com cake video"),
]
for label, q in queries:
    url = "https://www.bing.com/search?q=" + urllib.parse.quote(q) + "&count=30"
    status, body = go(url)
    decoded = decode_bing(body)
    report(f"bing {label}", status, body, decoded)
    time.sleep(3)

print()
print("=" * 74)
print("BING (desktop UA)")
for label, q in [("tiktok", "site:tiktok.com cake"),
                 ("instagram", "site:instagram.com reel cooking")]:
    url = "https://www.bing.com/search?q=" + urllib.parse.quote(q) + "&count=30"
    status, body = go(url, ua=UA_DESKTOP)
    decoded = decode_bing(body)
    report(f"bing-desktop {label}", status, body, decoded)
    time.sleep(3)

print()
print("=" * 74)
print("DUCKDUCKGO lite (may still throttle)")
for label, q in [("tiktok", "site:tiktok.com cake")]:
    url = "https://lite.duckduckgo.com/lite/?q=" + urllib.parse.quote(q)
    status, body = go(url)
    report(f"ddg-lite {label}", status, body)
    time.sleep(2)

print()
print("=" * 74)
print("MOJEEK plain (no site: operator - that was what 403'd)")
for label, q in [("cake", "cake"), ("tiktok", "tiktok cake video")]:
    url = "https://www.mojeek.com/search?q=" + urllib.parse.quote(q)
    status, body = go(url)
    report(f"mojeek {label}", status, body)
    time.sleep(2)
