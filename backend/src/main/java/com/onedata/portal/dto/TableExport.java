package com.onedata.portal.dto;

/**
 * 表数据导出结果。
 *
 * <p>承载导出字节、Content-Type、文件扩展名与实际表名，由服务层产出数据，
 * 控制器据此组装文件名与 HTTP 响应头。
 */
public class TableExport {

    private final byte[] data;
    private final String contentType;
    private final String fileExtension;
    private final String tableName;

    public TableExport(byte[] data, String contentType, String fileExtension, String tableName) {
        this.data = data;
        this.contentType = contentType;
        this.fileExtension = fileExtension;
        this.tableName = tableName;
    }

    public byte[] getData() {
        return data;
    }

    public String getContentType() {
        return contentType;
    }

    public String getFileExtension() {
        return fileExtension;
    }

    public String getTableName() {
        return tableName;
    }
}
