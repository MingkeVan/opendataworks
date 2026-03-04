package com.onedata.portal.dto.assistant;

import lombok.Data;

import java.util.List;

@Data
public class AssistantSessionDetailResponse {
    private AssistantSessionView session;
    private List<AssistantMessageView> messages;
    private List<AssistantRunView> runs;
    private List<AssistantArtifactView> artifacts;
}
