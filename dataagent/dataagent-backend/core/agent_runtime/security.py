from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time
from typing import Any, Dict, Optional


DEFAULT_CAPABILITY_AUDIENCE = "runtime-gateway"
DEFAULT_TOKEN_TTL_SECONDS = 3600  # 1 hour


class SecurityError(Exception):
    """Raised when security verification fails."""
    pass


def sign_capability_token(
    run_id: str,
    task_attempt_id: str,
    topic_id: str,
    purpose: str = "interactive",
    secret_key: str = "dataagent-default-insecure-secret",
    audience: str = DEFAULT_CAPABILITY_AUDIENCE,
    ttl_seconds: int = DEFAULT_TOKEN_TTL_SECONDS,
) -> str:
    """
    Generate an HMAC-SHA256 signed capability token for a run attempt.
    """
    now = int(time.time())
    payload = {
        "run_id": run_id,
        "task_attempt_id": task_attempt_id,
        "topic_id": topic_id,
        "purpose": purpose,
        "aud": audience,
        "iat": now,
        "exp": now + ttl_seconds,
    }
    payload_json = json.dumps(payload, sort_keys=True, separators=(",", ":"))
    payload_b64 = base64.urlsafe_b64encode(payload_json.encode("utf-8")).decode("ascii").rstrip("=")
    
    sig = hmac.new(
        secret_key.encode("utf-8"),
        payload_b64.encode("ascii"),
        hashlib.sha256,
    ).digest()
    sig_b64 = base64.urlsafe_b64encode(sig).decode("ascii").rstrip("=")
    
    return f"{payload_b64}.{sig_b64}"


def verify_capability_token(
    token: str,
    expected_run_id: str,
    expected_task_attempt_id: str,
    secret_key: str = "dataagent-default-insecure-secret",
    expected_audience: str = DEFAULT_CAPABILITY_AUDIENCE,
) -> Dict[str, Any]:
    """
    Verify signature, expiration, audience, and run binding of a capability token.
    """
    if not token or "." not in token:
        raise SecurityError("Malformed capability token")
    
    parts = token.split(".", 1)
    if len(parts) != 2:
        raise SecurityError("Malformed capability token structure")
    
    payload_b64, sig_b64 = parts
    
    # Verify HMAC
    expected_sig = hmac.new(
        secret_key.encode("utf-8"),
        payload_b64.encode("ascii"),
        hashlib.sha256,
    ).digest()
    
    # Pad base64 for decoding
    padding = "=" * ((4 - len(sig_b64) % 4) % 4)
    try:
        actual_sig = base64.urlsafe_b64decode(sig_b64 + padding)
    except Exception as e:
        raise SecurityError(f"Invalid base64 signature: {e}")
        
    if not hmac.compare_digest(expected_sig, actual_sig):
        raise SecurityError("Capability token signature mismatch")
        
    # Decode and parse payload
    padding_payload = "=" * ((4 - len(payload_b64) % 4) % 4)
    try:
        raw_json = base64.urlsafe_b64decode(payload_b64 + padding_payload).decode("utf-8")
        payload = json.loads(raw_json)
    except Exception as e:
        raise SecurityError(f"Failed to parse capability payload: {e}")
        
    # Verify expiration
    now = int(time.time())
    if payload.get("exp", 0) < now:
        raise SecurityError("Capability token has expired")
        
    # Verify audience
    if payload.get("aud") != expected_audience:
        raise SecurityError(f"Audience mismatch: expected {expected_audience}, got {payload.get('aud')}")
        
    # Verify run_id and task_attempt_id binding
    if payload.get("run_id") != expected_run_id:
        raise SecurityError(f"Run ID mismatch: expected {expected_run_id}, got {payload.get('run_id')}")
        
    if payload.get("task_attempt_id") != expected_task_attempt_id:
        raise SecurityError(
            f"Task attempt ID mismatch: expected {expected_task_attempt_id}, got {payload.get('task_attempt_id')}"
        )
        
    return payload
