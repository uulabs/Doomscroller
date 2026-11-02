#!/usr/bin/env python3
"""Anonymous discovery spike for UU Doomscroller.

One question per provider: can an anonymous client, given a randomly chosen
subject, obtain a FRESH, PORTRAIT, PLAYABLE video?

  DISCOVER   subject -> at least one item id
  RESOLVE    item id -> a playable stream URL
  PORTRAIT   the same response states width/height so the feed can filter

Writes JSON + a plain-text digest into $UULABS_SCRATCH_DIR/spike/.
Stdlib only. Polite: one request at a time, sleeps between them.
"""

import gzip
import json
import os
import random
import re
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

UA = (
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
)
WEB_UA = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)

SUBJECTS = ["cake", "蛋糕", "futebol"]

OUT = Path(os.environ.get("UULABS_SCRATCH_DIR", ".")) / "spike"
OUT.mkdir(parents=True, exist_ok=True)

CTX = ssl.create_default_context()
RESULTS = []


def fetch(url, headers=None, data=None, timeout=25, method=None, ua=UA):
    h = {
        "User-Agent": ua,
        "Accept": "*/*",
        "Accept-Language": "en-AU,en;q=0.9",
        "Accept-Encoding": "gzip",
    }
    if headers:
        h.update(headers)
    req = urllib.request.Request(url, data=data, headers=h, method=method)
    started = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
            raw = r.read()
            enc = r.headers.get("Content-Encoding") or ""
            if "gzip" in enc:
                try:
                    raw = gzip.decompress(raw)
                except Exception:
                    pass
            return {
                "status": r.status,
                "final": r.geturl(),
                "bytes": len(raw),
                "ms": int((time.time() - started) * 1000),
                "headers": {k.lower(): v for k, v in r.headers.items()},
                "body": raw,
            }
    except urllib.error.HTTPError as e:
        raw = b""
        try:
            raw = e.read()
            if "gzip" in (e.headers.get("Content-Encoding") or ""):
                raw = gzip.decompress(raw)
        except Exception:
            pass
        return {
            "status": e.code,
            "final": url,
            "bytes": len(raw),
            "ms": int((time.time() - started) * 1000),
            "headers": {k.lower(): v for k, v in (e.headers or {}).items()},
            "body": raw,
        }
    except Exception as e:  # noqa: BLE001 - a probe records any failure as data
        return {
            "status": None,
            "final": url,
            "bytes": 0,
            "ms": int((time.time() - started) * 1000),
            "headers": {},
            "body": b"",
            "error": repr(e),
        }


def text(res):
    return res["body"].decode("utf-8", "ignore")


def wall_reason(body):
    """Distinguish a login wall from a bot check - they fail for different reasons."""
    low = body.lower()
    if "accounts/login" in low or "log in to instagram" in low or "log into facebook" in low:
        return "LOGIN_WALL"
    if "captcha" in low or "verify you are human" in low or "verify to continue" in low:
        return "BOT_CHECK"
    if "access denied" in low or "forbidden" in low:
        return "DENIED"
    return None


def record(provider, probe, subject, question, ok, detail, res=None):
    item = {
        "provider": provider,
        "probe": probe,
        "subject": subject,
        "question": question,
        "ok": ok,
        "detail": detail,
    }
    if res is not None:
        item.update(
            status=res.get("status"),
            bytes=res.get("bytes"),
            ms=res.get("ms"),
            final=res.get("final"),
        )
    RESULTS.append(item)
    mark = "PASS" if ok else "FAIL"
    print(f"  [{mark}] {probe:<10} {subject!r:<12} {detail}")
    return item


# --------------------------------------------------------------------------
# YouTube - the control. If this fails, the harness is wrong, not YouTube.
# --------------------------------------------------------------------------
YT_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"


