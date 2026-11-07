from fastapi.testclient import TestClient

from cold_storage_ml.app import app


def test_health_endpoint_returns_service_identity():
    with TestClient(app) as client:
        response = client.get("/api/v1/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "service": "ml"}


def test_predictions_reject_invalid_payload():
    with TestClient(app) as client:
        response = client.post(
            "/api/v1/predictions/temperature",
            json={}
        )

    assert response.status_code == 422