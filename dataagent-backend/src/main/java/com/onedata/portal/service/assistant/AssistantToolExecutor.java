package com.onedata.portal.service.assistant;

import com.onedata.portal.dto.SqlAnalyzeRequest;
import com.onedata.portal.dto.SqlAnalyzeResponse;
import com.onedata.portal.dto.SqlQueryRequest;
import com.onedata.portal.dto.SqlQueryResponse;
import com.onedata.portal.dto.StopQueryRequest;
import com.onedata.portal.dto.assistant.AssistantContextDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AssistantToolExecutor {

    private static final int LIMIT_VALIDATION = 1;
    private static final int LIMIT_TEXT_ANSWER = 500;
    private static final int LIMIT_BI_SAMPLING = 2000;
    private static final int LIMIT_MANUAL_DEFAULT = 200;
    private static final int LIMIT_MAX = 10000;

    private final ToolGatewayClient toolGatewayClient;

    public SqlAnalyzeResponse analyzeSql(String runId, String sql, AssistantContextDTO context) {
        SqlAnalyzeRequest request = new SqlAnalyzeRequest();
        request.setClientQueryId(runId);
        request.setClusterId(resolveSourceId(context));
        request.setDatabase(resolveDatabase(context));
        request.setSql(sql);
        return toolGatewayClient.postForData("/v1/data-query/analyze", request, SqlAnalyzeResponse.class);
    }

    public SqlQueryResponse executeSql(String runId,
                                       String sql,
                                       AssistantContextDTO context,
                                       Integer forcedLimit,
                                       List<SqlAnalyzeResponse.ConfirmChallenge> challenges,
                                       boolean autoConfirm) {
        SqlQueryRequest request = new SqlQueryRequest();
        request.setClientQueryId(runId);
        request.setClusterId(resolveSourceId(context));
        request.setDatabase(resolveDatabase(context));
        request.setSql(sql);
        request.setLimit(resolveLimit(context, forcedLimit));

        if (autoConfirm && !CollectionUtils.isEmpty(challenges)) {
            List<SqlQueryRequest.SqlConfirmation> confirmations = new ArrayList<SqlQueryRequest.SqlConfirmation>();
            for (SqlAnalyzeResponse.ConfirmChallenge challenge : challenges) {
                SqlQueryRequest.SqlConfirmation confirmation = new SqlQueryRequest.SqlConfirmation();
                confirmation.setStatementIndex(challenge.getStatementIndex());
                confirmation.setTargetObject(challenge.getTargetObject());
                confirmation.setInputText(challenge.getTargetObject());
                confirmation.setConfirmToken(challenge.getConfirmToken());
                confirmations.add(confirmation);
            }
            request.setConfirmations(confirmations);
        }

        return toolGatewayClient.postForData("/v1/data-query/execute", request, SqlQueryResponse.class);
    }

    public Integer resolveLimit(AssistantContextDTO context, Integer forcedLimit) {
        if (forcedLimit != null && forcedLimit > 0) {
            return Math.min(forcedLimit, LIMIT_MAX);
        }
        if (context == null) {
            return LIMIT_TEXT_ANSWER;
        }
        if (context.getManualLimit() != null && context.getManualLimit() > 0) {
            return Math.min(context.getManualLimit(), LIMIT_MAX);
        }
        String profile = context.getLimitProfile();
        if (!StringUtils.hasText(profile)) {
            return LIMIT_TEXT_ANSWER;
        }
        if ("validation".equalsIgnoreCase(profile)) {
            return LIMIT_VALIDATION;
        }
        if ("bi_sampling".equalsIgnoreCase(profile)) {
            return LIMIT_BI_SAMPLING;
        }
        if ("manual_execute".equalsIgnoreCase(profile)) {
            return LIMIT_MANUAL_DEFAULT;
        }
        return LIMIT_TEXT_ANSWER;
    }

    public void stopRunQuery(String userId, String runId) {
        StopQueryRequest request = new StopQueryRequest();
        request.setClientQueryId(runId);
        Boolean stopped = toolGatewayClient.postForData("/v1/data-query/stop", request, Boolean.class);
        if (stopped == null || !stopped) {
            throw new RuntimeException("停止查询失败");
        }
    }

    private Long resolveSourceId(AssistantContextDTO context) {
        return context == null ? null : context.getSourceId();
    }

    private String resolveDatabase(AssistantContextDTO context) {
        if (context == null || !StringUtils.hasText(context.getDatabase())) {
            throw new IllegalArgumentException("请先选择数据库上下文");
        }
        return context.getDatabase();
    }
}
