package com.milktea.bar.service;

import com.milktea.bar.common.BizException;
import com.milktea.bar.common.Dict;

/**
 * 操作人/角色解析：所有写接口都必须带操作人；负责人才能撤销合并、解决冲突、
 * 导入历史重复档。无鉴权中间件，角色由请求显式携带并在服务端强校验。
 */
final class OperatorContext {

    final String name;
    final String role;

    private OperatorContext(String name, String role) {
        this.name = name;
        this.role = role;
    }

    static OperatorContext of(String name, String role) {
        if (name == null || name.isBlank()) {
            throw new BizException("缺少操作人");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 32) {
            throw new BizException("操作人名称过长");
        }
        String r = (role == null || role.isBlank()) ? Dict.ROLE_STAFF : role.trim();
        if (!Dict.OPERATOR_ROLES.contains(r)) {
            throw new BizException("操作人角色必须是：店员 / 负责人");
        }
        return new OperatorContext(trimmed, r);
    }

    void requireManager(String action) {
        if (!Dict.ROLE_MANAGER.equals(role)) {
            throw new BizException(action + "只能由负责人发起");
        }
    }
}
