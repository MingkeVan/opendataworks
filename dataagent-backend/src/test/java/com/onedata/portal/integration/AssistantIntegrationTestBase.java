package com.onedata.portal.integration;

import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Locale;

public abstract class AssistantIntegrationTestBase {

    protected static final String MYSQL_HOST = env("AQS_IT_MYSQL_HOST", "127.0.0.1");
    protected static final int MYSQL_PORT = Integer.parseInt(env("AQS_IT_MYSQL_PORT", "3306"));
    protected static final String MYSQL_ROOT_USER = env("AQS_IT_MYSQL_ROOT_USER", "root");
    protected static final String MYSQL_ROOT_PASSWORD = env("AQS_IT_MYSQL_ROOT_PASSWORD", "root123");
    protected static final String TEST_DB = env("AQS_IT_DB_NAME", "opendataworks");
    protected static final String TEST_DB_USER = env("AQS_IT_DB_USER", "opendataworks");
    protected static final String TEST_DB_PASSWORD = env("AQS_IT_DB_PASSWORD", "opendataworks123");

    protected static final MockWebServer CORE_BACKEND = new MockWebServer();

    static {
        try {
            initDatabase();
            CORE_BACKEND.start();
        } catch (Exception ex) {
            throw new RuntimeException("初始化集成测试环境失败", ex);
        }
    }

