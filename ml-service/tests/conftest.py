"""Shared pytest fixtures for the ml-service test suite.

`ml-service` itself is put on sys.path by the `pythonpath` setting in the repository
root `pytest.ini`, so test modules import `worker`, `ml_core` and `helpers` directly.
"""

from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]


@pytest.fixture(scope="session")
def repo_root() -> Path:
    return REPO_ROOT


@pytest.fixture(scope="session")
def testdata_dir() -> Path:
    return REPO_ROOT / "testdata"


@pytest.fixture(scope="session")
def sample_mzml(testdata_dir: Path) -> Path:
    """The committed fixture spectrum, from `python3 testdata/generate_fixtures.py`."""
    path = testdata_dir / "sample.mzML"
    if not path.exists():
        pytest.skip(f"{path} is missing - run python3 testdata/generate_fixtures.py")
    return path
