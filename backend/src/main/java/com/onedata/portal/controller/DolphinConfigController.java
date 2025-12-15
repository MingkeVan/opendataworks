package com.onedata.portal.controller;

import com.onedata.portal.annotation.RequireAuth;
import com.onedata.portal.dto.Result;
import com.onedata.portal.entity.DolphinConfig;
import com.onedata.portal.service.DolphinConfigService;
import com.onedata.portal.service.dolphin.DolphinOpenApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/settings/dolphin")
@RequiredArgsConstructor
public class DolphinConfigController {

    private final DolphinConfigService dolphinConfigService;
    private final DolphinOpenApiClient dolphinOpenApiClient;

    @GetMapping
    public Result<DolphinConfig> getConfig() {
        return Result.success(dolphinConfigService.getConfig());
    }

    @RequireAuth
    @PutMapping
    public Result<DolphinConfig> updateConfig(@RequestBody DolphinConfig config) {
        DolphinConfig updated = dolphinConfigService.updateConfig(config);
        // Clear project code dependency cache in apiClient if needed
        return Result.success(updated);
    }

    @RequireAuth
    @PostMapping("/test")
    public Result<Boolean> testConnection(@RequestBody DolphinConfig config) {
        // Use the API client to test connection with the provided config
        // Pass the temporary config to the client method
        boolean success = dolphinOpenApiClient.testConnection(config);
        return Result.success(success);
    }
}
