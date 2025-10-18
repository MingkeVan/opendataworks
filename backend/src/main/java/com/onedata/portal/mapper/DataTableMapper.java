package com.onedata.portal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.onedata.portal.entity.DataTable;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据表 Mapper
 */
@Mapper
public interface DataTableMapper extends BaseMapper<DataTable> {
}
