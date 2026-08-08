"""Generate the test fixtures the test suite needs but git cannot carry.

`data/` and `ml-service/artifacts/*.joblib` are gitignored, so a fresh clone has neither
test spectra nor a model artifact. This script recreates both deterministically:

* ``testdata/sample.mzML``        - a tiny valid mzML with one MS1 and one MS2 spectrum.
* ``testdata/wrong-extension.txt`` - a non-mzML upload, for the negative cases.
* a tiny sklearn artifact bundle, so E2E jobs reach ``DONE`` in milliseconds.

The two small files are committed; the ``.joblib`` is not, per the repository's artifact
rules. Regenerate it before running the E2E suite.

Usage:

    python3 testdata/generate_fixtures.py            # spectra only if the artifact exists
    python3 testdata/generate_fixtures.py --force    # also overwrite the artifact
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Tuple

import numpy as np

REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT / "ml-service"))

from ml_core.artifacts import save_artifact_bundle  # noqa: E402
from train import build_pipeline  # noqa: E402

RANDOM_SEED = 20260808

# Three synthetic lipid classes, each a cluster of m/z peaks around its own centres.
# Deliberately well separated so the tiny model is confident and its prediction stable.
CLASS_CENTRES: Dict[str, List[float]] = {
    "PC": [184.07, 496.34, 758.57, 782.57],
    "PE": [196.04, 452.28, 716.52, 744.55],
    "TG": [247.24, 551.50, 603.53, 875.73],
}
SAMPLE_CLASS = "PC"

MIN_MZ = 100.0
MAX_MZ = 1000.0
BIN_WIDTH = 1.0
PEAKS_PER_SPECTRUM = 40
SPECTRA_PER_CLASS = 12


def synthesize_mz_values(rng: np.random.Generator, lipid_class: str) -> np.ndarray:
    """Draw one spectrum's worth of m/z values for the given class."""
    centres = np.asarray(CLASS_CENTRES[lipid_class], dtype=np.float64)
    picked = rng.choice(centres, size=PEAKS_PER_SPECTRUM)
    jitter = rng.normal(loc=0.0, scale=1.5, size=PEAKS_PER_SPECTRUM)
    values = np.clip(picked + jitter, MIN_MZ, MAX_MZ - 0.001)
    return np.sort(values)


def build_training_frame() -> Tuple[List[str], List[str]]:
    """Return (mz_value_strings, labels) for the tiny training set."""
    rng = np.random.default_rng(RANDOM_SEED)
    x: List[str] = []
    y: List[str] = []
    for lipid_class in CLASS_CENTRES:
        for _ in range(SPECTRA_PER_CLASS):
            values = synthesize_mz_values(rng, lipid_class)
            x.append(" ".join(f"{value:.4f}" for value in values))
            y.append(lipid_class)
    return x, y


def write_sample_mzml(path: Path) -> None:
    """Write a minimal but genuinely valid mzML file via pyopenms.

    The worker runs with ``ms_level=2``, so the MS2 spectrum is the one that matters;
    the MS1 survey scan is there to make the file realistic.
    """
    import pyopenms as oms

    rng = np.random.default_rng(RANDOM_SEED + 1)
    experiment = oms.MSExperiment()

    survey = oms.MSSpectrum()
    survey.setMSLevel(1)
    survey.setRT(30.0)
    survey.set_peaks(([760.585], [1.0e6]))
    experiment.addSpectrum(survey)

    mz_values = synthesize_mz_values(rng, SAMPLE_CLASS)
    intensities = rng.uniform(1.0e3, 1.0e5, size=mz_values.size)

    fragment = oms.MSSpectrum()
    fragment.setMSLevel(2)
    fragment.setRT(30.5)
    precursor = oms.Precursor()
    precursor.setMZ(760.585)
    precursor.setCharge(1)
    fragment.setPrecursors([precursor])
    fragment.set_peaks((mz_values.tolist(), intensities.tolist()))
    experiment.addSpectrum(fragment)

    path.parent.mkdir(parents=True, exist_ok=True)
    oms.MzMLFile().store(str(path), experiment)


def write_wrong_extension(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "This file is not an mzML spectrum.\n"
        "It exists so the upload tests can exercise the rejected-extension block.\n",
        encoding="utf-8",
    )


def write_tiny_artifact(output: Path) -> Dict[str, object]:
    from sklearn.preprocessing import LabelEncoder

    x, y = build_training_frame()
    label_encoder = LabelEncoder()
    y_encoded = label_encoder.fit_transform(y)

    pipeline = build_pipeline("random_forest", MIN_MZ, MAX_MZ, BIN_WIDTH)
    pipeline.fit(x, y_encoded)

    metadata = {
        "artifact_version": "test-fixture-1",
        "trained_at": datetime.now(timezone.utc).isoformat(),
        "source_dataset": "testdata/generate_fixtures.py (synthetic)",
        "num_rows": len(x),
        "num_classes": int(len(label_encoder.classes_)),
        "label_mapping": {str(i): label for i, label in enumerate(label_encoder.classes_)},
        "featureization": {
            "type": "fixed_mz_histogram",
            "min_mz": MIN_MZ,
            "max_mz": MAX_MZ,
            "bin_width": BIN_WIDTH,
            "normalize": True,
            "input_values": "m/z only",
        },
        "models": {},
        "best_model": "random_forest",
        "random_state": RANDOM_SEED,
    }

    save_artifact_bundle(
        {"pipeline": pipeline, "label_encoder": label_encoder, "metadata": metadata},
        output,
    )
    return metadata


def display(path: Path) -> str:
    """Repo-relative path where possible, absolute otherwise."""
    try:
        return str(path.resolve().relative_to(REPO_ROOT))
    except ValueError:
        return str(path)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--testdata-dir", type=Path, default=REPO_ROOT / "testdata")
    parser.add_argument(
        "--artifact",
        type=Path,
        default=REPO_ROOT / "ml-service" / "artifacts" / "lipid_class_pipeline.joblib",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Overwrite an existing model artifact (by default an existing one is kept).",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()

    sample = args.testdata_dir / "sample.mzML"
    write_sample_mzml(sample)
    print(f"wrote {display(sample)} ({sample.stat().st_size} bytes)")

    wrong = args.testdata_dir / "wrong-extension.txt"
    write_wrong_extension(wrong)
    print(f"wrote {display(wrong)} ({wrong.stat().st_size} bytes)")

    if args.artifact.exists() and not args.force:
        print(
            f"kept existing {display(args.artifact)} "
            f"({args.artifact.stat().st_size / 1_048_576:.1f} MB) - pass --force to replace it "
            "with the tiny test model"
        )
        return

    # Note: ml-service/artifacts/lipid_class_metadata.json is tracked in git and describes
    # the real trained model, so it is deliberately left alone. The bundle carries its own
    # metadata, which is what the worker actually reads.
    metadata = write_tiny_artifact(args.artifact)
    print(
        f"wrote {display(args.artifact)} "
        f"({args.artifact.stat().st_size / 1024:.1f} KB, "
        f"{metadata['num_classes']} classes: {', '.join(CLASS_CENTRES)})"
    )


if __name__ == "__main__":
    main()
