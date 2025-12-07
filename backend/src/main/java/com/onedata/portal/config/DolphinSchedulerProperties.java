package com.onedata.portal.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for DolphinScheduler integration.
 */
@Data
@Component
@ConfigurationProperties(prefix = "dolphin")
public class DolphinSchedulerProperties {

    /** DolphinScheduler base URL (including /dolphinscheduler path) */
    private String url;

    /** DolphinScheduler API token */
    private String token;

    /** DolphinScheduler project name managed by the Python service. */
    private String projectName = "opendataworks";

    /** DolphinScheduler project code (if known) */
    private Long projectCode;

    /** Default tenant code forwarded to the dolphinscheduler-service. */
    private String tenantCode = "default";

    /** DolphinScheduler worker group assigned to shell tasks. */
    private String workerGroup = "default";

    /** Default execution type applied when ensuring or syncing workflows. */
    private String executionType = "PARALLEL";

    /** Optional workflow code for fixed deployments. */
    private Long workflowCode;
}
