from fastapi.testclient import TestClient

from backend.server.app import app
from backend.server import app as app_module
from unittest.mock import AsyncMock
import pytest


@pytest.fixture(autouse=True)
def mock_readiness(monkeypatch):
    monkeypatch.setattr(app_module, "readiness_status", AsyncMock(return_value={"ready": True}))
    monkeypatch.setattr(app_module, "dispose_engine", AsyncMock())


def test_root_returns_api_status_json():
    with TestClient(app) as client:
        response = client.get("/")

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("application/json")
    assert response.json() == {
        "service": "researchrunner-api",
        "status": "ok",
        "docs": "/docs",
    }


def test_legacy_frontend_routes_are_not_available():
    with TestClient(app) as client:
        assert client.get("/site/index.html").status_code == 404
        assert client.get("/static/styles.css").status_code == 404


def test_application_routes_are_unique():
    route_keys = [
        (method, route.path)
        for route in app.routes
        for method in getattr(route, "methods", set())
    ]

    assert len(route_keys) == len(set(route_keys))
