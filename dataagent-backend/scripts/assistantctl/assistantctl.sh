#!/usr/bin/env bash
set -euo pipefail
shopt -s nullglob

CMD="${1:-}"
shift || true

usage() {
  cat <<USAGE
assistantctl commands:
  export-meta --core-mysql-url JDBC --core-mysql-user USER --core-mysql-pass PASS --out DIR
  ingest-meta --assistant-mysql-url JDBC --assistant-mysql-user USER --assistant-mysql-pass PASS --in DIR --version TAG
  ingest-knowledge --assistant-mysql-url JDBC --assistant-mysql-user USER --assistant-mysql-pass PASS --dir DIR --version TAG
  doctor --assistant-base-url URL --core-base-url URL
USAGE
}

require() {
  local name="$1"
  local value="$2"
  if [[ -z "$value" ]]; then
    echo "missing required arg: $name" >&2
    exit 1
  fi
}

require_cmd() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "required command not found: $cmd" >&2
    exit 1
  fi
}

get_arg() {
  local key="$1"
  shift
  local default_value=""
  if [[ $# -gt 0 && "$1" != --* ]]; then
    default_value="$1"
    shift
  fi
  local value="$default_value"
  while [[ $# -gt 0 ]]; do
    case "$1" in
      "$key")
        value="$2"
        shift 2
        ;;
      *)
        shift
        ;;
    esac
  done
  echo "$value"
}

jdbc_host() {
  echo "$1" | sed -E 's#jdbc:mysql://([^:/?]+).*#\1#'
}

jdbc_port() {
  local port
  port=$(echo "$1" | sed -nE 's#jdbc:mysql://[^:/?]+:([0-9]+)/.*#\1#p')
  echo "${port:-3306}"
}

jdbc_db() {
  echo "$1" | sed -E 's#jdbc:mysql://[^/]+/([^?]+).*#\1#'
}

mysql_exec() {
  local jdbc_url="$1"
  local user="$2"
  local pass="$3"
  local sql="$4"
  mysql \
    -h "$(jdbc_host "$jdbc_url")" \
    -P "$(jdbc_port "$jdbc_url")" \
    -u "$user" \
    -p"$pass" \
    "$(jdbc_db "$jdbc_url")" \
    --default-character-set=utf8mb4 \
    -e "$sql"
}

mysql_exec_file() {
  local jdbc_url="$1"
  local user="$2"
  local pass="$3"
  local sql_file="$4"
  mysql \
    -h "$(jdbc_host "$jdbc_url")" \
    -P "$(jdbc_port "$jdbc_url")" \
    -u "$user" \
    -p"$pass" \
    "$(jdbc_db "$jdbc_url")" \
    --default-character-set=utf8mb4 \
    < "$sql_file"
}

sql_quote() {
  local raw="$1"
  if [[ -z "$raw" || "$raw" == "null" ]]; then
    echo "NULL"
    return
  fi
  raw="${raw//$'\r'/}"
  raw="${raw//$'\n'/\\n}"
  raw="${raw//\\/\\\\}"
  raw="${raw//\'/\'\'}"
  printf "'%s'" "$raw"
}

sql_int() {
  local raw="$1"
  if [[ "$raw" =~ ^-?[0-9]+$ ]]; then
    echo "$raw"
  else
    echo "NULL"
  fi
}

sha256_of_files() {
  if [[ "$#" -eq 0 ]]; then
    echo ""
    return
  fi
  cat "$@" | shasum -a 256 | awk '{print $1}'
}

append_meta_table_inserts() {
  local sql_file="$1"
  local version="$2"
  local file="$3"
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "$line" ]] && continue
    local table_id
    local cluster_id
    local db_name
    local table_name
    local table_comment
    table_id=$(echo "$line" | jq -r '.table_id // empty')
    cluster_id=$(echo "$line" | jq -r '.cluster_id // empty')
    db_name=$(echo "$line" | jq -r '.db_name // empty')
    table_name=$(echo "$line" | jq -r '.table_name // empty')
    table_comment=$(echo "$line" | jq -r '.table_comment // empty')
    [[ -z "$table_id" || -z "$table_name" ]] && continue
    cat >> "$sql_file" <<SQL
INSERT INTO aq_meta_table (snapshot_version, table_id, cluster_id, db_name, table_name, table_comment, payload_json)
VALUES ($(sql_quote "$version"), $(sql_int "$table_id"), $(sql_int "$cluster_id"), $(sql_quote "$db_name"), $(sql_quote "$table_name"), $(sql_quote "$table_comment"), $(sql_quote "$line"));
SQL
  done < "$file"
}