    @Autowired
    protected DataSource dataSource;

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> dbJdbcUrl(TEST_DB));
        registry.add("spring.datasource.username", () -> TEST_DB_USER);
        registry.add("spring.datasource.password", () -> TEST_DB_PASSWORD);
        registry.add("assistant.core-backend.base-url", AssistantIntegrationTestBase::coreBackendBaseUrl);
        registry.add("assistant.startup.fail-fast", () -> "false");
        registry.add("spring.flyway.validate-on-migrate", () -> "false");
        registry.add("auth.anonymous.enabled", () -> "true");
        registry.add("auth.anonymous.user-id", () -> "assistant_it_user");
        registry.add("auth.anonymous.username", () -> "assistant_it");
    }

    @AfterEach
    void cleanTablesAfterEach() throws Exception {
        cleanAssistantTables();
    }

    protected void cleanAssistantTables() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            executeIgnoreError(statement, "DELETE FROM assistant_artifact");
            executeIgnoreError(statement, "DELETE FROM assistant_run_step");
            executeIgnoreError(statement, "DELETE FROM assistant_message");
            executeIgnoreError(statement, "DELETE FROM assistant_run");
            executeIgnoreError(statement, "DELETE FROM assistant_session");
            executeIgnoreError(statement, "DELETE FROM assistant_skill_rule");
            executeIgnoreError(statement, "DELETE FROM assistant_policy_profile");
            executeIgnoreError(statement, "DELETE FROM aq_meta_lineage_edge");
            executeIgnoreError(statement, "DELETE FROM aq_meta_field");
            executeIgnoreError(statement, "DELETE FROM aq_meta_table");
            executeIgnoreError(statement, "DELETE FROM aq_knowledge_semantic");
            executeIgnoreError(statement, "DELETE FROM aq_knowledge_business");
            executeIgnoreError(statement, "DELETE FROM aq_knowledge_qa");
            executeIgnoreError(statement, "DELETE FROM aq_knowledge_version");
        }
    }

    protected static String coreBackendBaseUrl() {
        String url = CORE_BACKEND.url("/api").toString();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static void initDatabase() throws Exception {
        try (Connection root = DriverManager.getConnection(dbJdbcUrl(""), MYSQL_ROOT_USER, MYSQL_ROOT_PASSWORD);
             Statement statement = root.createStatement()) {
            createDatabase(statement);
            try {
                statement.execute("GRANT ALL PRIVILEGES ON `" + TEST_DB + "`.* TO '" + TEST_DB_USER + "'@'%'");
                statement.execute("FLUSH PRIVILEGES");
            } catch (Exception ignored) {
                // 某些环境不允许 GRANT；测试用户已有权限即可。
            }
        } catch (Exception rootEx) {
            // root 不可用时，默认复用已有数据库。
        }

        try (Connection connection = DriverManager.getConnection(dbJdbcUrl(TEST_DB), TEST_DB_USER, TEST_DB_PASSWORD);
             Statement statement = connection.createStatement()) {
            ensureKnowledgeTables(statement);
        }
    }

    private static void createDatabase(Statement statement) throws Exception {
        statement.execute("CREATE DATABASE IF NOT EXISTS `" + TEST_DB + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    }

    private static void ensureKnowledgeTables(Statement statement) throws Exception {
        statement.execute("CREATE TABLE IF NOT EXISTS aq_meta_table ("
            + "id BIGINT NOT NULL AUTO_INCREMENT,"
            + "snapshot_version VARCHAR(64) NOT NULL,"
            + "table_id BIGINT NOT NULL,"
            + "cluster_id BIGINT DEFAULT NULL,"
            + "db_name VARCHAR(128) DEFAULT NULL,"
            + "table_name VARCHAR(255) NOT NULL,"
            + "table_comment TEXT DEFAULT NULL,"
            + "payload_json MEDIUMTEXT DEFAULT NULL,"
            + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP,"
            + "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
            + "PRIMARY KEY (id),"
            + "KEY idx_aq_meta_table_version_table (snapshot_version, table_id),"
            + "KEY idx_aq_meta_table_db_name (db_name, table_name)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

        statement.execute("CREATE TABLE IF NOT EXISTS aq_meta_field ("
            + "id BIGINT NOT NULL AUTO_INCREMENT,"
            + "snapshot_version VARCHAR(64) NOT NULL,"
            + "field_id BIGINT NOT NULL,"
            + "table_id BIGINT NOT NULL,"
            + "field_name VARCHAR(255) NOT NULL,"
            + "field_type VARCHAR(255) DEFAULT NULL,"
            + "field_comment TEXT DEFAULT NULL,"
            + "payload_json MEDIUMTEXT DEFAULT NULL,"
            + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP,"
            + "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
            + "PRIMARY KEY (id),"
            + "KEY idx_aq_meta_field_version_table (snapshot_version, table_id),"
            + "KEY idx_aq_meta_field_name (field_name)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

        statement.execute("CREATE TABLE IF NOT EXISTS aq_meta_lineage_edge ("
            + "id BIGINT NOT NULL AUTO_INCREMENT,"
            + "snapshot_version VARCHAR(64) NOT NULL,"
            + "lineage_id BIGINT DEFAULT NULL,"
            + "task_id BIGINT DEFAULT NULL,"
            + "upstream_table_id BIGINT NOT NULL,"
            + "downstream_table_id BIGINT NOT NULL,"
            + "lineage_type VARCHAR(32) DEFAULT NULL,"
            + "payload_json MEDIUMTEXT DEFAULT NULL,"
            + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP,"
            + "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
            + "PRIMARY KEY (id),"
            + "KEY idx_aq_meta_lineage_version (snapshot_version),"
            + "KEY idx_aq_meta_lineage_edge (upstream_table_id, downstream_table_id)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

        statement.execute("CREATE TABLE IF NOT EXISTS aq_knowledge_semantic ("
            + "id BIGINT NOT NULL AUTO_INCREMENT,"
            + "version_tag VARCHAR(64) NOT NULL,"
            + "domain_name VARCHAR(128) DEFAULT NULL,"
            + "table_name VARCHAR(255) DEFAULT NULL,"
            + "field_name VARCHAR(255) DEFAULT NULL,"
            + "business_name VARCHAR(255) NOT NULL,"
            + "synonyms TEXT DEFAULT NULL,"
            + "description TEXT DEFAULT NULL,"
            + "enabled TINYINT DEFAULT 1,"
            + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP,"
            + "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
            + "PRIMARY KEY (id)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

        statement.execute("CREATE TABLE IF NOT EXISTS aq_knowledge_business ("
            + "id BIGINT NOT NULL AUTO_INCREMENT,"
            + "version_tag VARCHAR(64) NOT NULL,"
            + "term VARCHAR(255) NOT NULL,"
            + "synonyms TEXT DEFAULT NULL,"
            + "definition MEDIUMTEXT DEFAULT NULL,"
            + "enabled TINYINT DEFAULT 1,"
            + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP,"
            + "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
            + "PRIMARY KEY (id)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

        statement.execute("CREATE TABLE IF NOT EXISTS aq_knowledge_qa ("
            + "id BIGINT NOT NULL AUTO_INCREMENT,"
            + "version_tag VARCHAR(64) NOT NULL,"
            + "question TEXT NOT NULL,"
            + "answer MEDIUMTEXT NOT NULL,"
            + "tags VARCHAR(512) DEFAULT NULL,"
            + "enabled TINYINT DEFAULT 1,"
            + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP,"
            + "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
            + "PRIMARY KEY (id)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

        statement.execute("CREATE TABLE IF NOT EXISTS aq_knowledge_version ("
            + "id BIGINT NOT NULL AUTO_INCREMENT,"
            + "version_tag VARCHAR(64) NOT NULL,"
            + "meta_hash VARCHAR(128) DEFAULT NULL,"
            + "semantic_hash VARCHAR(128) DEFAULT NULL,"
            + "business_hash VARCHAR(128) DEFAULT NULL,"
            + "qa_hash VARCHAR(128) DEFAULT NULL,"
            + "source VARCHAR(64) DEFAULT 'assistantctl',"
            + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP,"
            + "PRIMARY KEY (id),"
            + "UNIQUE KEY uk_aq_knowledge_version (version_tag)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    private void executeIgnoreError(Statement statement, String sql) {
        try {
            statement.execute(sql);
        } catch (Exception ignored) {
            // Ignore missing-table cleanup errors in shared test DB.
        }
    }

    protected static String dbJdbcUrl(String db) {
        String dbPart = db == null ? "" : db.trim().toLowerCase(Locale.ROOT);
        return "jdbc:mysql://" + MYSQL_HOST + ":" + MYSQL_PORT + "/" + dbPart
            + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value.trim();
    }
}
