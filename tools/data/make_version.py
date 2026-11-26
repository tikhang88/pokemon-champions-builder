#!/usr/bin/env python3
"""Write version.json describing a data snapshot, for the app's online update check.

Usage: python3 make_version.py <champions_data.json> <version.json>
The app downloads version.json first, compares dataVersion and schemaVersion, then downloads
the snapshot and checks its SHA-256 against this file before importing it.
"""
import hashlib, json, sys

src, out = sys.argv[1], sys.argv[2]
raw = open(src, 'rb').read()
d = json.loads(raw)
m = d['meta']
info = {
    'schemaVersion': m['schemaVersion'],
    'dataVersion': m['dataVersion'],
    'generatedAt': m['generatedAt'],
    'currentRegulation': m['currentRegulation'],
    'nextRegulation': m.get('nextRegulation'),
    'sha256': hashlib.sha256(raw).hexdigest(),
    'bytes': len(raw),
    'file': 'champions_data.json',
    'regulations': [
        {'id': r['id'], 'provisional': bool(r.get('provisional')), 'verification': r['verification'],
         'teamSelectable': (r.get('counts') or {}).get('teamSelectable')}
        for r in d['regulations']
    ],
    'source': {'commit': m['source']['commit'], 'commitDate': m['source']['commitDate']},
}
with open(out, 'w', encoding='utf-8') as fh:
    json.dump(info, fh, ensure_ascii=False, indent=2)
print(info['dataVersion'], info['sha256'][:16], info['bytes'])
