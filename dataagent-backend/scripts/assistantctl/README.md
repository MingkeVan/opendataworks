# assistantctl

CLI for metadata export/ingest and health checks.

## Quick start

```bash
./assistantctl.sh doctor --assistant-base-url http://localhost:8090 --core-base-url http://localhost:8080
```

The `export-meta` command writes JSONL snapshots from OpenDataWorks core metadata tables.

## Commands

```bash
# 1) Export metadata snapshot from core DB
./assistantctl.sh export-meta \
  --core-mysql-url jdbc:mysql://127.0.0.1:3306/opendataworks \
  --core-mysql-user opendataworks \
  --core-mysql-pass opendataworks123 \
  --out ./knowledge/meta_snapshot

# 2) Ingest metadata snapshot to AQS knowledge tables
./assistantctl.sh ingest-meta \
  --assistant-mysql-url jdbc:mysql://127.0.0.1:3306/opendataworks \
  --assistant-mysql-user opendataworks \
  --assistant-mysql-pass opendataworks123 \
  --in ./knowledge/meta_snapshot \
  --version 20260303

# 3) Ingest semantic/business/qa docs
./assistantctl.sh ingest-knowledge \
  --assistant-mysql-url jdbc:mysql://127.0.0.1:3306/opendataworks \
  --assistant-mysql-user opendataworks \
  --assistant-mysql-pass opendataworks123 \
  --dir ./knowledge \
  --version 20260303
```

`ingest-meta` updates `aq_meta_table/aq_meta_field/aq_meta_lineage_edge`.
`ingest-knowledge` updates `aq_knowledge_semantic/aq_knowledge_business/aq_knowledge_qa`.
