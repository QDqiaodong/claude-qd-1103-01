SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET character_set_client = utf8mb4;
SET character_set_connection = utf8mb4;
SET character_set_results = utf8mb4;

-- ---------------------------------------------------------------------
-- 奶茶饮品店 · 原料库存与配方出单 + 会员档案合并 / 联系方式变更审计
-- 库名 milktea_bar；docker 构建期实际建库使用 mysql/schema.sql 本文件内容一致。
-- mysql 容器是 UTC，时间戳一律写 UTC_TIMESTAMP() + INTERVAL 8 HOUR（北京时间）。

CREATE DATABASE IF NOT EXISTS milktea_bar
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE milktea_bar;

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS cup_order_stock_snapshot;
DROP TABLE IF EXISTS cup_order_failure;
DROP TABLE IF EXISTS recipe_topping;
DROP TABLE IF EXISTS cup_order_item;
DROP TABLE IF EXISTS cup_order;
DROP TABLE IF EXISTS member_contact_change_log;
DROP TABLE IF EXISTS member_points_ledger;
DROP TABLE IF EXISTS member_merge_request;
DROP TABLE IF EXISTS member_phone_guard;
DROP TABLE IF EXISTS member;
DROP TABLE IF EXISTS recipe;
DROP TABLE IF EXISTS ingredient;
SET FOREIGN_KEY_CHECKS = 1;

