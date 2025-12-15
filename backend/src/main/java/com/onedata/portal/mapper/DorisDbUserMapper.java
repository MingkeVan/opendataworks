package com.onedata.portal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.onedata.portal.entity.DorisDbUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * Doris数据库用户配置Mapper
 */
@Mapper
public interface DorisDbUserMapper extends BaseMapper<DorisDbUser> {
}
