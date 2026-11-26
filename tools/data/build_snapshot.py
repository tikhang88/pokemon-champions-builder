#!/usr/bin/env python3
"""Build the offline data snapshot for the Pokémon Champions Team Builder.

Usage:
  python3 build_snapshot.py --showdown <pokemon-showdown checkout> --out <file.json>

Showdown's TypeScript data files are parsed as text by extract.mjs (TypeScript AST,
literal values only); nothing from the Showdown checkout is executed.

Regulations are detected automatically from Showdown's config/formats.ts: every
"[Gen 9 Champions] ... Reg M-X" format names the mod that holds that Regulation's
legal list. When Showdown adds Regulation M-D, the next run picks it up with no code
change. Dates and notes come from regulations_meta.json next to this script.
"""
import argparse, datetime, json, os, re, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCHEMA_VERSION = 1
COMMUNITY = 'COMMUNITY_SOURCE'
PENDING = 'PENDING_VERIFICATION'

ap = argparse.ArgumentParser()
ap.add_argument('--showdown', required=True)
ap.add_argument('--out', required=True)
ap.add_argument('--now', help='ISO time used to pick the current Regulation (default: now)')
args = ap.parse_args()
SD = os.path.abspath(args.showdown)
TMP = tempfile.mkdtemp(prefix='champions-raw-')


def git(*a):
    return subprocess.check_output(['git', '-C', SD, *a], text=True).strip()


COMMIT = git('rev-parse', 'HEAD')
COMMIT_DATE = git('log', '-1', '--format=%cs')
NOW = datetime.datetime.fromisoformat(args.now.replace('Z', '+00:00')) if args.now else datetime.datetime.now(datetime.timezone.utc)


def load_ts(rel):
    """Parse one Showdown data file to JSON (literals only)."""
    src = os.path.join(SD, rel)
    if not os.path.exists(src):
        return None
    dst = os.path.join(TMP, rel.replace('/', '_') + '.json')
    subprocess.check_call(['node', os.path.join(HERE, 'extract.mjs'), src, dst], stdout=subprocess.DEVNULL)
    return json.load(open(dst))


dex = load_ts('data/pokedex.ts')
bmoves, bitems, babil = load_ts('data/moves.ts'), load_ts('data/items.ts'), load_ts('data/abilities.ts')
tchart, natures = load_ts('data/typechart.ts'), load_ts('data/natures.ts')
cmoves = load_ts('data/mods/champions/moves.ts') or {}
citems = load_ts('data/mods/champions/items.ts') or {}
cabil = load_ts('data/mods/champions/abilities.ts') or {}
clearn = load_ts('data/mods/champions/learnsets.ts')
blearn = load_ts('data/learnsets.ts')  # main-series learnsets: only for provisional (not yet in Champions) Pokémon
tmoves, tabil, titems = load_ts('data/text/moves.ts'), load_ts('data/text/abilities.ts'), load_ts('data/text/items.ts')
zdex = load_ts('data/text/zh-tw/pokedex.ts') or {}
zmoves = load_ts('data/text/zh-tw/moves.ts') or {}
zabil = load_ts('data/text/zh-tw/abilities.ts') or {}
zitems = load_ts('data/text/zh-tw/items.ts') or {}
formats = load_ts('config/formats.ts')

# ---- Regulation → Showdown mod --------------------------------------------------------
REG_RE = re.compile(r'^\[Gen 9 Champions\].*\bReg (M-[A-Z])\b')
reg_mods = {}
for f in formats:
    if not isinstance(f, dict) or 'name' not in f:
        continue
    m = REG_RE.match(f['name'])
    if m and f.get('mod'):
        reg_mods.setdefault(m.group(1), f['mod'])
if not reg_mods:
    sys.exit('No Champions regulation formats found in config/formats.ts')
reg_fd = {}
for reg, mod in reg_mods.items():
    fd = load_ts(f'data/mods/{mod}/formats-data.ts')
    if fd:
        reg_fd[reg] = fd
print('Regulations in Showdown:', {r: reg_mods[r] for r in sorted(reg_fd)})

META = json.load(open(os.path.join(HERE, 'regulations_meta.json')))
META_REGS = META['regulations']