append_meta_field_inserts() {
  local sql_file="$1"
  local version="$2"
  local file="$3"
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "$line" ]] && continue
    local field_id
    local table_id
    local field_name
    local field_type
    local field_comment
    field_id=$(echo "$line" | jq -r '.field_id // empty')
    table_id=$(echo "$line" | jq -r '.table_id // empty')
    field_name=$(echo "$line" | jq -r '.field_name // empty')
    field_type=$(echo "$line" | jq -r '.field_type // empty')
    field_comment=$(echo "$line" | jq -r '.field_comment // empty')
    [[ -z "$field_id" || -z "$table_id" || -z "$field_name" ]] && continue
    cat >> "$sql_file" <<SQL
INSERT INTO aq_meta_field (snapshot_version, field_id, table_id, field_name, field_type, field_comment, payload_json)
VALUES ($(sql_quote "$version"), $(sql_int "$field_id"), $(sql_int "$table_id"), $(sql_quote "$field_name"), $(sql_quote "$field_type"), $(sql_quote "$field_comment"), $(sql_quote "$line"));
SQL
  done < "$file"
}

append_meta_lineage_inserts() {
  local sql_file="$1"
  local version="$2"
  local file="$3"
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "$line" ]] && continue
    local lineage_id
    local task_id
    local upstream_table_id
    local downstream_table_id
    local lineage_type
    lineage_id=$(echo "$line" | jq -r '.lineage_id // empty')
    task_id=$(echo "$line" | jq -r '.task_id // empty')
    upstream_table_id=$(echo "$line" | jq -r '.upstream_table_id // empty')
    downstream_table_id=$(echo "$line" | jq -r '.downstream_table_id // empty')
    lineage_type=$(echo "$line" | jq -r '.lineage_type // empty')
    [[ -z "$upstream_table_id" || -z "$downstream_table_id" ]] && continue
    cat >> "$sql_file" <<SQL
INSERT INTO aq_meta_lineage_edge (snapshot_version, lineage_id, task_id, upstream_table_id, downstream_table_id, lineage_type, payload_json)
VALUES ($(sql_quote "$version"), $(sql_int "$lineage_id"), $(sql_int "$task_id"), $(sql_int "$upstream_table_id"), $(sql_int "$downstream_table_id"), $(sql_quote "$lineage_type"), $(sql_quote "$line"));
SQL
  done < "$file"
}

append_semantic_inserts() {
  local sql_file="$1"
  local version="$2"
  local semantic_dir="$3"
  local file
  for file in "$semantic_dir"/*; do
    [[ -f "$file" ]] || continue
    local base
    base=$(basename "$file")
    case "$file" in
      *.jsonl)
        while IFS= read -r line || [[ -n "$line" ]]; do
          [[ -z "$line" ]] && continue
          local domain_name
          local table_name
          local field_name
          local business_name
          local synonyms
          local description
          domain_name=$(echo "$line" | jq -r '.domain_name // empty')
          table_name=$(echo "$line" | jq -r '.table_name // empty')
          field_name=$(echo "$line" | jq -r '.field_name // empty')
          business_name=$(echo "$line" | jq -r '.business_name // empty')
          synonyms=$(echo "$line" | jq -r '.synonyms // empty')
          description=$(echo "$line" | jq -r '.description // empty')
          [[ -z "$business_name" ]] && business_name="$base"
          cat >> "$sql_file" <<SQL
INSERT INTO aq_knowledge_semantic (version_tag, domain_name, table_name, field_name, business_name, synonyms, description, enabled)
VALUES ($(sql_quote "$version"), $(sql_quote "$domain_name"), $(sql_quote "$table_name"), $(sql_quote "$field_name"), $(sql_quote "$business_name"), $(sql_quote "$synonyms"), $(sql_quote "$description"), 1);
SQL
        done < "$file"
        ;;
      *.json)
        while IFS= read -r line || [[ -n "$line" ]]; do
          [[ -z "$line" ]] && continue
          local domain_name
          local table_name
          local field_name
          local business_name
          local synonyms
          local description
          domain_name=$(echo "$line" | jq -r '.domain_name // empty')
          table_name=$(echo "$line" | jq -r '.table_name // empty')
          field_name=$(echo "$line" | jq -r '.field_name // empty')
          business_name=$(echo "$line" | jq -r '.business_name // empty')
          synonyms=$(echo "$line" | jq -r '.synonyms // empty')
          description=$(echo "$line" | jq -r '.description // empty')
          [[ -z "$business_name" ]] && business_name="$base"
          cat >> "$sql_file" <<SQL
INSERT INTO aq_knowledge_semantic (version_tag, domain_name, table_name, field_name, business_name, synonyms, description, enabled)
VALUES ($(sql_quote "$version"), $(sql_quote "$domain_name"), $(sql_quote "$table_name"), $(sql_quote "$field_name"), $(sql_quote "$business_name"), $(sql_quote "$synonyms"), $(sql_quote "$description"), 1);
SQL
        done < <(jq -c '.[]? // empty' "$file")
        ;;
      *)
        local content
        content=$(cat "$file")
        cat >> "$sql_file" <<SQL
INSERT INTO aq_knowledge_semantic (version_tag, business_name, description, enabled)
VALUES ($(sql_quote "$version"), $(sql_quote "${base%.*}"), $(sql_quote "$content"), 1);
SQL
        ;;
    esac
  done
}

append_business_inserts() {
  local sql_file="$1"
  local version="$2"
  local business_dir="$3"
  local file
  for file in "$business_dir"/*; do
    [[ -f "$file" ]] || continue
    local term
    local content
    term="${file##*/}"
    term="${term%.*}"
    content=$(cat "$file")
    cat >> "$sql_file" <<SQL
