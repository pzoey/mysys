package org.dromara.system.dingtalk.config;

import lombok.Data;
import lombok.ToString;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 钉钉通讯录同步配置。
 * <p>
 * client-id / client-secret 留空时，回退到 {@code justauth.type.dingtalk} 的登录凭证。
 * 不要在代码或配置样例中写入真实 AppSecret。
 *
 * @author dromara
 */
@Data
@Component
@ConfigurationProperties(prefix = "dingtalk")
public class DingTalkProperties {

    /**
     * 钉钉应用 AppKey。为空时使用 justauth.type.dingtalk.client-id。
     */
    private String clientId;

    /**
     * 钉钉应用 AppSecret。为空时使用 justauth.type.dingtalk.client-secret。
     */
    @ToString.Exclude
    private String clientSecret;

    /**
     * 旧版开放平台地址，通讯录 topapi 走这里。
     */
    private String apiBaseUrl = "https://oapi.dingtalk.com";

    /**
     * 同步行为。
     */
    private Sync sync = new Sync();

    /**
     * 去掉末尾斜杠后的接口根地址。
     *
     * @return 接口根地址
     */
    public String resolvedApiBaseUrl() {
        if (StringUtils.isBlank(apiBaseUrl)) {
            return "https://oapi.dingtalk.com";
        }
        String value = apiBaseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /**
     * 同步参数。
     */
    @Data
    public static class Sync {

        /**
         * 两次钉钉请求之间的最小间隔，避免打满通讯录接口 QPS。
         */
        private long minIntervalMs = 100L;

        /**
         * 部门用户分页大小，钉钉上限 100。
         */
        private int userPageSize = 100;

        /**
         * 遇到限流时的退避基数，单位毫秒。
         */
        private int retryDelayMs = 300;

        /**
         * 钉钉根部门挂载到的本地部门。为空时挂到 parentId=0 的根部门下，并为钉钉根部门单独建本地部门。
         */
        private Long anchorDeptId;

        /**
         * 仅新建用户时授予的角色。为空则不授角色，也不会授予超级管理员。
         */
        private Long defaultRoleId;

    }

}
