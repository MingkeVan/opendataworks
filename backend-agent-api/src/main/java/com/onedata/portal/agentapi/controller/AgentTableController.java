package com.onedata.portal.agentapi.controller;

import com.onedata.portal.agentapi.dto.AgentTableCreateRequest;
import com.onedata.portal.agentapi.service.AgentTableService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/ai/table")
public class AgentTableController {

    private final AgentTableService agentTableService;

    @PostMapping("/preview")
    public Object preview(
            @Validated @RequestBody AgentTableCreateRequest request,
            @RequestHeader(value = "X-Agent-Operator", required = false) String operator) {
        return agentTableService.previewCreateTable(request, operator);
    }

    @PostMapping
    public Object create(
            @Validated @RequestBody AgentTableCreateRequest request,
            @RequestHeader(value = "X-Agent-Operator", required = false) String operator) {
        return agentTableService.createTable(request, operator);
    }
}
