"""Retrain the notebook's missing RandomForest artifacts with explicit approval.

Uses the original local datasets and notebook hyperparameters. Writes trusted
Python pickles for development and ONNX models for future Android validation.
The resulting classifiers are not enabled in the Android app until feature
extraction parity and independent generalization tests pass.
"""

from hashlib import sha256
import json
from pathlib import Path
import pickle
import sys

import numpy as np
import onnx
import onnxruntime as ort
import pandas as pd
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import accuracy_score, classification_report, confusion_matrix
from sklearn.model_selection import train_test_split
from skl2onnx import to_onnx


ROOT = Path(__file__).resolve().parents[2]
SPECS = ROOT / "model-specs"
PACKS = ROOT / "model-packs"


def digest(path):
    h = sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def prepare(name, spec):
    path = ROOT / spec["source_dataset_local_only"]
    sep = "," if name == "ddos" else "|"
    frame = pd.read_csv(path, sep=sep, low_memory=False)
    if name == "ddos":
        frame.columns = frame.columns.str.strip()
        labels = frame["Label"].map({"BENIGN": 0, "DDoS": 1})
    else:
        labels = frame["legitimate"]
    features = frame[spec["feature_order"]].apply(pd.to_numeric, errors="coerce")
    valid = labels.isin([0, 1]) & np.isfinite(features.to_numpy(dtype=np.float64)).all(axis=1)
    dropped = int((~valid).sum())
    x = features.loc[valid].to_numpy(dtype=np.float32)
    y = labels.loc[valid].to_numpy(dtype=np.int64)
    return path, x, y, dropped


def main(name):
    if name not in {"ddos", "pe_malware"}:
        raise ValueError("Choose ddos or pe_malware")
    spec = json.loads((SPECS / f"{name}.json").read_text(encoding="utf-8"))
    source, x, y, dropped = prepare(name, spec)
    split = 0.30 if name == "ddos" else 0.20
    x_train, x_test, y_train, y_test = train_test_split(x, y, test_size=split, random_state=42)
    model = (RandomForestClassifier(n_estimators=50, random_state=42) if name == "ddos"
             else RandomForestClassifier(max_depth=2, random_state=0))
    print(name, "training rows", len(x_train), "test rows", len(x_test), "dropped", dropped, flush=True)
    model.fit(x_train, y_train)
    prediction = model.predict(x_test)
    report = classification_report(y_test, prediction, output_dict=True, zero_division=0)
    cm = confusion_matrix(y_test, prediction, labels=[0, 1]).tolist()
    print("accuracy", accuracy_score(y_test, prediction), "confusion", cm, flush=True)

    output = PACKS / f"{name}-v1"
    output.mkdir(parents=True, exist_ok=True)
    pkl_path = output / "model.pkl"
    with pkl_path.open("wb") as file:
        pickle.dump(model, file)
    proto = to_onnx(model, np.zeros((1, x.shape[1]), dtype=np.float32),
                    options={id(model): {"zipmap": False}}, target_opset=17)
    onnx.checker.check_model(proto)
    onnx_path = output / "model.onnx"
    onnx_path.write_bytes(proto.SerializeToString())
    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    onnx_prediction = []
    for start in range(0, len(x_test), 256):
        part = x_test[start:start + 256]
        onnx_prediction.extend(session.run(None, {session.get_inputs()[0].name: part})[0].tolist())
    onnx_prediction = np.asarray(onnx_prediction)
    if not np.array_equal(onnx_prediction, prediction):
        raise AssertionError(f"ONNX disagreed on {int(np.sum(onnx_prediction != prediction))} held-out labels")

    golden = []
    for i in range(min(20, len(x_test))):
        golden.append({"features": x_test[i].tolist(), "truth": int(y_test[i]),
                       "prediction": int(prediction[i])})
    (output / "golden.json").write_text(json.dumps(golden, indent=2), encoding="utf-8")
    manifest = {
        "id": name, "version": 1, "source_sha256": digest(source),
        "feature_order": spec["feature_order"], "input_shape": [1, x.shape[1]],
        "input_dtype": "float32", "classes": [0, 1],
        "training_rows": len(x_train), "test_rows": len(x_test), "dropped_nonfinite_or_unlabeled": dropped,
        "split": {"test_fraction": split, "random_state": 42, "stratified": False},
        "metrics": {"accuracy": accuracy_score(y_test, prediction),
                    "classification_report": report, "confusion_matrix": cm},
        "artifacts": {"model.onnx": digest(onnx_path), "golden.json": digest(output / "golden.json")},
        "validation": "Python ONNX Runtime label parity on all held-out rows; Android and field-feature parity pending",
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print("ONNX parity passed", len(x_test), "held-out rows; Android inference remains gated", flush=True)


if __name__ == "__main__":
    main(sys.argv[1])
