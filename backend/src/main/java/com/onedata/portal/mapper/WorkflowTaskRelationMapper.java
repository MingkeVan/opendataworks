package com.onedata.portal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.onedata.portal.entity.WorkflowTaskRelation;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工作流-任务关系 Mapper
 */
@Mapper
public interface WorkflowTaskRelationMapper extends BaseMapper<WorkflowTaskRelation> {
}