def probe_youtube(subject):
    q = urllib.parse.quote(subject)
    # sp=EgIYAQ%3D%3D is the "Shorts" type filter.
    url = f"https://www.youtube.com/results?search_query={q}&sp=EgIYAQ%3D%3D"
    res = fetch(url, headers={"Cookie": "SOCS=CAI; CONSENT=YES+cb"})
    body = text(res)
    ids = []
    for m in re.findall(r'"videoId":"([A-Za-z0-9_-]{11})"', body):
        if m not in ids:
            ids.append(m)
    shorts = sorted(set(re.findall(r'/shorts/([A-Za-z0-9_-]{11})', body)))
    record(
        "youtube", "discover", subject, "fresh item ids anonymously",
        len(ids) >= 5,
        f"{len(ids)} ids, {len(shorts)} from /shorts/ links",
        res,
    )
    if not ids:
        return None
    vid = random.choice(ids)

    payload = json.dumps(
        {
            "context": {
                "client": {
                    "clientName": "ANDROID",
                    "clientVersion": "19.09.37",
                    "androidSdkVersion": 30,
                    "hl": "en",
                    "gl": "AU",
                }
            },
            "videoId": vid,
        }
    ).encode()
    pres = fetch(
        f"https://www.youtube.com/youtubei/v1/player?key={YT_KEY}",
        headers={"Content-Type": "application/json", "User-Agent": "com.google.android.youtube/19.09.37 (Linux; U; Android 14)"},
        data=payload,
        method="POST",
    )
    pbody = text(pres)
    try:
        root = json.loads(pbody)
    except Exception:
        root = {}
    streaming = (root.get("streamingData") or {}) if isinstance(root, dict) else {}
    formats = (streaming.get("formats") or []) + (streaming.get("adaptiveFormats") or [])
    sizes = [(f.get("width"), f.get("height")) for f in formats if f.get("width")]
    portrait = [s for s in sizes if s[1] and s[0] and s[1] > s[0]]
    urls = [f for f in formats if f.get("url")]
    record(
        "youtube", "resolve", vid, "playable stream url anonymously",
        bool(urls),
        f"{len(urls)} stream urls, {len(formats)} formats",
        pres,
    )
    record(
        "youtube", "portrait", vid, "response states width/height before play",
        bool(sizes),
        f"{len(sizes)} sized, portrait={len(portrait)}",
        pres,
    )
    return vid


# --------------------------------------------------------------------------
# X - second control. Guest token, then search, then a playable variant.
# --------------------------------------------------------------------------
X_BEARER = (
    "AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D"
    "1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"
)


def probe_x(subject):
    res = fetch(
        "https://api.x.com/1.1/guest/activate.json",
        headers={
            "Authorization": f"Bearer {X_BEARER}",
            "Content-Type": "application/json",
            "x-twitter-active-user": "yes",
            "User-Agent": WEB_UA,
        },
        data=b"",
        method="POST",
    )
    tok = None
    try:
        tok = json.loads(text(res)).get("guest_token")
    except Exception:
        pass
    record(
        "x", "token", subject, "anonymous guest token",
        bool(tok), f"token={'yes' if tok else 'no'} {res.get('status')}", res,
    )
    if not tok:
        return None

    q = urllib.parse.quote(f"{subject} filter:native_video")
    sres = fetch(
        f"https://api.x.com/1.1/search/tweets.json?q={q}&count=20&result_type=recent&tweet_mode=extended",
        headers={
            "Authorization": f"Bearer {X_BEARER}",
            "x-guest-token": tok,
            "x-twitter-active-user": "yes",
            "User-Agent": WEB_UA,
        },
    )
    try:
        data = json.loads(text(sres))
    except Exception:
        data = {}
    statuses = data.get("statuses") or []
    with_video = []
    for st in statuses:
        ext = st.get("extended_entities") or st.get("entities") or {}
        for media in ext.get("media") or []:
            variants = ((media.get("video_info") or {}).get("variants") or [])
            mp4 = [v for v in variants if v.get("content_type") == "video/mp4"]
            if mp4:
                sizes = ((media.get("sizes") or {}).get("large") or {})
                with_video.append(
                    {
                        "id": st.get("id_str"),
                        "mp4": max(mp4, key=lambda v: v.get("bitrate") or 0)["url"],
                        "w": sizes.get("w"),
                        "h": sizes.get("h"),
                    }
                )
    record(
        "x", "discover", subject, "fresh video posts anonymously",
        bool(with_video),
        f"{len(statuses)} statuses, {len(with_video)} with mp4",
        sres,
    )
    if with_video:
        v = with_video[0]
        portrait = bool(v["w"] and v["h"] and v["h"] > v["w"])
        record("x", "resolve", v["id"], "progressive mp4 variant", True, v["mp4"][:60] + "...")
        record("x", "portrait", v["id"], "dimensions before play", bool(v["w"]), f"{v['w']}x{v['h']} portrait={portrait}")
    return with_video[0]["id"] if with_video else None


