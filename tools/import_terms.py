"""Import the proofread term table (data/SkyBlock术语表.xlsx) into the mod's built-in data.

Usage: python tools/import_terms.py [主表.xlsx] [补充表.xlsx ...]
       补充表可以是 tools/merge_collected.py 生成的收集汇总（读取「收集-*」工作表中已填写校对译名的行）
       (默认：data/SkyBlock术语表-社区版.xlsx)

Writes, under src/main26/resources/assets/hypixel-npc-and-items-translate/defaults/:
  - table_names.json   names of items, pets, mobs, areas, reforges and enchantments
  - table_lines.json   item description lines ({0} placeholders become %1$s)
  - npc_translator_enchants.json   enchantment names and descriptions (updated in place)

data/术语表修正.json (context fixes the table cannot hold) is applied on top of names and lines.
"""
import json
import os
import re
import sys

from openpyxl import load_workbook

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULTS = os.path.join(ROOT, "src/main26/resources/assets/hypixel-npc-and-items-translate/defaults")
SRCS = sys.argv[1:] or [os.path.join(ROOT, "data/SkyBlock术语表-社区版.xlsx")]

# earlier sheets win when the same English name appears twice
NAME_SHEETS = ["物品名称", "宠物", "生物", "区域", "修饰词", "附魔"]

# wording decisions applied to every translation; also corrected place/mob names whose old
# translation is still used inside other entries (the table fixed 水晶残核 → 水晶洞窟 for the area,
# but some descriptions and item names still carried the old word). Order matters.
REPLACE = {
    "地下墓穴": "地牢",  # 2026-10-25 user: Catacombs
    "水晶残核": "水晶洞窟",
    "残核": "洞窟",
    "Kada 骑士": "卡达骑士",
    "Kuudra 追随者": "库德拉追随者",
}

ALIAS_RE = re.compile(r"(?:社区常称|社区俗称|社区简称|社区亦称|俗称|简称|亦称|别称|别名|又称)\s*[：:]?\s*([^（(。；;【]+)")
ABBR_RE = re.compile(r"\b([A-Z][A-Za-z]{1,6})\s*可作(?:英文)?缩写")


def fix(s):
    for a, b in REPLACE.items():
        s = s.replace(a, b)
    return s


def aliases(note, en, zh):
    """Community nicknames from the note column ("社区常称：神剑/Hype"), without the name itself."""
    if not isinstance(note, str):
        return []
    out = []
    for m in ALIAS_RE.finditer(note):
        for a in re.split(r"[/／、,，]| 或 ", m.group(1)):
            a = fix(a.strip(" ：:\"'“”「」"))
            if a:
                out.append(a)
    out += ABBR_RE.findall(note)
    clean = []
    for a in out:
        if len(a) > 16 or a.lower() == en.lower() or a in zh or a.lower() in en.lower().split():
            continue
        if a.lower() in ("minion", "pet") or a in clean:
            continue
        clean.append(a)
    return clean[:4]


def rows(ws):
    header = [c.value for c in ws[1]]
    for values in ws.iter_rows(min_row=2, values_only=True):
        yield dict(zip(header, values))


def final(row):
    """Proofread translation, falling back to the current one."""
    for key in ("校对译名", "当前译名"):
        v = row.get(key)
        if isinstance(v, str) and v.strip():
            return fix(v.strip())
    return None


def placeholders(s):
    return re.sub(r"\{(\d+)\}", lambda m: "%" + str(int(m.group(1)) + 1) + "$s", s)


# merged rows of the supplement ("合并行数" > 1) that are really separate "Label: value" lines
# (stat lists, "Time Between Actions: …  Max Storage: …") are split back into one entry per line,
# otherwise the game would show them as one long line
UNITS = "秒级层点个次倍格"


def renumber(en, zh):
    order = []
    for m in re.findall(r"\{(\d+)\}", en):
        if m not in order:
            order.append(m)
    mp = {old: str(i) for i, old in enumerate(order)}
    f = lambda m: "{" + mp.get(m.group(1), m.group(1)) + "}"
    return re.sub(r"\{(\d+)\}", f, en), re.sub(r"\{(\d+)\}", f, zh)


