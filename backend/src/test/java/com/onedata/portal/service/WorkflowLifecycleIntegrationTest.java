package com.onedata.portal.service;

import com.onedata.portal.entity.DataLineage;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.mapper.DataLineageMapper;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.DataTaskMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工作流生命周期集成测试
 *
 * 测试场景：
 * 1. 创建3个表：table_a, table_b, table_c
 * 2. 创建3个串行任务：task_1 (读 table_a 写 table_b), task_2 (读 table_b 写 table_c), task_3 (读 table_c)
 * 3. 发布工作流并上线（ONLINE状态）
 * 4. 添加新任务 task_4（依赖 table_b），先下线工作流，添加任务，再上线
 * 5. 验证所有任务的依赖关系和状态
 *
 * 前置条件：
 * - DolphinScheduler 服务运行中
 * - 数据库已初始化
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("工作流完整生命周期集成测试")
class WorkflowLifecycleIntegrationTest {

    @Autowired
    private DataTaskService dataTaskService;

    @Autowired
    private DataTableService dataTableService;

    @Autowired
    private DolphinSchedulerService dolphinSchedulerService;

    @Autowired
    private DataTaskMapper dataTaskMapper;

    @Autowired
    private DataTableMapper dataTableMapper;

    @Autowired
    private DataLineageMapper dataLineageMapper;

    // 保存创建的表和任务ID，用于后续测试
    private static Long tableAId;
    private static Long tableBId;
    private static Long tableCId;
    private static Long task1Id;
    private static Long task2Id;
    private static Long task3Id;
    private static Long task4Id;
    private static Long workflowCode;

