#!/usr/bin/env python3
"""Thirteenth diagnostic: what do the sitemaps enumerate, and do those
entries lead to more content without any curated list?"""

import gzip
import re
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter

CTX = ssl.create_default_context()
DESKTOP = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
           "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")


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
print("A. TIKTOK sitemap - what kind of URLs does it enumerate?")
s, body = go("https://www.tiktok.com/sitemap.xml")
locs = re.findall(r"<loc>([^<]+)</loc>", body)
print(f"  status={s} total_loc={len(locs)}")
kinds = Counter()
tags, users, videos = [], [], []
for u in locs:
    p = urllib.parse.urlparse(u).path
    if "/video/" in p:
        kinds["video"] += 1
        videos.append(u)
    elif p.startswith("/tag/") or "/tag/" in p:
        kinds["tag"] += 1
        tags.append(p.split("/tag/")[-1].strip("/"))
    elif p.startswith("/@") or "/@" in p:
        kinds["profile"] += 1
        users.append(p.split("/@")[-1].strip("/"))
    else:
        kinds["other"] += 1
print(f"  kinds: {dict(kinds)}")
print(f"  sample tags:    {tags[:8]}")
print(f"  sample users:   {users[:5]}")
print(f"  sample videos:  {videos[:3]}")

print()
print("=" * 78)
print("B. Does a random sitemap tag yield videos? (embed/tag, polite)")
for tag in tags[:3]:
    s2, b2 = go("https://www.tiktok.com/embed/tag/" + urllib.parse.quote(tag))
    ids = list(dict.fromkeys(re.findall(r"tiktok\.com/@[\w.\-]+/video/(\d{15,25})", b2)))
    print(f"  tag={tag[:24]:<26} status={s2} bytes={len(b2):>7} ids={len(ids)} {ids[:2]}")
    time.sleep(8)

print()
print("=" * 78)
print("C. X sitemap.xml - does it enumerate profiles?")
s, body = go("https://x.com/sitemap.xml")
print(f"  status={s} bytes={len(body)}")
print(f"  <sitemapindex>={body.count('<sitemapindex')} <sitemap>={body.count('<sitemap>')} "
      f"<url>={body.count('<url>')}")
locs = re.findall(r"<loc>([^<]+)</loc>", body)
print(f"  locs={len(locs)}")
for u in locs[:8]:
    print(f"      {u}")
handles = [re.search(r"/([A-Za-z0-9_]{2,20})/?$", u).group(1)
           for u in locs if re.search(r"/([A-Za-z0-9_]{2,20})/?$", u)]
handles = [h for h in handles if h.lower() not in {"xml", "sitemap"}]
print(f"  handle-like entries: {len(handles)} sample={handles[:10]}")

if handles:
    time.sleep(3)
    probe = handles[0]
    s3, b3 = go("https://syndication.twitter.com/srv/timeline-profile/screen-name/" + probe)
    xs = list(dict.fromkeys(re.findall(r"status/(\d{15,25})", b3)))
    print(f"  timeline for sitemap handle '{probe}': status={s3} bytes={len(b3)} status_ids={len(xs)}")
