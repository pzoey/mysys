package org.dromara.system.dingtalk.support;

import java.util.List;

/**
 * 与一期 JustAuth 钉钉登录对齐的绑定常量。
 * <p>
 * {@code AuthDingTalkV2Request} 的 source 是 {@code DINGTALK_V2}，uuid 是 unionId。
 * 登录按 {@code source + uuid} 查 {@code sys_social.auth_id}。
 *
 * @author dromara
 */
public final class DingTalkSocials {

    /**
     * 与 JustAuth AuthUser.source 一致的来源。
     */
    public static final String SOURCE = "DINGTALK_V2";

    /**
     * 同步占位 token。通讯录接口没有用户级 OAuth token，扫码登录后的绑定记录不会被空 token 覆盖。
     */
    public static final String TOKEN_PLACEHOLDER = "-";

    /**
     * 已有登录绑定可能写成这些来源，匹配时一并识别。
     */
    public static final List<String> SOURCES = List.of(SOURCE, "dingtalk", "DINGTALK");

    private DingTalkSocials() {
    }

    /**
     * 生成登录使用的 authId。
     *
     * @param unionIdOrUserId 优先 unionId，没有 unionId 时用钉钉 userid
     * @return authId
     */
    public static String authId(String unionIdOrUserId) {
        return SOURCE + unionIdOrUserId;
    }

}
