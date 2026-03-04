package com.onedata.portal.service.assistant;

import com.onedata.portal.dto.SqlQueryResponse;
import lombok.Data;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AssistantChartService {

    @Data
    public static class ChartBuildResult {
        private String chartType;
        private String reasoning;
        private Map<String, Object> chartSpec;
        private Map<String, Object> echartsOption;
    }

    public ChartBuildResult buildChart(SqlQueryResponse queryResponse, String preferredType) {
        if (queryResponse == null || CollectionUtils.isEmpty(queryResponse.getColumns()) || CollectionUtils.isEmpty(queryResponse.getRows())) {
            return null;
        }

        List<String> columns = queryResponse.getColumns();
        List<Map<String, Object>> rows = queryResponse.getRows();

        List<String> numericColumns = detectNumericColumns(columns, rows);
        List<String> timeColumns = detectTimeColumns(columns, rows);
        List<String> categoryColumns = detectCategoryColumns(columns, numericColumns, timeColumns);

        String chartType = decideChartType(preferredType, numericColumns, timeColumns, categoryColumns);
        String xField = !timeColumns.isEmpty() ? timeColumns.get(0)
            : (!categoryColumns.isEmpty() ? categoryColumns.get(0) : columns.get(0));

        List<String> yFields = new ArrayList<String>();
        if (!numericColumns.isEmpty()) {
            if ("pie".equals(chartType)) {
                yFields.add(numericColumns.get(0));
            } else if ("multi-line".equals(chartType)) {
                yFields.addAll(numericColumns.subList(0, Math.min(3, numericColumns.size())));
            } else {
                yFields.add(numericColumns.get(0));
            }
        }

        if (yFields.isEmpty()) {
            // 无数值列时回退到计数图
            yFields.add("__count");
        }

        Map<String, Object> chartSpec = buildChartSpec(chartType, xField, yFields, columns);
        Map<String, Object> option = buildEchartsOption(chartType, xField, yFields, rows);

        ChartBuildResult result = new ChartBuildResult();
        result.setChartType(chartType);
        result.setReasoning(buildReasoning(chartType, timeColumns, categoryColumns, numericColumns));
        result.setChartSpec(chartSpec);
        result.setEchartsOption(option);
        return result;
    }

    private List<String> detectNumericColumns(List<String> columns, List<Map<String, Object>> rows) {
        List<String> numericColumns = new ArrayList<String>();
        for (String column : columns) {
            int valid = 0;
            int total = 0;
            for (Map<String, Object> row : rows) {
                Object value = row.get(column);
                if (value == null) {
                    continue;
                }
                total++;
                if (value instanceof Number || isNumeric(String.valueOf(value))) {
                    valid++;
                }
            }
            if (total > 0 && valid >= Math.max(1, total / 2)) {
                numericColumns.add(column);
            }
        }
        return numericColumns;
    }

    private List<String> detectTimeColumns(List<String> columns, List<Map<String, Object>> rows) {
        List<String> timeColumns = new ArrayList<String>();
        for (String column : columns) {
            String lower = column == null ? "" : column.toLowerCase(Locale.ROOT);
            if (lower.contains("date") || lower.contains("time") || lower.endsWith("dt") || lower.contains("day")) {
                timeColumns.add(column);
                continue;
            }

            int sample = 0;
            int matched = 0;
            for (Map<String, Object> row : rows) {
                Object value = row.get(column);
                if (value == null) {
                    continue;
                }
                sample++;
                String text = String.valueOf(value);
                if (text.matches("^\\d{4}-\\d{2}-\\d{2}.*") || text.matches("^\\d{4}/\\d{2}/\\d{2}.*")) {
                    matched++;
                }
                if (sample >= 20) {
                    break;
                }
            }
            if (sample > 0 && matched >= Math.max(1, sample / 2)) {
                timeColumns.add(column);
            }
        }
        return timeColumns;
    }

    private List<String> detectCategoryColumns(List<String> columns, List<String> numericColumns, List<String> timeColumns) {
        Set<String> numeric = new LinkedHashSet<String>(numericColumns);
        Set<String> time = new LinkedHashSet<String>(timeColumns);
        List<String> category = new ArrayList<String>();
        for (String column : columns) {
            if (numeric.contains(column) || time.contains(column)) {
                continue;
            }
            category.add(column);
        }
        return category;
    }

    private String decideChartType(String preferredType,
                                   List<String> numericColumns,
                                   List<String> timeColumns,
                                   List<String> categoryColumns) {
        if (StringUtils.hasText(preferredType)) {
            String normalized = preferredType.toLowerCase(Locale.ROOT);
            if ("line".equals(normalized)
                || "bar".equals(normalized)
                || "pie".equals(normalized)
                || "multi-line".equals(normalized)) {
                return normalized;
            }
        }

        if (!timeColumns.isEmpty() && numericColumns.size() > 1) {
            return "multi-line";
        }
        if (!timeColumns.isEmpty() && !numericColumns.isEmpty()) {
            return "line";
        }
        if (!categoryColumns.isEmpty() && !numericColumns.isEmpty()) {
            String lower = numericColumns.get(0).toLowerCase(Locale.ROOT);
            if (lower.contains("ratio") || lower.contains("rate") || lower.contains("percent") || lower.contains("占比")) {
                return "pie";
            }
            return "bar";
        }
        return "bar";
    }

    private Map<String, Object> buildChartSpec(String chartType,
                                               String xField,
                                               List<String> yFields,
                                               List<String> columns) {
        Map<String, Object> spec = new LinkedHashMap<String, Object>();
        spec.put("chartType", chartType);
        spec.put("xField", xField);
        spec.put("yFields", yFields);
        spec.put("columns", columns);
        return spec;
    }

    private Map<String, Object> buildEchartsOption(String chartType,
                                                   String xField,
                                                   List<String> yFields,
                                                   List<Map<String, Object>> rows) {
        if ("pie".equals(chartType)) {
            return buildPieOption(xField, yFields.get(0), rows);
        }
        if ("multi-line".equals(chartType)) {
            return buildMultiLineOption(xField, yFields, rows);
        }
        if ("line".equals(chartType)) {
            return buildSingleSeriesOption("line", xField, yFields.get(0), rows);
        }
        return buildSingleSeriesOption("bar", xField, yFields.get(0), rows);
    }

    private Map<String, Object> buildSingleSeriesOption(String seriesType,
                                                        String xField,
                                                        String yField,
                                                        List<Map<String, Object>> rows) {
        List<Object> xData = new ArrayList<Object>();
        List<Object> yData = new ArrayList<Object>();
        int index = 1;
        for (Map<String, Object> row : rows) {
            Object x = row.get(xField);
            if (x == null) {
                x = "Row " + index;
            }
            xData.add(x);
            if ("__count".equals(yField)) {
                yData.add(1);
            } else {
                yData.add(toNumber(row.get(yField)));
            }
            index++;
        }

        Map<String, Object> option = new LinkedHashMap<String, Object>();
        option.put("tooltip", mapOf("trigger", "axis"));
        option.put("legend", mapOf("show", true));
        option.put("xAxis", mapOf("type", "category", "data", xData));
        option.put("yAxis", mapOf("type", "value"));

        Map<String, Object> series = new LinkedHashMap<String, Object>();
        series.put("name", "__count".equals(yField) ? "count" : yField);
        series.put("type", seriesType);
        series.put("data", yData);

        List<Map<String, Object>> seriesList = new ArrayList<Map<String, Object>>();
        seriesList.add(series);
        option.put("series", seriesList);
        return option;
    }

    private Map<String, Object> buildMultiLineOption(String xField,
                                                     List<String> yFields,
                                                     List<Map<String, Object>> rows) {
        List<Object> xData = new ArrayList<Object>();
        List<Map<String, Object>> seriesList = new ArrayList<Map<String, Object>>();
        List<List<Object>> dataBySeries = new ArrayList<List<Object>>();

        for (int i = 0; i < yFields.size(); i++) {
            dataBySeries.add(new ArrayList<Object>());
        }

        int rowNum = 1;
        for (Map<String, Object> row : rows) {
            Object x = row.get(xField);
            if (x == null) {
                x = "Row " + rowNum;
            }
            xData.add(x);
            for (int i = 0; i < yFields.size(); i++) {
                String yField = yFields.get(i);
                dataBySeries.get(i).add(toNumber(row.get(yField)));
            }
            rowNum++;
        }

        for (int i = 0; i < yFields.size(); i++) {
            Map<String, Object> series = new LinkedHashMap<String, Object>();
            series.put("name", yFields.get(i));
            series.put("type", "line");
            series.put("data", dataBySeries.get(i));
            seriesList.add(series);
        }

        Map<String, Object> option = new LinkedHashMap<String, Object>();
        option.put("tooltip", mapOf("trigger", "axis"));
        option.put("legend", mapOf("show", true));
        option.put("xAxis", mapOf("type", "category", "data", xData));
        option.put("yAxis", mapOf("type", "value"));
        option.put("series", seriesList);
        return option;
    }

    private Map<String, Object> buildPieOption(String nameField,
                                                String valueField,
                                                List<Map<String, Object>> rows) {
        List<Map<String, Object>> data = new ArrayList<Map<String, Object>>();
        int index = 1;
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            Object name = row.get(nameField);
            if (name == null) {
                name = "Item " + index;
            }
            item.put("name", name);
            item.put("value", "__count".equals(valueField) ? 1 : toNumber(row.get(valueField)));
            data.add(item);
            index++;
        }

        Map<String, Object> series = new LinkedHashMap<String, Object>();
        series.put("name", valueField);
        series.put("type", "pie");
        series.put("radius", "60%");
        series.put("data", data);

        List<Map<String, Object>> seriesList = new ArrayList<Map<String, Object>>();
        seriesList.add(series);

        Map<String, Object> option = new LinkedHashMap<String, Object>();
        option.put("tooltip", mapOf("trigger", "item"));
        option.put("legend", mapOf("show", true, "left", "center"));
        option.put("series", seriesList);
        return option;
    }

    private String buildReasoning(String chartType,
                                  List<String> timeColumns,
                                  List<String> categoryColumns,
                                  List<String> numericColumns) {
        if ("multi-line".equals(chartType)) {
            return "检测到时间维和多个数值指标，采用多序列趋势图展示指标变化。";
        }
        if ("line".equals(chartType)) {
            return "检测到时间维和数值指标，采用折线图展示趋势。";
        }
        if ("pie".equals(chartType)) {
            return "检测到类别与占比指标，采用饼图展示构成。";
        }
        if (!categoryColumns.isEmpty() && !numericColumns.isEmpty()) {
            return "检测到类别维和单指标，采用柱状图对比。";
        }
        if (!timeColumns.isEmpty()) {
            return "检测到时间字段，采用趋势图回退展示。";
        }
        return "采用默认柱状图展示结果。";
    }

    private Double toNumber(Object value) {
        if (value == null) {
            return 0D;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ex) {
            return 0D;
        }
    }

    private boolean isNumeric(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        return text.trim().matches("^-?\\d+(\\.\\d+)?$");
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < values.length - 1; i += 2) {
            String key = String.valueOf(values[i]);
            Object value = values[i + 1];
            map.put(key, value);
        }
        return map;
    }
}
