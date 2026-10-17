"""Export SkyBlockZH (GPL-3.0) corpus into two data files for the AI translator:
   - npc_translator_glossary.json : term -> fixed translation (prompt injection)
   - npc_translator_phrasebook.json : fixed sentence templates (%s placeholders) -> zh
"""
import json, glob, re, sys, collections, os
ROOT = sys.argv[1]; OUT = sys.argv[2]
SRC = os.path.join(ROOT, 'original_text')
PH = re.compile(r'%(?:\d+\$)?s')
CODE = re.compile(r'§.')
strip = lambda s: CODE.sub('', s or '')

def records(obj):
    """yield every dict that looks like a text record (has text + zh)."""
    if isinstance(obj, dict):
        if 'text' in obj and 'zh' in obj and isinstance(obj.get('text'), str):
            yield obj
        for v in obj.values():
            if isinstance(v, (list, dict)):
                yield from records(v)
    elif isinstance(obj, list):
        for v in obj:
            yield from records(v)

def zh_of(r):
    segs = r.get('segments')
    if segs and all(isinstance(s, dict) for s in segs):
        parts = []
        for s in sorted(segs, key=lambda s: s.get('order', 0)) if any('order' in s for s in segs) else segs:
            z = s.get('zh')
            if z is None or (z == '' and s.get('text', '').strip()):
                return None  # segment untranslated -> skip whole record
            parts.append((s.get('color') or '') + z)
        out = ''.join(parts)
        return out if strip(out).strip() else None
    z = r.get('zh') or ''
    return z if strip(z).strip() else None

STOP = {'if','when','while','then','this','that','your','you','also','there','here','some','each','every','none','none!','yes','no','okay','sure','well','what','when','where','which','with','into','onto','upon','only','just','more','most','less','many','much','next','last','back','done','open','close','click','enter','none','other','others','total','left','right'}
SMALL = {'of','the','and','a','an','to','in','on','for','with','at','by','from','or','vs','de'}
def is_term(en):
    words = en.split()
    if not (1 <= len(words) <= 4): return False
    if re.search(r'[.!?,;"]', en) or en.endswith(':'): return False
    if not re.match(r"^[A-Z0-9]", en): return False
    if words[-1].lower() in SMALL: return False
    if words[-1].lower() in {'it','you','your','me','my','them','this','that','is','are','be'}: return False
    if len(words) == 1 and (len(re.sub(r"[^A-Za-z]", "", en)) < 4 or en.lower() in STOP): return False
    for w in words:
        core = re.sub(r"[^A-Za-z]", "", w)
        if not core: continue
        if core.lower() in SMALL: continue
        if not core[0].isupper(): return False
    return True
def is_clean_zh(z):
    if not z or len(z) > 24 or '%' in z: return False
    if re.match(r'^[，,。.、:：)）]', z) or re.search(r'[，,、(（]$', z): return False
    return bool(re.search(r'[\u4e00-\u9fff]', z)) or re.fullmatch(r"[A-Za-z0-9 '\-+]+", z) is not None

files = sorted(glob.glob(os.path.join(SRC, '**', '*.json'), recursive=True))
phr = collections.defaultdict(collections.Counter)
short = collections.defaultdict(collections.Counter)
stats = collections.Counter()
for f in files:
    if f.endswith('Terms.json'):
        continue
    try:
        data = json.load(open(f, encoding='utf-8'))
    except Exception:
        stats['bad_file'] += 1; continue
    recs = list(records(data))
    for i, r in enumerate(recs):
        stats['records'] += 1
        if r.get('translate') is False or r.get('continuation') or r.get('exclude'):
            continue
        nxt = recs[i + 1] if i + 1 < len(recs) else None
        if nxt and nxt.get('continuation'):
            stats['skip_head_with_continuation'] += 1; continue
        en = strip(r['text']).strip()
        zh = zh_of(r)
        if not en or not zh:
            continue
        if '\n' in en or '\n' in zh:
            continue
        n_en = len(PH.findall(en)); n_zh = len(PH.findall(zh))
        if n_en != n_zh:
            stats['skip_placeholder_mismatch'] += 1; continue
        literal = PH.sub('', en)
        if not re.search(r'[A-Za-z]{2,}', literal) and not any(ord(ch) > 0x7F for ch in literal):
            stats['skip_no_literal'] += 1; continue
        phr[en][zh] += 1
        # short term candidates (no placeholders, <=4 words, looks like a name/label)
        if n_en == 0 and is_term(en) and is_clean_zh(strip(zh).strip()):
            short[en][strip(zh).strip()] += 1

# ---------- phrasebook ----------
book = []
conflicts = 0
for en, c in sorted(phr.items()):
    ranked = c.most_common()
    if len(en) < 4: continue
    if len(ranked) > 1:
        conflicts += 1
        if ranked[0][1] < 2 * ranked[1][1]:
            continue  # ambiguous across contexts -> leave it to the AI
    book.append({'en': en, 'zh': ranked[0][0]})
stats['phrasebook'] = len(book); stats['phrasebook_conflicts'] = conflicts

# ---------- glossary ----------
gloss = collections.OrderedDict()
gloss['_说明'] = '由 SkyBlockZH 语料（GPL-3.0，github.com/BingKKni/SkyBlockZH）自动导出；左英文右固定译名，左右相同表示保留英文。可自由增删改。'
terms = json.load(open(os.path.join(SRC, '_shared', 'Terms.json'), encoding='utf-8'))['terms']
for t in terms:
    en = (t.get('en') or '').strip(); zh = (t.get('zh') or '').strip()
    if en and zh and en not in gloss:
        gloss[en] = zh
stats['glossary_terms_json'] = len(gloss) - 1
added = 0
for en, c in sorted(short.items()):
    if en in gloss: continue
    ranked = c.most_common()
    zh, n = ranked[0]
    if len(ranked) > 1 and n < 2 * ranked[1][1]: continue
    w = len(en.split()); zl = len(re.findall(r'[\u4e00-\u9fff]', zh))
    if zl > 3 * w + 2:
        keep = '-' in en and "'" not in en or (w == 1 and re.search(r'(er|ist|izer)$', en) and en[0].isupper() and not en.isupper())
        if not keep:
            continue  # almost certainly a sentence fragment, not a term
    gloss[en] = zh; added += 1
stats['glossary_short_lines'] = added
json.dump(gloss, open(os.path.join(OUT, 'npc_translator_glossary.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
json.dump({'_说明': '固定译文短语库，由 SkyBlockZH 语料（GPL-3.0）导出。%s 为占位符。', 'entries': book},
          open(os.path.join(OUT, 'npc_translator_phrasebook.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
print(dict(stats))
