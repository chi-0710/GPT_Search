from fastapi.testclient import TestClient

from backend.server.app import app


def test_root_returns_api_status_json():
    with TestClient(app) as client:
        response = client.get("/")

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("application/json")
    assert response.json() == {
        "service": "gpt-researcher-api",
        "status": "ok",
        "docs": "/docs",
    }


def test_legacy_frontend_routes_are_not_available():
    with TestClient(app) as client:
        assert client.get("/site/index.html").status_code == 404
        assert client.get("/static/styles.css").status_code == 404
