package com.onedata.portal.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.onedata.portal.dto.Result;
import com.onedata.portal.dto.SqlQueryRequest;
import com.onedata.portal.dto.SqlQueryResponse;
import com.onedata.portal.entity.DataQueryHistory;
import com.onedata.portal.service.DataQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 数据查询 Controller
 */
@RestController
@RequestMapping("/v1/data-query")
@RequiredArgsConstructor
public class DataQueryController {

    private final DataQueryService dataQueryService;

    @PostMapping("/execute")
    public Result<SqlQueryResponse> execute(@Validated @RequestBody SqlQueryRequest request) {
        return Result.success(dataQueryService.executeQuery(request));
    }

    @GetMapping("/history")
    public Result<Page<DataQueryHistory>> history(@RequestParam(value = "pageNum", required = false) Integer pageNum,
                                                  @RequestParam(value = "pageSize", required = false) Integer pageSize,
                                                  @RequestParam(value = "clusterId", required = false) Long clusterId,
                                                  @RequestParam(value = "database", required = false) String database) {
        return Result.success(dataQueryService.listHistory(pageNum, pageSize, clusterId, database));
    }
}
