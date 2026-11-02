#!/usr/bin/env python3
"""Refresh localized family queries from Wikidata CC0 labels; no video catalogue."""
import hashlib, json, os, pathlib, urllib.parse, urllib.request
root = pathlib.Path(__file__).resolve().parents[1]
receipt = json.loads((root / "docs/topic-catalog-receipt.json").read_text())
languages = "en ja zh ko es pt fr de it ru ar hi id tr th".split()
params = {"action": "wbgetentities", "ids": "|".join(receipt["roots"].values()),
          "props": "labels", "languages": "|".join(languages), "format": "json"}
url = "https://www.wikidata.org/w/api.php?" + urllib.parse.urlencode(params)
with urllib.request.urlopen(urllib.request.Request(url, headers={
        "User-Agent": "UUDoomscrollerVocabulary/1.0 (https://github.com/uulabs/Doomscroller)"}), timeout=25) as response:
    raw = response.read()
(pathlib.Path(os.environ["UULABS_SCRATCH_DIR"]) / "family-labels.json").write_bytes(raw)
entities = json.loads(raw)["entities"]
lines = ["# language\tfamily\tquery; Wikidata root labels, CC0"]
for family, qid in receipt["roots"].items():
    for language in languages:
        label = entities[qid]["labels"].get(language, {}).get("value")
        if label and not any(c in label for c in "\t\n\r"):
            lines.append("\t".join([language, family, label]))
output = root / "app/src/main/assets/topic-families.tsv"
output.write_text("\n".join(lines) + "\n")
print(len(lines)-1, "localized queries", hashlib.sha256(output.read_bytes()).hexdigest())
