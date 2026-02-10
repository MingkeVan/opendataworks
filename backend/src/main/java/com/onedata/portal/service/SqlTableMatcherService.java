package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.dto.SqlTableAnalyzeResponse;
import com.onedata.portal.dto.SqlTableMatchResponse;
import com.onedata.portal.dto.SqlTableMatchResponse.MatchedTable;
import com.onedata.portal.entity.DataLineage;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.entity.TableTaskRelation;
import com.onedata.portal.mapper.DataLineageMapper;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * SQL 表名匹配服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqlTableMatcherService {

    private static final Pattern INPUT_CONTEXT_PATTERN = Pattern.compile(
        "(?i)\\b(?:FROM|JOIN|USING)\\s+((?:`?[a-z0-9_]+`?\\s*\\.\\s*)?`?[a-z0-9_]+`?)"
    );

    private static final Pattern OUTPUT_CONTEXT_PATTERN = Pattern.compile(
        "(?i)\\b(?:INSERT\\s+(?:INTO|OVERWRITE(?:\\s+TABLE)?(?:\\s+INTO)?)|REPLACE\\s+INTO|MERGE\\s+INTO|CREATE\\s+TABLE(?:\\s+IF\\s+NOT\\s+EXISTS)?)\\s+((?:`?[a-z0-9_]+`?\\s*\\.\\s*)?`?[a-z0-9_]+`?)"
    );

    private static final Pattern CTE_ALIAS_PATTERN = Pattern.compile(
        "(?i)(?:\\bWITH\\b|,)\\s*`?([a-z0-9_]+)`?\\s+AS\\s*\\("
    );

    private static final Set<String> OUTPUT_STATEMENT_TYPES = new HashSet<>(
        Arrays.asList("Insert", "Replace", "Merge", "CreateTable")
    );

    private final DataTableMapper dataTableMapper;
    private final TableTaskRelationMapper tableTaskRelationMapper;
    private final DataLineageMapper dataLineageMapper;
    private final DorisClusterMapper dorisClusterMapper;

    /**
     * 解析 SQL 返回增强结果
     */
    public SqlTableAnalyzeResponse analyze(String sql, String nodeType) {
        SqlTableAnalyzeResponse response = new SqlTableAnalyzeResponse();
        if (!StringUtils.hasText(sql)) {
            return response;
        }

        if (StringUtils.hasText(nodeType) && !"SQL".equalsIgnoreCase(nodeType)) {
            return response;
        }

        String originalSql = sql;
        String maskedSql = maskCommentsAndLiterals(originalSql);
        Set<String> cteAliases = extractCteAliases(maskedSql);

        Map<Direction, LinkedHashMap<String, TableReference>> astRefs = new HashMap<>();
        astRefs.put(Direction.INPUT, new LinkedHashMap<>());
        astRefs.put(Direction.OUTPUT, new LinkedHashMap<>());

        boolean astParsed = false;
        try {
            extractWithAst(originalSql, astRefs);
            astParsed = true;
        } catch (Exception ex) {
            log.debug("AST SQL parse failed, fallback to regex parser. reason={}", ex.getMessage());
        }

        LinkedHashMap<String, TableReference> regexInputRefs = extractWithRegex(maskedSql, Direction.INPUT);
        LinkedHashMap<String, TableReference> regexOutputRefs = extractWithRegex(maskedSql, Direction.OUTPUT);

        LinkedHashMap<String, TableReference> finalInputs = decideFinalRefs(astParsed,
                astRefs.get(Direction.INPUT), regexInputRefs);
        LinkedHashMap<String, TableReference> finalOutputs = decideFinalRefs(astParsed,
                astRefs.get(Direction.OUTPUT), regexOutputRefs);

        removeCteAliases(finalInputs, cteAliases);
        fillSpansFromRegex(maskedSql, finalInputs.values(), Direction.INPUT);
        fillSpansFromRegex(maskedSql, finalOutputs.values(), Direction.OUTPUT);

        List<SqlTableAnalyzeResponse.TableRefMatch> inputMatches = new ArrayList<>();
        List<SqlTableAnalyzeResponse.TableRefMatch> outputMatches = new ArrayList<>();
        LinkedHashSet<String> unmatched = new LinkedHashSet<>();
        LinkedHashSet<String> ambiguous = new LinkedHashSet<>();

        for (TableReference ref : finalInputs.values()) {
            SqlTableAnalyzeResponse.TableRefMatch match = matchReference(ref);
            inputMatches.add(match);
            collectStatusNames(match, unmatched, ambiguous);
        }
        for (TableReference ref : finalOutputs.values()) {
            SqlTableAnalyzeResponse.TableRefMatch match = matchReference(ref);
            outputMatches.add(match);
            collectStatusNames(match, unmatched, ambiguous);
        }

        response.setInputRefs(inputMatches);
        response.setOutputRefs(outputMatches);
        response.setUnmatched(new ArrayList<>(unmatched));
        response.setAmbiguous(new ArrayList<>(ambiguous));
        return response;
    }

    /**
     * 兼容旧接口：返回简化匹配结果
     */
    public SqlTableMatchResponse match(String sql) {
        return toLegacyResponse(analyze(sql, "SQL"));
    }

    /**
     * 解析 SQL 并建立表与任务、血缘的关联
     */
    @Transactional
    public SqlTableMatchResponse bindTaskRelations(Long taskId, String sql) {
        if (taskId == null) {
            throw new IllegalArgumentException("任务ID不能为空");
        }

        SqlTableAnalyzeResponse analyzeResponse = analyze(sql, "SQL");
        SqlTableMatchResponse response = toLegacyResponse(analyzeResponse);

        // 清理旧数据
        tableTaskRelationMapper.delete(
            new LambdaQueryWrapper<TableTaskRelation>()
                .eq(TableTaskRelation::getTaskId, taskId)
        );
        dataLineageMapper.delete(
            new LambdaQueryWrapper<DataLineage>()
                .eq(DataLineage::getTaskId, taskId)
        );

        for (SqlTableAnalyzeResponse.TableRefMatch input : analyzeResponse.getInputRefs()) {
            if (!"matched".equals(input.getMatchStatus()) || input.getChosenTable() == null) {
                continue;
            }
            Long tableId = input.getChosenTable().getTableId();
            if (tableId == null) {
                continue;
            }
            insertRelation(taskId, tableId, "read");
            insertLineage(taskId, tableId, null, "input");
        }

        for (SqlTableAnalyzeResponse.TableRefMatch output : analyzeResponse.getOutputRefs()) {
            if (!"matched".equals(output.getMatchStatus()) || output.getChosenTable() == null) {
                continue;
            }
            Long tableId = output.getChosenTable().getTableId();
            if (tableId == null) {
                continue;
            }
            insertRelation(taskId, tableId, "write");
            insertLineage(taskId, null, tableId, "output");
        }

        return response;
    }

    private void extractWithAst(String sql, Map<Direction, LinkedHashMap<String, TableReference>> collector)
            throws JSQLParserException {
        Statements statements = CCJSqlParserUtil.parseStatements(sql);
        if (statements == null || statements.getStatements() == null) {
            return;
        }

        for (Statement statement : statements.getStatements()) {
            if (statement == null) {
                continue;
            }

            String statementType = statement.getClass().getSimpleName();
            if (OUTPUT_STATEMENT_TYPES.contains(statementType)) {
                Table output = invokeTable(statement, "getTable");
                addReference(collector.get(Direction.OUTPUT), toReference(output, Direction.OUTPUT, null));
            }

            if (statement instanceof Select) {
                collectInputFromSelect((Select) statement, collector.get(Direction.INPUT));
            }

            Select innerSelect = invokeSelect(statement, "getSelect");
            if (innerSelect != null) {
                collectInputFromSelect(innerSelect, collector.get(Direction.INPUT));
            }

            Object itemsList = invoke(statement, "getItemsList");
            if (itemsList instanceof Select) {
                collectInputFromSelect((Select) itemsList, collector.get(Direction.INPUT));
            }

            Table usingTable = invokeTable(statement, "getUsingTable");
            addReference(collector.get(Direction.INPUT), toReference(usingTable, Direction.INPUT, null));

            Select usingSelect = invokeSelect(statement, "getUsingSelect");
            if (usingSelect != null) {
                collectInputFromSelect(usingSelect, collector.get(Direction.INPUT));
            }
        }
    }

    private void collectInputFromSelect(Select select, LinkedHashMap<String, TableReference> collector) {
        if (select == null) {
            return;
        }

        Set<String> cteNames = new LinkedHashSet<>();
        List<WithItem> withItems = select.getWithItemsList();
        if (withItems != null) {
            for (WithItem withItem : withItems) {
                if (withItem == null) {
                    continue;
                }
                if (StringUtils.hasText(withItem.getName())) {
                    cteNames.add(normalizeIdentifier(withItem.getName()));
                }

                SelectBody withBody = invokeSelectBody(withItem, "getSelectBody");
                if (withBody == null) {
                    Object subSelect = invoke(withItem, "getSubSelect");
                    withBody = invokeSelectBody(subSelect, "getSelectBody");
                }
                collectInputFromSelectBody(withBody, collector, cteNames);
            }
        }

        collectInputFromSelectBody(select.getSelectBody(), collector, cteNames);
    }

    private void collectInputFromSelectBody(SelectBody body,
            LinkedHashMap<String, TableReference> collector,
            Set<String> cteNames) {
        if (body == null) {
            return;
        }

        if (body instanceof PlainSelect) {
            PlainSelect plain = (PlainSelect) body;
            collectFromItem(plain.getFromItem(), collector, cteNames);

            List<Join> joins = plain.getJoins();
            if (joins != null) {
                for (Join join : joins) {
                    if (join == null) {
                        continue;
                    }
                    collectFromItem(join.getRightItem(), collector, cteNames);
                }
            }
            return;
        }

        if (body instanceof SetOperationList) {
            SetOperationList setList = (SetOperationList) body;
            List<SelectBody> selects = setList.getSelects();
            if (selects != null) {
                for (SelectBody each : selects) {
                    collectInputFromSelectBody(each, collector, cteNames);
                }
            }
        }
    }

    private void collectFromItem(FromItem fromItem,
            LinkedHashMap<String, TableReference> collector,
            Set<String> cteNames) {
        if (fromItem == null) {
            return;
        }

        if (fromItem instanceof Table) {
            Table table = (Table) fromItem;
            TableReference ref = toReference(table, Direction.INPUT, null);
            if (ref == null) {
                return;
            }
            if (!StringUtils.hasText(ref.getDatabase()) && cteNames.contains(ref.getTable())) {
                return;
            }
            addReference(collector, ref);
            return;
        }

        SelectBody subBody = invokeSelectBody(fromItem, "getSelectBody");
        if (subBody != null) {
            collectInputFromSelectBody(subBody, collector, cteNames);
            return;
        }

        Select subSelect = invokeSelect(fromItem, "getSelect");
        if (subSelect != null) {
            collectInputFromSelect(subSelect, collector);
            return;
        }

        Object subSelectObject = invoke(fromItem, "getSubSelect");
        if (subSelectObject instanceof Select) {
            collectInputFromSelect((Select) subSelectObject, collector);
        }
    }

    private LinkedHashMap<String, TableReference> extractWithRegex(String maskedSql, Direction direction) {
        Pattern pattern = direction == Direction.INPUT ? INPUT_CONTEXT_PATTERN : OUTPUT_CONTEXT_PATTERN;
        LinkedHashMap<String, TableReference> refs = new LinkedHashMap<>();
        Matcher matcher = pattern.matcher(maskedSql);
        while (matcher.find()) {
            String raw = matcher.group(1);
            ParsedName parsed = parseName(raw);
            if (parsed == null || !StringUtils.hasText(parsed.table)) {
                continue;
            }
            TableReference ref = new TableReference(direction, parsed.database, parsed.table, parsed.rawName);
            ref.getSpans().add(new SqlTableAnalyzeResponse.Span(matcher.start(1), matcher.end(1)));
            addReference(refs, ref);
        }
        return refs;
    }

    private LinkedHashMap<String, TableReference> decideFinalRefs(boolean astParsed,
            LinkedHashMap<String, TableReference> astRefs,
            LinkedHashMap<String, TableReference> regexRefs) {
        if (!astParsed) {
            return regexRefs;
        }
        if (astRefs == null || astRefs.isEmpty()) {
            return regexRefs;
        }

        for (TableReference regexRef : regexRefs.values()) {
            String key = regexRef.key();
            TableReference astRef = astRefs.get(key);
            if (astRef != null) {
                astRef.getSpans().addAll(regexRef.getSpans());
            } else {
                astRefs.put(key, regexRef);
            }
        }
        return astRefs;
    }

    private Set<String> extractCteAliases(String maskedSql) {
        if (!StringUtils.hasText(maskedSql)) {
            return Collections.emptySet();
        }
        Set<String> aliases = new LinkedHashSet<>();
        Matcher matcher = CTE_ALIAS_PATTERN.matcher(maskedSql);
        while (matcher.find()) {
            String alias = normalizeIdentifier(matcher.group(1));
            if (StringUtils.hasText(alias)) {
                aliases.add(alias);
            }
        }
        return aliases;
    }

    private void removeCteAliases(LinkedHashMap<String, TableReference> refs, Set<String> cteAliases) {
        if (refs == null || refs.isEmpty() || cteAliases == null || cteAliases.isEmpty()) {
            return;
        }
        refs.entrySet().removeIf(entry -> {
            TableReference ref = entry.getValue();
            return ref != null
                    && !StringUtils.hasText(ref.getDatabase())
                    && cteAliases.contains(ref.getTable());
        });
    }

    private void fillSpansFromRegex(String maskedSql, Iterable<TableReference> refs, Direction direction) {
        Pattern pattern = direction == Direction.INPUT ? INPUT_CONTEXT_PATTERN : OUTPUT_CONTEXT_PATTERN;
        Matcher matcher = pattern.matcher(maskedSql);

        Map<String, List<SqlTableAnalyzeResponse.Span>> spanMap = new HashMap<>();
        while (matcher.find()) {
            ParsedName parsed = parseName(matcher.group(1));
            if (parsed == null || !StringUtils.hasText(parsed.table)) {
                continue;
            }
            String key = buildKey(direction, parsed.database, parsed.table);
            spanMap.computeIfAbsent(key, k -> new ArrayList<>())
                .add(new SqlTableAnalyzeResponse.Span(matcher.start(1), matcher.end(1)));
        }

        for (TableReference ref : refs) {
            List<SqlTableAnalyzeResponse.Span> spans = spanMap.get(ref.key());
            if (spans == null || spans.isEmpty()) {
                continue;
            }
            if (ref.getSpans().isEmpty()) {
                ref.getSpans().addAll(spans);
            }
        }
    }

    private SqlTableAnalyzeResponse.TableRefMatch matchReference(TableReference ref) {
        SqlTableAnalyzeResponse.TableRefMatch match = new SqlTableAnalyzeResponse.TableRefMatch();
        match.setRawName(ref.getRawName());
        match.setDatabase(ref.getDatabase());
        match.setTableName(ref.getTable());
        match.setSpans(ref.getSpans());

        List<DataTable> candidates = resolveCandidates(ref);
        Map<Long, DorisCluster> clusterMap = loadClusterMap(candidates);
        List<SqlTableAnalyzeResponse.TableCandidate> candidateDtos = candidates.stream()
            .map(table -> toCandidate(table, clusterMap.get(table.getClusterId())))
            .sorted(Comparator.comparing((SqlTableAnalyzeResponse.TableCandidate c) -> String.valueOf(c.getClusterName()))
                    .thenComparing(c -> String.valueOf(c.getDbName()))
                    .thenComparing(c -> String.valueOf(c.getTableName())))
            .collect(Collectors.toList());

        match.setCandidates(candidateDtos);

        if (candidateDtos.isEmpty()) {
            match.setMatchStatus("unmatched");
            match.setConfidence(0D);
            return match;
        }

        if (candidateDtos.size() == 1) {
            match.setMatchStatus("matched");
            match.setConfidence(StringUtils.hasText(ref.getDatabase()) ? 1D : 0.9D);
            match.setChosenTable(candidateDtos.get(0));
            return match;
        }

        match.setMatchStatus("ambiguous");
        match.setConfidence(StringUtils.hasText(ref.getDatabase()) ? 0.6D : 0.5D);
        return match;
    }

    private List<DataTable> resolveCandidates(TableReference ref) {
        if (ref == null || !StringUtils.hasText(ref.getTable())) {
            return Collections.emptyList();
        }

        List<DataTable> list;
        if (StringUtils.hasText(ref.getDatabase())) {
            list = dataTableMapper.selectActiveByDbAndTable(ref.getDatabase(), ref.getTable());
        } else {
            list = dataTableMapper.selectActiveByTable(ref.getTable());
        }

        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }

        return list.stream()
            .filter(Objects::nonNull)
            .filter(table -> !"deprecated".equalsIgnoreCase(table.getStatus()))
            .collect(Collectors.toList());
    }

    private Map<Long, DorisCluster> loadClusterMap(List<DataTable> tables) {
        if (tables == null || tables.isEmpty()) {
            return Collections.emptyMap();
        }

        Set<Long> clusterIds = tables.stream()
            .map(DataTable::getClusterId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

        if (clusterIds.isEmpty()) {
            return Collections.emptyMap();
        }

        return dorisClusterMapper.selectBatchIds(clusterIds).stream()
            .collect(Collectors.toMap(DorisCluster::getId, c -> c, (a, b) -> a));
    }

    private SqlTableAnalyzeResponse.TableCandidate toCandidate(DataTable table, DorisCluster cluster) {
        SqlTableAnalyzeResponse.TableCandidate candidate = new SqlTableAnalyzeResponse.TableCandidate();
        candidate.setTableId(table.getId());
        candidate.setClusterId(table.getClusterId());
        candidate.setClusterName(cluster != null ? cluster.getClusterName() : null);
        candidate.setSourceType(cluster != null ? cluster.getSourceType() : null);
        candidate.setDbName(table.getDbName());
        candidate.setTableName(table.getTableName());
        candidate.setTableComment(table.getTableComment());
        return candidate;
    }

    private void collectStatusNames(SqlTableAnalyzeResponse.TableRefMatch match,
            LinkedHashSet<String> unmatched,
            LinkedHashSet<String> ambiguous) {
        if (match == null || !StringUtils.hasText(match.getRawName())) {
            return;
        }
        if ("unmatched".equals(match.getMatchStatus())) {
            unmatched.add(match.getRawName());
        }
        if ("ambiguous".equals(match.getMatchStatus())) {
            ambiguous.add(match.getRawName());
        }
    }

    private SqlTableMatchResponse toLegacyResponse(SqlTableAnalyzeResponse analyze) {
        SqlTableMatchResponse response = new SqlTableMatchResponse();
        if (analyze == null) {
            return response;
        }

        List<MatchedTable> upstream = new ArrayList<>();
        List<MatchedTable> downstream = new ArrayList<>();
        List<String> unmatchedUpstream = new ArrayList<>();
        List<String> unmatchedDownstream = new ArrayList<>();

        for (SqlTableAnalyzeResponse.TableRefMatch input : analyze.getInputRefs()) {
            if ("matched".equals(input.getMatchStatus()) && input.getChosenTable() != null) {
                DataTable table = buildLegacyTable(input.getChosenTable());
                upstream.add(new MatchedTable(input.getRawName(), input.getDatabase(), table));
            } else if (StringUtils.hasText(input.getRawName())) {
                unmatchedUpstream.add(input.getRawName());
            }
        }

        for (SqlTableAnalyzeResponse.TableRefMatch output : analyze.getOutputRefs()) {
            if ("matched".equals(output.getMatchStatus()) && output.getChosenTable() != null) {
                DataTable table = buildLegacyTable(output.getChosenTable());
                downstream.add(new MatchedTable(output.getRawName(), output.getDatabase(), table));
            } else if (StringUtils.hasText(output.getRawName())) {
                unmatchedDownstream.add(output.getRawName());
            }
        }

        response.setUpstreamMatches(upstream);
        response.setDownstreamMatches(downstream);
        response.setUnmatchedUpstream(unmatchedUpstream);
        response.setUnmatchedDownstream(unmatchedDownstream);
        return response;
    }

    private DataTable buildLegacyTable(SqlTableAnalyzeResponse.TableCandidate candidate) {
        DataTable table = new DataTable();
        table.setId(candidate.getTableId());
        table.setClusterId(candidate.getClusterId());
        table.setDbName(candidate.getDbName());
        table.setTableName(candidate.getTableName());
        table.setTableComment(candidate.getTableComment());
        return table;
    }

    private TableReference toReference(Table table, Direction direction, String rawOverride) {
        if (table == null) {
            return null;
        }

        ParsedName parsed = parseTable(table);
        if (parsed == null || !StringUtils.hasText(parsed.table)) {
            return null;
        }

        String raw = StringUtils.hasText(rawOverride) ? rawOverride : parsed.rawName;
        return new TableReference(direction, parsed.database, parsed.table, raw);
    }

    private ParsedName parseTable(Table table) {
        if (table == null) {
            return null;
        }

        String schema = normalizeIdentifier(table.getSchemaName());
        String name = normalizeIdentifier(table.getName());
        if (!StringUtils.hasText(name)) {
            String full = normalizeIdentifier(table.getFullyQualifiedName());
            if (!StringUtils.hasText(full)) {
                return null;
            }
            return parseName(full);
        }

        String raw = StringUtils.hasText(schema) ? schema + "." + name : name;
        return new ParsedName(schema, name, raw);
    }

    private ParsedName parseName(String rawName) {
        if (!StringUtils.hasText(rawName)) {
            return null;
        }

        String normalized = normalizeIdentifier(rawName);
        if (!StringUtils.hasText(normalized)) {
            return null;
        }

        String[] parts = normalized.split("\\.");
        if (parts.length == 1) {
            return new ParsedName(null, parts[0], parts[0]);
        }

        String database = parts[parts.length - 2];
        String table = parts[parts.length - 1];
        String raw = database + "." + table;
        return new ParsedName(database, table, raw);
    }

    private String normalizeIdentifier(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value
            .replace("`", "")
            .replace("\"", "")
            .replace(" ", "")
            .trim()
            .toLowerCase(Locale.ROOT);
    }

    private void addReference(LinkedHashMap<String, TableReference> refs, TableReference ref) {
        if (refs == null || ref == null || !StringUtils.hasText(ref.getTable())) {
            return;
        }

        String key = ref.key();
        TableReference existing = refs.get(key);
        if (existing == null) {
            refs.put(key, ref);
            return;
        }

        if (existing.getSpans().isEmpty() && !ref.getSpans().isEmpty()) {
            existing.getSpans().addAll(ref.getSpans());
        }
    }

    private String buildKey(Direction direction, String database, String table) {
        String db = normalizeIdentifier(database);
        String tb = normalizeIdentifier(table);
        return direction.name() + ":" + (StringUtils.hasText(db) ? db + "." : "") + tb;
    }

    private void insertRelation(Long taskId, Long tableId, String relationType) {
        if (taskId == null || tableId == null) {
            return;
        }
        TableTaskRelation relation = new TableTaskRelation();
        relation.setTaskId(taskId);
        relation.setTableId(tableId);
        relation.setRelationType(relationType);
        tableTaskRelationMapper.insert(relation);
    }

    private void insertLineage(Long taskId, Long upstreamId, Long downstreamId, String lineageType) {
        DataLineage lineage = new DataLineage();
        lineage.setTaskId(taskId);
        lineage.setUpstreamTableId(upstreamId);
        lineage.setDownstreamTableId(downstreamId);
        lineage.setLineageType(lineageType);
        dataLineageMapper.insert(lineage);
    }

    private Object invoke(Object target, String methodName) {
        if (target == null || !StringUtils.hasText(methodName)) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Table invokeTable(Object target, String methodName) {
        Object result = invoke(target, methodName);
        return result instanceof Table ? (Table) result : null;
    }

    private Select invokeSelect(Object target, String methodName) {
        Object result = invoke(target, methodName);
        return result instanceof Select ? (Select) result : null;
    }

    private SelectBody invokeSelectBody(Object target, String methodName) {
        Object result = invoke(target, methodName);
        return result instanceof SelectBody ? (SelectBody) result : null;
    }

    private String maskCommentsAndLiterals(String sql) {
        if (!StringUtils.hasText(sql)) {
            return "";
        }

        StringBuilder builder = new StringBuilder(sql.length());
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = 0; i < sql.length(); i++) {
            char current = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (current == '\n' || current == '\r') {
                    inLineComment = false;
                    builder.append(current);
                } else {
                    builder.append(' ');
                }
                continue;
            }

            if (inBlockComment) {
                if (current == '*' && next == '/') {
                    builder.append(' ').append(' ');
                    inBlockComment = false;
                    i++;
                } else {
                    builder.append(' ');
                }
                continue;
            }

            if (inSingleQuote) {
                if (current == '\'' && next == '\'') {
                    builder.append(' ').append(' ');
                    i++;
                    continue;
                }
                builder.append(' ');
                if (current == '\'') {
                    inSingleQuote = false;
                }
                continue;
            }

            if (inDoubleQuote) {
                if (current == '"' && next == '"') {
                    builder.append(' ').append(' ');
                    i++;
                    continue;
                }
                builder.append(' ');
                if (current == '"') {
                    inDoubleQuote = false;
                }
                continue;
            }

            if (current == '-' && next == '-') {
                inLineComment = true;
                builder.append(' ').append(' ');
                i++;
                continue;
            }

            if (current == '/' && next == '*') {
                inBlockComment = true;
                builder.append(' ').append(' ');
                i++;
                continue;
            }

            if (current == '\'') {
                inSingleQuote = true;
                builder.append(' ');
                continue;
            }

            if (current == '"') {
                inDoubleQuote = true;
                builder.append(' ');
                continue;
            }

            builder.append(current);
        }

        return builder.toString();
    }

    private enum Direction {
        INPUT,
        OUTPUT
    }

    private static class ParsedName {
        private final String database;
        private final String table;
        private final String rawName;

        private ParsedName(String database, String table, String rawName) {
            this.database = database;
            this.table = table;
            this.rawName = rawName;
        }
    }

    private class TableReference {
        private final Direction direction;
        private final String database;
        private final String table;
        private final String rawName;
        private final List<SqlTableAnalyzeResponse.Span> spans = new ArrayList<>();

        private TableReference(Direction direction, String database, String table, String rawName) {
            this.direction = direction;
            this.database = database;
            this.table = table;
            this.rawName = rawName;
        }

        private String key() {
            return buildKey(direction, database, table);
        }

        public String getDatabase() {
            return database;
        }

        public String getTable() {
            return table;
        }

        public String getRawName() {
            return rawName;
        }

        public List<SqlTableAnalyzeResponse.Span> getSpans() {
            return spans;
        }
    }
}