# --------------------------------------------------------------------------
# TikTok - the open question.
# --------------------------------------------------------------------------
def probe_tiktok(subject):
    q = urllib.parse.quote(subject)
    url = f"https://www.tiktok.com/tag/{q}"
    res = fetch(
        url,
        headers={
            "Accept": "text/html,application/xhtml+xml",
            "Accept-Language": "en-AU,en;q=0.9",
            "Sec-Fetch-Mode": "navigate",
            "Sec-Fetch-Dest": "document",
        },
    )
    body = text(res)
    wall = wall_reason(body)
    hydrated = "__UNIVERSAL_DATA_FOR_REHYDRATION__" in body
    ids = sorted(set(re.findall(r'"id":"(7[0-9]{17,19})"', body)))
    record(
        "tiktok", "discover", subject, "tag page -> fresh item ids",
        bool(ids),
        f"{len(ids)} ids, hydrated={hydrated}, wall={wall}",
        res,
    )

    # Second surface: the site-wide search page, which a WebView would use.
    if not ids:
        sres = fetch(
            f"https://www.tiktok.com/search?q={q}",
            headers={"Accept": "text/html", "Sec-Fetch-Mode": "navigate"},
        )
        sbody = text(sres)
        sids = sorted(set(re.findall(r'"id":"(7[0-9]{17,19})"', sbody)))
        record(
            "tiktok", "search", subject, "/search page -> fresh item ids",
            bool(sids),
            f"{len(sids)} ids, wall={wall_reason(sbody)}",
            sres,
        )
        ids = sids
    return ids[0] if ids else None


# --------------------------------------------------------------------------
# Instagram - the open question.
# --------------------------------------------------------------------------
def probe_instagram(subject):
    q = urllib.parse.quote(subject)
    res = fetch(
        f"https://www.instagram.com/explore/tags/{q}/",
        headers={"Accept": "text/html", "Sec-Fetch-Mode": "navigate"},
        ua=WEB_UA,
    )
    body = text(res)
    wall = wall_reason(body)
    codes = sorted(set(re.findall(r'"shortcode":"([A-Za-z0-9_-]{8,15})"', body)))
    record(
        "instagram", "discover", subject, "tag page -> fresh reel codes",
        bool(codes),
        f"{len(codes)} codes, wall={wall}, final={res.get('final','')[:60]}",
        res,
    )
    if not codes:
        ares = fetch(
            f"https://www.instagram.com/api/v1/tags/web_info/?tag_name={q}",
            headers={
                "Accept": "*/*",
                "X-IG-App-ID": "936619743392459",
                "X-Requested-With": "XMLHttpRequest",
                "Referer": f"https://www.instagram.com/explore/tags/{q}/",
            },
            ua=WEB_UA,
        )
        abody = text(ares)
        record(
            "instagram", "api", subject, "tags/web_info endpoint",
            '"name"' in abody and ares.get("status") == 200,
            f"status={ares.get('status')} wall={wall_reason(abody)}",
            ares,
        )
        codes = sorted(set(re.findall(r'"shortcode":"([A-Za-z0-9_-]{8,15})"', abody)))
    return codes[0] if codes else None


# --------------------------------------------------------------------------
# Reddit - not on the brief, tested because it is the cheapest proof that a
# genuinely open, always-fresh pool exists somewhere.
# --------------------------------------------------------------------------
def probe_reddit(subject):
    res = fetch(
        "https://www.reddit.com/r/interestingasfuck/top.json?t=day&limit=50",
        headers={"Accept": "application/json"},
        ua=WEB_UA,
    )
    try:
        data = json.loads(text(res))
    except Exception:
        data = {}
    children = ((data.get("data") or {}).get("children") or [])
    vids = [c["data"] for c in children if c.get("data", {}).get("is_video")]
    record(
        "reddit", "discover", "top/day", "anonymous json listing -> video ids",
        bool(vids),
        f"{len(children)} posts, {len(vids)} videos",
        res,
    )
    if vids:
        v = vids[0]
        record("reddit", "portrait", v.get("id"), "dimensions before play",
               bool((v.get("secure_media") or {}).get("reddit_video")),
               (v.get("secure_media") or {}).get("reddit_video", {}).get("height") and
               f"{((v.get('secure_media') or {}).get('reddit_video') or {}).get('width')}x{((v.get('secure_media') or {}).get('reddit_video') or {}).get('height')}")
    return vids[0]["id"] if vids else None


def main():
    which = sys.argv[1:] or ["youtube", "x", "tiktok", "instagram", "reddit"]
    print(f"anonymous discovery spike -> {OUT}")
    for name in which:
        fn = globals().get(f"probe_{name}")
        if not fn:
            print(f"unknown probe {name}")
            continue
        print(f"\n=== {name} ===")
        for subject in (SUBJECTS if name != "reddit" else ["top/day"]):
            try:
                fn(subject)
            except Exception as e:  # noqa: BLE001
                record(name, "crash", subject, "probe raised", False, repr(e))
            time.sleep(2.5)

    (OUT / "results.json").write_text(json.dumps(RESULTS, indent=2) + "\n")
    passed = [r for r in RESULTS if r["ok"]]
    print(f"\n{len(passed)}/{len(RESULTS)} checks passed -> {OUT/'results.json'}")


if __name__ == "__main__":
    main()
