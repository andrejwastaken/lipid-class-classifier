import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from helpers.msp_converter import iter_msp_spectra, msp_to_spectra_csv


def test_iter_msp_spectra_extracts_class_and_mz_values(tmp_path: Path) -> None:
    msp_path = tmp_path / "sample.msp"
    msp_path.write_text(
        "\n".join(
            [
                "Name: SQDG 54:0",
                "Synon: first",
                "Synon: second",
                'Comments: "compound class=SQDG" "retention time=12.1"',
                "PrecursorMZ: 1101.85788",
                "Precursor_type: [M-H]-",
                "Ion_mode: N",
                "Spectrum_type: MS2",
                "DB#: LipidBlast485796",
                "Num Peaks: 2",
                "225.0069 20.020020",
                "1101.858 100.000000",
                "",
            ]
        ),
        encoding="utf-8",
    )

    spectra = list(iter_msp_spectra(msp_path))

    assert len(spectra) == 1
    assert spectra[0].lipid_class == "SQDG"
    assert spectra[0].mz_values == [225.0069, 1101.858]
    assert spectra[0].num_peaks == 2


def test_msp_to_spectra_csv_writes_mz_only_feature_column(tmp_path: Path) -> None:
    msp_path = tmp_path / "sample.msp"
    csv_path = tmp_path / "out.csv"
    msp_path.write_text(
        "\n".join(
            [
                "Name: PC 16:0",
                'Comments: "compound class=PC"',
                "Num Peaks: 2",
                "184.0733 999",
                "760.5851 100",
                "",
            ]
        ),
        encoding="utf-8",
    )

    count = msp_to_spectra_csv(msp_path, csv_path)

    assert count == 1
    lines = csv_path.read_text(encoding="utf-8").splitlines()
    assert lines[0].endswith("num_peaks,mz_values")
    assert lines[1].endswith('2,184.0733 760.5851')
