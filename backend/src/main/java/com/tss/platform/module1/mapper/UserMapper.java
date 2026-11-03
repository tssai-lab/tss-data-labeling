package com.tss.platform.module1.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tss.platform.module1.dto.UserQueryDTO;
import com.tss.platform.module1.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface UserMapper extends BaseMapper<User> {
    @Select("""
            <script>
            SELECT u.id, u.username, u.mobile, u.email, u.role_id, u.status,
                   u.created_at, u.updated_at,
                   COALESCE(r.role_name, '普通用户') AS role_name
            FROM users u
            LEFT JOIN roles r ON u.role_id = r.id
            WHERE u.deleted_at IS NULL
            <if test="requiredRoleId != null">
              AND u.role_id = #{requiredRoleId}
            </if>
            ORDER BY u.created_at DESC, u.id DESC
            </script>
            """)
    List<Map<String, Object>> selectUserWithRole(@Param("requiredRoleId") Integer requiredRoleId);

    IPage<Map<String, Object>> selectUserPage(
            Page<Map<String, Object>> page,
            @Param("query") UserQueryDTO queryDTO,
            @Param("requiredRoleId") Integer requiredRoleId
    );

    @Select("""
            <script>
            SELECT u.id, u.username, u.mobile, u.email, u.role_id, u.status,
                   u.created_at, u.updated_at, r.role_name
            FROM users u
            JOIN roles r ON u.role_id = r.id
            WHERE u.id = #{userId} AND u.deleted_at IS NULL
            <if test="requiredRoleId != null">
              AND u.role_id = #{requiredRoleId}
            </if>
            </script>
            """)
    Map<String, Object> selectUserDetail(
            @Param("userId") Integer userId,
            @Param("requiredRoleId") Integer requiredRoleId
    );
}
