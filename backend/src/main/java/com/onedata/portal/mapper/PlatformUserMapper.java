package com.onedata.portal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.onedata.portal.entity.PlatformUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * 平台用户Mapper
 */
@Mapper
public interface PlatformUserMapper extends BaseMapper<PlatformUser> {
}
