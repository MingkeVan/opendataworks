#!/bin/bash

#############################################
# OpenDataWorks - 数据库初始化脚本
# 用于自动化数据库创建、用户配置和数据导入
#############################################

set -e  # 遇到错误立即退出

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 默认配置
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-opendataworks}"
DB_USER="${DB_USER:-opendataworks}"
DB_PASSWORD="${DB_PASSWORD:-}"
DB_ROOT_PASSWORD="${DB_ROOT_PASSWORD:-}"
LOAD_SAMPLE_DATA="${LOAD_SAMPLE_DATA:-false}"

# SQL 文件路径
SCHEMA_SQL="$REPO_ROOT/database/mysql/10-core-schema.sql"
SAMPLE_DATA_SQL="$REPO_ROOT/database/mysql/30-sample-data.sql"
INSPECTION_SCHEMA_SQL="$REPO_ROOT/database/mysql/20-inspection-schema.sql"

# 日志函数
log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 打印横幅
print_banner() {
    echo ""
    echo "==========================================="
    echo "  OpenDataWorks - 数据库初始化脚本"
    echo "==========================================="
    echo ""
}

# 显示使用说明
show_usage() {
    cat <<EOF
使用说明:
  $0 [选项]

选项:
  -h, --host HOST           MySQL 主机地址 (默认: localhost)
  -P, --port PORT           MySQL 端口 (默认: 3306)
  -d, --database NAME       数据库名称 (默认: opendataworks)
  -u, --user USER           应用数据库用户名 (默认: opendataworks)
  -p, --password PASSWORD   应用数据库用户密码 (必需)
  -r, --root-password PWD   MySQL root 密码 (必需)
  -s, --sample-data         加载示例数据
  --help                    显示此帮助信息

环境变量:
  DB_HOST                   MySQL 主机地址
  DB_PORT                   MySQL 端口
  DB_NAME                   数据库名称
  DB_USER                   应用数据库用户名
  DB_PASSWORD               应用数据库用户密码
  DB_ROOT_PASSWORD          MySQL root 密码
  LOAD_SAMPLE_DATA          是否加载示例数据 (true/false)

示例:
  # 基本用法
  $0 -r root_password -p app_password

  # 自定义配置
  $0 -h localhost -P 3306 -d opendataworks -u opendataworks -p mypassword -r rootpwd -s

  # 使用环境变量
  DB_ROOT_PASSWORD=rootpwd DB_PASSWORD=apppwd ./init-database.sh
EOF
}

# 解析命令行参数
parse_args() {
    while [[ $# -gt 0 ]]; do
        case $1 in
            -h|--host)
                DB_HOST="$2"
                shift 2
                ;;
            -P|--port)
                DB_PORT="$2"
                shift 2
                ;;
            -d|--database)
                DB_NAME="$2"
                shift 2
                ;;
            -u|--user)
                DB_USER="$2"
                shift 2
                ;;
            -p|--password)
                DB_PASSWORD="$2"
                shift 2
                ;;
            -r|--root-password)
                DB_ROOT_PASSWORD="$2"
                shift 2
                ;;
            -s|--sample-data)
                LOAD_SAMPLE_DATA=true
                shift
                ;;
            --help)
                show_usage
                exit 0
                ;;
            *)
                log_error "未知选项: $1"
                show_usage
                exit 1
                ;;
        esac
    done
}

# 验证参数
validate_args() {
    if [ -z "$DB_ROOT_PASSWORD" ]; then
        log_error "必须提供 MySQL root 密码 (使用 -r 选项或 DB_ROOT_PASSWORD 环境变量)"
        show_usage
        exit 1
    fi

    if [ -z "$DB_PASSWORD" ]; then
        log_error "必须提供应用数据库用户密码 (使用 -p 选项或 DB_PASSWORD 环境变量)"
        show_usage
        exit 1
    fi
}

# 检查 MySQL 是否可访问
check_mysql_connection() {
    log_info "检查 MySQL 连接..."

    if ! command -v mysql &> /dev/null; then
        log_error "未找到 mysql 命令，请先安装 MySQL 客户端"
        exit 1
    fi

    if mysql -h "$DB_HOST" -P "$DB_PORT" -u root -p"$DB_ROOT_PASSWORD" -e "SELECT 1;" &> /dev/null; then
        log_success "MySQL 连接成功"
    else
        log_error "无法连接到 MySQL，请检查主机、端口和密码"
        exit 1
    fi
}

# 检查 SQL 文件是否存在
check_sql_files() {
    log_info "检查 SQL 文件..."

    if [ ! -f "$SCHEMA_SQL" ]; then
        log_error "找不到建表脚本: $SCHEMA_SQL"
        exit 1
    fi
    log_success "找到建表脚本: $SCHEMA_SQL"

    if [ "$LOAD_SAMPLE_DATA" = true ] && [ ! -f "$SAMPLE_DATA_SQL" ]; then
        log_warning "找不到示例数据脚本: $SAMPLE_DATA_SQL (跳过示例数据加载)"
        LOAD_SAMPLE_DATA=false
    fi

    if [ -f "$INSPECTION_SCHEMA_SQL" ]; then
        log_success "找到巡检模块脚本: $INSPECTION_SCHEMA_SQL"
    fi
}

