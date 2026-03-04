package com.onedata.portal.service.assistant.nl2lf;

import com.onedata.portal.service.assistant.KnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class DefaultLfGroundingService implements LfGroundingService {

    private static final Pattern FROM_JOIN_PATTERN = Pattern.compile(
        "(?i)\\b(?:from|join)\\s+([a-zA-Z0-9_`]+(?:\\.[a-zA-Z0-9_`]+)?)"
    );

    private final KnowledgeService knowledgeService;

    @Override
    public LogicalForm ground(LogicalForm draft, MetadataContext metadataContext) {
        if (draft == null) {
            return null;
        }

        draft.getEntities().clear();
        draft.getJoins().clear();

        Set<String> tableRefs = extractTableRefs(draft.getSqlDraft());
        List<KnowledgeService.MatchedTable> matchedTables = knowledgeService.matchTables(
            tableRefs,
            metadataContext == null ? null : metadataContext.getDatabase()
        );

        if (!matchedTables.isEmpty()) {
            for (KnowledgeService.MatchedTable mt : matchedTables) {
                Map<String, Object> entity = new LinkedHashMap<String, Object>();
                entity.put("role", "input");
                entity.put("rawName", mt.getRawRef());
                entity.put("tableId", mt.getTableId());
                entity.put("dbName", mt.getDbName());
                entity.put("tableName", mt.getTableName());
                entity.put("tableComment", mt.getTableComment());
                entity.put("matchStatus", "matched");
                entity.put("confidence", 0.9D);
                draft.getEntities().add(entity);
            }

            List<Map<String, Object>> lineageJoins = knowledgeService.buildLineageJoins(matchedTables);
            draft.getJoins().addAll(lineageJoins);

            List<String> unmatched = findUnmatchedRefs(tableRefs, matchedTables);
            boolean needClarification = !unmatched.isEmpty();
            draft.getClarification().put("required", needClarification);
            draft.getClarification().put("unmatched", unmatched);
            draft.getClarification().put("ambiguous", new ArrayList<String>());
            draft.getClarification().put("lineageMissing", matchedTables.size() > 1 && lineageJoins.isEmpty());

            draft.getTrace().put("grounding", "aq-metadata-lineage");
            draft.getTrace().put("metadataSourceId", metadataContext == null ? null : metadataContext.getSourceId());
            draft.getTrace().put("metadataDatabase", metadataContext == null ? null : metadataContext.getDatabase());
            draft.getTrace().put("matchedTableCount", matchedTables.size());
            draft.getTrace().put("lineageEdgeCount", lineageJoins.size());
            draft.getTrace().put("lineageUsed", !lineageJoins.isEmpty());
            return draft;
        }

        for (String tableRef : tableRefs) {
            Map<String, Object> entity = new LinkedHashMap<String, Object>();
            entity.put("role", "input");
            entity.put("rawName", tableRef);
            String[] split = splitTableRef(tableRef);
            entity.put("dbName", split[0]);
            entity.put("tableName", split[1]);
            entity.put("matchStatus", "unmatched");
            entity.put("confidence", 0.3D);
            draft.getEntities().add(entity);
        }

        draft.getClarification().put("required", !tableRefs.isEmpty());
        draft.getClarification().put("unmatched", new ArrayList<String>(tableRefs));
        draft.getClarification().put("ambiguous", new ArrayList<String>());
        draft.getClarification().put("lineageMissing", false);

        draft.getTrace().put("grounding", "aqs-fallback");
        draft.getTrace().put("metadataSourceId", metadataContext == null ? null : metadataContext.getSourceId());
        draft.getTrace().put("metadataDatabase", metadataContext == null ? null : metadataContext.getDatabase());
        draft.getTrace().put("matchedTableCount", 0);
        draft.getTrace().put("lineageUsed", false);
        return draft;
    }

    private List<String> findUnmatchedRefs(Set<String> tableRefs, List<KnowledgeService.MatchedTable> matchedTables) {
        Set<String> matched = new LinkedHashSet<String>();
        for (KnowledgeService.MatchedTable mt : matchedTables) {
            if (StringUtils.hasText(mt.getRawRef())) {
                matched.add(mt.getRawRef().toLowerCase(Locale.ROOT));
            }
        }
        List<String> unmatched = new ArrayList<String>();
        for (String ref : tableRefs) {
            if (!matched.contains(ref.toLowerCase(Locale.ROOT))) {
                unmatched.add(ref);
            }
        }
        return unmatched;
    }

    private Set<String> extractTableRefs(String sql) {
        Set<String> refs = new LinkedHashSet<String>();
        if (!StringUtils.hasText(sql)) {
            return refs;
        }
        Matcher matcher = FROM_JOIN_PATTERN.matcher(sql);
        while (matcher.find()) {
            String ref = normalizeRef(matcher.group(1));
            if (StringUtils.hasText(ref)) {
                refs.add(ref);
            }
        }
        return refs;
    }

    private String normalizeRef(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        return raw.replace("`", "").trim().toLowerCase(Locale.ROOT);
    }

    private String[] splitTableRef(String tableRef) {
        if (!StringUtils.hasText(tableRef)) {
            return new String[]{null, null};
        }
        String[] split = tableRef.split("\\.", 2);
        if (split.length == 2) {
            return split;
        }
        return new String[]{null, split[0]};
    }
}
