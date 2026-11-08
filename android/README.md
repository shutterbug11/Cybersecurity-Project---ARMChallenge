# Android app (in progress)

The project currently contains a native Kotlin/Compose app foundation, Room history, and a bundled phishing logistic-regression ONNX candidate. Other detectors remain explicitly unavailable until their input extraction and Android parity gates pass. The UI does not claim whole-device protection.

## Build

Open this `android/` directory in Android Studio with JDK 17 or 21 and Android SDK 36, or run `gradlew.bat assembleDebug` on Windows / `./gradlew assembleDebug` elsewhere. The app targets `arm64-v8a` phones with Android 9 (API 28) or newer. The source manifest has no `INTERNET`, SMS, storage, or VPN permission. Build dependencies are downloaded on the development machine only; runtime classification uses the bundled ONNX model and vocabulary.

## Current model contract

`app/src/main/assets/phishing-v1/` contains the ONNX model, indexed vocabulary, and hash manifest produced by `../tools/model-export/export_phishing.py`. The original scikit-learn pickle files are local development inputs and are not loaded in Android. The app hashes the model and vocabulary before use. Input is a complete HTTP(S) URL; output is `bad` or `good`, with no calibrated confidence claim. Inference and Room history run locally. A `good` result does not prove a site is safe.

## Verification status

- Python ONNX Runtime matched the original sklearn classifier on 1,000 sampled URLs: zero label differences and maximum probability difference approximately 1.1×10⁻⁷.
- The Lucene Java Snowball stemmer matched NLTK normalized URL text on 1,000 sampled URLs.
- GitHub Actions [run 42452032203](https://github.com/shutterbug11/Cybersecurity-Project---ARMChallenge/actions/runs/42452032203) assembled the debug APK successfully. The downloaded APK is 115,712,810 bytes (SHA-256 `3c06f02b2fc829078a3e57d5b22473c0a3003c8be0e219d1dbbf9ffda17b4f13`). This confirms compilation, not on-device inference parity. The local Gradle client still cannot start because this workspace blocks Java loopback sockets.
- SMS's missing scaler was reconstructed from the original 7,230 rows, reproducing notebook accuracy and precision. Its three converted ONNX estimators matched the saved Python soft vote on 1,446 held-out rows. The Android tokenizer is still unvalidated, so SMS scanning remains disabled.
- The previously missing DDoS and Windows PE RandomForest models were retrained with separate user approval. Their ONNX predictions matched Python on all 19,871 and 27,610 held-out rows respectively. Packet-flow and PE extraction parity remain untested, so neither detector is enabled.
- The existing grayscale CNN checkpoint exported to a 554,632-byte ExecuTorch XNNPACK `.pte`. Runtime validation on Windows is pending after a PyTorch/ExecuTorch environment error; Android image preprocessing and on-device parity are untested. It remains disabled.

No production release APK is claimed.

## Optional Llama model provisioning

The local advisor bridge uses ExecuTorch 1.5.1 and is gated until a matching model pack is imported and device validated. The pinned public source is [ExecuTorch Community Llama 3.2 1B Instruct ET](https://huggingface.co/executorch-community/Llama-3.2-1B-Instruct-ET/tree/a890af65db35c8a68836d9cb1921b4b7223420a4), under the Llama 3.2 license. Acquire `Llama-3.2-1B-Instruct.pte` and `tokenizer.model` from that revision on a development machine. In the app, open **Offline AI status** and select both local files. The app copies them into private storage and checks their published size and SHA-256 values; it has no network permission. The `.pte` alone is 2,471,922,052 bytes, so provision on a device with ample free storage. Android generation, memory use, latency, and response quality are not yet verified. The model is not included in Git or the APK.