# 创建数据库
create_database() {
    log_info "创建数据库 $DB_NAME..."

    # 检查数据库是否已存在
    if mysql -h "$DB_HOST" -P "$DB_PORT" -u root -p"$DB_ROOT_PASSWORD" -e "USE $DB_NAME;" 2>/dev/null; then
        log_warning "数据库 $DB_NAME 已存在"
        read -p "是否删除并重建数据库? (y/N): " -n 1 -r
        echo
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            mysql -h "$DB_HOST" -P "$DB_PORT" -u root -p"$DB_ROOT_PASSWORD" -e "DROP DATABASE $DB_NAME;"
            log_info "已删除旧数据库"
        else
            log_info "保留现有数据库，跳过创建步骤"
            return
        fi
    fi

    mysql -h "$DB_HOST" -P "$DB_PORT" -u root -p"$DB_ROOT_PASSWORD" <<EOF
CREATE DATABASE $DB_NAME
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
EOF

    log_success "数据库 $DB_NAME 创建成功"
}

# 创建应用用户并授权
create_user() {
    log_info "创建应用用户 $DB_USER..."

    # 检查用户是否已存在
    USER_EXISTS=$(mysql -h "$DB_HOST" -P "$DB_PORT" -u root -p"$DB_ROOT_PASSWORD" -sse "SELECT EXISTS(SELECT 1 FROM mysql.user WHERE user = '$DB_USER' AND host = '%')")

    if [ "$USER_EXISTS" = "1" ]; then
        log_warning "用户 $DB_USER 已存在，更新密码和权限..."
        mysql -h "$DB_HOST" -P "$DB_PORT" -u root -p"$DB_ROOT_PASSWORD" <<EOF
ALTER USER '$DB_USER'@'%' IDENTIFIED BY '$DB_PASSWORD';
GRANT ALL PRIVILEGES ON $DB_NAME.* TO '$DB_USER'@'%';
FLUSH PRIVILEGES;
EOF
    else
        mysql -h "$DB_HOST" -P "$DB_PORT" -u root -p"$DB_ROOT_PASSWORD" <<EOF
CREATE USER '$DB_USER'@'%' IDENTIFIED BY '$DB_PASSWORD';
GRANT ALL PRIVILEGES ON $DB_NAME.* TO '$DB_USER'@'%';
FLUSH PRIVILEGES;
EOF
    fi

    log_success "用户 $DB_USER 配置成功"
}

# 执行建表脚本
execute_schema() {
    log_info "执行建表脚本..."

    mysql -h "$DB_HOST" -P "$DB_PORT" -u root -p"$DB_ROOT_PASSWORD" "$DB_NAME" < "$SCHEMA_SQL"

    log_success "数据表创建成功"
}

# 执行巡检模块脚本
execute_inspection_schema() {
    if [ -f "$INSPECTION_SCHEMA_SQL" ]; then
        log_info "执行巡检模块脚本..."
        mysql -h "$DB_HOST" -P "$DB_PORT" -u root -p"$DB_ROOT_PASSWORD" "$DB_NAME" < "$INSPECTION_SCHEMA_SQL"
        log_success "巡检模块表创建成功"
    fi
}

# 加载示例数据
load_sample_data() {
    if [ "$LOAD_SAMPLE_DATA" = true ] && [ -f "$SAMPLE_DATA_SQL" ]; then
        log_info "加载示例数据..."
        mysql -h "$DB_HOST" -P "$DB_PORT" -u root -p"$DB_ROOT_PASSWORD" "$DB_NAME" < "$SAMPLE_DATA_SQL"
        log_success "示例数据加载成功"
    fi
}

# 验证数据库初始化
verify_database() {
    log_info "验证数据库初始化..."

    # 检查核心表是否存在
    TABLES=(
        "data_table"
        "data_task"
        "data_lineage"
        "task_execution_log"
        "data_domain"
        "business_domain"
    )

    for table in "${TABLES[@]}"; do
        if mysql -h "$DB_HOST" -P "$DB_PORT" -u root -p"$DB_ROOT_PASSWORD" "$DB_NAME" -e "DESCRIBE $table;" &> /dev/null; then
            log_success "表 $table 存在"
        else
            log_warning "表 $table 不存在"
        fi
    done

    # 统计表数量
    TABLE_COUNT=$(mysql -h "$DB_HOST" -P "$DB_PORT" -u root -p"$DB_ROOT_PASSWORD" "$DB_NAME" -sse "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '$DB_NAME';")
    log_info "数据库中共有 $TABLE_COUNT 个表"
}

# 显示连接信息
show_connection_info() {
    echo ""
    echo "==========================================="
    echo "  数据库初始化完成！"
    echo "==========================================="
    echo ""
    echo "连接信息:"
    echo "  主机:     $DB_HOST:$DB_PORT"
    echo "  数据库:   $DB_NAME"
    echo "  用户名:   $DB_USER"
    echo "  密码:     $DB_PASSWORD"
    echo ""
    echo "测试连接:"
    echo "  mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASSWORD $DB_NAME"
    echo ""
    echo "应用配置 (application.yml):"
    echo "  spring:"
    echo "    datasource:"
    echo "      url: jdbc:mysql://$DB_HOST:$DB_PORT/$DB_NAME?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
    echo "      username: $DB_USER"
    echo "      password: $DB_PASSWORD"
    echo ""
    echo "==========================================="
}

# 主函数
main() {
    print_banner

    # 解析参数
    parse_args "$@"

    # 验证参数
    validate_args

    # 显示配置
    log_info "初始化配置:"
    log_info "  主机: $DB_HOST:$DB_PORT"
    log_info "  数据库: $DB_NAME"
    log_info "  用户: $DB_USER"
    log_info "  加载示例数据: $LOAD_SAMPLE_DATA"
    echo ""

    # 执行初始化流程
    check_mysql_connection
    check_sql_files
    create_database
    create_user
    execute_schema
    execute_inspection_schema
    load_sample_data
    verify_database
    show_connection_info

    log_success "数据库初始化完成！"
}

# 运行主函数
main "$@"
