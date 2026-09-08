import pytest
from fastapi.testclient import TestClient
from runtime_gateway.app import app


@pytest.fixture
def client():
    return TestClient(app)


def test_gateway_health(client: TestClient):
    response = client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "ok"
    assert data["runtime_kind"] == "pi_agent_core"


def test_gateway_manifest(client: TestClient):
    response = client.get("/manifest")
    assert response.status_code == 200
    manifest = response.json()
    assert manifest["runtime_kind"] == "pi_agent_core"
    assert manifest["pi_agent_core_version"] == "0.85.1"
    assert manifest["pi_ai_version"] == "0.85.1"
    assert manifest["node_version"] == "22.19.0"
