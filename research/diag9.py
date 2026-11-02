#!/usr/bin/env python3
"""Ninth diagnostic: the techniques not yet tried.

1. Crawler user agents (SEO cloaking) - platforms render content for Googlebot
   that they withhold from browsers.
2. Public X syndication widgets - the timeline-profile endpoint is keyless.
3. Third-party frontends: Nitter/xcancel, ProxiTok, RSSHub.
4. Legacy hostnames (mbasic, m.).
5. Sitemaps and profile embeds.
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

PATTERNS = {
    "tiktok": r"tiktok\.com/@[\w.\-]+/video/(\d{15,25})",
    "ig": r"instagram\.com/(?:reel|p|tv)/([A-Za-z0-9_-]{9,14})",
    "x": r"(?:x|twitter)\.com/[A-Za-z0-9_]{1,30}/status/(\d{15,25})",
    "yt": r"(?:youtube\.com/shorts/|youtu\.be/)([A-Za-z0-9_-]{11})",
}

UAS = {
    "googlebot": "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
    "bingbot": "Mozilla/5.0 (compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm)",
    "facebook": "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)",
    "twitterbot": "Twitterbot/1.0",
    "chrome": ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
               "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"),
}


def go(url, ua="chrome", headers=None, timeout=25):
    h = {"User-Agent": UAS[ua], "Accept": "text/html,application/xhtml+xml,application/json",
         "Accept-Language": "en-AU,en;q=0.9", "Accept-Encoding": "gzip"}
    if headers:
        h.update(headers)
    req = urllib.request.Request(url, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
            raw = r.read()
            if "gzip" in (r.headers.get("Content-Encoding") or ""):
                raw = gzip.decompress(raw)
            return r.status, raw.decode("utf-8", "ignore"), r.geturl()
    except urllib.error.HTTPError as e:
        raw = b""
        try:
            raw = e.read()
            if "gzip" in ((e.headers or {}).get("Content-Encoding") or ""):
                raw = gzip.decompress(raw)
        except Exception:
            pass
        return e.code, raw.decode("utf-8", "ignore"), url
    except Exception as e:  # noqa: BLE001
        return None, repr(e), url


def check(label, url, ua="chrome", headers=None, quiet=False):
    s, body, final = go(url, ua, headers)
    hits = {}
    for name, pat in PATTERNS.items():
        found = list(dict.fromkeys(re.findall(pat, body)))
        if found:
            hits[name] = found
    login = "accounts/login" in body or "mode=login" in final
    captcha = "captcha" in body.lower()
    print(f"  {label:<34} {ua:<11} status={str(s):<5} bytes={len(body):>7} "
          f"login={int(login)} captcha={int(captcha)} "
          f"hits={ {k: len(v) for k, v in hits.items()} }")
    for k, v in hits.items():
        print(f"        {k}: {v[:3]}")
    return hits, body


print("=" * 78)
print("1. CRAWLER UA / SEO CLOAKING")
check("instagram tag x googlebot", "https://www.instagram.com/explore/tags/cake/", "googlebot")
time.sleep(2)
check("instagram tag x facebook", "https://www.instagram.com/explore/tags/cake/", "facebook")
time.sleep(2)
check("tiktok tag x googlebot", "https://www.tiktok.com/tag/cake", "googlebot")
time.sleep(2)
check("tiktok tag x bingbot", "https://www.tiktok.com/tag/cake", "bingbot")
time.sleep(2)
check("x search x googlebot", "https://x.com/search?q=cake&f=live", "googlebot")

print()
print("=" * 78)
print("2. X SYNDICATION WIDGETS (keyless)")
check("synd timeline-profile", "https://syndication.twitter.com/srv/timeline-profile/screen-name/elonmusk")
time.sleep(2)
check("synd cdn timeline profile",
      "https://cdn.syndication.twimg.com/timeline/profile?screen_name=elonmusk&dnt=true")
time.sleep(2)
check("synd search widget", "https://syndication.twitter.com/srv/timeline-profile/search?q=cake")

print()
print("=" * 78)
print("3. THIRD-PARTY FRONTENDS")
for host in ["https://xcancel.com", "https://nitter.poast.org", "https://nitter.tiekoetter.com"]:
    check(f"{host}/search", f"{host}/search?f=tweets&q=cake")
    time.sleep(2)
for host in ["https://proxitok.pabloferreiro.es", "https://proxitok.privacydev.net"]:
    check(f"{host}/tag/cake", f"{host}/tag/cake")
    time.sleep(2)
check("rsshub tiktok tag", "https://rsshub.app/tiktok/tag/cake")

print()
print("=" * 78)
print("4. LEGACY HOSTNAMES")
check("mbasic.facebook reel", "https://mbasic.facebook.com/reel/1234567890")
time.sleep(2)
check("m.instagram tag", "https://m.instagram.com/explore/tags/cake/")
time.sleep(2)
check("m.tiktok tag", "https://m.tiktok.com/tag/cake")

print()
print("=" * 78)
print("5. SITEMAPS / PROFILE EMBEDS")
check("tiktok sitemap", "https://www.tiktok.com/sitemap.xml")
time.sleep(2)
check("tiktok embed profile", "https://www.tiktok.com/embed/@tiktok")
time.sleep(2)
check("tiktok embed tag", "https://www.tiktok.com/embed/tag/cake")
