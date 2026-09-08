from __future__ import annotations

import logging
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from core.agent_runtime.contracts import AgentInteraction
from core.agent_runtime.client import RuntimePlaneClient

logger = logging.getLogger(__name__)


class InteractionService:
    """
    Manages human-in-the-loop interactions (permission, question, plan)
    and relays resolutions to the active Runtime Gateway.
    """

    def __init__(self, runtime_client: Optional[RuntimePlaneClient] = None):
        self.runtime_client = runtime_client
        self._interactions: Dict[str, AgentInteraction] = {}

    def register_interaction(self, interaction: AgentInteraction) -> AgentInteraction:
        self._interactions[interaction.interaction_id] = interaction
        return interaction

    def get_interaction(self, interaction_id: str) -> Optional[AgentInteraction]:
        return self._interactions.get(interaction_id)

    def list_pending(self, run_id: Optional[str] = None) -> List[AgentInteraction]:
        pending = [i for i in self._interactions.values() if i.status == "pending"]
        if run_id:
            pending = [i for i in pending if i.run_id == run_id]
        return pending

    async def resolve_interaction(
        self,
        interaction_id: str,
        resolution: Dict[str, Any],
    ) -> bool:
        interaction = self.get_interaction(interaction_id)
        if not interaction:
            logger.warning("Interaction %s not found", interaction_id)
            return False

        if interaction.status != "pending":
            logger.warning("Interaction %s already in status %s", interaction_id, interaction.status)
            return False

        interaction.status = "resolved"
        interaction.response = resolution
        interaction.updated_at = datetime.now(timezone.utc).isoformat()

        if self.runtime_client:
            return await self.runtime_client.resolve_interaction(
                run_id=interaction.run_id,
                interaction_id=interaction_id,
                resolution=resolution,
            )
        return True
