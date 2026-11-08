"""Attempt XNNPACK ExecuTorch export of the existing trained grayscale CNN.

Run with the version-pinned ExecuTorch environment. This never retrains the
model. A successful .pte still needs Android device parity before enabling.
"""

from hashlib import sha256
import importlib.util
import json
from pathlib import Path

import torch
from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
from executorch.exir import to_edge_transform_and_lower


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "Malware Detection using CNN" / "malware_cnn-main"
OUT = ROOT / "model-packs" / "malware-cnn-v1"


def digest(path):
    return sha256(path.read_bytes()).hexdigest()


def main():
    definition = importlib.util.spec_from_file_location("train_cnn", SOURCE / "train_cnn.py")
    module = importlib.util.module_from_spec(definition)
    definition.loader.exec_module(module)
    source_path = SOURCE / "results" / "malware_cnn.pt"
    state = torch.load(source_path, map_location="cpu", weights_only=True)
    model = module.MalwareCNN().eval()
    model.load_state_dict(state["model_state_dict"])
    example = torch.zeros(1, 1, 64, 64, dtype=torch.float32)
    exported = torch.export.export(model, (example,))
    program = to_edge_transform_and_lower(exported, partitioner=[XnnpackPartitioner()]).to_executorch()
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / "malware_cnn.pte"
    path.write_bytes(program.buffer)
    manifest = {
        "id": "malware-cnn", "version": 1, "classes": state["classes"],
        "input_shape": [1, 1, 64, 64], "input_dtype": "float32",
        "preprocessing": "Decode PNG as grayscale, resize to 64x64, divide uint8 pixels by 255",
        "output": "two logits in benign, malware order",
        "source_sha256": digest(source_path), "pte_sha256": digest(path),
        "status": "ExecuTorch export only; runtime, Android parity, and raw-binary image provenance unverified",
    }
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print("Exported", path, "bytes", path.stat().st_size)


if __name__ == "__main__":
    main()
