"""Verify bundled focus-classifier input normalization, labels, and output decoding."""
import argparse
from pathlib import Path
import json
import numpy as np
from PIL import Image
from ai_edge_litert.interpreter import Interpreter

parser = argparse.ArgumentParser()
parser.add_argument("image")
parser.add_argument("--expect", required=True)
args = parser.parse_args()
assets = Path(__file__).resolve().parents[1] / "app/src/main/assets"
labels = (assets / "imagenet-labels.txt").read_text().splitlines()
catalog = json.loads((assets / "object-catalog.json").read_text())
assert len(catalog) == len({entry["name"] for entry in catalog}) == 100
assert sum(entry["mode"] == "focus" for entry in catalog) == 20
assert all(alias in labels for entry in catalog if entry["mode"] == "focus" for alias in entry["aliases"])
interpreter = Interpreter(model_path=str(assets / "mobilenet_v1_1.0_224.tflite"), num_threads=2)
interpreter.allocate_tensors()
input_info = interpreter.get_input_details()[0]
output_info = interpreter.get_output_details()[0]
assert input_info["dtype"] == np.float32
assert list(input_info["shape"]) == [1, 224, 224, 3]
assert output_info["shape"][-1] == len(labels) == 1001
image = Image.open(args.image).convert("RGB").resize((224, 224), Image.Resampling.BILINEAR)
pixels = (np.asarray(image, dtype=np.float32) - 127.5) / 127.5
interpreter.set_tensor(input_info["index"], pixels[np.newaxis, ...])
interpreter.invoke()
scores = interpreter.get_tensor(output_info["index"])[0]
top = int(np.argmax(scores))
print(f"{labels[top]}: {scores[top]:.3f}")
assert labels[top] == args.expect
print("PASS: focus model inference, normalization, 100-category catalog, and 1001 feature outputs")
