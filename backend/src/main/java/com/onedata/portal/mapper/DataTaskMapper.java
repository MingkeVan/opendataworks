package com.onedata.portal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.onedata.portal.entity.DataTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Mapper;

/**
 * 任务定义 Mapper
 */
@Mapper
public interface DataTaskMapper extends BaseMapper<DataTask> {

    @Select("SELECT COUNT(1) FROM data_task WHERE task_code = #{taskCode}")
    Long countByTaskCodeIncludingDeleted(@Param("taskCode") String taskCode);
}
