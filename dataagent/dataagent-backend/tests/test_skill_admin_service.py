from __future__ import annotations

import sys
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from core.skill_admin_service import _merge_provider_settings


def test_merge_provider_settings_can_reenable_provider_with_models():
    current = {
        "anyrouter": {
            "provider_id": "anyrouter",
            "auth_token": "existing-token",
            "base_url": "https://a-ocnfniawgw.cn-shanghai.fcapp.run",
            "enabled_models": [],
            "custom_models": [],
            "enabled": False,
            "validation_status": "unverified",
            "validation_message": "请至少开启一个模型",
        }
    }
    patch = {
        "anyrouter": {
            "provider_id": "anyrouter",
            "auth_token": "existing-token",
            "base_url": "https://a-ocnfniawgw.cn-shanghai.fcapp.run",
            "enabled_models": ["claude-opus-4-6"],
            "custom_models": [],
        }
    }

    merged = _merge_provider_settings(
        current,
        patch,
        legacy_payload={"provider_id": "anyrouter", "model": "claude-opus-4-6"},
    )

    provider = merged["anyrouter"]
    assert provider["enabled_models"] == ["claude-opus-4-6"]
    assert provider["validation_status"] == "verified"
    assert provider["enabled"] is True
