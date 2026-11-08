"""Reconstruct the missing SMS scaler from the notebook's original training rows.

This preserves the saved voting classifier. It uses the trusted local-only CSVs
and pickles; no classifier is retrained. Android parity is a separate gate.
"""

from hashlib import sha256
import json
from pathlib import Path
import pickle
import warnings

import nltk
import numpy as np
import onnx
import onnxruntime as ort
import pandas as pd
from nltk.corpus import stopwords
from nltk.stem.porter import PorterStemmer
from sklearn.metrics import accuracy_score, precision_score
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import MinMaxScaler
from skl2onnx import to_onnx


ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "Spam SMS Classifier"
OUT = ROOT / "model-packs" / "sms-v1"


def digest(path):
    return sha256(path.read_bytes()).hexdigest()


def transform_text(text, stopwords_set, stemmer):
    words = nltk.word_tokenize(text.lower())
    words = [word for word in words if word.isalnum() and word not in stopwords_set]
    return " ".join(stemmer.stem(word) for word in words)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    first = pd.read_csv(BASE / "sms_data" / "spam_ham_india.csv")
    second = pd.read_csv(BASE / "sms_data" / "spam_utf8.csv")
    second = second.drop(columns=["Unnamed: 2", "Unnamed: 3", "Unnamed: 4"])
    second.rename(columns={"v1": "Label", "v2": "Msg"}, inplace=True)
    first = first.dropna(subset=["Msg"]).drop_duplicates().reset_index(drop=True)
    second = second.drop_duplicates()
    frame = pd.concat([first, second], ignore_index=True).drop_duplicates().reset_index(drop=True)
    if frame.shape != (7230, 2):
        raise ValueError(f"Expected original 7230 SMS rows; got {frame.shape}")
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", category=UserWarning)
        with (BASE / "code" / "vectorizer.pkl").open("rb") as file:
            vectorizer = pickle.load(file)
        with (BASE / "code" / "model.pkl").open("rb") as file:
            model = pickle.load(file)
    stopwords_set = set(stopwords.words("english"))
    stemmer = PorterStemmer()
    transformed = frame.Msg.map(lambda message: transform_text(message, stopwords_set, stemmer))
    tfidf = vectorizer.transform(transformed).toarray()
    scaler = MinMaxScaler().fit(tfidf)
    features = np.hstack((scaler.transform(tfidf), frame.Msg.str.len().to_numpy().reshape(-1, 1)))
    labels = frame.Label.map({"ham": 0, "spam": 1}).to_numpy()
    _, x_test, _, y_test = train_test_split(features, labels, test_size=0.2,
                                            random_state=2, stratify=labels)
    predictions = model.predict(x_test)
    accuracy, precision = accuracy_score(y_test, predictions), precision_score(y_test, predictions)
    if not np.isclose(accuracy, 0.9861687413554634) or not np.isclose(precision, 0.9923664122137404):
        raise AssertionError("Reconstructed features do not reproduce saved notebook metrics")

    # sklearn-onnx cannot directly export VotingClassifier with flatten_transform.
    # Preserve its three fitted estimators and average their class probabilities,
    # exactly as the saved soft-vote classifier does.
    onnx_paths = []
    onnx_probabilities = []
    for name, estimator in zip(["random_forest", "multinomial_nb", "extra_trees"], model.estimators_):
        proto = to_onnx(estimator, np.zeros((1, 3001), dtype=np.float32),
                        options={id(estimator): {"zipmap": False}}, target_opset=17)
        onnx.checker.check_model(proto)
        path = OUT / f"{name}.onnx"
        path.write_bytes(proto.SerializeToString())
        onnx_paths.append(path)
        session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
        input_name = session.get_inputs()[0].name
        prediction = session.run(None, {input_name: x_test.astype(np.float32)})[1]
        onnx_probabilities.append(np.asarray(prediction))
    combined = np.mean(onnx_probabilities, axis=0)
    reference = model.predict_proba(x_test)
    np.testing.assert_allclose(combined, reference, atol=1e-4, rtol=1e-4)
    if not np.array_equal(combined.argmax(axis=1), predictions):
        raise AssertionError("Converted estimators changed a held-out prediction")

    # Numeric arrays are portable build inputs; the original pickle remains local.
    np.savez_compressed(OUT / "scaler.npz", minimum=scaler.data_min_, maximum=scaler.data_max_)
    vocab = [None] * len(vectorizer.vocabulary_)
    for word, index in vectorizer.vocabulary_.items():
        vocab[index] = word
    (OUT / "vocabulary.txt").write_text("\n".join(vocab) + "\n", encoding="utf-8")
    np.save(OUT / "idf.npy", vectorizer.idf_)
    samples = []
    # Synthetic fixtures avoid redistributing any message from the local CSVs.
    for message in [
        "Hello, the meeting starts at 10 tomorrow.",
        "Claim your free prize now at https://example.invalid/win",
        "Your parcel is ready. Please check the official app.",
        "URGENT: verify your account at http://example.invalid/login",
        "See you soon!",
    ]:
        normalized = transform_text(message, stopwords_set, stemmer)
        vector = scaler.transform(vectorizer.transform([normalized]).toarray())
        vector = np.hstack((vector, np.asarray([[len(message)]])))
        samples.append({"raw": message, "normalized": normalized,
                        "feature_sha256": sha256(vector.astype(np.float32).tobytes()).hexdigest(),
                        "prediction": int(model.predict(vector)[0]),
                        "probabilities_ham_spam": model.predict_proba(vector)[0].tolist()})
    (OUT / "golden.json").write_text(json.dumps(samples, indent=2), encoding="utf-8")
    manifest = {
        "id": "sms-voting", "version": 1, "classes": ["ham", "spam"],
        "input_shape": [1, 3001], "input_dtype": "float32",
        "recovery": "Fitted missing MinMaxScaler on the same 7230 rows and saved TF-IDF vectorizer; notebook test metrics reproduced exactly",
        "android_parity": "pending",
        "classifier_conversion": "Three fitted estimators exported separately; equal-weight soft vote matches saved sklearn classifier on 1446 held-out rows in Python ONNX Runtime",
        "onnx_artifacts": {path.name: digest(path) for path in onnx_paths},
        "source_hashes": {str(p.relative_to(BASE)): digest(p) for p in [
            BASE / "sms_data" / "spam_ham_india.csv", BASE / "sms_data" / "spam_utf8.csv",
            BASE / "code" / "vectorizer.pkl", BASE / "code" / "model.pkl"]},
    }
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"Recovered scaler; reproduced notebook test accuracy {accuracy:.12f}, precision {precision:.12f}")
    print("Python ONNX Runtime parity passed on 1446 held-out rows; Android preprocessing parity remains unvalidated")


if __name__ == "__main__":
    main()
