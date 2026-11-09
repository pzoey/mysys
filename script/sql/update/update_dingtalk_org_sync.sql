-- 钉钉组织同步（MySQL）
-- 存量库执行本脚本。全新建库使用 script/sql/ry_vue.sql 时已包含这两张表和菜单。
-- 不改 sys_dept / sys_user 主表，钉钉部门只记在映射表。

CREATE TABLE IF NOT EXISTS sys_dingtalk_dept (
    id              bigint(20)      not null                   comment '主键',
    dept_id         bigint(20)      not null                   comment '本地部门ID',
    ding_dept_id    bigint(20)      not null                   comment '钉钉部门ID',
    ding_parent_id  bigint(20)      default null               comment '钉钉父部门ID',
    create_dept     bigint(20)      default null               comment '创建部门',
    create_by       bigint(20)      default null               comment '创建者',
    create_time     datetime                                   comment '创建时间',
    update_by       bigint(20)      default null               comment '更新者',
    update_time     datetime                                   comment '更新时间',
    primary key (id),
    unique key uk_sys_dingtalk_dept_ding (ding_dept_id),
    unique key uk_sys_dingtalk_dept_local (dept_id)
) engine=innodb comment = '钉钉部门与本地部门映射';

CREATE TABLE IF NOT EXISTS sys_dingtalk_sync_log (
    id              bigint(20)      not null                   comment '主键',
    batch_no        varchar(32)     not null                   comment '同步批次号',
    status          char(1)         not null                   comment '状态（0进行中 1成功 2部分失败 3失败）',
    dept_success    int             default 0                  comment '部门成功数',
    dept_fail       int             default 0                  comment '部门失败数',
    user_success    int             default 0                  comment '用户成功数',
    user_fail       int             default 0                  comment '用户失败数',
    user_skip       int             default 0                  comment '用户跳过数',
    summary         varchar(500)    default null               comment '结果摘要',
    fail_detail     varchar(2000)   default null               comment '失败明细',
    start_time      datetime                                   comment '开始时间',
    finish_time     datetime                                   comment '结束时间',
    create_dept     bigint(20)      default null               comment '创建部门',
    create_by       bigint(20)      default null               comment '创建者',
    create_time     datetime                                   comment '创建时间',
    update_by       bigint(20)      default null               comment '更新者',
    update_time     datetime                                   comment '更新时间',
    primary key (id),
    key idx_sys_dingtalk_sync_start (start_time)
) engine=innodb comment = '钉钉组织同步批次';

INSERT INTO sys_menu
SELECT 1761400000000000140, '钉钉组织同步', 1761400000000000001, 12, 'dingtalk', 'system/dingtalk/index', '', 'N', 'Y', 'C', '0', '0', 'system:dingtalk:query', 'international', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '从钉钉全量同步部门与人员'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1761400000000000140);

INSERT INTO sys_menu
SELECT 1761400000000000141, '钉钉同步', 1761400000000000140, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:dingtalk:sync', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, ''
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1761400000000000141);
