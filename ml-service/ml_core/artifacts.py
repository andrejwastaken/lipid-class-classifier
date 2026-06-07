from pathlib import Path
from typing import Any, Dict, List

import joblib

from ml_core.features import mzml_to_mz_values


def save_artifact_bundle(bundle: Dict[str, Any], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    joblib.dump(bundle, path)


def load_artifact_bundle(path: Path) -> Dict[str, Any]:
    return joblib.load(path)


def predict_mz_values(bundle: Dict[str, Any], mz_values: List[float]) -> Dict[str, Any]:
    pipeline = bundle["pipeline"]
    label_encoder = bundle["label_encoder"]
    encoded_prediction = pipeline.predict([mz_values])[0]
    class_name = label_encoder.inverse_transform([encoded_prediction])[0]

    probabilities = pipeline.predict_proba([mz_values])[0]
    class_order = pipeline.named_steps["classifier"].classes_.tolist()
    probability = float(probabilities[class_order.index(encoded_prediction)])

    return {
        "predicted_class": class_name,
        "probability": probability,
    }


def predict_mzml_file(bundle: Dict[str, Any], path: Path, ms_level: int | None = None) -> Dict[str, Any]:
    return predict_mz_values(bundle, mzml_to_mz_values(path, ms_level=ms_level))
