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

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工作流完整生命周期集成测试（合并版）
 *
 * 所有步骤在一个测试方法中完成，确保状态一致性
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("工作流生命周期集成测试")
class WorkflowLifecycleFullTest {

    @Autowired
    private DataTaskService dataTaskService;

    @Autowired
    private DolphinSchedulerService dolphinSchedulerService;

    @Autowired
    private DataTaskMapper dataTaskMapper;

    @Autowired
    private DataTableMapper dataTableMapper;

    @Autowired
    private DataLineageMapper dataLineageMapper;

    @BeforeAll
    static void setup() {
        System.out.println("\n" + createSep(80));
        System.out.println("🚀 工作流生命周期集成测试");
        System.out.println(createSep(80) + "\n");
    }

    @Test
    @DisplayName("完整工作流生命周期测试")
    void testCompleteWorkflowLifecycle() {
        // 清理旧数据
        cleanupOldTestData();

        // 步骤1：创建3个表
        System.out.println("\n[步骤1] 创建3个测试表\n");
        Long tableAId = createTable("test_table_a", "ods", "源表A");
        Long tableBId = createTable("test_table_b", "dwd", "中间表B");
        Long tableCId = createTable("test_table_c", "dws", "目标表C");
        System.out.println("✅ 3个表创建成功\n");

        // 步骤2：创建3个串行任务
        System.out.println("\n[步骤2] 创建3个串行依赖的SQL任务\n");
        Long task1Id = createTask("test_task_1_a_to_b", "转换任务1",
            "INSERT INTO test_table_b SELECT * FROM test_table_a",
            Arrays.asList(tableAId), Arrays.asList(tableBId));

        Long task2Id = createTask("test_task_2_b_to_c", "转换任务2",
            "INSERT INTO test_table_c SELECT * FROM test_table_b",
            Arrays.asList(tableBId), Arrays.asList(tableCId));

        Long task3Id = createTask("test_task_3_verify_c", "验证任务3",
            "SELECT COUNT(*) FROM test_table_c",
            Arrays.asList(tableCId), null);

        System.out.println("✅ 3个任务创建成功");
        System.out.println("   依赖关系: task_1 -> task_2 -> task_3\n");

        // 步骤3：发布工作流并上线
        System.out.println("\n[步骤3] 发布工作流并上线\n");
        dataTaskService.publish(task1Id);
        dataTaskService.publish(task2Id);
        dataTaskService.publish(task3Id);

        DataTask task1 = dataTaskMapper.selectById(task1Id);
        assertNotNull(task1.getDolphinProcessCode(), "工作流代码应已生成");
        Long workflowCode = task1.getDolphinProcessCode();

        System.out.println("✅ 工作流已创建并上线");
        System.out.println("   工作流代码: " + workflowCode);
        System.out.println("   状态: ONLINE\n");

        // 步骤4：下线工作流
        System.out.println("\n[步骤4] 下线工作流\n");
        dolphinSchedulerService.setWorkflowReleaseState(workflowCode, "OFFLINE");
        System.out.println("✅ 工作流已下线\n");

        // 步骤5：添加新任务
        System.out.println("\n[步骤5] 添加新任务 task_4\n");
        Long task4Id = createTask("test_task_4_analyze_b", "分析任务4",
            "SELECT AVG(value) FROM test_table_b",
            Arrays.asList(tableBId), null);

        System.out.println("✅ 任务4创建成功");
        System.out.println("   依赖关系: task_1 -> task_2 -> task_3");
        System.out.println("                   \\-> task_4\n");

        // 步骤6：重新发布并上线
        System.out.println("\n[步骤6] 重新发布工作流\n");
        dataTaskService.publish(task4Id);

        DataTask task4 = dataTaskMapper.selectById(task4Id);
        assertNotNull(task4.getDolphinProcessCode());
        assertEquals(workflowCode, task4.getDolphinProcessCode(), "新任务应属于同一工作流");

        System.out.println("✅ 工作流重新上线");
        System.out.println("   任务数量: 4\n");

        // 步骤7：验证血缘关系
        System.out.println("\n[步骤7] 验证血缘关系\n");
        verifyLineage(task1Id, tableAId, tableBId, "table_a -> task_1 -> table_b");
        verifyLineage(task2Id, tableBId, tableCId, "table_b -> task_2 -> table_c");
        verifyLineage(task3Id, tableCId, null, "table_c -> task_3");
        verifyLineage(task4Id, tableBId, null, "table_b -> task_4");

        System.out.println("✅ 所有血缘关系验证通过\n");

        // 步骤8：清理
        System.out.println("\n[步骤8] 清理测试数据\n");
        dolphinSchedulerService.setWorkflowReleaseState(workflowCode, "OFFLINE");
        dataTaskService.delete(task1Id);
        dataTaskService.delete(task2Id);
        dataTaskService.delete(task3Id);
        dataTaskService.delete(task4Id);
        dataTableMapper.deleteById(tableAId);
        dataTableMapper.deleteById(tableBId);
        dataTableMapper.deleteById(tableCId);
        System.out.println("✅ 清理完成\n");

        System.out.println(createSep(80));
        System.out.println("✅ 所有测试通过！");
        System.out.println(createSep(80) + "\n");
    }

