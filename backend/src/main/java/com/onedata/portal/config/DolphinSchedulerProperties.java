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

    /** dolphinscheduler-service base url, for example: http://localhost:8081 */
    private String serviceUrl;

    /** DolphinScheduler Web UI base URL, for example: http://localhost:12345/dolphinscheduler */
    private String webuiUrl;

    /** DolphinScheduler project name managed by the Python service. */
    private String projectName = "data-portal";

    /** Unified workflow name that aggregates data portal tasks. */
    private String workflowName = "data-portal-pipeline";

    /** Default tenant code forwarded to the dolphinscheduler-service. */
    private String tenantCode = "default";

    /** DolphinScheduler worker group assigned to shell tasks. */
    private String workerGroup = "default";

    /** Default execution type applied when ensuring or syncing workflows. */
    private String executionType = "PARALLEL";

    /** Optional workflow code for fixed deployments. */
    private Long workflowCode;
}
