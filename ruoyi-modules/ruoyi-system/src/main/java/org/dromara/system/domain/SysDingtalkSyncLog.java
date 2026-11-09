package org.dromara.system.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 钉钉组织同步批次。
 *
 * @author dromara
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_dingtalk_sync_log")
public class SysDingtalkSyncLog extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 批次号
     */
    private String batchNo;

    /**
     * 状态（0进行中 1成功 2部分失败 3失败）
     */
    private String status;

    /**
     * 部门成功数
     */
    private Integer deptSuccess;

    /**
     * 部门失败数
     */
    private Integer deptFail;

    /**
     * 用户成功数
     */
    private Integer userSuccess;

    /**
     * 用户失败数
     */
    private Integer userFail;

    /**
     * 用户跳过数
     */
    private Integer userSkip;

    /**
     * 结果摘要
     */
    private String summary;

    /**
     * 失败明细
     */
    private String failDetail;

    /**
     * 开始时间
     */
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    private LocalDateTime finishTime;

}
