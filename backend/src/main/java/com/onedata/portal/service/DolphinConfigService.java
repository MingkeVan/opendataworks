package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.entity.DolphinConfig;
import com.onedata.portal.mapper.DolphinConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for managing DolphinScheduler configuration.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DolphinConfigService {

    private final DolphinConfigMapper dolphinConfigMapper;

    // Simple in-memory cache variable could serve as L1 cache if Spring Cache is
    // not configured
    // But here we rely on DB or basic queries. Since config changes rarely, we can
    // query DB.
    // To optimize, we can use a volatile field.
    private volatile DolphinConfig cachedConfig;

    /**
     * Get the active DolphinScheduler configuration.
     * Returns null if no active config exists.
     */
    public DolphinConfig getActiveConfig() {
        if (cachedConfig != null) {
            return cachedConfig;
        }

        // Find the active config (assuming there's only one active or we take the
        // latest)
        DolphinConfig config = dolphinConfigMapper.selectOne(new LambdaQueryWrapper<DolphinConfig>()
                .eq(DolphinConfig::getIsActive, true)
                .orderByDesc(DolphinConfig::getId)
                .last("LIMIT 1"));

        if (config != null) {
            cachedConfig = config;
        }
        return config;
    }

    /**
     * Get config for editing (can be same as active).
     */
    public DolphinConfig getConfig() {
        return getActiveConfig();
    }

    /**
     * Update the DolphinScheduler configuration.
     * Implementation: Update the existing active record or insert a new one if none
     * exists.
     * For simplicity, we maintain a single active record.
     */
    @Transactional
    public DolphinConfig updateConfig(DolphinConfig newConfig) {
        DolphinConfig current = getActiveConfig();

        if (current == null) {
            newConfig.setIsActive(true);
            dolphinConfigMapper.insert(newConfig);
            current = newConfig;
        } else {
            current.setUrl(newConfig.getUrl());
            current.setToken(newConfig.getToken());
            current.setProjectName(newConfig.getProjectName());
            current.setProjectCode(newConfig.getProjectCode());
            current.setTenantCode(newConfig.getTenantCode());
            current.setWorkerGroup(newConfig.getWorkerGroup());
            current.setExecutionType(newConfig.getExecutionType());
            dolphinConfigMapper.updateById(current);
        }

        // Update cache
        cachedConfig = current;
        log.info("Updated DolphinScheduler configuration");
        return current;
    }
}
