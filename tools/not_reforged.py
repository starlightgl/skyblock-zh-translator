"""Write defaults/not_reforged.json: NEU item names that only look like "reforge + item".

Usage: python tools/not_reforged.py <NotEnoughUpdates-REPO/items>
TableNames uses the list so that real items such as "Refined Mithril" or "Giant Cleaver" are not split into
a reforge and an item name.
"""
import glob, json, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
D = os.path.join(ROOT, "src/main26/resources/assets/hypixel-npc-and-items-translate/defaults/")
reforges = json.load(open(D + "table_names.json", encoding="utf-8"))["reforges"]
names = set()
for f in glob.glob(os.path.join(sys.argv[1], "*.json")):
    try:
        n = re.sub("§.", "", json.load(open(f, encoding="utf-8")).get("displayname", "")).strip()
    except Exception:
        continue
    if n:
        names.add(n)

def prefix(t):
    at = t.rfind(" ")
    while at > 0:
        if t[:at] in reforges:
            return t[:at]
        at = t.rfind(" ", 0, at)
    return None

additions = os.path.join(ROOT, "data/not_reforged_additions.json")
extra = json.load(open(additions, encoding="utf-8"))["names"] if os.path.exists(additions) else []
out = sorted({n for n in names if prefix(n)} | set(extra))
json.dump(out, open(D + "not_reforged.json", "w", encoding="utf-8", newline="\n"), ensure_ascii=False, indent=0)
print(len(out))
