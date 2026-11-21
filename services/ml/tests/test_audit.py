from fastapi.testclient import TestClient

from cold_storage_ml.app import app


def test_lifecycle_alert_warning_and_invalid_request_are_audited(audit_collection):
    with TestClient(app) as client:
        assert client.post("/api/v1/alerts/current", json={
            "sensor_id": "SENSOR01", "temperature": 11, "threshold": 10,
        }).status_code == 200
        assert client.post("/api/v1/predictions/temperature", json={
            "history": ["raw input should stay out of audit records"],
        }).status_code == 422

    rows = [row for call in audit_collection.insert_many.call_args_list for row in call.args[0]]
    assert [row["eventType"] for row in rows] == [
        "ML_STARTED", "ML_ALERT_CHECK_SUCCEEDED", "ML_THRESHOLD_WARNING",
        "ML_INFERENCE_FAILED", "ML_STOPPING",
    ]
    assert all(row["actor"] == "service:ml" and row["timestamp"].tzinfo for row in rows)
    assert rows[2]["details"]["sensorId"] == "SENSOR01"
    assert rows[3]["outcome"] == "FAILURE"
    assert rows[3]["error"]["code"] == "HTTP_422"
    assert rows[3]["details"] == {}
