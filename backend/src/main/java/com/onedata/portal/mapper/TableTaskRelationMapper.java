package com.onedata.portal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.onedata.portal.entity.TableTaskRelation;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 表任务关联 Mapper
 */
@Mapper
public interface TableTaskRelationMapper extends BaseMapper<TableTaskRelation> {

    /**
     * 物理删除指定任务的所有关联关系，避免逻辑删除导致唯一键冲突
     */
    @Delete("DELETE FROM table_task_relation WHERE task_id = #{taskId}")
    int hardDeleteByTaskId(Long taskId);

    @Select("SELECT COUNT(DISTINCT t_write.task_id) " +
        "FROM table_task_relation t_read " +
        "JOIN table_task_relation t_write ON t_read.table_id = t_write.table_id " +
        "WHERE t_read.task_id = #{taskId} " +
        "  AND t_read.deleted = 0 " +
        "  AND t_write.deleted = 0 " +
        "  AND t_read.relation_type = 'read' " +
        "  AND t_write.relation_type = 'write' " +
        "  AND t_write.task_id <> t_read.task_id")
    int countUpstreamTasks(Long taskId);

    @Select("SELECT COUNT(DISTINCT t_read.task_id) " +
        "FROM table_task_relation t_write " +
        "JOIN table_task_relation t_read ON t_write.table_id = t_read.table_id " +
        "WHERE t_write.task_id = #{taskId} " +
        "  AND t_write.deleted = 0 " +
        "  AND t_read.deleted = 0 " +
        "  AND t_write.relation_type = 'write' " +
        "  AND t_read.relation_type = 'read' " +
        "  AND t_read.task_id <> t_write.task_id")
    int countDownstreamTasks(Long taskId);
}