def eff(base, mod):
    out = dict(base)
    for k, v in (mod or {}).items():
        if k != 'inherit':
            out[k] = v
    return out


def zh(table, key):
    e = table.get(key, {})
    for f in ('name', 'baseSpecies'):
        if isinstance(e.get(f), str):
            return e[f]
    return None


def champ_text(t, key):
    e = t.get(key, {})
    c = e.get('champions', {}) if isinstance(e.get('champions'), dict) else {}
    return c.get('shortDesc') or e.get('shortDesc') or c.get('desc') or e.get('desc')


def to_id(name):
    return ''.join(ch for ch in (name or '').lower() if ch.isalnum())


def is_mega(s):
    return 'Mega' in (s.get('forme') or '') and bool(s.get('requiredItem') or s.get('requiredItems'))


def team_selectable(k):
    s = dex[k]
    return not s.get('battleOnly') or is_mega(s)


def legal_ids(fd):
    out = []
    for k, v in fd.items():
        if v.get('isNonstandard') or v.get('tier') == 'Illegal':
            continue
        s = dex.get(k)
        if not s:
            continue
        tags = s.get('tags', [])
        if 'Mythical' in tags or 'Restricted Legendary' in tags:
            continue
        out.append(k)
    return out


legal = {r: legal_ids(fd) for r, fd in reg_fd.items()}

# Provisional lists: an announced Regulation that Showdown doesn't have yet = the Regulation it
# builds on + the officially named additions (regulations_meta.json "provisional").
provisional, provisional_adds, ability_overrides, custom_abilities = {}, set(), {}, []
for m in META_REGS:
    pv = m.get('provisional')
    if not pv or m['id'] in legal or pv.get('basedOn') not in legal:
        continue
    adds = [k for k in pv.get('add', []) if k in dex]
    missing = [k for k in pv.get('add', []) if k not in dex]
    if missing:
        print('WARNING: provisional IDs not in Showdown pokedex:', missing)
    provisional[m['id']] = legal[pv['basedOn']] + [k for k in adds if k not in legal[pv['basedOn']]]
    provisional_adds.update(k for k in adds if not any(k in l for l in legal.values()))
    ability_overrides.update(pv.get('abilityOverrides', {}))
    custom_abilities += pv.get('customAbilities', [])
all_legal = {**legal, **provisional}

# ---- Pokémon --------------------------------------------------------------------------
all_ids = sorted({k for lst in all_legal.values() for k in lst}, key=lambda k: (dex[k]['num'], k))
reg_order = sorted(all_legal)
pokemon = []
for k in all_ids:
    s = dex[k]
    mega = is_mega(s)
    base_id = to_id(s.get('baseSpecies', s['name']))
    pokemon.append({
        'id': k, 'name': s['name'], 'nameZh': zh(zdex, k) or zh(zdex, base_id),
        'num': s['num'], 'types': s['types'], 'baseStats': s['baseStats'],
        'bst': sum(s['baseStats'].values()),
        'abilities': ability_overrides.get(k) if k in provisional_adds and k in ability_overrides else list(s['abilities'].values()),
        'forme': s.get('forme'), 'baseSpecies': s.get('baseSpecies', s['name']),
        'isMega': mega, 'megaStone': s.get('requiredItem') if mega else None,
        'megaOf': base_id if mega else None,
        'battleOnly': bool(s.get('battleOnly')), 'teamSelectable': team_selectable(k),
        'heightM': s.get('heightm'), 'weightKg': s.get('weightkg'),
        'gender': s.get('gender'), 'otherFormes': s.get('otherFormes', []),
        'legalIn': [r for r in reg_order if k in all_legal[r]],
        'source': 'showdown', 'verification': PENDING if k in provisional_adds else COMMUNITY,
    })
    if k in provisional_adds and k in ability_overrides:
        pokemon[-1]['abilityNote'] = 'Official Mega ability from the announcement; Showdown has no Champions data yet.'

# ---- Learnsets ------------------------------------------------------------------------
LEARNSET_ALIASES = {'floettemega': 'floetteeternal'}  # Mega Floette comes from Eternal Floette


