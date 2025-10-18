package com.onedata.portal.controller;

import com.onedata.portal.dto.Result;
import com.onedata.portal.dto.SqlTableMatchRequest;
import com.onedata.portal.dto.SqlTableMatchResponse;
import com.onedata.portal.service.SqlTableMatcherService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * SQL 表名匹配 Controller
 */
@RestController
@RequestMapping("/v1/sql-table-matcher")
@RequiredArgsConstructor
public class SqlTableMatcherController {

    private final SqlTableMatcherService sqlTableMatcherService;

    @PostMapping("/match")
    public Result<SqlTableMatchResponse> match(@RequestBody SqlTableMatchRequest request) {
        return Result.success(sqlTableMatcherService.match(request.getSql()));
    }

    @PostMapping("/tasks/{taskId}/bind")
    public Result<SqlTableMatchResponse> bindTask(@PathVariable Long taskId, @RequestBody SqlTableMatchRequest request) {
        return Result.success(sqlTableMatcherService.bindTaskRelations(taskId, request.getSql()));
    }
}
