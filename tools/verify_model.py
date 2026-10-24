"""Validate the Android detector's tensor contract and optional labeled sample.

Development dependencies: ai-edge-litert, numpy, Pillow.
Example: python tools/verify_model.py sample.jpg --expect dog
"""
import argparse
from pathlib import Path
import numpy as np
from ai_edge_litert.interpreter import Interpreter
from PIL import Image

parser = argparse.ArgumentParser()
parser.add_argument("image", nargs="?")
parser.add_argument("--expect")
args = parser.parse_args()
assets = Path(__file__).resolve().parents[1] / "app/src/main/assets"
interpreter = Interpreter(model_path=str(assets / "efficientdet-lite0.tflite"), num_threads=2)
interpreter.allocate_tensors()
input_tensor = interpreter.get_input_details()[0]
outputs = interpreter.get_output_details()
assert input_tensor["dtype"] == np.uint8
assert list(input_tensor["shape"]) == [1, 320, 320, 3]
assert len(outputs) == 4
assert outputs[0]["shape"][-1] == 4
image = Image.open(args.image).convert("RGB") if args.image else Image.new("RGB", (640, 480))
pixels = np.asarray(image.resize((320, 320), Image.Resampling.BILINEAR), dtype=np.uint8)
interpreter.set_tensor(input_tensor["index"], pixels[np.newaxis, ...])
interpreter.invoke()
boxes, classes, scores, count = [interpreter.get_tensor(t["index"]) for t in outputs]
labels = (assets / "labels.txt").read_text().splitlines()
predictions = []
for i in range(min(int(count.flat[0]), scores.shape[1])):
    if scores[0, i] >= 0.45:
        label = labels[int(classes[0, i])]
        assert np.all(np.isfinite(boxes[0, i]))
        predictions.append(label)
        print(f"{label}: {scores[0, i]:.3f}; box={boxes[0, i].tolist()}")
if args.expect:
    assert args.expect in predictions, f"Expected {args.expect!r}, got {predictions!r}"
print("PASS: model tensor contract, inference, and output decoding")
