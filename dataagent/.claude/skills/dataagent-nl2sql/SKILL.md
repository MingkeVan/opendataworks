---
name: dataagent-nl2sql
description: Use this skill for natural-language data questions, NL2Semantic2LF2SQL conversion, SQL generation, and tool execution guidance with DataAgent knowledge files.
---

# DataAgent NL2Semantic2LF2SQL Skill

## Scope
- 智能问数流程编排与约束
- 基于语义层的 LF（JSON DSL）到 SQL 编译与执行
- 面向可迁移的文件化知识治理

## Workflow
- 1) NL 解析与语义命中
- 2) 生成 LF(JSON DSL)
- 3) LF 校验与约束检查
- 4) 按引擎编译 SQL
- 5) 工具执行并返回结果

## References
- `methodology/nl2semantic2lf2sql.md`
- `ontology/ontology.json`
- `ontology/business_concepts.json`
- `ontology/metrics.json`
- `ontology/constraints.json`
- `knowledge/few_shots.json`
- `knowledge/business_rules.json`
- `metadata/semantic_mappings.json`
- `metadata/metadata_catalog.json`
- `metadata/lineage_catalog.json`
- `metadata/source_mapping.json`
- `manifest.json`
