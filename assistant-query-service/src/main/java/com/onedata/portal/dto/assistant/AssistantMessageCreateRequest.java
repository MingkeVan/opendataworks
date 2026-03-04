package com.onedata.portal.dto.assistant;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;

@Data
public class AssistantMessageCreateRequest {
    @NotBlank(message = "消息内容不能为空")
    private String content;
    private AssistantContextDTO context;
}