def split_en(en):
    """Cut before every label after the first: "A: {0}s Max Storage: {1}" → ["A: {0}s", "Max Storage: {1}"]."""
    cols = [m.start() for m in re.finditer(r":", en)]
    if len(cols) < 2:
        return None
    cuts = [0]
    for c in cols[1:]:
        i = c
        while i > 0 and re.match(r"[A-Za-z'\u2019 \-]", en[i - 1]):
            i -= 1
        while i < c and not re.match(r"[A-Z\u2600-\u2bff]", en[i]):  # "{0}s Max" → "Max"
            i += 1
        if i <= cuts[-1] or i >= c or en[i - 1] != " ":
            return None
        cuts.append(i)
    cuts.append(len(en))
    return [en[a:b].strip() for a, b in zip(cuts, cuts[1:])]


def split_bullets(en, zh):
    """"- Bazaar (I-V) - Experiments (VI-VII)" (merged "- " list lines) → one entry per line."""
    if not en.startswith("- ") or not zh.lstrip().startswith("-"):
        return None
    ens = [p.strip() for p in re.split(r" (?=- )", en)]
    zhs = [p.strip() for p in re.split(r"\s+(?=-\s)", zh.strip())]
    if len(ens) < 2 or len(ens) != len(zhs):
        return None
    out = []
    for e, z in zip(ens, zhs):
        if set(re.findall(r"\{[^}]*\}", e)) != set(re.findall(r"\{[^}]*\}", z)):
            return None
        out.append(renumber(e, z))
    return out


ROMAN_GROUP = re.compile(r"\(([IVXLC]+(?:-[IVXLC]+)?)\)")


def generalize_roman(en, zh):
    """"- Bazaar (I-V)" → "- Bazaar ({0})" so every tier range uses one entry; None when not applicable."""
    groups = ROMAN_GROUP.findall(en)
    if not groups or any(zh.count("(" + g + ")") != 1 for g in groups) or len(set(groups)) != len(groups):
        return None
    k = max([int(x) for x in re.findall(r"\{(\d+)\}", en)] + [-1]) + 1
    for g in groups:
        en = en.replace("(" + g + ")", "({%d})" % k, 1)
        zh = zh.replace("(" + g + ")", "({%d})" % k, 1)
        k += 1
    return en, zh


def split_list(en, zh):
    parts = split_en(en)
    if not parts:
        return split_bullets(en, zh)
    cols = [m.start() for m in re.finditer(r"[:：]", zh)]
    if len(cols) != len(parts):
        return None
    cuts = [0]
    for c in cols[1:]:
        i = c
        while i > 0 and not re.match(r"[\s}%)\]+\-\d]", zh[i - 1]):
            i -= 1
        if i > 0 and zh[i - 1] == "}" and zh[i] in UNITS:  # "{0}秒" keeps its unit
            i += 1
        if i <= cuts[-1]:
            return None
        cuts.append(i)
    cuts.append(len(zh))
    zhs = [zh[a:b].strip() for a, b in zip(cuts, cuts[1:])]
    out = []
    for e, z in zip(parts, zhs):
        if not z or set(re.findall(r"\{[^}]*\}", e)) != set(re.findall(r"\{[^}]*\}", z)):
            return None
        out.append(renumber(e.strip(), z))
    return out


books = [load_workbook(p, data_only=True) for p in SRCS]
wb = books[0]

names = {}
alias = {}
for sheet in NAME_SHEETS:
    for r in rows(wb[sheet]):
        en, zh = (r.get("英文") or "").strip(), final(r)
        if not en or not zh:
            continue
        names.setdefault(en, zh)
        al = aliases(r.get("备注"), en, zh)
        if al and en not in alias:
            alias[en] = al
        # NPC entries are listed as "Name (NPC)"; the game shows just "Name"
        m = re.match(r"^(.*\S)\s*\((NPC|Monster|Animal)\)$", en)
        if m:
            zh_base = re.sub(r"\s*[（(](NPC|怪物|动物)[)）]$", "", zh)
            names.setdefault(m.group(1), zh_base)

