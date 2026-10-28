from scripts.migrate_legacy_storage import (
    normalize_path,
    orphan_group_key,
    referenced_outputs,
)


def test_referenced_outputs_accept_windows_paths_and_missing_values():
    reports = {
        "legacy-id": {
            "orderedData": [
                {
                    "type": "path",
                    "output": {
                        "docx": r"outputs\legacy.docx",
                        "pdf": "",
                    },
                }
            ]
        }
    }

    assert referenced_outputs(reports) == {
        "outputs/legacy.docx": "legacy-id"
    }


def test_normalize_path_removes_leading_and_duplicate_separators():
    assert normalize_path(r"\outputs\\nested\file.md") == "outputs/nested/file.md"
    assert normalize_path(r"F:\old-install\outputs\nested\file.md") == "outputs/nested/file.md"


def test_orphan_variants_share_one_group_key():
    assert orphan_group_key(r"outputs\archive\report.pdf") == "outputs/archive/report"
    assert orphan_group_key("outputs/archive/report.docx") == "outputs/archive/report"
