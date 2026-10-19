"""Official Minecraft Chinese names of vanilla items and blocks, which take priority over the term table.

Usage: python tools/vanilla_names.py [en_us.json] [zh_cn.json]
       (默认：data/vanilla/en_us.json 与 data/vanilla/zh_cn.json)

en_us.json comes from the client jar (assets/minecraft/lang/en_us.json); zh_cn.json from the game's
asset index (minecraft/lang/zh_cn.json); both stay out of the repository (data/vanilla/ is ignored).
Writes defaults/vanilla_names.json. Names that are also
reforges in the term table (e.g. "Light") are left out, so the reforge keeps its translation.
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULTS = os.path.join(ROOT, "src/main26/resources/assets/hypixel-npc-and-items-translate/defaults")
EN = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "data/vanilla/en_us.json")
ZH = sys.argv[2] if len(sys.argv) > 2 else os.path.join(ROOT, "data/vanilla/zh_cn.json")

en = json.load(open(EN, encoding="utf-8"))
zh = json.load(open(ZH, encoding="utf-8"))
table = json.load(open(os.path.join(DEFAULTS, "table_names.json"), encoding="utf-8"))
reforges = set(table.get("reforges", {}))
# SkyBlock still uses 1.8 item names; where a modern name now means something else, keep the table
LEGACY = {"Grass"}  # 1.8 "Grass" is the grass block, modern "Grass" is short grass

names = {}
for key, name in en.items():
    if not re.fullmatch(r"(item|block)\.minecraft\.[a-z0-9_]+", key):
        continue
    cn = zh.get(key)
    if not cn or "%" in name or name in reforges or name in LEGACY:
        continue
    names.setdefault(name, cn)

changed = {k: (table["names"][k], v) for k, v in names.items() if k in table["names"] and table["names"][k] != v}
out = {"_说明": "由 tools/vanilla_names.py 从 Minecraft 官方语言文件生成：原版物品与方块的官方中文名，优先于术语表。",
       "names": dict(sorted(names.items()))}
with open(os.path.join(DEFAULTS, "vanilla_names.json"), "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, indent=1)
print(f"vanilla names: {len(names)}, replacing {len(changed)} table translations")
for k, (old, new) in sorted(changed.items()):
    print(f"  {k}: {old} -> {new}")
