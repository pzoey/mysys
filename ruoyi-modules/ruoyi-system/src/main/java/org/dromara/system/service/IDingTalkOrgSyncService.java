package org.dromara.system.service;

import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.system.domain.vo.SysDingtalkSyncLogVo;

/**
 * 钉钉组织与人员同步到本地部门、用户和社交绑定。
 *
 * @author dromara
 */
public interface IDingTalkOrgSyncService {

    /**
     * 手动全量同步。同步进行中时拒绝重复触发。
     *
     * @return 本批次结果
     */
    SysDingtalkSyncLogVo syncAll();

    /**
     * 最近一次同步结果。没有记录时返回 null。
     *
     * @return 最近批次
     */
    SysDingtalkSyncLogVo latest();

    /**
     * 分页查询同步批次，按开始时间倒序。
     *
     * @param pageQuery 分页参数
     * @return 批次分页
     */
    PageResult<SysDingtalkSyncLogVo> queryPage(PageQuery pageQuery);

}
