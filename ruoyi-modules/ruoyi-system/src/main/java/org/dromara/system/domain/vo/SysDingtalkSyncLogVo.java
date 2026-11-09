package org.dromara.system.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 钉钉组织同步批次结果。
 *
 * @author dromara
 */
@Data
public class SysDingtalkSyncLogVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private String batchNo;

    /**
     * 0进行中 1成功 2部分失败 3失败
     */
    private String status;

    /**
     * 状态说明
     */
    private String statusName;

    private Integer deptSuccess;

    private Integer deptFail;

    private Integer userSuccess;

    private Integer userFail;

    private Integer userSkip;

    private String summary;

    private String failDetail;

    private LocalDateTime startTime;

    private LocalDateTime finishTime;

}