-- ---------------- 原料库存 ----------------
CREATE TABLE ingredient (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(64) NOT NULL UNIQUE COMMENT '原料名',
    category   VARCHAR(16) NOT NULL COMMENT '茶底 / 奶 / 小料 / 糖浆',
    stock      INT         NOT NULL COMMENT '库存',
    unit       VARCHAR(16) NOT NULL COMMENT '单位',
    status     VARCHAR(16) NOT NULL COMMENT '启用 / 停用',
    created_at DATETIME    COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 配方 ----------------
CREATE TABLE recipe (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(64) NOT NULL UNIQUE COMMENT '配方名',
    base_tea   VARCHAR(64) NOT NULL COMMENT '茶底',
    price      DECIMAL(10,2) NOT NULL COMMENT '售价',
    created_at DATETIME COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 配方小料用量：@ElementCollection + @CollectionTable + @MapKeyColumn 承载 Map<String,Integer>
CREATE TABLE recipe_topping (
    recipe_id     BIGINT NOT NULL,
    topping_name  VARCHAR(64) NOT NULL COMMENT '小料名（= ingredient.name）',
    qty           INT NOT NULL COMMENT '份数',
    PRIMARY KEY (recipe_id, topping_name),
    CONSTRAINT fk_topping_recipe FOREIGN KEY (recipe_id) REFERENCES recipe (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 会员 ----------------
-- 有效会员（status='有效'）手机号全局唯一，由 member_phone_guard 行锁 +
-- 应用层最新版本校验共同保证；已合并档（status='已合并'）保留只读别名与 phone 快照，
-- 不参与唯一性，因此 member.phone 不再建数据库 UNIQUE。
CREATE TABLE member (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    phone         VARCHAR(24) NOT NULL COMMENT '手机号（有效会员唯一，已合并档保留别名）',
    name          VARCHAR(32) NOT NULL COMMENT '昵称',
    points        INT          NOT NULL DEFAULT 0 COMMENT '累计积分（与积分流水合计一致）',
    level         VARCHAR(16) NOT NULL COMMENT '普通 / 银卡 / 金卡',
    status        VARCHAR(16) NOT NULL DEFAULT '有效' COMMENT '有效 / 已合并',
    version       BIGINT      NOT NULL DEFAULT 0 COMMENT '乐观锁版本（服务端按最新版本校验）',
    merged_into_id BIGINT     COMMENT '合并后归属的主档 id（已合并档只读追溯用）',
    merged_at     DATETIME    COMMENT '被合并时间',
    created_at    DATETIME    COMMENT '创建时间',
    KEY idx_member_phone (phone),
    KEY idx_member_status (status),
    KEY idx_member_merged_into (merged_into_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 有效手机号守卫：一个有效手机号恰好一行。建会员 / 改手机号 / 合并冲突处理时
-- 对涉及号码行做 SELECT ... FOR UPDATE，串行化“同一手机号绑定两个有效会员”。
CREATE TABLE member_phone_guard (
    phone      VARCHAR(24) NOT NULL PRIMARY KEY COMMENT '有效手机号',
    member_id  BIGINT      NOT NULL COMMENT '当前绑定的有效会员 id',
    updated_at DATETIME    COMMENT '最近一次绑定变更时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 会员合并工单 ----------------
-- 状态机：待处理 PENDING → 已合并 MERGED →（仅负责人）已撤销 REVOKED；
-- 任一阶段检查到手机号/积分来源问题则进入 CONFLICT，解决后回到 PENDING 再执行。
CREATE TABLE member_merge_request (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    master_id        BIGINT      NOT NULL COMMENT '保留的主档 id',
    slave_id         BIGINT      NOT NULL COMMENT '待并入的重复档 id',
    status           VARCHAR(24) NOT NULL COMMENT 'PENDING / CONFLICT / MERGED / REVOKED',
    reason           VARCHAR(255) NOT NULL COMMENT '合并原因',
    operator         VARCHAR(32) NOT NULL COMMENT '发起人',
    conflict_types   VARCHAR(255) COMMENT '冲突类型：PHONE_CONFLICT / POINTS_SOURCE（逗号分隔）',
    conflict_detail  VARCHAR(1000) COMMENT '冲突说明',
    resolved_phone   VARCHAR(24) COMMENT '手机号冲突解决后主档保留的手机号',
    points_strategy  VARCHAR(32) COMMENT '积分来源冲突解决策略：TRUST_BALANCE / REBUILD_FROM_LEDGER',
    merge_operator   VARCHAR(32) COMMENT '执行合并的操作人',
    merged_at        DATETIME    COMMENT '合并完成时间',
    revoke_operator  VARCHAR(32) COMMENT '撤销发起人（仅负责人）',
    revoke_reason    VARCHAR(255) COMMENT '撤销原因',
    revoked_at       DATETIME    COMMENT '撤销时间',
    master_snapshot  LONGTEXT    COMMENT '合并前主档快照（手机/姓名/积分/等级）',
    slave_snapshot   LONGTEXT    COMMENT '合并前从档快照',
    result_summary   VARCHAR(1000) COMMENT '合并结果说明（迁移订单数/流水数/积分合计）',
    revoke_summary   VARCHAR(1000) COMMENT '撤销结果说明（还原订单数/流水数）',
    created_at       DATETIME    COMMENT '创建时间',
    version          BIGINT      NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    -- 仅 PENDING/CONFLICT 时有值（master-slave 对），已合并/已撤销为 NULL；
    -- MySQL 唯一索引允许多个 NULL，因此“同一对档案至多一张开放工单”成立，
    -- 而 合并→撤销→重新合并→再撤销 的完整生命周期不被唯一索引挡住。
    open_key         VARCHAR(64) COMMENT '开放工单去重键（仅待处理/冲突待处理有值）',
    UNIQUE KEY uk_merge_open (open_key),
    KEY idx_merge_pair (master_id, slave_id),
    KEY idx_merge_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 会员积分流水 ----------------
-- 每一笔积分变动都有流水；member_id 为当前归属（合并后改为主档，撤销还原），
-- origin_member_id 为原始产生该积分的档案（撤销时按它还原归属），绝不重复计算。
CREATE TABLE member_points_ledger (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id        BIGINT      NOT NULL COMMENT '当前归属会员 id',
    origin_member_id BIGINT      NOT NULL COMMENT '积分原始产生会员 id（还原归属用）',
    change_type      VARCHAR(24) NOT NULL COMMENT 'OPENING / EARN / REFUND / MANUAL / MERGE_MARK / REVOKE_MARK',
    points_delta     INT         NOT NULL COMMENT '本次变动（正为增加，负为扣减，标记为 0）',
    balance_after    INT         NOT NULL COMMENT '在当前归属档案上的变动后余额',
    ref_type         VARCHAR(24) COMMENT 'OPENING / ORDER / MANUAL / MERGE / REVOKE',
    ref_id           BIGINT      COMMENT '关联单据 id（订单/合并工单等）',
    remark           VARCHAR(255) COMMENT '说明',
    operator         VARCHAR(32) COMMENT '操作人',
    created_at       DATETIME    NOT NULL COMMENT '生效时间',
    KEY idx_ledger_member (member_id, id),
    KEY idx_ledger_origin (origin_member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 会员联系方式变更审计 ----------------
-- 手机号或姓名每一次修改都留痕：前后值、原因、操作人、生效时间；不做物理删除/覆盖。
CREATE TABLE member_contact_change_log (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id         BIGINT      NOT NULL COMMENT '变更时的会员 id（可能已合并）',
    current_member_id BIGINT      COMMENT '当前归属主档 id（合并后查询可追溯）',
    field_name        VARCHAR(16) NOT NULL COMMENT 'PHONE / NAME',
    before_value      VARCHAR(64) COMMENT '变更前值',
    after_value       VARCHAR(64) COMMENT '变更后值',
    reason            VARCHAR(255) NOT NULL COMMENT '变更原因',
    operator          VARCHAR(32) NOT NULL COMMENT '操作人',
    effective_at      DATETIME    NOT NULL COMMENT '生效时间',
    KEY idx_contact_member (member_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 出杯记录 ----------------
CREATE TABLE cup_order (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id        BIGINT     COMMENT '会员当前归属 id（散客为空；合并后归主档，撤销还原）',
    origin_member_id BIGINT     COMMENT '下单时原始会员 id（撤销合并还原归属依据）',
    pay_type         VARCHAR(16) NOT NULL COMMENT '微信 / 支付宝 / 现金',
    total            DECIMAL(10,2) NOT NULL COMMENT '金额合计（自动汇总）',
    status           VARCHAR(16) NOT NULL COMMENT '已出杯 / 已退款',
    points_earned    INT         COMMENT '本次为会员带来的积分（退款回退用）',
    created_at       DATETIME    COMMENT '创建时间',
    KEY idx_order_member (member_id),
    KEY idx_order_origin (origin_member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cup_order_item (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id   BIGINT NOT NULL COMMENT '所属出杯单',
    recipe_id  BIGINT NOT NULL COMMENT '配方 id',
    qty        INT    NOT NULL COMMENT '杯数',
    CONSTRAINT fk_item_order FOREIGN KEY (order_id) REFERENCES cup_order (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 原料扣减快照 ----------------
-- 新订单出杯时按当次配方实际用量保存，之后配方/原料如何编辑都不改变历史消耗；
-- 退款时严格按快照逐行回补，refund_qty 为 NULL 表示尚未回补。
CREATE TABLE cup_order_stock_snapshot (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id        BIGINT      NOT NULL COMMENT '所属出杯单',
    ingredient_id   BIGINT      NOT NULL COMMENT '原料 id（可追溯）',
    ingredient_name VARCHAR(64) NOT NULL COMMENT '下单时原料名（快照）',
    unit            VARCHAR(16) NOT NULL COMMENT '下单时单位（快照）',
    used_qty        INT         NOT NULL COMMENT '本次实际扣减量',
    refund_qty      INT         COMMENT '退款已回补量（未退款为 NULL）',
    CONSTRAINT fk_snapshot_order FOREIGN KEY (order_id) REFERENCES cup_order (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 出杯失败记录 ----------------
-- 任一原料不足/停用/配方失效即整单不落 cup_order，此处只留审计记录，
-- 不扣库存、不加积分，供出杯记录页查看失败原因。
CREATE TABLE cup_order_failure (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id     BIGINT       COMMENT '会员 id（散客为空）',
    member_label  VARCHAR(64)  COMMENT '下单时会员描述（快照）',
    pay_type      VARCHAR(16)  NOT NULL COMMENT '支付方式',
    request_items VARCHAR(255) NOT NULL COMMENT '请求的配方与杯数',
    reason        VARCHAR(500) NOT NULL COMMENT '失败原因',
    created_at    DATETIME     COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =====================================================================
-- 种子数据
-- =====================================================================

INSERT IGNORE INTO ingredient (name, category, stock, unit, status, created_at) VALUES
('红茶',     '茶底', 50, 'g',  '启用', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
('绿茶',     '茶底', 40, 'g',  '启用', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
('乌龙茶',   '茶底', 30, 'g',  '启用', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
('鲜牛奶',   '奶',   60, 'ml', '启用', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
('珍珠',     '小料', 100, '份', '启用', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 55 DAY),
('椰果',     '小料', 80,  '份', '启用', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 55 DAY),
('红豆',     '小料', 70,  '份', '启用', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 55 DAY),
('布丁',     '小料', 40,  '份', '停用', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 DAY),
('黑糖糖浆', '糖浆', 90,  'ml', '启用', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 DAY),
('蜂蜜',     '糖浆', 35,  'ml', '停用', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 DAY);

INSERT IGNORE INTO recipe (name, base_tea, price, created_at) VALUES
('招牌珍珠奶茶', '红茶', 12.00, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY),
('椰果绿茶',     '绿茶', 11.00, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY),
('红豆乌龙',     '乌龙茶', 13.00, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 35 DAY),
('双倍珍珠奶茶', '红茶', 15.00, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 35 DAY),
('椰香红豆奶',   '绿茶', 12.00, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY);

INSERT IGNORE INTO recipe_topping (recipe_id, topping_name, qty) VALUES
(1, '珍珠', 2), (1, '鲜牛奶', 1),
(2, '椰果', 2),
(3, '红豆', 2), (3, '黑糖糖浆', 1),
(4, '珍珠', 3), (4, '鲜牛奶', 1),
(5, '红豆', 1), (5, '椰果', 1);

INSERT IGNORE INTO member (id, phone, name, points, level, status, version, created_at) VALUES
(1, '13800001111', '小明', 300,  '普通', '有效', 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY),
(2, '13800002222', '小红', 600,  '银卡', '有效', 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY),
(3, '13800003333', '阿强', 2200, '金卡', '有效', 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY),
(4, '13800004444', '丽丽', 120,  '普通', '有效', 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 18 DAY),
(5, '13800005555', '老王', 1800, '银卡', '有效', 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 18 DAY),
(6, '13800006666', '婷婷', 50,   '普通', '有效', 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 15 DAY);

-- 种子的有效手机号各自唯一；同手机号重复会员场景由“历史档案导入”入口构造（见验收脚本）。
INSERT IGNORE INTO member_phone_guard (phone, member_id, updated_at) VALUES
('13800001111', 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY),
('13800002222', 2, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY),
('13800003333', 3, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY),
('13800004444', 4, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 18 DAY),
('13800005555', 5, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 18 DAY),
('13800006666', 6, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 15 DAY);

INSERT IGNORE INTO cup_order (id, member_id, origin_member_id, pay_type, total, status, points_earned, created_at) VALUES
(1, 1, 1, '微信',   24.00, '已出杯', 24, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 DAY),
(2, 2, 2, '支付宝', 11.00, '已出杯', 11, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 9 DAY),
(3, 3, 3, '现金',   28.00, '已出杯', 28, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 8 DAY),
(4, 1, 1, '微信',   36.00, '已退款', 36, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 7 DAY),
(5, NULL, NULL, '现金', 12.00, '已出杯', 0,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 DAY);

INSERT IGNORE INTO cup_order_item (order_id, recipe_id, qty) VALUES
(1, 1, 2),
(2, 2, 1),
(3, 3, 1), (3, 4, 1),
(4, 5, 3),
(5, 1, 1);

-- 种子会员的积分流水（开档余额 + 逐笔订单/退款，余额合计与 member.points 严格一致）：
-- 会员#1：开档 276 → 订单#1 +24=300 → 订单#4 +36=336 → 订单#4退款 -36=300
-- 会员#2：开档 589 → 订单#2 +11=600；会员#3：开档 2172 → 订单#3 +28=2200
INSERT IGNORE INTO member_points_ledger
    (member_id, origin_member_id, change_type, points_delta, balance_after, ref_type, ref_id, remark, operator, created_at)
VALUES
(1, 1, 'OPENING', 276, 276, 'OPENING', NULL, '开档累计积分', '系统', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 21 DAY),
(2, 2, 'OPENING', 589, 589, 'OPENING', NULL, '开档累计积分', '系统', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 21 DAY),
(3, 3, 'OPENING', 2172, 2172, 'OPENING', NULL, '开档累计积分', '系统', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 21 DAY),
(4, 4, 'OPENING', 120, 120, 'OPENING', NULL, '开档累计积分', '系统', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 21 DAY),
(5, 5, 'OPENING', 1800, 1800, 'OPENING', NULL, '开档累计积分', '系统', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 21 DAY),
(6, 6, 'OPENING', 50, 50, 'OPENING', NULL, '开档累计积分', '系统', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 21 DAY),
(1, 1, 'EARN', 24, 300, 'ORDER', 1, '订单#1 消费累计积分', '系统', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 DAY),
(2, 2, 'EARN', 11, 600, 'ORDER', 2, '订单#2 消费累计积分', '系统', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 9 DAY),
(3, 3, 'EARN', 28, 2200, 'ORDER', 3, '订单#3 消费累计积分', '系统', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 8 DAY),
(1, 1, 'EARN', 36, 336, 'ORDER', 4, '订单#4 消费累计积分', '系统', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 7 DAY + INTERVAL 10 MINUTE),
(1, 1, 'REFUND', -36, 300, 'ORDER', 4, '订单#4 退款回退积分', '系统', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 7 DAY + INTERVAL 20 MINUTE);
