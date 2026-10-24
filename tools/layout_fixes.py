"""Post-processing of the generated term table (called by import_terms.py).

- unify: the same name is written the same way everywhere ("unify" rules in data/术语表修正.json:
  where the English line contains `en`, the Chinese variants in `from` become `to`)
- multi-line entries that only glue the translations of their own lines together are dropped, so the
  lines are matched one by one and keep their own layout ("Chimera IV" + "Copies 10% of …")
- entries made of a label and its progress bar ("Progress: 34%      1,021/3k") are dropped: the bar is
  a line of its own
- list rows joined into one entry ("❣ Requires A. ❣ Requires B.") also give each row its own entry
- "%1$sM" stays "%1$sM" (not "%1$s 百万"), like "%1$sk"
- a space between Chinese and Latin letters / digits in the literal text; ", " after Chinese becomes "，"
"""
import re
from functools import lru_cache

CJK = r"[一-鿿]"
_W = re.compile(r"(?<![A-Za-z])")


def unify_one(en, zh, rules, log=None):
    for r in rules:
        if not re.search(r"(?<![A-Za-z])" + r["en"] + r"(?![a-z])", en):
            continue
        if r.get("where") and not re.search(r["where"], en):
            continue
        z2 = zh
        for f in r["from"]:
            z2 = re.sub(f, r["to"], z2)
        if z2 != zh:
            if log is not None:
                log.append((en, zh, z2))
            zh = z2
    return zh


def million(en, zh):
    if "百万" not in zh:
        return zh
    def sub(m):
        ph = m.group(1)
        return ph + "M" if re.search(re.escape(ph) + r"[Mm](?![a-z])(?! ago)", en) else m.group(0)
    return re.sub(r"(%\d+\$s) ?百万", sub, zh)


_CODE = re.compile(r"§.")
_KEEP = ("T恤",)


def spacing(zh):
    """A space between Chinese and Latin letters / digits in literal text (placeholders are spaced at runtime)."""
    if any(k in zh for k in _KEEP):
        return zh
    # mask placeholders and colour codes
    masks = []
    def mask(m):
        masks.append(m.group(0))
        return "\u0000%d\u0001" % (len(masks) - 1)
    t = re.sub(r"%\d+\$s|%s|§.|\{\d+\}", mask, zh)
    # half-width list punctuation after Chinese: "水生, 动物" -> "水生、动物", "暴击率, +15" -> "暴击率，+15"
    sep = "、" if t.lstrip().startswith("生物种族") else "，"
    t = re.sub(r"(" + CJK + r"(?:\u0000\d+\u0001)?), ", lambda m: m.group(1) + sep, t)
    t = re.sub(r"(" + CJK + r"); ", r"\1；", t)
    t = re.sub(r"(" + CJK + r")(?=[A-Za-z0-9])", r"\1 ", t)
    t = re.sub(r"(?<=[A-Za-z0-9])(" + CJK + r")", r" \1", t)
    return re.sub(r"\u0000(\d+)\u0001", lambda m: masks[int(m.group(1))], t)


_BULLET = r"(?:❣|☠|■|✔|✖|•|▶|◆|●|-(?= [A-Z0-9%])|\+(?=%\d|\d))"
_BULLET_ZH = r"(?:❣|☠|■|✔|✖|•|▶|◆|●|(?<!\S)-(?= \S)|(?<!\S)\+(?=%\d|\d))"


