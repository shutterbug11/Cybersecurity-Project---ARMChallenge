"""Compare the exported CNN against its original PyTorch checkpoint.

Run with the version-pinned ExecuTorch Python environment. This tests runtime
numerics on the original held-out split, not Android image decoding or device
performance.
"""

from hashlib import sha256
import importlib.util
import json
from pathlib import Path

import numpy as np
from executorch.runtime import Runtime
from sklearn.model_selection import train_test_split
import torch


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "Malware Detection using CNN" / "malware_cnn-main"
PACK = ROOT / "model-packs" / "malware-cnn-v1"


def main():
    definition = importlib.util.spec_from_file_location("train_cnn", SOURCE / "train_cnn.py")
    module = importlib.util.module_from_spec(definition)
    definition.loader.exec_module(module)
    source_path = SOURCE / "results" / "malware_cnn.pt"
    checkpoint = torch.load(source_path, map_location="cpu", weights_only=True)
    model = module.MalwareCNN().eval()
    model.load_state_dict(checkpoint["model_state_dict"])
    images, labels, paths, _ = module.load_images()
    indices = np.arange(len(labels))
    _, remainder = train_test_split(indices, test_size=.30, stratify=labels, random_state=42)
    _, test_idx = train_test_split(remainder, test_size=.50, stratify=labels[remainder], random_state=42)
    method = Runtime.get().load_program(PACK / "malware_cnn.pte").load_method("forward")
    max_abs_logit_error = 0.0
    disagreements = 0
    with torch.no_grad():
        for idx in test_idx:
            tensor = torch.from_numpy(images[idx].copy()).unsqueeze(0).unsqueeze(0).float().div_(255)
            reference = model(tensor)
            actual = method.execute((tensor,))[0]
            max_abs_logit_error = max(max_abs_logit_error, float((reference - actual).abs().max()))
            disagreements += int(reference.argmax(1).item() != actual.argmax(1).item())
    report = {
        "source_sha256": sha256(source_path.read_bytes()).hexdigest(),
        "pte_sha256": sha256((PACK / "malware_cnn.pte").read_bytes()).hexdigest(),
        "split": "original stratified test split, seed 42",
        "samples": len(test_idx),
        "label_disagreements": disagreements,
        "max_abs_logit_error": max_abs_logit_error,
        "scope": "ExecuTorch Python CPU runtime; Android pixel decoding and device parity remain untested",
    }
    (PACK / "runtime_validation.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
