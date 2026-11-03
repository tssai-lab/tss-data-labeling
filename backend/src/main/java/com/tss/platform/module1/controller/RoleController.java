package com.tss.platform.module1.controller;

import com.tss.platform.module1.common.Result;
import com.tss.platform.module1.entity.Role;
import com.tss.platform.module1.service.RoleService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/role")
public class RoleController {

    @Resource
    private RoleService roleService;

    @GetMapping("/list")
    public Result<List<Role>> getRoleList() {
        List<Role> list = roleService.list();
        return Result.success(list, "查询成功");
    }

    @GetMapping("/detail/{roleId}")
    public Result<Role> getRoleDetail(@PathVariable Integer roleId) {
        Role role = roleService.getById(roleId);
        if (role == null) {
            return Result.fail("角色不存在");
        }
        return Result.success(role, "查询成功");
    }

    @GetMapping("/options")
    public Result<Map<Integer, String>> getRoleOptions() {
        List<Role> list = roleService.list();
        Map<Integer, String> options = new java.util.HashMap<>();
        for (Role role : list) {
            options.put(role.getId(), role.getRoleName());
        }
        return Result.success(options, "查询成功");
    }
}
