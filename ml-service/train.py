import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, Tuple

import pandas as pd
from sklearn.ensemble import RandomForestClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, f1_score
from sklearn.model_selection import train_test_split
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import LabelEncoder

from ml_core.artifacts import save_artifact_bundle
from ml_core.features import MzHistogramFeaturizer


RANDOM_STATE = 42


def load_training_dataset(path: Path, max_rows: int | None = None) -> Tuple[pd.Series, pd.Series]:
    columns = ["lipid_class", "mz_values"]
    df = pd.read_csv(path, usecols=columns, nrows=max_rows)
    df = df.dropna(subset=columns)
    df = df[df["mz_values"].astype(str).str.len() > 0]
    df = df[df["lipid_class"].astype(str).str.len() > 0]

    if df.empty:
        raise ValueError(f"No usable training rows found in {path}")

    return df["mz_values"], df["lipid_class"]


def build_pipeline(model_name: str, min_mz: float, max_mz: float, bin_width: float) -> Pipeline:
    if model_name == "logistic_regression":
        classifier = LogisticRegression(
            max_iter=200,
            tol=0.01,
            class_weight="balanced",
            solver="saga",
            random_state=RANDOM_STATE,
        )
    elif model_name == "random_forest":
        classifier = RandomForestClassifier(
            n_estimators=80,
            max_depth=35,
            min_samples_leaf=2,
            class_weight="balanced_subsample",
            n_jobs=-1,
            random_state=RANDOM_STATE,
        )
    else:
        raise ValueError(f"Unsupported model: {model_name}")

    return Pipeline(
        steps=[
            (
                "mz_histogram",
                MzHistogramFeaturizer(
                    min_mz=min_mz,
                    max_mz=max_mz,
                    bin_width=bin_width,
                    normalize=True,
                ),
            ),
            ("classifier", classifier),
        ]
    )


def evaluate_pipeline(pipeline: Pipeline, x_test: pd.Series, y_test_encoded) -> Dict[str, float]:
    predictions = pipeline.predict(x_test)
    return {
        "accuracy": float(accuracy_score(y_test_encoded, predictions)),
        "macro_f1": float(f1_score(y_test_encoded, predictions, average="macro")),
    }


def train_and_compare(args: argparse.Namespace) -> Dict[str, object]:
    print(f"Loading training data from {args.input}", flush=True)
    x, y = load_training_dataset(args.input, args.max_rows)
    print(f"Loaded {len(x)} rows", flush=True)

    label_encoder = LabelEncoder()
    y_encoded = label_encoder.fit_transform(y)
    print(f"Found {len(label_encoder.classes_)} lipid classes", flush=True)

    stratify = y_encoded if min(pd.Series(y_encoded).value_counts()) >= 2 else None
    x_train, x_test, y_train, y_test = train_test_split(
        x,
        y_encoded,
        test_size=args.test_size,
        random_state=RANDOM_STATE,
        stratify=stratify,
    )

    results: Dict[str, Dict[str, float]] = {}
    pipelines: Dict[str, Pipeline] = {}

    for model_name in ("logistic_regression", "random_forest"):
        print(f"Training {model_name}", flush=True)
        pipeline = build_pipeline(model_name, args.min_mz, args.max_mz, args.bin_width)
        pipeline.fit(x_train, y_train)
        results[model_name] = evaluate_pipeline(pipeline, x_test, y_test)
        pipelines[model_name] = pipeline
        print(f"{model_name} metrics: {results[model_name]}", flush=True)

    best_model_name = max(
        results,
        key=lambda name: (results[name]["macro_f1"], results[name]["accuracy"]),
    )

    metadata = {
        "artifact_version": 1,
        "trained_at": datetime.now(timezone.utc).isoformat(),
        "source_dataset": str(args.input),
        "num_rows": int(len(x)),
        "num_classes": int(len(label_encoder.classes_)),
        "label_mapping": {str(index): label for index, label in enumerate(label_encoder.classes_)},
        "featureization": {
            "type": "fixed_mz_histogram",
            "min_mz": args.min_mz,
            "max_mz": args.max_mz,
            "bin_width": args.bin_width,
            "normalize": True,
            "input_values": "m/z only",
        },
        "models": results,
        "best_model": best_model_name,
        "random_state": RANDOM_STATE,
    }

    bundle = {
        "pipeline": pipelines[best_model_name],
        "label_encoder": label_encoder,
        "metadata": metadata,
    }
    save_artifact_bundle(bundle, args.output)

    args.metadata_output.parent.mkdir(parents=True, exist_ok=True)
    args.metadata_output.write_text(json.dumps(metadata, indent=2), encoding="utf-8")

    return metadata


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Train m/z-only lipid class baseline models.")
    parser.add_argument("--input", type=Path, default=Path("data/processed/lipidblast_spectra.csv"))
    parser.add_argument("--output", type=Path, default=Path("ml-service/artifacts/lipid_class_pipeline.joblib"))
    parser.add_argument("--metadata-output", type=Path, default=Path("ml-service/artifacts/lipid_class_metadata.json"))
    parser.add_argument("--max-rows", type=int, default=None, help="Optional training row cap for smoke runs.")
    parser.add_argument("--test-size", type=float, default=0.2)
    parser.add_argument("--min-mz", type=float, default=0.0)
    parser.add_argument("--max-mz", type=float, default=2000.0)
    parser.add_argument("--bin-width", type=float, default=1.0)
    return parser.parse_args()


def main() -> None:
    metadata = train_and_compare(parse_args())
    print(json.dumps(metadata, indent=2))


if __name__ == "__main__":
    main()