def bullet_rows(en, zh):
    """'❣ A. ❣ B.' / '❣ 甲。❣ 乙。' -> [('❣ A.', '❣ 甲。'), ('❣ B.', '❣ 乙。')] when both sides split the same way."""
    bul, bulz = _BULLET, _BULLET_ZH
    pe = re.split(r"\s+(?=" + bul + r" ?)", en.strip())
    # "+5 Coins" rows only after a complete first row ("Combo (lasts 5s)"), never inside a sentence ("Grants +5 …")
    if len(pe) > 1 and re.search(r"\s\+(?:%\d|\d)", en) and not re.search(r"(?:[):]|%\d\$s|\d)$", pe[0]):
        bul, bulz = bul.replace(r"|\+(?=%\d|\d)", ""), bulz.replace(r"|(?<!\S)\+(?=%\d|\d)", "")
        pe = re.split(r"\s+(?=" + bul + r" ?)", en.strip())
    pz = re.split(r"\s*(?=" + bulz + r" ?)", zh.strip())
    pz = [p for p in pz if p.strip()]
    if len(pe) < 2 or len(pe) != len(pz):
        return []
    for a, b in list(zip(pe, pz))[1:]:
        if a[0] != b[0]:
            return []
    return [renumber(a, b.strip()) for a, b in zip(pe, pz)]


def renumber(en, zh):
    """Placeholders numbered by their order in the English row ("%2$s" alone becomes "%1$s")."""
    order = {}
    for m in re.finditer(r"%(\d+)\$s", en):
        order.setdefault(m.group(1), str(len(order) + 1))
    f = lambda m: "%" + order.get(m.group(1), m.group(1)) + "$s"
    return re.sub(r"%(\d+)\$s", f, en), re.sub(r"%(\d+)\$s", f, zh)


_FUNCTION_END = re.compile(r"\b(?:and|or|the|to|of|a|an|for|with|your|by|in|on|at|from|as|is|are|you|will|be|per|when|while|if|that|which|than)$", re.I)


def _row_ok(row):
    """A derived row must be a whole row: a bullet row, or a heading ending like a line ends; never a fragment."""
    letters = len(re.sub(r"%\d+\$s|[^A-Za-z]", "", row))
    if letters < 3 or _FUNCTION_END.search(row.rstrip(" .")) or "[" in row and "]" in row and not row.startswith("-"):
        return False
    if re.match(r"(?:❣|☠|■|✔|✖|•|▶|◆|●|-|\+)", row):
        return True
    return bool(re.search(r"[.!?:)]$", row))


_END = re.compile(r"(?:[.!?:)*\]]|\b[IVXLC]+|%\d\$s|\d)$")


def glued_parts(en, zh, exact):
    """The parts of a multi-line entry whose translation is just its parts' translations glued together."""
    if " " not in en or len(en) < 8:
        return None
    sp = [m.start() for m in re.finditer(r"(?<! ) (?! )", en)]
    target = re.sub(r"\s+", "", zh)

    @lru_cache(None)
    def go(i, zi):
        if i == len(en):
            return () if zi == len(target) else None
        for s in sp + [len(en)]:
            if s <= i:
                continue
            part = en[i:s]
            if part == en or part not in exact:
                continue
            zp = re.sub(r"\s+", "", exact[part])
            if not zp or not target.startswith(zp, zi):
                continue
            if s < len(en) and not (_END.search(part) and re.match(r"[A-Z]", en[s + 1:])):
                continue
            # a later part must look like a line of its own (a sentence), not a word of one line ("Tier V Rewards")
            if i > 0 and not (len(part.split()) >= 3 or re.search(r"[.!?]$", en[:i - 1])):
                continue
            r = go(s + 1 if s < len(en) else s, zi + len(zp))
            if r is not None:
                return (part,) + r
        return None
    r = go(0, 0)
    return r if r and len(r) >= 2 else None