    private Long createTable(String name, String layer, String comment) {
        DataTable table = new DataTable();
        table.setTableName(name);
        table.setDbName("test_db");
        table.setLayer(layer);
        table.setTableComment(comment + " - 集成测试");
        table.setOwner("test_user");
        table.setStatus("active");
        dataTableMapper.insert(table);
        System.out.println("  ✓ 创建表: " + name + " (ID: " + table.getId() + ")");
        return table.getId();
    }

    private Long createTask(String code, String name, String sql,
                           List<Long> inputs, List<Long> outputs) {
        DataTask task = new DataTask();
        task.setTaskCode(code);
        task.setTaskName(name);
        task.setTaskType("batch");
        task.setEngine("dolphin");
        task.setDolphinNodeType("SQL");
        task.setDatasourceName("doris_test");
        task.setDatasourceType("DORIS");
        task.setTaskSql(sql);
        task.setTaskDesc(name + " - 集成测试");
        task.setPriority(5);
        task.setTimeoutSeconds(600);
        task.setRetryTimes(1);
        task.setRetryInterval(60);
        task.setOwner("test_user");

        DataTask created = dataTaskService.create(task, inputs, outputs);
        System.out.println("  ✓ 创建任务: " + code + " (ID: " + created.getId() + ")");
        return created.getId();
    }

    private void verifyLineage(Long taskId, Long inputTableId, Long outputTableId, String desc) {
        List<DataLineage> inputs = dataLineageMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DataLineage>()
                .eq(DataLineage::getTaskId, taskId)
                .eq(DataLineage::getLineageType, "input")
        );

        if (inputTableId != null) {
            assertEquals(1, inputs.size());
            assertEquals(inputTableId, inputs.get(0).getUpstreamTableId());
        }

        if (outputTableId != null) {
            List<DataLineage> outputs = dataLineageMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DataLineage>()
                    .eq(DataLineage::getTaskId, taskId)
                    .eq(DataLineage::getLineageType, "output")
            );
            assertEquals(1, outputs.size());
            assertEquals(outputTableId, outputs.get(0).getDownstreamTableId());
        }

        System.out.println("  ✓ 验证血缘: " + desc);
    }

    private void cleanupOldTestData() {
        System.out.println("\n[清理] 删除旧测试数据\n");

        List<DataTask> oldTasks = dataTaskMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DataTask>()
                .and(wrapper -> wrapper
                    .like(DataTask::getTaskCode, "test_task_%")
                    .or()
                    .like(DataTask::getTaskCode, "sample_%"))
        );
        for (DataTask task : oldTasks) {
            dataLineageMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DataLineage>()
                    .eq(DataLineage::getTaskId, task.getId())
            );
            dataTaskMapper.deleteById(task.getId());
        }

        List<DataTable> oldTables = dataTableMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DataTable>()
                .like(DataTable::getTableName, "test_table_%")
        );
        for (DataTable table : oldTables) {
            dataTableMapper.deleteById(table.getId());
        }

        System.out.println("  ✓ 旧测试数据已清理\n");
    }

    private static String createSep(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append('=');
        return sb.toString();
    }
}
