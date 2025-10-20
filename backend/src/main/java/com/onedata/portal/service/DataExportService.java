package com.onedata.portal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据导出服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataExportService {

    private final DorisConnectionService dorisConnectionService;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 导出为CSV格式
     */
    public byte[] exportToCsv(Long clusterId, String database, String tableName, int limit) {
        List<Map<String, Object>> data = dorisConnectionService.previewTableData(clusterId, database, tableName, limit);

        if (data.isEmpty()) {
            return new byte[0];
        }

        StringBuilder csv = new StringBuilder();

        // 写入表头
        List<String> columns = new ArrayList<>(data.get(0).keySet());
        csv.append(String.join(",", columns.stream()
                .map(this::escapeCsvValue)
                .toArray(String[]::new)))
           .append("\n");

        // 写入数据行
        for (Map<String, Object> row : data) {
            csv.append(String.join(",", columns.stream()
                    .map(col -> escapeCsvValue(formatValue(row.get(col))))
                    .toArray(String[]::new)))
               .append("\n");
        }

        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 导出为Excel格式
     */
    public byte[] exportToExcel(Long clusterId, String database, String tableName, int limit) throws IOException {
        List<Map<String, Object>> data = dorisConnectionService.previewTableData(clusterId, database, tableName, limit);

        if (data.isEmpty()) {
            return new byte[0];
        }

        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet(tableName);

            // 创建表头样式
            CellStyle headerStyle = createHeaderStyle(workbook);
            CellStyle dateStyle = createDateStyle(workbook);
            CellStyle dateTimeStyle = createDateTimeStyle(workbook);

            // 写入表头
            List<String> columns = new ArrayList<>(data.get(0).keySet());
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(columns.get(i));
                cell.setCellStyle(headerStyle);
            }

            // 写入数据行
            for (int i = 0; i < data.size(); i++) {
                Row row = sheet.createRow(i + 1);
                Map<String, Object> rowData = data.get(i);

                for (int j = 0; j < columns.size(); j++) {
                    Cell cell = row.createCell(j);
                    Object value = rowData.get(columns.get(j));
                    setCellValue(cell, value, dateStyle, dateTimeStyle);
                }
            }

            // 自动调整列宽
            for (int i = 0; i < columns.size(); i++) {
                sheet.autoSizeColumn(i);
                // 设置最大列宽
                if (sheet.getColumnWidth(i) > 15000) {
                    sheet.setColumnWidth(i, 15000);
                }
            }

            workbook.write(out);
            return out.toByteArray();
        }
    }

    /**
     * 导出为JSON格式
     */
    public byte[] exportToJson(Long clusterId, String database, String tableName, int limit) throws IOException {
        List<Map<String, Object>> data = dorisConnectionService.previewTableData(clusterId, database, tableName, limit);
        return objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(data);
    }

    /**
     * 转义CSV值（处理逗号、引号、换行符）
     */
    private String escapeCsvValue(String value) {
        if (value == null) {
            return "";
        }

        // 如果包含逗号、引号或换行符，需要用引号包围并转义引号
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }

        return value;
    }

    /**
     * 格式化值为字符串
     */
    private String formatValue(Object value) {
        if (value == null) {
            return "";
        }

        if (value instanceof LocalDateTime) {
            return ((LocalDateTime) value).format(DATETIME_FORMATTER);
        }

        if (value instanceof LocalDate) {
            return ((LocalDate) value).format(DATE_FORMATTER);
        }

        return String.valueOf(value);
    }

    /**
     * 设置单元格值（根据类型）
     */
    private void setCellValue(Cell cell, Object value, CellStyle dateStyle, CellStyle dateTimeStyle) {
        if (value == null) {
            cell.setCellValue("");
            return;
        }

        if (value instanceof Number) {
            cell.setCellValue(((Number) value).doubleValue());
        } else if (value instanceof Boolean) {
            cell.setCellValue((Boolean) value);
        } else if (value instanceof LocalDateTime) {
            cell.setCellValue(((LocalDateTime) value).format(DATETIME_FORMATTER));
            cell.setCellStyle(dateTimeStyle);
        } else if (value instanceof LocalDate) {
            cell.setCellValue(((LocalDate) value).format(DATE_FORMATTER));
            cell.setCellStyle(dateStyle);
        } else if (value instanceof java.sql.Timestamp) {
            cell.setCellValue(value.toString());
            cell.setCellStyle(dateTimeStyle);
        } else if (value instanceof java.sql.Date) {
            cell.setCellValue(value.toString());
            cell.setCellStyle(dateStyle);
        } else {
            cell.setCellValue(String.valueOf(value));
        }
    }

    /**
     * 创建表头样式
     */
    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 11);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setAlignment(HorizontalAlignment.CENTER);
        return style;
    }

    /**
     * 创建日期样式
     */
    private CellStyle createDateStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
        return style;
    }

    /**
     * 创建日期时间样式
     */
    private CellStyle createDateTimeStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
        return style;
    }
}