    @BeforeAll
    static void cleanupBeforeTests(@Autowired DataTaskMapper taskMapper,
                                    @Autowired DataTableMapper tableMapper,
                                    @Autowired DataLineageMapper lineageMapper) {
        System.out.println("\n🧹 清理旧测试数据...\n");

        // 删除旧的测试数据
        List<DataTask> oldTasks = taskMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DataTask>()
                .like(DataTask::getTaskCode, "test_task_%")
        );
        for (DataTask task : oldTasks) {
            lineageMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DataLineage>()
                    .eq(DataLineage::getTaskId, task.getId())
            );
            taskMapper.deleteById(task.getId());
        }

        List<DataTable> oldTables = tableMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DataTable>()
                .like(DataTable::getTableName, "test_table_%")
        );
        for (DataTable table : oldTables) {
            tableMapper.deleteById(table.getId());
        }

        System.out.println("✅ 旧测试数据已清理\n");
    }

    @BeforeEach
    void printSeparator(TestInfo testInfo) {
        String separator = createSeparator(80);
        System.out.println("\n" + separator);
        System.out.println("🧪 " + testInfo.getDisplayName());
        System.out.println(separator);
    }

    private String createSeparator(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append('=');
        }
        return sb.toString();
    }

    @Test
    @Order(1)
    @DisplayName("步骤1：创建3个测试表")
    void step1_createTables() {
        System.out.println("\n📋 创建测试表...\n");

        // 创建 table_a
        DataTable tableA = new DataTable();
        tableA.setTableName("test_table_a");
        tableA.setDbName("test_db");
        tableA.setLayer("ods");
        tableA.setTableComment("源表A - 集成测试");
        tableA.setOwner("test_user");
        tableA.setStatus("active");
        dataTableMapper.insert(tableA);
        tableAId = tableA.getId();
        System.out.println("✅ 创建表 table_a (ID: " + tableAId + ")");

        // 创建 table_b
        DataTable tableB = new DataTable();
        tableB.setTableName("test_table_b");
        tableB.setDbName("test_db");
        tableB.setLayer("dwd");
        tableB.setTableComment("中间表B - 集成测试");
        tableB.setOwner("test_user");
        tableB.setStatus("active");
        dataTableMapper.insert(tableB);
        tableBId = tableB.getId();
        System.out.println("✅ 创建表 table_b (ID: " + tableBId + ")");

        // 创建 table_c
        DataTable tableC = new DataTable();
        tableC.setTableName("test_table_c");
        tableC.setDbName("test_db");
        tableC.setLayer("dws");
        tableC.setTableComment("目标表C - 集成测试");
        tableC.setOwner("test_user");
        tableC.setStatus("active");
        dataTableMapper.insert(tableC);
        tableCId = tableC.getId();
        System.out.println("✅ 创建表 table_c (ID: " + tableCId + ")");

        assertNotNull(tableAId);
        assertNotNull(tableBId);
        assertNotNull(tableCId);
    }

    @Test
    @Order(2)
    @DisplayName("步骤2：创建3个串行依赖的任务")
    void step2_createSerialTasks() {
        System.out.println("\n⚙️ 创建串行任务...\n");

        // Task 1: 读 table_a, 写 table_b
        DataTask task1 = new DataTask();
        task1.setTaskName("转换任务1_A到B");
        task1.setTaskCode("test_task_1_a_to_b");
        task1.setTaskType("batch");
        task1.setEngine("dolphin");
        task1.setDolphinNodeType("SQL");
        task1.setDatasourceName("doris_test");
        task1.setDatasourceType("MYSQL");
        task1.setTaskSql("INSERT INTO test_table_b SELECT * FROM test_table_a");
        task1.setTaskDesc("从table_a读取数据并写入table_b");
        task1.setPriority(5);
        task1.setTimeoutSeconds(600);
        task1.setRetryTimes(1);
        task1.setRetryInterval(60);
        task1.setOwner("test_user");

        DataTask createdTask1 = dataTaskService.create(
            task1,
            Arrays.asList(tableAId),  // 读取 table_a
            Arrays.asList(tableBId)   // 写入 table_b
        );
        task1Id = createdTask1.getId();
        System.out.println("✅ 创建任务1 (ID: " + task1Id + "): table_a -> table_b");

        // Task 2: 读 table_b, 写 table_c
        DataTask task2 = new DataTask();
        task2.setTaskName("转换任务2_B到C");
        task2.setTaskCode("test_task_2_b_to_c");
        task2.setTaskType("batch");
        task2.setEngine("dolphin");
        task2.setDolphinNodeType("SQL");
        task2.setDatasourceName("doris_test");
        task2.setDatasourceType("MYSQL");
        task2.setTaskSql("INSERT INTO test_table_c SELECT * FROM test_table_b");
        task2.setTaskDesc("从table_b读取数据并写入table_c");
        task2.setPriority(5);
        task2.setTimeoutSeconds(600);
        task2.setRetryTimes(1);
        task2.setRetryInterval(60);
        task2.setOwner("test_user");

        DataTask createdTask2 = dataTaskService.create(
            task2,
            Arrays.asList(tableBId),  // 读取 table_b
            Arrays.asList(tableCId)   // 写入 table_c
        );
        task2Id = createdTask2.getId();
        System.out.println("✅ 创建任务2 (ID: " + task2Id + "): table_b -> table_c");

        // Task 3: 读 table_c (仅读取，数据验证任务)
        DataTask task3 = new DataTask();
        task3.setTaskName("验证任务3_读C");
        task3.setTaskCode("test_task_3_verify_c");
        task3.setTaskType("batch");
        task3.setEngine("dolphin");
        task3.setDolphinNodeType("SQL");
        task3.setDatasourceName("doris_test");
        task3.setDatasourceType("MYSQL");
        task3.setTaskSql("SELECT COUNT(*) FROM test_table_c");
        task3.setTaskDesc("验证table_c的数据");
        task3.setPriority(5);
        task3.setTimeoutSeconds(300);
        task3.setRetryTimes(1);
        task3.setRetryInterval(60);
        task3.setOwner("test_user");

        DataTask createdTask3 = dataTaskService.create(
            task3,
            Arrays.asList(tableCId),  // 读取 table_c
            null                      // 不写入任何表
        );
        task3Id = createdTask3.getId();
        System.out.println("✅ 创建任务3 (ID: " + task3Id + "): 读取 table_c 进行验证");

        assertNotNull(task1Id);
        assertNotNull(task2Id);
        assertNotNull(task3Id);

        System.out.println("\n📊 任务依赖关系：");
        System.out.println("   task_1 -> task_2 -> task_3 (串行依赖)");
    }

    @Test
    @Order(3)
    @DisplayName("步骤3：发布工作流并上线")
    void step3_publishAndOnlineWorkflow() {
        System.out.println("\n🚀 发布工作流...\n");

        // 发布 task_1 (会触发整个工作流的同步)
        dataTaskService.publish(task1Id);
        System.out.println("✅ 任务1已发布");

        // 发布 task_2
        dataTaskService.publish(task2Id);
        System.out.println("✅ 任务2已发布");

        // 发布 task_3
        dataTaskService.publish(task3Id);
        System.out.println("✅ 任务3已发布");

        // 验证工作流已创建
        DataTask task1 = dataTaskMapper.selectById(task1Id);
        assertNotNull(task1.getDolphinProcessCode(), "工作流代码不应为空");
        assertNotNull(task1.getDolphinTaskCode(), "任务代码不应为空");
        assertEquals("published", task1.getStatus(), "任务状态应为published");

        workflowCode = task1.getDolphinProcessCode();
        System.out.println("\n✅ 工作流已创建并上线");
        System.out.println("   工作流代码: " + workflowCode);
        System.out.println("   工作流状态: ONLINE");
        System.out.println("   任务数量: 3");

        // 验证所有任务都关联到同一个工作流
        DataTask task2 = dataTaskMapper.selectById(task2Id);
        DataTask task3 = dataTaskMapper.selectById(task3Id);
        assertEquals(workflowCode, task2.getDolphinProcessCode(), "所有任务应属于同一工作流");
        assertEquals(workflowCode, task3.getDolphinProcessCode(), "所有任务应属于同一工作流");

        System.out.println("\n📊 工作流详情：");
        System.out.println("   - 任务1代码: " + task1.getDolphinTaskCode());
        System.out.println("   - 任务2代码: " + task2.getDolphinTaskCode());
        System.out.println("   - 任务3代码: " + task3.getDolphinTaskCode());
    }

    @Test
    @Order(4)
    @DisplayName("步骤4：下线工作流")
    void step4_offlineWorkflow() {
        System.out.println("\n⏸️ 下线工作流以添加新任务...\n");

        // 下线工作流
        dolphinSchedulerService.setWorkflowReleaseState(workflowCode, "OFFLINE");
        System.out.println("✅ 工作流已下线 (OFFLINE)");
        System.out.println("   工作流代码: " + workflowCode);
        System.out.println("   现在可以安全地修改工作流");
    }

    @Test
    @Order(5)
    @DisplayName("步骤5：添加新的串行任务 task_4")
    void step5_addNewTask() {
        System.out.println("\n➕ 添加新任务到工作流...\n");

        // Task 4: 读 table_b (与 task_2 并行依赖 table_b)
        DataTask task4 = new DataTask();
        task4.setTaskName("分析任务4_读B");
        task4.setTaskCode("test_task_4_analyze_b");
        task4.setTaskType("batch");
        task4.setEngine("dolphin");
        task4.setDolphinNodeType("SQL");
        task4.setDatasourceName("doris_test");
        task4.setDatasourceType("MYSQL");
        task4.setTaskSql("SELECT AVG(value) FROM test_table_b");
        task4.setTaskDesc("分析table_b的数据");
        task4.setPriority(5);
        task4.setTimeoutSeconds(300);
        task4.setRetryTimes(1);
        task4.setRetryInterval(60);
        task4.setOwner("test_user");

        DataTask createdTask4 = dataTaskService.create(
            task4,
            Arrays.asList(tableBId),  // 读取 table_b
            null                      // 不写入任何表
        );
        task4Id = createdTask4.getId();
        System.out.println("✅ 创建任务4 (ID: " + task4Id + "): 读取 table_b 进行分析");

        assertNotNull(task4Id);

        System.out.println("\n📊 更新后的任务依赖关系：");
        System.out.println("   task_1 -> task_2 -> task_3");
        System.out.println("         \\-> task_4");
        System.out.println("   (task_2 和 task_4 都依赖 task_1，因为它们都读取 table_b)");
    }

    @Test
    @Order(6)
    @DisplayName("步骤6：重新发布并上线工作流")
    void step6_republishAndOnlineWorkflow() {
        System.out.println("\n🔄 重新发布工作流...\n");

        // 发布新任务 task_4 (会触发工作流重新同步)
        dataTaskService.publish(task4Id);
        System.out.println("✅ 任务4已发布");

        // 验证 task_4 已关联到工作流
        DataTask task4 = dataTaskMapper.selectById(task4Id);
        assertNotNull(task4.getDolphinProcessCode(), "工作流代码不应为空");
        assertNotNull(task4.getDolphinTaskCode(), "任务代码不应为空");
        assertEquals("published", task4.getStatus(), "任务状态应为published");
        assertEquals(workflowCode, task4.getDolphinProcessCode(), "新任务应属于同一工作流");

        System.out.println("\n✅ 工作流已重新上线");
        System.out.println("   工作流代码: " + workflowCode);
        System.out.println("   工作流状态: ONLINE");
        System.out.println("   任务数量: 4");

        System.out.println("\n📊 最终工作流详情：");
        DataTask task1 = dataTaskMapper.selectById(task1Id);
        DataTask task2 = dataTaskMapper.selectById(task2Id);
        DataTask task3 = dataTaskMapper.selectById(task3Id);
        System.out.println("   - 任务1代码: " + task1.getDolphinTaskCode());
        System.out.println("   - 任务2代码: " + task2.getDolphinTaskCode());
        System.out.println("   - 任务3代码: " + task3.getDolphinTaskCode());
        System.out.println("   - 任务4代码: " + task4.getDolphinTaskCode());
    }

    @Test
    @Order(7)
    @DisplayName("步骤7：验证血缘关系和依赖")
    void step7_verifyLineageAndDependencies() {
        System.out.println("\n🔍 验证血缘关系和依赖...\n");

        // 验证 task_1 的血缘
        List<DataLineage> task1Input = dataLineageMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DataLineage>()
                .eq(DataLineage::getTaskId, task1Id)
                .eq(DataLineage::getLineageType, "input")
        );
        List<DataLineage> task1Output = dataLineageMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DataLineage>()
                .eq(DataLineage::getTaskId, task1Id)
                .eq(DataLineage::getLineageType, "output")
        );
        assertEquals(1, task1Input.size(), "task_1应有1个输入表");
        assertEquals(1, task1Output.size(), "task_1应有1个输出表");
        assertEquals(tableAId, task1Input.get(0).getUpstreamTableId());
        assertEquals(tableBId, task1Output.get(0).getDownstreamTableId());
        System.out.println("✅ 任务1血缘验证通过: table_a -> task_1 -> table_b");

        // 验证 task_2 的血缘
        List<DataLineage> task2Input = dataLineageMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DataLineage>()
                .eq(DataLineage::getTaskId, task2Id)
                .eq(DataLineage::getLineageType, "input")
        );
        List<DataLineage> task2Output = dataLineageMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DataLineage>()
                .eq(DataLineage::getTaskId, task2Id)
                .eq(DataLineage::getLineageType, "output")
        );
        assertEquals(1, task2Input.size(), "task_2应有1个输入表");
        assertEquals(1, task2Output.size(), "task_2应有1个输出表");
        assertEquals(tableBId, task2Input.get(0).getUpstreamTableId());
        assertEquals(tableCId, task2Output.get(0).getDownstreamTableId());
        System.out.println("✅ 任务2血缘验证通过: table_b -> task_2 -> table_c");

        // 验证 task_3 的血缘
        List<DataLineage> task3Input = dataLineageMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DataLineage>()
                .eq(DataLineage::getTaskId, task3Id)
                .eq(DataLineage::getLineageType, "input")
        );
        assertEquals(1, task3Input.size(), "task_3应有1个输入表");
        assertEquals(tableCId, task3Input.get(0).getUpstreamTableId());
        System.out.println("✅ 任务3血缘验证通过: table_c -> task_3");

        // 验证 task_4 的血缘
        List<DataLineage> task4Input = dataLineageMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DataLineage>()
                .eq(DataLineage::getTaskId, task4Id)
                .eq(DataLineage::getLineageType, "input")
        );
        assertEquals(1, task4Input.size(), "task_4应有1个输入表");
        assertEquals(tableBId, task4Input.get(0).getUpstreamTableId());
        System.out.println("✅ 任务4血缘验证通过: table_b -> task_4");

        System.out.println("\n📊 完整血缘图：");
        System.out.println("   table_a -> task_1 -> table_b -> task_2 -> table_c -> task_3");
        System.out.println("                                 \\-> task_4");
        System.out.println("\n✅ 所有血缘关系验证通过");
    }

    @Test
    @Order(8)
    @DisplayName("步骤8：清理测试数据")
    void step8_cleanup() {
        System.out.println("\n🧹 清理测试数据...\n");

        // 先下线工作流
        if (workflowCode != null) {
            dolphinSchedulerService.setWorkflowReleaseState(workflowCode, "OFFLINE");
            System.out.println("✅ 工作流已下线");
        }

        // 删除任务
        if (task1Id != null) {
            dataTaskService.delete(task1Id);
            System.out.println("✅ 删除任务1");
        }
        if (task2Id != null) {
            dataTaskService.delete(task2Id);
            System.out.println("✅ 删除任务2");
        }
        if (task3Id != null) {
            dataTaskService.delete(task3Id);
            System.out.println("✅ 删除任务3");
        }
        if (task4Id != null) {
            dataTaskService.delete(task4Id);
            System.out.println("✅ 删除任务4");
        }

        // 删除表
        if (tableAId != null) {
            dataTableMapper.deleteById(tableAId);
            System.out.println("✅ 删除表 table_a");
        }
        if (tableBId != null) {
            dataTableMapper.deleteById(tableBId);
            System.out.println("✅ 删除表 table_b");
        }
        if (tableCId != null) {
            dataTableMapper.deleteById(tableCId);
            System.out.println("✅ 删除表 table_c");
        }

        System.out.println("\n✅ 清理完成");
    }

    @AfterEach
    void printCompletion() {
        System.out.println(createSeparator(80) + "\n");
    }
}
