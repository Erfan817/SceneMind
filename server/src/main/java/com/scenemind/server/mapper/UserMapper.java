package com.scenemind.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.scenemind.server.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
    //MyBatis-Plus自动搞定增删改查
}