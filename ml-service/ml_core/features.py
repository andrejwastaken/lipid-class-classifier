import logging
from pathlib import Path
from typing import Iterable, List, Sequence

import numpy as np
from scipy import sparse
from sklearn.base import BaseEstimator, TransformerMixin


logger = logging.getLogger("lipid-worker.ml_core.features")


def parse_mz_values(value: object) -> np.ndarray:
    if value is None:
        return np.array([], dtype=np.float64)

    if isinstance(value, np.ndarray):
        return value.astype(np.float64, copy=False)

    if isinstance(value, (list, tuple)):
        return np.asarray(value, dtype=np.float64)

    text = str(value).strip()
    if not text:
        return np.array([], dtype=np.float64)

    return np.fromstring(text.replace(",", " "), sep=" ", dtype=np.float64)


def mzml_to_mz_values(path: Path, ms_level: int | None = None) -> List[float]:
    import pyopenms as oms

    experiment = oms.MSExperiment()
    oms.MzMLFile().load(str(path), experiment)

    mz_values: List[float] = []
    for spectrum in experiment.getSpectra():
        if ms_level is not None and spectrum.getMSLevel() != ms_level:
            continue
        mz_array, _ = spectrum.get_peaks()
        mz_values.extend(float(mz) for mz in mz_array)

    logger.info(
        "Extracted %s m/z values from %s spectra in %s with ms_level=%s",
        len(mz_values),
        len(experiment.getSpectra()),
        path,
        ms_level if ms_level is not None else "all",
    )
    return mz_values


class MzHistogramFeaturizer(BaseEstimator, TransformerMixin):
    def __init__(
        self,
        min_mz: float = 0.0,
        max_mz: float = 2000.0,
        bin_width: float = 1.0,
        normalize: bool = True,
    ) -> None:
        self.min_mz = min_mz
        self.max_mz = max_mz
        self.bin_width = bin_width
        self.normalize = normalize

    def fit(self, x: Sequence[object], y: object = None) -> "MzHistogramFeaturizer":
        if self.bin_width <= 0:
            raise ValueError("bin_width must be positive")
        if self.max_mz <= self.min_mz:
            raise ValueError("max_mz must be greater than min_mz")

        self.n_bins_ = int(np.ceil((self.max_mz - self.min_mz) / self.bin_width))
        self.bin_edges_ = self.min_mz + np.arange(self.n_bins_ + 1) * self.bin_width
        return self

    def transform(self, x: Sequence[object]) -> sparse.csr_matrix:
        if not hasattr(self, "n_bins_"):
            raise ValueError("MzHistogramFeaturizer must be fitted before transform")

        rows: List[int] = []
        cols: List[int] = []
        data: List[float] = []

        for row_index, raw_values in enumerate(x):
            mz_values = parse_mz_values(raw_values)
            if mz_values.size == 0:
                continue

            in_range = mz_values[(mz_values >= self.min_mz) & (mz_values < self.max_mz)]
            if in_range.size == 0:
                continue

            bin_indices = np.floor((in_range - self.min_mz) / self.bin_width).astype(np.int64)
            unique_bins, counts = np.unique(bin_indices, return_counts=True)

            if self.normalize:
                counts = counts.astype(np.float64) / float(in_range.size)
            else:
                counts = counts.astype(np.float64)

            rows.extend([row_index] * len(unique_bins))
            cols.extend(unique_bins.tolist())
            data.extend(counts.tolist())

        return sparse.csr_matrix((data, (rows, cols)), shape=(len(x), self.n_bins_))

    def get_feature_names_out(self, input_features: Iterable[str] | None = None) -> np.ndarray:
        if not hasattr(self, "n_bins_"):
            raise ValueError("MzHistogramFeaturizer must be fitted before feature names are available")

        return np.asarray(
            [
                f"mz_bin_{self.bin_edges_[i]:.4f}_{self.bin_edges_[i + 1]:.4f}"
                for i in range(self.n_bins_)
            ],
            dtype=object,
        )