def learnset_for(k):
    s = dex[k]
    for cand in (k, to_id(s.get('changesFrom')), LEARNSET_ALIASES.get(k), to_id(s.get('baseSpecies'))):
        if cand and cand in clearn and 'learnset' in clearn[cand]:
            return cand, sorted(clearn[cand]['learnset'].keys())
    return None, []


def main_series_learnset(k):
    """Scarlet/Violet (gen 9) learnset, used only for provisional Pokémon and always labelled."""
    s = dex[k]
    for cand in (k, to_id(s.get('baseSpecies'))):
        ls = blearn.get(cand, {}).get('learnset')
        if ls:
            return cand, sorted(m for m, src in ls.items() if any(x.startswith('9') for x in src))
    return None, []


learnsets, missing_learnsets = {}, []
for p in pokemon:
    src, mv = learnset_for(p['id'])
    if not mv and p['id'] in provisional_adds:
        src, mv = main_series_learnset(p['id'])
        if mv:
            p['learnsetSource'] = 'MAIN_SERIES_SV'
    if mv:
        learnsets[p['id']] = mv
        if src != p['id']:
            p['learnsetFrom'] = src
    else:
        missing_learnsets.append(p['id'])

# ---- Moves ----------------------------------------------------------------------------
CHANGE_FIELDS = ('basePower', 'accuracy', 'pp', 'priority', 'type', 'category', 'target', 'secondary', 'secondaries')
moves = []
patch_moves = {c['move'] for bp in META.get('balancePatches', []) for key in ('moveChanges', 'newMoves') for c in bp.get(key, [])}
for k in sorted({m for ms in learnsets.values() for m in ms} | patch_moves):
    if k not in bmoves:
        continue
    m = eff(bmoves[k], cmoves.get(k))
    if m.get('isNonstandard'):
        continue
    changed = k in cmoves and any(f in cmoves[k] for f in CHANGE_FIELDS)
    base_pp = min(m['pp'], 20)
    max_pp = base_pp if m.get('noPPBoosts') else (base_pp // 5 + 1) * 4
    moves.append({
        'id': k, 'name': m['name'], 'nameZh': zh(zmoves, k), 'type': m['type'], 'category': m['category'],
        'basePower': m.get('basePower', 0), 'accuracy': m['accuracy'], 'pp': base_pp, 'maxPp': max_pp,
        'priority': m.get('priority', 0), 'target': m.get('target'),
        'flags': sorted(m.get('flags', {}).keys()), 'critRatio': m.get('critRatio', 1),
        'multihit': m.get('multihit'), 'secondary': m.get('secondary'),
        'drain': m.get('drain'), 'recoil': m.get('recoil'),
        'shortDesc': champ_text(tmoves, k), 'shortDescZh': champ_text(zmoves, k),
        'championsChanged': changed,
        'mainSeriesValues': {f: bmoves[k].get(f) for f in ('basePower', 'accuracy', 'pp', 'type', 'secondary') if f in cmoves.get(k, {})} if changed else None,
        'source': 'showdown', 'verification': COMMUNITY,
    })
move_ids = {m['id'] for m in moves}
for p in learnsets:
    learnsets[p] = [m for m in learnsets[p] if m in move_ids]

# ---- Abilities / Items ----------------------------------------------------------------
abilities = []
custom_ids = {c['id'] for c in custom_abilities}
for c in custom_abilities:
    abilities.append({'championsChanged': True, **c})
for k in sorted({to_id(a) for p in pokemon for a in p['abilities']} - custom_ids):
    a = eff(babil.get(k, {'name': k}), cabil.get(k))
    abilities.append({
        'id': k, 'name': a.get('name', k), 'nameZh': zh(zabil, k),
        'shortDesc': champ_text(tabil, k), 'shortDescZh': champ_text(zabil, k),
        'championsChanged': k in cabil, 'source': 'showdown', 'verification': COMMUNITY,
    })

items = []
provisional_stones = {to_id(dex[k].get('requiredItem')) for k in provisional_adds if is_mega(dex[k])}
for k, b in bitems.items():
    it = eff(b, citems.get(k))
    pending_item = k in provisional_stones
    if it.get('isNonstandard') and not pending_item:
        continue
    stone = it.get('megaStone')
    items.append({
        'id': k, 'name': it['name'], 'nameZh': zh(zitems, k),
        'megaStone': stone, 'isMegaStone': bool(stone),
        'shortDesc': champ_text(titems, k), 'shortDescZh': champ_text(zitems, k),
        'source': 'showdown', 'verification': PENDING if pending_item else COMMUNITY,
    })
items.sort(key=lambda i: i['name'])

# ---- Type chart / natures -------------------------------------------------------------
TYPES = ['Normal', 'Fire', 'Water', 'Electric', 'Grass', 'Ice', 'Fighting', 'Poison', 'Ground',
         'Flying', 'Psychic', 'Bug', 'Rock', 'Ghost', 'Dragon', 'Dark', 'Steel', 'Fairy']
MULT = {0: 1.0, 1: 2.0, 2: 0.5, 3: 0.0}
type_chart = {atk: {dfn: MULT[tchart[dfn.lower()]['damageTaken'][atk]] for dfn in TYPES} for atk in TYPES}
nat = [{'id': k, 'name': v['name'], 'plus': v.get('plus'), 'minus': v.get('minus')} for k, v in natures.items()]

# ---- Regulations ----------------------------------------------------------------------
COMMON_RULES = {
    'level': 'All Pokémon set to Level 50', 'speciesClause': True, 'itemClause': True,
    'bannedCategories': ['Mythical', 'Restricted Legendary'],
    'megaRules': 'Mega Stone required; Mega Evolution once per battle per player',
    'statSystem': 'Stat Points (SP): 66 total, max 32 per stat; IVs fixed at maximum; Nature ("Stat Alignment") ±10%',
    'formats': {'singles': {'teamSize': '3–6', 'bring': 3}, 'doubles': {'teamSize': '4–6', 'bring': 4}},
}


def reg_counts(lst):
    sel = [k for k in lst if team_selectable(k)]
    megas = [k for k in lst if is_mega(dex[k])]
    return {'legalEntries': len(lst), 'teamSelectable': len(sel), 'megaForms': len(megas),
            'nonMegaSelectable': len([k for k in sel if not is_mega(dex[k])])}


def parse_time(s):
    return datetime.datetime.fromisoformat(s.replace('Z', '+00:00')) if s else None


meta_by_id = {r['id']: r for r in META_REGS}
reg_ids = sorted(set(meta_by_id) | set(all_legal))
regulations = []
prev_list = None
for rid in reg_ids:
    m = meta_by_id.get(rid, {})
    r = {'id': rid, 'name': m.get('name', f'Regulation Set {rid}'), 'version': 1,
         'effectiveDate': m.get('effectiveDate'), 'endDate': m.get('endDate'), **COMMON_RULES}
    if rid in legal:
        lst = legal[rid]
        r.update({'counts': reg_counts(lst),
                  'allowedPokemon': sorted(k for k in lst if team_selectable(k)),
                  'verification': COMMUNITY,
                  'source': f'Showdown mod "{reg_mods[rid]}"' + (f"; dates: {m['source']}" if m.get('source') else '')})
        if prev_list is not None:
            r['newVsPrevious'] = sorted(k for k in lst if k not in prev_list)
        prev_list = lst
    elif rid in provisional:
        lst = provisional[rid]
        r.update({'version': 0, 'provisional': True, 'counts': reg_counts(lst),
                  'allowedPokemon': sorted(k for k in lst if team_selectable(k)),
                  'newVsPrevious': sorted(k for k in lst if k not in (prev_list or [])),
                  'verification': PENDING,
                  'source': m.get('source', '') + '; provisional list = ' + m['provisional']['basedOn'] + ' + officially named Pokémon',
                  'allowedPokemonNote': 'Provisional: only officially named additions are included.'})
    else:
        r.update({'version': 0, 'allowedPokemon': None, 'verification': PENDING,
                  'source': m.get('source', 'unknown'),
                  'allowedPokemonNote': m.get('note', 'Not yet in any data source.')})
    if m.get('announced') and rid not in legal:  # also kept for provisional lists
        r['announced'] = m['announced']
    regulations.append(r)

# Current = the Regulation in effect now that has a legal list; next = the following one.
def in_effect(r):
    a, b = parse_time(r['effectiveDate']), parse_time(r['endDate'])
    return a and a <= NOW and (b is None or NOW <= b)

with_list = [r for r in regulations if r['allowedPokemon']]
current = next((r for r in with_list if in_effect(r)), None)
if current is None:  # e.g. new Regulation started but Showdown not updated yet → newest available
    current = max(with_list, key=lambda r: r['effectiveDate'] or '')
current['isCurrent'] = True
later = [r for r in regulations if (r['effectiveDate'] or '') > (current['effectiveDate'] or '')]
nxt = min(later, key=lambda r: r['effectiveDate'])['id'] if later else None
in_effect_now = next((r['id'] for r in regulations if in_effect(r)), None)

MECHANICS = {
    'statFormulaLv50': {'hp': 'base + SP + 75', 'other': 'floor((base + SP + 20) × nature)', 'nature': '1.1 / 1.0 / 0.9',
                        'source': 'Showdown champions/scripts.ts; Smogon damage-calc; Smogon mechanics research', 'verification': 'VERIFIED'},
    'pp': {'rule': 'base PP capped at 20; max PP = 4 × (basePP/5 + 1); all moves at max PP', 'verification': 'VERIFIED'},
    'damageFormula': {'rule': 'Gen 9 formula (spread 0.75×, crit 1.5×, random 85–100%, STAB 1.5×, burn 0.5×)',
                      'note': 'Champions mod keeps the Gen 9 damage pipeline; no Terastallization', 'verification': COMMUNITY},
    'paralysis': {'fullParaChance': '1/8', 'speed': '×0.5', 'verification': 'VERIFIED'},
    'sleep': {'rule': 'always asleep on 1st turn, 1/3 chance to wake on 2nd, wakes on 3rd', 'verification': 'VERIFIED'},
    'freeze': {'rule': '25% thaw per move attempt, guaranteed thaw by 3rd turn', 'verification': 'VERIFIED'},
    'megaRevert': 'Mega forme persists after fainting (no reversion)',
    'terastallization': 'Not available',
}

meta = {
    'app': 'Pokémon Champions Team Builder & Battle Simulator', 'schemaVersion': SCHEMA_VERSION,
    'dataVersion': f"{NOW.strftime('%Y.%m.%d')}-sd{COMMIT[:7]}",
    'generatedAt': datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
    'currentRegulation': current['id'], 'nextRegulation': nxt, 'regulationInEffect': in_effect_now,
    'source': {'name': 'Pokémon Showdown (smogon/pokemon-showdown), mod "champions"',
               'url': 'https://github.com/smogon/pokemon-showdown', 'commit': COMMIT, 'commitDate': COMMIT_DATE,
               'regulationMods': {r: reg_mods[r] for r in sorted(legal)},
               'basis': 'Community-maintained; Champions values come from a game data dump.'},
    'verificationLevels': {
        'OFFICIAL': 'Confirmed by The Pokémon Company announcement or in-game text',
        'VERIFIED': 'Confirmed by game data dump and matching independent source',
        'COMMUNITY_SOURCE': 'Community database (Showdown), datamine-based, not independently re-checked',
        'PENDING_VERIFICATION': 'Announced or partial; not yet in any data source',
    },
    'counts': {'pokemon': len(pokemon), 'moves': len(moves), 'abilities': len(abilities), 'items': len(items),
               'learnsets': len(learnsets), 'missingLearnsets': missing_learnsets},
}
bundle = {'meta': meta, 'regulations': regulations, 'mechanics': MECHANICS, 'types': TYPES,
          'balancePatches': META.get('balancePatches', []),
          'typeChart': type_chart, 'natures': nat, 'pokemon': pokemon, 'moves': moves,
          'abilities': abilities, 'items': items, 'learnsets': learnsets}
with open(args.out, 'w', encoding='utf-8') as fh:
    json.dump(bundle, fh, ensure_ascii=False, separators=(',', ':'))
print(json.dumps({'dataVersion': meta['dataVersion'], 'current': meta['currentRegulation'], 'next': nxt,
                  'inEffect': in_effect_now, **meta['counts']}, ensure_ascii=False))
for r in regulations:
    print(' ', r['id'], r['verification'], r.get('counts'))
if missing_learnsets:
    print('WARNING: missing learnsets', missing_learnsets)
