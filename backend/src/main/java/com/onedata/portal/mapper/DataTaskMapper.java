package com.onedata.portal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.onedata.portal.entity.DataTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 任务定义 Mapper
 */
@Mapper
public interface DataTaskMapper extends BaseMapper<DataTask> {
}
