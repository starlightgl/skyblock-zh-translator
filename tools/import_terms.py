"""Import the proofread term table (data/SkyBlock术语表.xlsx) into the mod's built-in data.

Usage: python tools/import_terms.py [主表.xlsx] [补充表.xlsx ...]
       (默认：data/SkyBlock术语表.xlsx，以及存在时的 data/SkyBlock术语表-补充描述.xlsx)

Writes, under src/main26/resources/assets/hypixel-npc-and-items-translate/defaults/:
  - table_names.json   names of items, pets, mobs, areas, reforges and enchantments
  - table_lines.json   item description lines ({0} placeholders become %1$s)
  - npc_translator_enchants.json   enchantment names and descriptions (updated in place)
"""
import json
import os
import re
import sys

from openpyxl import load_workbook

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULTS = os.path.join(ROOT, "src/main26/resources/assets/hypixel-npc-and-items-translate/defaults")
SRCS = sys.argv[1:] or [p for p in (os.path.join(ROOT, "data/SkyBlock术语表.xlsx"),
                                    os.path.join(ROOT, "data/SkyBlock术语表-补充描述.xlsx")) if os.path.exists(p)]

# earlier sheets win when the same English name appears twice
NAME_SHEETS = ["物品名称", "宠物", "生物", "区域", "修饰词", "附魔"]

# wording decisions applied to every translation
REPLACE = {"爪牙": "小人"}

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

reforges = {}
for r in rows(wb["修饰词"]):
    en, zh = (r.get("英文") or "").strip(), final(r)
    if en and zh:
        reforges.setdefault(en, zh)

lines = []
seen = set()
desc_rows = [r for b in books for name in b.sheetnames if name.startswith("物品描述") for r in rows(b[name])]
for r in desc_rows:
    en, zh = (r.get("英文") or "").strip(), final(r)
    if not en or not zh or en in seen:
        continue
    if set(re.findall(r"\{\d+\}", en)) != set(re.findall(r"\{\d+\}", zh)):
        print("skipped (placeholders differ):", en)
        continue
    seen.add(en)
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

with open(os.path.join(DEFAULTS, "table_names.json"), "w", encoding="utf-8") as f:
    json.dump({"_说明": "由 tools/import_terms.py 从 data/SkyBlock术语表.xlsx 生成，请勿手动修改。", "names": names, "aliases": alias,
               "reforges": reforges},
              f, ensure_ascii=False, indent=0)
with open(os.path.join(DEFAULTS, "table_lines.json"), "w", encoding="utf-8") as f:
    json.dump({"_说明": "由 tools/import_terms.py 从 data/SkyBlock术语表.xlsx 生成，请勿手动修改。", "entries": lines},
              f, ensure_ascii=False, indent=0)
with open(enchant_path, "w", encoding="utf-8") as f:
    json.dump(enchants, f, ensure_ascii=False, indent=1)

print(f"aliases: {len(alias)}")
print(f"names: {len(names)}, description lines: {len(lines)}, enchantments: {len(enchants['enchants'])} (+{added})")