INSERT INTO aq_knowledge_business (version_tag, term, definition, enabled)
VALUES ($(sql_quote "$version"), $(sql_quote "$term"), $(sql_quote "$content"), 1);
SQL
  done
}

append_qa_inserts() {
  local sql_file="$1"
  local version="$2"
  local qa_dir="$3"
  local file
  for file in "$qa_dir"/*; do
    [[ -f "$file" ]] || continue
    local base
    base=$(basename "$file")
    case "$file" in
      *.jsonl)
        while IFS= read -r line || [[ -n "$line" ]]; do
          [[ -z "$line" ]] && continue
          local question
          local answer
          local tags
          question=$(echo "$line" | jq -r '.question // empty')
          answer=$(echo "$line" | jq -r '.answer // empty')
          tags=$(echo "$line" | jq -r '.tags // empty')
          [[ -z "$question" || -z "$answer" ]] && continue
          cat >> "$sql_file" <<SQL
INSERT INTO aq_knowledge_qa (version_tag, question, answer, tags, enabled)
VALUES ($(sql_quote "$version"), $(sql_quote "$question"), $(sql_quote "$answer"), $(sql_quote "$tags"), 1);
SQL
        done < "$file"
        ;;
      *.json)
        while IFS= read -r line || [[ -n "$line" ]]; do
          [[ -z "$line" ]] && continue
          local question
          local answer
          local tags
          question=$(echo "$line" | jq -r '.question // empty')
          answer=$(echo "$line" | jq -r '.answer // empty')
          tags=$(echo "$line" | jq -r '.tags // empty')
          [[ -z "$question" || -z "$answer" ]] && continue
          cat >> "$sql_file" <<SQL
INSERT INTO aq_knowledge_qa (version_tag, question, answer, tags, enabled)
VALUES ($(sql_quote "$version"), $(sql_quote "$question"), $(sql_quote "$answer"), $(sql_quote "$tags"), 1);
SQL
        done < <(jq -c '.[]? // empty' "$file")
        ;;
      *)
        local content
        content=$(cat "$file")
        cat >> "$sql_file" <<SQL
INSERT INTO aq_knowledge_qa (version_tag, question, answer, tags, enabled)
VALUES ($(sql_quote "$version"), $(sql_quote "${base%.*}"), $(sql_quote "$content"), NULL, 1);
SQL
        ;;
    esac
  done
}

health_check() {
  local base="$1"
  local path_a="$2"
  local path_b="$3"
  if curl -fsS "${base}${path_a}" >/dev/null 2>&1; then
    return 0
  fi
  curl -fsS "${base}${path_b}" >/dev/null 2>&1
}

if [[ -z "$CMD" ]]; then
  usage
  exit 1
fi

case "$CMD" in
  export-meta)
    require_cmd mysql
    CORE_URL=$(get_arg "--core-mysql-url" "$@")
    CORE_USER=$(get_arg "--core-mysql-user" "$@")
    CORE_PASS=$(get_arg "--core-mysql-pass" "$@")
    OUT_DIR=$(get_arg "--out" "$@")
    require "--core-mysql-url" "$CORE_URL"
    require "--core-mysql-user" "$CORE_USER"
    require "--core-mysql-pass" "$CORE_PASS"
    require "--out" "$OUT_DIR"
    mkdir -p "$OUT_DIR"
    mysql_exec "$CORE_URL" "$CORE_USER" "$CORE_PASS" "SELECT JSON_OBJECT('table_id',id,'cluster_id',cluster_id,'db_name',db_name,'table_name',table_name,'table_comment',table_comment) FROM data_table WHERE deleted=0" | tail -n +2 > "$OUT_DIR/tables.jsonl"
    mysql_exec "$CORE_URL" "$CORE_USER" "$CORE_PASS" "SELECT JSON_OBJECT('field_id',id,'table_id',table_id,'field_name',field_name,'field_type',field_type,'field_comment',field_comment) FROM data_field WHERE deleted=0" | tail -n +2 > "$OUT_DIR/fields.jsonl"
    mysql_exec "$CORE_URL" "$CORE_USER" "$CORE_PASS" "SELECT JSON_OBJECT('lineage_id',id,'task_id',task_id,'upstream_table_id',upstream_table_id,'downstream_table_id',downstream_table_id,'lineage_type',lineage_type) FROM data_lineage WHERE deleted=0" | tail -n +2 > "$OUT_DIR/lineage_edges.jsonl"
    echo "{\"generated_at\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}" > "$OUT_DIR/snapshot.json"
    echo "export-meta done: $OUT_DIR"
    ;;
  ingest-meta)
    require_cmd mysql
    require_cmd jq
    AQS_URL=$(get_arg "--assistant-mysql-url" "$@")
    AQS_USER=$(get_arg "--assistant-mysql-user" "$@")
    AQS_PASS=$(get_arg "--assistant-mysql-pass" "$@")
    IN_DIR=$(get_arg "--in" "$@")
    VERSION=$(get_arg "--version" "$@")
    require "--assistant-mysql-url" "$AQS_URL"
    require "--assistant-mysql-user" "$AQS_USER"
    require "--assistant-mysql-pass" "$AQS_PASS"
    require "--in" "$IN_DIR"
    require "--version" "$VERSION"

    TABLES_FILE="$IN_DIR/tables.jsonl"
    FIELDS_FILE="$IN_DIR/fields.jsonl"
    LINEAGE_FILE="$IN_DIR/lineage_edges.jsonl"
    [[ -f "$TABLES_FILE" ]] || { echo "missing file: $TABLES_FILE" >&2; exit 1; }
    [[ -f "$FIELDS_FILE" ]] || { echo "missing file: $FIELDS_FILE" >&2; exit 1; }
    [[ -f "$LINEAGE_FILE" ]] || { echo "missing file: $LINEAGE_FILE" >&2; exit 1; }

    SQL_FILE=$(mktemp)
    {
      echo "SET NAMES utf8mb4;"
      echo "START TRANSACTION;"
      echo "DELETE FROM aq_meta_lineage_edge WHERE snapshot_version = $(sql_quote "$VERSION");"
      echo "DELETE FROM aq_meta_field WHERE snapshot_version = $(sql_quote "$VERSION");"
      echo "DELETE FROM aq_meta_table WHERE snapshot_version = $(sql_quote "$VERSION");"
    } > "$SQL_FILE"

    append_meta_table_inserts "$SQL_FILE" "$VERSION" "$TABLES_FILE"
    append_meta_field_inserts "$SQL_FILE" "$VERSION" "$FIELDS_FILE"
    append_meta_lineage_inserts "$SQL_FILE" "$VERSION" "$LINEAGE_FILE"

    META_HASH=$(sha256_of_files "$TABLES_FILE" "$FIELDS_FILE" "$LINEAGE_FILE")
    cat >> "$SQL_FILE" <<SQL
INSERT INTO aq_knowledge_version (version_tag, meta_hash, source)
VALUES ($(sql_quote "$VERSION"), $(sql_quote "$META_HASH"), 'assistantctl')
ON DUPLICATE KEY UPDATE
  meta_hash = VALUES(meta_hash),
  source = VALUES(source),
  created_at = CURRENT_TIMESTAMP;
COMMIT;
SQL

    mysql_exec_file "$AQS_URL" "$AQS_USER" "$AQS_PASS" "$SQL_FILE"
    rm -f "$SQL_FILE"
    echo "ingest-meta done: version=$VERSION, meta_hash=$META_HASH"
    ;;
  ingest-knowledge)
    require_cmd mysql
    require_cmd jq
    AQS_URL=$(get_arg "--assistant-mysql-url" "$@")
    AQS_USER=$(get_arg "--assistant-mysql-user" "$@")
    AQS_PASS=$(get_arg "--assistant-mysql-pass" "$@")
    DIR=$(get_arg "--dir" "$@")
    VERSION=$(get_arg "--version" "$@")
    require "--assistant-mysql-url" "$AQS_URL"
    require "--assistant-mysql-user" "$AQS_USER"
    require "--assistant-mysql-pass" "$AQS_PASS"
    require "--dir" "$DIR"
    require "--version" "$VERSION"

    SEMANTIC_DIR="$DIR/semantic_models"
    BUSINESS_DIR="$DIR/business_knowledge"
    QA_DIR="$DIR/qa"
    [[ -d "$SEMANTIC_DIR" ]] || { echo "missing dir: $SEMANTIC_DIR" >&2; exit 1; }
    [[ -d "$BUSINESS_DIR" ]] || { echo "missing dir: $BUSINESS_DIR" >&2; exit 1; }
    [[ -d "$QA_DIR" ]] || { echo "missing dir: $QA_DIR" >&2; exit 1; }

    SQL_FILE=$(mktemp)
    {
      echo "SET NAMES utf8mb4;"
      echo "START TRANSACTION;"
      echo "DELETE FROM aq_knowledge_semantic WHERE version_tag = $(sql_quote "$VERSION");"
      echo "DELETE FROM aq_knowledge_business WHERE version_tag = $(sql_quote "$VERSION");"
      echo "DELETE FROM aq_knowledge_qa WHERE version_tag = $(sql_quote "$VERSION");"
    } > "$SQL_FILE"

    append_semantic_inserts "$SQL_FILE" "$VERSION" "$SEMANTIC_DIR"
    append_business_inserts "$SQL_FILE" "$VERSION" "$BUSINESS_DIR"
    append_qa_inserts "$SQL_FILE" "$VERSION" "$QA_DIR"

    SEMANTIC_FILES=("$SEMANTIC_DIR"/*)
    BUSINESS_FILES=("$BUSINESS_DIR"/*)
    QA_FILES=("$QA_DIR"/*)
    SEMANTIC_HASH=$(sha256_of_files "${SEMANTIC_FILES[@]:-}")
    BUSINESS_HASH=$(sha256_of_files "${BUSINESS_FILES[@]:-}")
    QA_HASH=$(sha256_of_files "${QA_FILES[@]:-}")

    cat >> "$SQL_FILE" <<SQL
INSERT INTO aq_knowledge_version (version_tag, semantic_hash, business_hash, qa_hash, source)
VALUES ($(sql_quote "$VERSION"), $(sql_quote "$SEMANTIC_HASH"), $(sql_quote "$BUSINESS_HASH"), $(sql_quote "$QA_HASH"), 'assistantctl')
ON DUPLICATE KEY UPDATE
  semantic_hash = VALUES(semantic_hash),
  business_hash = VALUES(business_hash),
  qa_hash = VALUES(qa_hash),
  source = VALUES(source),
  created_at = CURRENT_TIMESTAMP;
COMMIT;
SQL

    mysql_exec_file "$AQS_URL" "$AQS_USER" "$AQS_PASS" "$SQL_FILE"
    rm -f "$SQL_FILE"
    echo "ingest-knowledge done: version=$VERSION"
    ;;
  doctor)
    ASSISTANT_BASE=$(get_arg "--assistant-base-url" "$@")
    CORE_BASE=$(get_arg "--core-base-url" "$@")
    require "--assistant-base-url" "$ASSISTANT_BASE"
    require "--core-base-url" "$CORE_BASE"
    health_check "$ASSISTANT_BASE" "/api/actuator/health" "/actuator/health"
    if ! health_check "$CORE_BASE" "/api/actuator/health" "/actuator/health"; then
      curl -fsS "${CORE_BASE}/api/v1/data-query/history?pageNum=1&pageSize=1" >/dev/null
    fi
    echo "doctor passed"
    ;;
  *)
    usage
    exit 1
    ;;
esac
