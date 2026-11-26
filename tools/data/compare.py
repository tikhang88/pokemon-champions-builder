#!/usr/bin/env python3
"""Exit 0 and print 'changed=true|false' depending on whether two snapshots differ in content.

Build-time fields (generation time, version label, Showdown commit) are ignored, so a new
Showdown commit that doesn't touch Champions data doesn't create a new data release.
"""
import json, sys

IGNORED_META = {'generatedAt', 'dataVersion', 'source', 'regulationInEffect'}

def content(path):
    try:
        d = json.load(open(path, encoding='utf-8'))
    except FileNotFoundError:
        return None
    d['meta'] = {k: v for k, v in d['meta'].items() if k not in IGNORED_META}
    return d

old, new = content(sys.argv[1]), content(sys.argv[2])
print('changed=' + ('true' if old != new else 'false'))
