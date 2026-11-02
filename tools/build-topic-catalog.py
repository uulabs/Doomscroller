#!/usr/bin/env python3
"""Refresh bundled CC0 Wikidata subjects. Offline at app runtime; never a video catalogue."""
import json, urllib.request, urllib.parse, time, pathlib, hashlib, os
ROOT=pathlib.Path(__file__).resolve().parents[1]
LANGS='en ja zh ko es pt fr de it ru ar hi id tr th'.split()
# Separate families are sampled equally, regardless of how many labels they have.
DOMAINS={
'sport':'Q349', 'food':'Q2095', 'drink':'Q40050', 'music':'Q34379',
'dance':'Q11639', 'craft':'Q2207288', 'art':'Q735', 'clothing':'Q11460',
'vehicles':'Q42889', 'aircraft':'Q11436', 'watercraft':'Q1229765',
'occupations':'Q28640', 'tools':'Q39546', 'machines':'Q11019',
'computing':'Q21198', 'artificial-intelligence':'Q11660', 'technology':'Q11016',
'science':'Q336', 'mathematics':'Q395', 'astronomy':'Q333',
'geology':'Q1069', 'engineering':'Q81096', 'weather':'Q11663', 'geography':'Q1071',
'architecture':'Q12271', 'games':'Q11410', 'hobbies':'Q47728',
'agriculture':'Q11451', 'plants':'Q756', 'animals':'Q729',
'culture':'Q11042', 'economics':'Q8134', 'education':'Q8434'}
cache=pathlib.Path(os.environ.get('CATALOG_CACHE',os.environ['UULABS_SCRATCH_DIR']+'/wikidata'));cache.mkdir(exist_ok=True)
rows={}; stats={}
for domain,root in DOMAINS.items():
 query=f"""SELECT DISTINCT ?item ?label WHERE {{
 {{ ?item wdt:P279 wd:{root} }} UNION {{ ?item wdt:P31 wd:{root} }}
 ?item rdfs:label ?label . FILTER(LANG(?label) IN ({','.join(chr(34)+l+chr(34) for l in LANGS)}))
 }} LIMIT 4500"""
 url='https://query.wikidata.org/sparql?'+urllib.parse.urlencode({'query':query,'format':'json'})
 cached=cache/(domain+'.json')
 if cached.exists():obj=json.loads(cached.read_bytes())
 else:
  for attempt in range(3):
   try:
    data=urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'UUDoomscrollerVocabulary/1.0 (https://github.com/uulabs/Doomscroller)'}),timeout=25).read()
    obj=json.loads(data);break
   except Exception:
    if attempt==2: raise
    time.sleep(3)
  cached.write_bytes(data)
 count=0
 for b in obj['results']['bindings']:
  qid=b['item']['value'].split('/')[-1];lang=b['label']['xml:lang'];label=b['label']['value'].strip()
  if not label or ':' in label or len(label)>90 or any(c in label for c in '\t\n\r'):continue
  # One concept belongs to only one family: overlap must not multiply its odds.
  if qid not in rows: rows[qid]={'domain':domain,'labels':{}}
  rows[qid]['labels'][lang]=label
  count+=1
 stats[domain]=count;print(domain,count,flush=True);time.sleep(0.3)
out=ROOT/'app/src/main/assets/topics';out.mkdir(exist_ok=True)
for lang in LANGS:
 lines=['# domain\tconcept-id\tquery; Wikidata CC0; see docs/TOPIC-CATALOG.md']
 seen=set()
 for qid,row in rows.items():
  if len(row['labels']) < 4: continue
  label=row['labels'].get(lang)
  if label and label.casefold() not in seen:
   lines.append(row['domain']+'\t'+qid+'\t'+label);seen.add(label.casefold())
 (out/(lang+'.tsv')).write_text('\n'.join(lines)+'\n')
 print(lang,len(lines)-1,flush=True)
(ROOT/'docs/topic-catalog-receipt.json').write_text(json.dumps({'source':'https://query.wikidata.org/sparql','license':'CC0','retrieved':'2026-11-02','roots':DOMAINS,'concepts':len(rows),'retainedConcepts':len({qid for qid,row in rows.items() if len(row['labels'])>=4}),'queriesPerLanguage':{p.stem:len(p.read_text().splitlines())-1 for p in out.glob('*.tsv')},'files':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in out.glob('*.tsv')}},indent=2)+'\n')