# menu tables (from config/npc_translator_menu_text.tsv): 类型 名称 = button name, 描述 = description line
menu_rows = [r for b in books for name in b.sheetnames if name.startswith("菜单") for r in rows(b[name])]
# merged player collections (tools/merge_collected.py): only rows with a proofread translation;
# 当前译名 there is whatever the game showed (possibly AI) and is not imported
for b in books:
    for name in b.sheetnames:
        if name.startswith("收集"):
            for r in rows(b[name]):
                zh = r.get("校对译名")
                if isinstance(zh, str) and zh.strip():
                    menu_rows.append({"英文": r.get("英文"), "类型": r.get("类型"), "校对译名": zh})
for r in menu_rows:
    en, zh = (r.get("英文") or "").strip(), final(r)
    if en and zh and r.get("类型") == "名称" and "{" not in en:
        names.setdefault(en, zh)

reforges = {}
for r in rows(wb["修饰词"]):
    en, zh = (r.get("英文") or "").strip(), final(r)
    if en and zh:
        reforges.setdefault(en, zh)

lines = []
seen = set()
desc_rows = [r for b in books for name in b.sheetnames if name.startswith("物品描述") for r in rows(b[name])]
desc_rows += [r for r in menu_rows if r.get("类型") != "名称" or "{" in (r.get("英文") or "")]
expanded = []
split_count = 0
for r in desc_rows:
    en, zh = (r.get("英文") or "").strip(), final(r)
    parts = split_list(en, zh) if en and zh and (r.get("合并行数") or 1) > 1 else None
    if parts:
        split_count += 1
        expanded += [{"英文": e, "校对译名": z} for e, z in parts]
    else:
        expanded.append(r)
for r in expanded:
    en, zh = (r.get("英文") or "").strip(), final(r)
    if not en or not zh or en in seen:
        continue
    if re.search(r"\{[A-Z_]+\}", en):  # NEU stat templates ({STRENGTH}); the game shows numbers
        continue
    if set(re.findall(r"\{\d+\}", en)) != set(re.findall(r"\{\d+\}", zh)):
        print("skipped (placeholders differ):", en)
        continue
    seen.add(en)
    gen = generalize_roman(en, zh)
    if gen and gen[0] not in seen and placeholders(gen[0]) not in {l["en"] for l in lines[-1:]}:
        expanded.append({"英文": gen[0], "校对译名": gen[1]})
    # "{物品}" (an item name folded by the text collector) becomes one more placeholder; the mod
    # translates the captured name with the term table
    if "{物品}" in en:
        if en.count("{物品}") != zh.count("{物品}"):
            print("skipped ({物品} differs):", en)
            continue
        k = max([int(x) for x in re.findall(r"\{(\d+)\}", en)] + [-1]) + 1
        en, zh = en.replace("{物品}", "{%d}" % k), zh.replace("{物品}", "{%d}" % k)
    lines.append({"en": placeholders(en), "zh": placeholders(zh)})

enchant_path = os.path.join(DEFAULTS, "npc_translator_enchants.json")
enchants = json.load(open(enchant_path, encoding="utf-8"))
by_en = {e["en"]: e for e in enchants["enchants"]}
added = 0
for r in rows(wb["附魔"]):
    en, zh = (r.get("英文") or "").strip(), final(r)
    if not en or not zh:
        continue
    hint = (r.get("功能说明") or "").strip()
    e = by_en.get(en)
    if e is None:
        e = {"en": en, "zh": zh, "hint": hint, "ultimate": r.get("终极附魔") == "是", "aliases": []}
        enchants["enchants"].append(e)
        by_en[en] = e
        added += 1
    else:
        e["zh"] = zh
        if hint:
            e["hint"] = hint
        if r.get("终极附魔") == "是":
            e["ultimate"] = True

# corrections the term table cannot express (the same English word meaning different things in
# different menus) or has not taken over yet: data/术语表修正.json, applied after the table so a
# re-import keeps them. Groups only document why; an entry overrides or adds one name / line.
OVERRIDES = os.path.join(ROOT, "data/术语表修正.json")
overrides = json.load(open(OVERRIDES, encoding="utf-8")) if os.path.exists(OVERRIDES) else {}
fixed_names = fixed_lines = 0
for group in overrides.get("names", {}).values():
    for en, zh in group.items():
        if names.get(en) != zh:
            names[en] = zh
            fixed_names += 1
