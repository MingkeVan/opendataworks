package com.onedata.portal.service.assistant.nl2lf;

import com.onedata.portal.dto.assistant.AssistantContextDTO;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class LogicalForm {
    private String version = "v1";
    private String intent;
    private String rawQuery;
    private AssistantContextDTO context;
    private String sqlDraft;

    private final List<Map<String, Object>> entities = new ArrayList<Map<String, Object>>();
    private final List<Map<String, Object>> dimensions = new ArrayList<Map<String, Object>>();
    private final List<Map<String, Object>> metrics = new ArrayList<Map<String, Object>>();
    private final List<Map<String, Object>> filters = new ArrayList<Map<String, Object>>();
    private final List<Map<String, Object>> joins = new ArrayList<Map<String, Object>>();
    private final List<String> groupBy = new ArrayList<String>();
    private final List<Map<String, Object>> orderBy = new ArrayList<Map<String, Object>>();

    private final Map<String, Object> chartIntent = new LinkedHashMap<String, Object>();
    private final Map<String, Object> taskIntent = new LinkedHashMap<String, Object>();
    private final Map<String, Object> clarification = new LinkedHashMap<String, Object>();
    private final Map<String, Object> confidence = new LinkedHashMap<String, Object>();
    private final Map<String, Object> trace = new LinkedHashMap<String, Object>();
}
