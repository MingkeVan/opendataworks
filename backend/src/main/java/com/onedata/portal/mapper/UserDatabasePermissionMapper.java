package com.onedata.portal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.onedata.portal.entity.UserDatabasePermission;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户数据库权限Mapper
 */
@Mapper
public interface UserDatabasePermissionMapper extends BaseMapper<UserDatabasePermission> {
}
