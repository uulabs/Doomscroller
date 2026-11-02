#!/usr/bin/env python3
"""Build an auditable cold-start URL cache from stratified public CDX pages.
Run through run-on-970p.py. Does not download videos or claim current playability.
"""
import argparse, datetime, hashlib, json, os, pathlib, random, re, time
import urllib.parse, urllib.request, urllib.error
parser = argparse.ArgumentParser()
parser.add_argument("--pages-per-source", type=int, default=24)
parser.add_argument("--limit-per-source", type=int, default=6000)
parser.add_argument("--seed", type=int, default=int(datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d")))
args = parser.parse_args()
if not 1 <= args.pages_per_source <= 100 or not 100 <= args.limit_per_source <= 20000:
    parser.error("bounded page/entry limits required")
root = pathlib.Path(__file__).resolve().parents[1]
cache = pathlib.Path(os.environ["UULABS_SCRATCH_DIR"]) / "cdx"
cache.mkdir()
rng = random.Random(args.seed)
receipt = {"retrieved": datetime.datetime.now(datetime.timezone.utc).isoformat(), "seed": args.seed,
           "source": "https://web.archive.org/cdx/search/cdx", "sampling": "one random page per equal index band", "providers": {}}
lines = ["# source\treel-id; public URL cache, not a playlist or archived media; see docs/META-DISCOVERY.md"]
def get(url):
    request = urllib.request.Request(url, headers={"User-Agent": "UUDoomscrollerCatalogue/1.0 (https://github.com/uulabs/Doomscroller)"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read(4000000)
for source, host in [("Instagram", "www.instagram.com"), ("Facebook", "www.facebook.com")]:
    params = {"url": host+"/reel/*", "pageSize": "1"}
    count_url = receipt["source"]+"?"+urllib.parse.urlencode(dict(params, showNumPages="true"))
    count = int(get(count_url).strip())
    if not 0 < count <= 10000000: raise RuntimeError("Invalid page count")
    bands = min(args.pages_per_source, count)
    pages = [rng.randrange(i*count//bands, (i+1)*count//bands) for i in range(bands)]
    rng.shuffle(pages)
    entries, records = {}, []
    for page in pages:
        time.sleep(2)
        url = receipt["source"]+"?"+urllib.parse.urlencode(dict(params, page=str(page), output="json",
                filter="statuscode:200", collapse="urlkey", fl="original,timestamp", limit="1000"))
        try:
            raw = get(url)
            rows = json.loads(raw)
        except (urllib.error.URLError, TimeoutError, ValueError) as failure:
            records.append({"page":page,"error":str(failure)})
            print(source, "stopped after index refusal/error:", str(failure), flush=True)
            break
        name = source.lower()+"-"+str(page)+".json"
        (cache/name).write_bytes(raw)
        added = 0
        for row in rows[1:]:
            if len(row) < 2: continue
            parsed = urllib.parse.urlsplit(row[0])
            if parsed.hostname not in (host, host.removeprefix("www.")): continue
            match = re.match(r"^/reel/([A-Za-z0-9_-]{5,})(?:/|$)", parsed.path)
            if not match: continue
            reel = match.group(1)
            if source == "Facebook" and not reel.isdigit(): continue
            if reel not in entries: added += 1
            entries[reel] = max(entries.get(reel, ""), row[1])
        records.append({"page":page,"url":url,"sha256":hashlib.sha256(raw).hexdigest(),"uniqueAdded":added})
        print(source,"page",page,"unique",len(entries),flush=True)
        if len(entries) >= args.limit_per_source: break
    if len(entries) < 1000: raise RuntimeError(source+" has insufficient breadth; existing asset left unchanged")
    ids = sorted(entries);rng.shuffle(ids);ids = ids[:args.limit_per_source]
    lines += [source+"\t"+reel for reel in ids]
    receipt["providers"][source] = {"indexPages":count,"entries":len(ids),"sampledPages":records}
asset = root/"app/src/main/assets/meta-reels.tsv"
body = ("\n".join(lines)+"\n").encode()
receipt["assetSha256"] = hashlib.sha256(body).hexdigest()
asset.write_bytes(body)
(root/"docs/meta-catalog-receipt.json").write_text(json.dumps(receipt,indent=2)+"\n")
print("Wrote",len(lines)-1,"public reel URLs",receipt["assetSha256"],flush=True)
