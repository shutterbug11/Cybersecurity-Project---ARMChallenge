# Cybersecurity Project — ARMChallenge

This repository contains code from six cybersecurity project folders:

1. `Cybersecurity_Intution`
2. `DDos Attack Classifier`
3. `Malware Detection`
4. `Malware Detection using CNN`
5. `Phishing Classifier`
6. `Spam SMS Classifier`

The local datasets and large archive chunks are not included in this code repository. The original notebooks may refer to local or Google Colab dataset paths.

## Malware Detection using CNN

`Malware Detection using CNN/malware_cnn-main/train_cnn.py` trains a grayscale CNN using PNG files in the neighboring `train/benign` and `train/malware` directories. Run it with:

```sh
python "Malware Detection using CNN/malware_cnn-main/train_cnn.py"
```

It requires PyTorch, Pillow, NumPy, scikit-learn, and Matplotlib. Its `results/` folder contains the trained model, performance pickle, and plots. The held-out test accuracy from this run was 85.1% on 902 images.