line_index = {l["en"]: l for l in lines}
for group in overrides.get("lines", {}).values():
    for en, zh in group.items():
        l = line_index.get(en)
        if not zh:  # null / "": drop a bad generated line (e.g. a wrapped fragment turned into a template)
            if l is not None:
                lines.remove(l)
                del line_index[en]
                fixed_lines += 1
            continue
        if l is None:
            l = {"en": en, "zh": zh}
            lines.append(l)
            line_index[en] = l
            fixed_lines += 1
        elif l["zh"] != zh:
            l["zh"] = zh
            fixed_lines += 1

# User wording decisions also apply to explicit overrides and enchant hints.
for en in names:
    names[en] = names[en].replace("地下墓穴", "地牢")
for entry in lines:
    entry["zh"] = entry["zh"].replace("地下墓穴", "地牢")
for enchant in enchants["enchants"]:
    for field in ("zh", "hint"):
        if isinstance(enchant.get(field), str):
            enchant[field] = enchant[field].replace("地下墓穴", "地牢")

# layout and consistency: one spelling per name, multi-line glue and progress-bar joins dropped,
# list rows split, "%1$sM" kept, Chinese / Latin spacing (tools/layout_fixes.py)
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import layout_fixes
layout_stats, _ = layout_fixes.post_process(lines, names, overrides.get("unify", []))
# Apply the same scoped decisions to all generated entries, after derived rows exist.
from terminology_rules import apply_context
for en in names:
    names[en] = apply_context(en, names[en])
for entry in lines:
    entry["zh"] = apply_context(entry["en"], entry["zh"])
# Layout/generalization can derive wrapped rows after explicit overrides were read.
# Explicit removals still win; a fragment must not invent a missing parameter.
dropped = {en for group in overrides.get("lines", {}).values() for en, zh in group.items() if not zh}
lines[:] = [entry for entry in lines if entry["en"] not in dropped]
# Derived templates must preserve their argument set as well as original workbook rows.
def format_args(text):
    args, next_arg = set(), 0
    for match in re.finditer(r"%(?:(\d+)\$)?[sdf]", text):
        if match.group(1):
            args.add(int(match.group(1)))
        else:
            next_arg += 1
            args.add(next_arg)
    return args
for entry in lines:
    if format_args(entry["en"]) != format_args(entry["zh"]):
        raise ValueError("Generated template loses or invents an argument: " + entry["en"])
# Keep real-name protection reproducible without needing a fresh NEU download.
protection = os.path.join(ROOT, "data/not_reforged_additions.json")
if os.path.exists(protection):
    nr_path = os.path.join(DEFAULTS, "not_reforged.json")
    protected = set(json.load(open(nr_path, encoding="utf-8")))
    protected.update(json.load(open(protection, encoding="utf-8"))["names"])
    with open(nr_path, "w", encoding="utf-8") as f:
        json.dump(sorted(protected), f, ensure_ascii=False, indent=0)

with open(os.path.join(DEFAULTS, "table_names.json"), "w", encoding="utf-8") as f:
    json.dump({"_说明": "由 tools/import_terms.py 从 data/SkyBlock术语表.xlsx 生成，请勿手动修改。", "names": names, "aliases": alias,
               "reforges": reforges},
              f, ensure_ascii=False, indent=0)
with open(os.path.join(DEFAULTS, "table_lines.json"), "w", encoding="utf-8") as f:
    json.dump({"_说明": "由 tools/import_terms.py 从 data/SkyBlock术语表.xlsx 生成，请勿手动修改。", "entries": lines},
              f, ensure_ascii=False, indent=0)
with open(enchant_path, "w", encoding="utf-8") as f:
    json.dump(enchants, f, ensure_ascii=False, indent=1)

print(f"corrections from {os.path.basename(OVERRIDES)}: {fixed_names} names, {fixed_lines} lines")
print("layout / consistency:", layout_stats)
print(f"aliases: {len(alias)}")
print(f"merged list rows split into lines: {split_count}")
print(f"names: {len(names)}, description lines: {len(lines)}, enchantments: {len(enchants['enchants'])} (+{added})")
