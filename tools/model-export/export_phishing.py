"""Export the existing phishing LR and exact vocabulary for an offline Android pack.

This script reads trusted local pickle files. Pickles are never shipped to Android.
Run from the repository root with Python, scikit-learn, skl2onnx and onnxruntime.
"""

from hashlib import sha256
import json
from pathlib import Path
import pickle
import warnings

import numpy as np
import onnx
import onnxruntime as ort
from nltk.stem.snowball import SnowballStemmer
from nltk.tokenize import RegexpTokenizer
from skl2onnx import to_onnx


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "Phishing Classifier" / "code"
OUT = ROOT / "model-packs" / "phishing-v1"


def digest(path):
    return sha256(path.read_bytes()).hexdigest()


def preprocess(url):
    tokens = RegexpTokenizer(r"[A-Za-z]+").tokenize(url)
    stemmer = SnowballStemmer("english")
    return " ".join(stemmer.stem(token) for token in tokens)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", category=UserWarning)
        with (SOURCE / "phishing_classifier_lr.pkl").open("rb") as file:
            model = pickle.load(file)
        with (SOURCE / "vectorization.pkl").open("rb") as file:
            vectorizer = pickle.load(file)
    if model.n_features_in_ != len(vectorizer.vocabulary_):
        raise ValueError("Model and vocabulary feature counts differ")
    if list(model.classes_) != ["bad", "good"]:
        raise ValueError("Unexpected class order")

    # This is a converter attempt, not an assumption about Android compatibility.
    proto = to_onnx(model, np.zeros((1, model.n_features_in_), dtype=np.float32),
                    options={id(model): {"zipmap": False}}, target_opset=17)
    onnx.checker.check_model(proto)
    onnx_path = OUT / "phishing_lr.onnx"
    onnx_path.write_bytes(proto.SerializeToString())

    # Line number is the feature index. A word/weight list can later replace the
    # dense ONNX input if Android memory or latency makes it impractical.
    vocab = [None] * model.n_features_in_
    for token, index in vectorizer.vocabulary_.items():
        vocab[index] = token
    if any(token is None for token in vocab):
        raise ValueError("Vocabulary contains gaps")
    vocab_path = OUT / "vocabulary.txt"
    vocab_path.write_text("\n".join(vocab) + "\n", encoding="utf-8")

    samples = [
        "https://example.com/",
        "http://secure-login-bank.example/verify-account",
        "HTTPS://pay.example/Account-Reset?id=3",
        "not a url but readable text",
        "https://xn--bcher-kva.example/a%20b",
    ]
    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name
    golden = []
    for raw in samples:
        normalized = preprocess(raw)
        x = vectorizer.transform([normalized]).astype(np.float32)
        expected = model.predict_proba(x)[0]
        outputs = session.run(None, {input_name: x.toarray()})
        actual = np.asarray(outputs[1]).reshape(-1)
        np.testing.assert_allclose(actual, expected, rtol=1e-5, atol=1e-5)
        golden.append({
            "raw": raw, "normalized": normalized,
            "features": {str(i): int(v) for i, v in zip(x.indices, x.data)},
            "probabilities_bad_good": expected.tolist(),
            "label": str(model.predict(x)[0]),
        })
    (OUT / "golden.json").write_text(json.dumps(golden, indent=2), encoding="utf-8")
    manifest = {
        "id": "phishing-lr", "version": 1, "classes": ["bad", "good"],
        "feature_count": model.n_features_in_, "input_dtype": "float32",
        "input_shape": [1, model.n_features_in_],
        "preprocessing": "NLTK [A-Za-z]+ tokenization, English Snowball stemming, join; default CountVectorizer",
        "source_pickles": {p.name: digest(p) for p in
                           [SOURCE / "phishing_classifier_lr.pkl", SOURCE / "vectorization.pkl"]},
        "artifacts": {p.name: digest(p) for p in [onnx_path, vocab_path, OUT / "golden.json"]},
        "validated": "Python ONNX Runtime parity on five smoke examples only; Android parity pending",
    }
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print("Exported", onnx_path, "bytes", onnx_path.stat().st_size)
    print("Python ORT smoke parity passed; Android parity is pending")


if __name__ == "__main__":
    main()
