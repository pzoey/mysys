package org.dromara.system.controller.system;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.redis.annotation.RepeatSubmit;
import org.dromara.common.web.core.BaseController;
import org.dromara.system.domain.vo.SysDingtalkSyncLogVo;
import org.dromara.system.service.IDingTalkOrgSyncService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * 钉钉组织同步。接口需要登录，并校验 system:dingtalk 权限。
 *
 * @author dromara
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/dingtalk/sync")
public class SysDingtalkSyncController extends BaseController {

    private final IDingTalkOrgSyncService syncService;

    /**
     * 分页查询同步批次。
     *
     * @param pageQuery 分页参数
     * @return 批次分页
     */
    @SaCheckPermission("system:dingtalk:query")
    @GetMapping("/list")
    public R<PageResult<SysDingtalkSyncLogVo>> list(PageQuery pageQuery) {
        return R.ok(syncService.queryPage(pageQuery));
    }

    /**
     * 查询最近一次同步结果。
     *
     * @return 最近批次，没有记录时 data 为 null
     */
    @SaCheckPermission("system:dingtalk:query")
    @GetMapping("/latest")
    public R<SysDingtalkSyncLogVo> latest() {
        return R.ok(syncService.latest());
    }

    /**
     * 手动触发一次全量同步。
     *
     * @return 本批次结果。部门或人员部分失败时仍返回批次，状态见 status
     */
    @SaCheckPermission("system:dingtalk:sync")
    @Log(title = "钉钉组织同步", businessType = BusinessType.OTHER)
    @RepeatSubmit(interval = 10, timeUnit = TimeUnit.SECONDS)
    @PostMapping
    public R<SysDingtalkSyncLogVo> sync() {
        return R.ok(syncService.syncAll());
    }

}
