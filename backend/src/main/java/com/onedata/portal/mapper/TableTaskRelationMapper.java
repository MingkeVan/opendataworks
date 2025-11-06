package com.onedata.portal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.onedata.portal.entity.TableTaskRelation;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;

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
}
