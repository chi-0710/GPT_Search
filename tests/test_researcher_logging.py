import pytest

from backend.server.postgres_store import normalize_legacy_path


def test_research_logs_are_exposed_as_database_backed_legacy_paths():
    assert normalize_legacy_path(r"outputs\run.json") == "outputs/run.json"


@pytest.mark.parametrize("path", ["outputs/run.json", "/outputs/run.json"])
def test_legacy_log_path_normalization_is_stable(path):
    assert normalize_legacy_path(path) == "outputs/run.json"
