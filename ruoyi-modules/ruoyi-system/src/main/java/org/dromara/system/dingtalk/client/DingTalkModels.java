package org.dromara.system.dingtalk.client;

import java.util.List;

/**
 * 钉钉通讯录接口用到的只读结构。
 *
 * @author dromara
 */
public final class DingTalkModels {

    private DingTalkModels() {
    }

    /**
     * 钉钉部门。
     *
     * @param deptId   钉钉部门 ID
     * @param parentId 钉钉父部门 ID，根部门为 0
     * @param name     部门名称
     * @param order    排序
     */
    public record DingDepartment(long deptId, long parentId, String name, int order) {
    }

    /**
     * 钉钉成员。leader 表示本次查询部门内是否为主管。
     *
     * @param userId    钉钉 userid
     * @param unionId   钉钉 unionId
     * @param name      姓名
     * @param mobile    手机号，可能为空
     * @param email     邮箱
     * @param title     职位
     * @param jobNumber 工号
     * @param avatar    头像地址
     * @param active    是否已激活
     * @param leader    在当前部门是否为主管
     * @param deptIds   所属钉钉部门
     */
    public record DingEmployee(
        String userId,
        String unionId,
        String name,
        String mobile,
        String email,
        String title,
        String jobNumber,
        String avatar,
        boolean active,
        boolean leader,
        List<Long> deptIds
    ) {
    }

    /**
     * 部门用户分页。
     *
     * @param users      当前页用户
     * @param hasMore    是否还有下一页
     * @param nextCursor 下一页游标
     */
    public record DingUserPage(List<DingEmployee> users, boolean hasMore, long nextCursor) {
    }

}
