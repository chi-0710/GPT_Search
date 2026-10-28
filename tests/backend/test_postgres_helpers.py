from backend.server.postgres_store import normalize_legacy_path, redact_options


def test_normalize_legacy_path_handles_windows_and_leading_slashes():
    assert normalize_legacy_path(r"\outputs\nested\report.pdf") == "outputs/nested/report.pdf"


def test_request_options_redact_nested_secrets():
    value = {
        "headers": {"authorization": "Bearer secret"},
        "OPENAI_API_KEY": "secret",
        "mcp": [{"token": "secret", "name": "safe"}],
    }

    assert redact_options(value) == {
        "headers": {"authorization": "[REDACTED]"},
        "OPENAI_API_KEY": "[REDACTED]",
        "mcp": [{"token": "[REDACTED]", "name": "safe"}],
    }
