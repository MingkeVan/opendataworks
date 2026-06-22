package com.onedata.portal.service.table;

import com.onedata.portal.util.DatasourceType;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Registry for datasource-engine table handlers.
 */
@Service
public class TableEngineHandlerRegistry {

    private final Map<DatasourceType, TableEngineHandler> handlers;

    public TableEngineHandlerRegistry(List<TableEngineHandler> handlerList) {
        EnumMap<DatasourceType, TableEngineHandler> registry = new EnumMap<>(DatasourceType.class);
        for (TableEngineHandler handler : handlerList) {
            TableEngineHandler previous = registry.put(handler.sourceType(), handler);
            if (previous != null) {
                throw new IllegalStateException("Duplicate table engine handler for " + handler.sourceType());
            }
        }
        this.handlers = registry;
    }

    public TableEngineHandler require(DatasourceType sourceType) {
        TableEngineHandler handler = handlers.get(sourceType);
        if (handler == null) {
            throw new RuntimeException("暂不支持数据源类型: " + sourceType.name());
        }
        return handler;
    }

    public TableEngineHandler find(DatasourceType sourceType) {
        return handlers.get(sourceType);
    }
}