def post_process(lines, names, rules):
    stats = {"unified": 0, "rows": 0, "glued": 0, "bars": 0, "million": 0, "spacing": 0}
    log = []
    for l in lines:
        z = unify_one(l["en"], l["zh"], rules, log)
        z2 = million(l["en"], z)
        if z2 != z:
            stats["million"] += 1
        z3 = spacing(z2)
        if z3 != z2:
            stats["spacing"] += 1
        if z3 != l["zh"]:
            l["zh"] = z3
    stats["unified"] = len(log)
    exact = {}
    for k, v in names.items():
        exact.setdefault(k, v)
    for l in lines:
        exact.setdefault(l["en"], l["zh"])
    # list rows joined into one entry: each row gets its own entry
    for l in list(lines):
        for a, b in bullet_rows(l["en"], l["zh"]):
            if not _row_ok(a):
                continue
            if a not in exact:
                exact[a] = b
                lines.append({"en": a, "zh": b})
                stats["rows"] += 1
    keep = []
    for l in lines:
        bar = re.search(r"(\S) {6,}((?:%\d+\$s|[\d,./%kKMB ()])+)$", l["en"])
        if bar:
            stats["bars"] += 1
            # keep the label line on its own: "Collections Unlocked: %1$s%" -> "已解锁收集: %1$s%"
            label = l["en"][:bar.start(1) + 1]
            tail = re.escape(bar.group(2).strip())
            z = re.sub(r"\s*" + re.sub(r"%\\d\\\+\\\$s|%\d+\\\$s", lambda m: m.group(0), tail) + r"\s*$", "", l["zh"])
            if z != l["zh"] and label not in exact:
                a, b = renumber(label, z.rstrip())
                exact[a] = b
                keep.append({"en": a, "zh": b})
            continue
        if glued_parts(l["en"], l["zh"], exact):
            stats["glued"] += 1
            continue
        keep.append(l)
    lines[:] = keep
    # families of lines that differ only by a name become templates
    stats["generalized"] = generalize(lines, names)
    return stats, log


def generalize(lines, names, min_names=3):
    """Templates from families of translated lines that differ only by a name:
    "View all your Seeds Collection progress and rewards!" + "... Cactus Collection ..." ->
    "View all your %1$s progress and rewards!". Only when at least `min_names` different names fill the slot."""
    first = {}
    for k, z in names.items():
        if len(k) < 4 or not re.match(r"[A-Z]", k) or re.search(r"[()\[\]%]", k):
            continue
        if not re.search(CJK, z) or re.search(r"[A-Za-z]{3,}", z):
            continue
        first.setdefault(k.split(" ")[0], []).append(k)
    for v in first.values():
        v.sort(key=len, reverse=True)
    exact = {l["en"] for l in lines}
    fams = {}
    for l in lines:
        en, zh = l["en"], l["zh"]
        phs = [int(x) for x in re.findall(r"%(\d+)\$s", en)]
        if len(phs) > 2 or len(en) > 160:
            continue
        nxt = max(phs + [0]) + 1
        for m in re.finditer(r"[A-Z][A-Za-z'\-]*", en):
            if m.start() > 0 and en[m.start() - 1].isalpha():
                continue
            for k in first.get(m.group(), []):
                end = m.start() + len(k)
                if not en.startswith(k, m.start()) or (end < len(en) and en[end].isalpha()):
                    continue
                z = names[k]
                if en.count(k) != 1 or zh.count(z) != 1:
                    break
                # the name must stand alone, not be part of a longer capitalised name or followed by a tier
                before = en[:m.start()].rstrip().split(" ")[-1] if en[:m.start()].strip() else ""
                after = en[end:].lstrip().split(" ")[0] if en[end:].strip() else ""
                if re.match(r"[A-Z][a-z]", before) and not before.endswith(":") \
                        or re.match(r"(?:[A-Z][a-z]|[IVXLC]+$|%\d)", after):
                    break
                ten = en[:m.start()] + "%%%d$s" % nxt + en[end:]
                tzh = zh.replace(z, "%%%d$s" % nxt)
                literal = re.sub(r"%\d+\$s", "", ten)
                if len(re.sub(r"[^A-Za-z]", "", literal)) < 8:
                    break
                fams.setdefault((ten, tzh), set()).add(k)
                break
    added = []
    seen = set()
    for (ten, tzh), ks in sorted(fams.items(), key=lambda x: -len(x[1])):
        if len(ks) < min_names or ten in exact or ten in seen:
            continue
        seen.add(ten)
        added.append({"en": ten, "zh": tzh})
    lines.extend(added)
    return len(added)
