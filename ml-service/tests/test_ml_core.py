import sys
from pathlib import Path

import joblib
import numpy as np
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import LabelEncoder

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from ml_core.artifacts import predict_mz_values, save_artifact_bundle
from ml_core.features import MzHistogramFeaturizer, parse_mz_values
from train import build_pipeline


def test_parse_mz_values_accepts_training_csv_payload() -> None:
    values = parse_mz_values("225.0069 409.4043 691.4457")

    np.testing.assert_allclose(values, [225.0069, 409.4043, 691.4457])


def test_mz_histogram_featurizer_uses_mz_values_only() -> None:
    featurizer = MzHistogramFeaturizer(min_mz=100.0, max_mz=105.0, bin_width=1.0, normalize=False)
    features = featurizer.fit_transform(["100.1 100.9 102.0 110.0"])

    assert features.shape == (1, 5)
    assert features.toarray().tolist() == [[2.0, 0.0, 1.0, 0.0, 0.0]]


def test_training_pipeline_bundle_predicts_from_saved_preprocessing(tmp_path: Path) -> None:
    label_encoder = LabelEncoder()
    y = label_encoder.fit_transform(["A", "A", "B", "B"])
    pipeline: Pipeline = build_pipeline("logistic_regression", min_mz=100.0, max_mz=400.0, bin_width=10.0)
    pipeline.fit(
        [
            "110.0 120.0 130.0",
            "111.0 121.0 131.0",
            "310.0 320.0 330.0",
            "311.0 321.0 331.0",
        ],
        y,
    )

    bundle = {
        "pipeline": pipeline,
        "label_encoder": label_encoder,
        "metadata": {"artifact_version": 1},
    }
    artifact_path = tmp_path / "bundle.joblib"

    save_artifact_bundle(bundle, artifact_path)
    loaded_bundle = joblib.load(artifact_path)
    prediction = predict_mz_values(loaded_bundle, [110.0, 120.0, 130.0])

    assert prediction["predicted_class"] == "A"
    assert 0.0 <= prediction["probability"] <= 1.0
