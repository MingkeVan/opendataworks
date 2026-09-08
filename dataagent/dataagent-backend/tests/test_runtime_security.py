import pytest
import time
from core.agent_runtime.security import (
    sign_capability_token,
    verify_capability_token,
    SecurityError,
)


def test_sign_and_verify_valid_token():
    token = sign_capability_token(
        run_id="run-1",
        task_attempt_id="att-1",
        topic_id="topic-1",
        secret_key="my-secret-key",
    )
    assert token and "." in token
    payload = verify_capability_token(
        token=token,
        expected_run_id="run-1",
        expected_task_attempt_id="att-1",
        secret_key="my-secret-key",
    )
    assert payload["run_id"] == "run-1"
    assert payload["task_attempt_id"] == "att-1"
    assert payload["topic_id"] == "topic-1"


def test_verify_rejects_tampered_signature():
    token = sign_capability_token(
        run_id="run-1",
        task_attempt_id="att-1",
        topic_id="topic-1",
        secret_key="my-secret-key",
    )
    parts = token.split(".")
    tampered_sig = "d3Jvbmdfc2lnbmF0dXJlX2Zvcl90ZXN0aW5nXzEyMzQ1Ng"
    tampered_token = f"{parts[0]}.{tampered_sig}"
    with pytest.raises(SecurityError, match="signature mismatch"):
        verify_capability_token(
            token=tampered_token,
            expected_run_id="run-1",
            expected_task_attempt_id="att-1",
            secret_key="my-secret-key",
        )


def test_verify_rejects_expired_token():
    token = sign_capability_token(
        run_id="run-1",
        task_attempt_id="att-1",
        topic_id="topic-1",
        secret_key="my-secret-key",
        ttl_seconds=-10,  # Already expired
    )
    with pytest.raises(SecurityError, match="expired"):
        verify_capability_token(
            token=token,
            expected_run_id="run-1",
            expected_task_attempt_id="att-1",
            secret_key="my-secret-key",
        )


def test_verify_rejects_mismatched_run_binding():
    token = sign_capability_token(
        run_id="run-1",
        task_attempt_id="att-1",
        topic_id="topic-1",
        secret_key="my-secret-key",
    )
    with pytest.raises(SecurityError, match="Run ID mismatch"):
        verify_capability_token(
            token=token,
            expected_run_id="run-2",  # Different run
            expected_task_attempt_id="att-1",
            secret_key="my-secret-key",
        )
