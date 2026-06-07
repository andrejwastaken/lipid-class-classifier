import argparse
import csv
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, List, Optional


COMPOUND_CLASS_RE = re.compile(r'"compound class=([^"]+)"')


@dataclass
class MspSpectrum:
    spectrum_id: int
    name: str
    lipid_class: str
    precursor_mz: str
    precursor_type: str
    ion_mode: str
    spectrum_type: str
    source_db_id: str
    mz_values: List[float]

    @property
    def num_peaks(self) -> int:
        return len(self.mz_values)


def _extract_lipid_class(metadata: Dict[str, str]) -> str:
    comments = metadata.get("Comments", "")
    match = COMPOUND_CLASS_RE.search(comments)
    if match:
        return match.group(1).strip()

    name = metadata.get("Name", "").strip()
    if name:
        return name.split()[0]

    return ""


def iter_msp_spectra(path: Path) -> Iterable[MspSpectrum]:
    metadata: Dict[str, str] = {}
    mz_values: List[float] = []
    in_peaks = False
    spectrum_id = 0

    def build_record() -> Optional[MspSpectrum]:
        nonlocal spectrum_id
        if not metadata and not mz_values:
            return None

        spectrum_id += 1
        return MspSpectrum(
            spectrum_id=spectrum_id,
            name=metadata.get("Name", ""),
            lipid_class=_extract_lipid_class(metadata),
            precursor_mz=metadata.get("PrecursorMZ", ""),
            precursor_type=metadata.get("Precursor_type", ""),
            ion_mode=metadata.get("Ion_mode", ""),
            spectrum_type=metadata.get("Spectrum_type", ""),
            source_db_id=metadata.get("DB#", ""),
            mz_values=mz_values.copy(),
        )

    with path.open("r", encoding="utf-8", errors="replace") as handle:
        for raw_line in handle:
            line = raw_line.strip()

            if not line:
                record = build_record()
                if record is not None:
                    yield record
                metadata.clear()
                mz_values.clear()
                in_peaks = False
                continue

            if in_peaks:
                parts = line.split()
                if len(parts) < 2:
                    continue
                try:
                    mz_values.append(float(parts[0]))
                except ValueError:
                    continue
                continue

            if line.lower().startswith("num peaks:"):
                in_peaks = True
                continue

            key, separator, value = line.partition(":")
            if separator:
                if key in metadata:
                    metadata[key] = f"{metadata[key]} | {value.strip()}"
                else:
                    metadata[key] = value.strip()

        record = build_record()
        if record is not None:
            yield record


def msp_to_spectra_csv(input_path: Path, output_path: Path, limit: Optional[int] = None) -> int:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = [
        "spectrum_id",
        "name",
        "lipid_class",
        "precursor_mz",
        "precursor_type",
        "ion_mode",
        "spectrum_type",
        "source_db_id",
        "num_peaks",
        "mz_values",
    ]

    count = 0
    with output_path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()

        for spectrum in iter_msp_spectra(input_path):
            if not spectrum.lipid_class or not spectrum.mz_values:
                continue

            writer.writerow(
                {
                    "spectrum_id": spectrum.spectrum_id,
                    "name": spectrum.name,
                    "lipid_class": spectrum.lipid_class,
                    "precursor_mz": spectrum.precursor_mz,
                    "precursor_type": spectrum.precursor_type,
                    "ion_mode": spectrum.ion_mode,
                    "spectrum_type": spectrum.spectrum_type,
                    "source_db_id": spectrum.source_db_id,
                    "num_peaks": spectrum.num_peaks,
                    "mz_values": " ".join(f"{mz:.10g}" for mz in spectrum.mz_values),
                }
            )
            count += 1

            if limit is not None and count >= limit:
                break

    return count


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Convert MoNA/LipidBlast MSP spectra to a training CSV with m/z-only feature values."
    )
    parser.add_argument(
        "--input",
        type=Path,
        default=Path("data/MoNA-export-LipidBlast.msp"),
        help="Path to the source MSP file.",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("data/processed/lipidblast_spectra.csv"),
        help="Path for the generated spectra CSV.",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help="Optional maximum number of spectra to export, useful for smoke tests.",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    count = msp_to_spectra_csv(args.input, args.output, args.limit)
    print(f"Converted {count} spectra to {args.output}")


if __name__ == "__main__":
    main()
