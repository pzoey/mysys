package org.dromara.system.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * 钉钉部门与本地部门的映射。本地部门仍以 sys_dept 为准。
 *
 * @author dromara
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_dingtalk_dept")
public class SysDingtalkDept extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 本地部门 ID
     */
    private Long deptId;

    /**
     * 钉钉部门 ID
     */
    private Long dingDeptId;

    /**
     * 钉钉父部门 ID
     */
    private Long dingParentId;

}
