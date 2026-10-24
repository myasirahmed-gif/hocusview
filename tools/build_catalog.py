"""Build 80 detector + 20 focused recognition entries from actual model labels."""
import json
from pathlib import Path

assets = Path(__file__).resolve().parents[1] / "app/src/main/assets"
base = [name for name in (assets / "labels.txt").read_text().splitlines() if name != "???"]
extra = {
    "helmet": ["crash helmet", "football helmet"],
    "hammer": ["hammer"],
    "screwdriver": ["screwdriver"],
    "power drill": ["power drill"],
    "wallet": ["wallet"],
    "sunglasses": ["sunglass", "sunglasses"],
    "digital watch": ["digital watch"],
    "microphone": ["microphone"],
    "modem": ["modem"],
    "binoculars": ["binoculars"],
    "accordion": ["accordion"],
    "padlock": ["padlock"],
    "stethoscope": ["stethoscope"],
    "iron": ["iron"],
    "perfume": ["perfume"],
    "paintbrush": ["paintbrush"],
    "pencil sharpener": ["pencil sharpener"],
    "rubber eraser": ["rubber eraser"],
    "syringe": ["syringe"],
    "combination lock": ["combination lock"],
}
classifier_labels = set((assets / "imagenet-labels.txt").read_text().splitlines())
assert len(base) == 80 and len(extra) == 20
assert len(set(base) | set(extra)) == 100
assert all(alias in classifier_labels for aliases in extra.values() for alias in aliases)
catalog = [dict(name=name, mode="scene", aliases=[name]) for name in base]
catalog += [dict(name=name, mode="focus", aliases=aliases) for name, aliases in extra.items()]
(assets / "object-catalog.json").write_text(json.dumps(catalog, indent=2) + "\n")
print("PASS: 100 distinct model-backed categories (80 scene + 20 focus)")
