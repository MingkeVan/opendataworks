package com.onedata.portal.service.assistant.nl2lf;

import com.onedata.portal.dto.assistant.AssistantContextDTO;
import lombok.Data;

@Data
public class NlQueryInput {
    private String content;
    private String intent;
    private AssistantContextDTO context;
}
