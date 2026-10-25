"""Persistent context fixes shared by the import and collaboration corpus pipeline."""
from pathlib import Path
import json, re

ROOT = Path(__file__).resolve().parents[1]
_data = json.loads((ROOT / "data/术语表修正.json").read_bytes())
_GROUP = 'GPT beta.30（2026-10-26）：按用户要求修复 Claude 已定名与语境冲突；附魔和重铸校对名保持不变'
_names = _data.get("names", {}).get(_GROUP, {})
_rules = [(re.compile(r"(?<![A-Za-z])" + r["en"] + r"(?![a-z])"),
           re.compile(r["where"]) if r.get("where") else None,
           [re.compile(f) for f in r["from"]], r["to"]) for r in _data.get("unify", [])]

def apply_context(en, zh):
    if en in _names:
        zh = _names[en]
    for english, where, old, new in _rules:
        if english.search(en) and (where is None or where.search(en)):
            for pattern in old:
                zh = pattern.sub(new, zh)
    return zh
