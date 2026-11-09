package org.dromara.system.dingtalk.support;

/**
 * 钉钉成员匹配本地用户的优先级：手机号、unionId、钉钉 userid。
 *
 * @author dromara
 */
public final class DingTalkUserMatch {

    private DingTalkUserMatch() {
    }

    /**
     * 命中方式。
     */
    public enum By {
        /**
         * 手机号。
         */
        MOBILE,
        /**
         * unionId。
         */
        UNION_ID,
        /**
         * 钉钉 userid。
         */
        USER_ID,
        /**
         * 未命中。
         */
        NONE
    }

    /**
     * 已有的社会化绑定引用。
     *
     * @param socialId sys_social.id
     * @param userId   本地用户 ID
     */
    public record SocialRef(Long socialId, Long userId) {
    }

    /**
     * 匹配结果。按手机号命中时 socialId 为空，调用方再按用户查绑定。
     *
     * @param userId   本地用户 ID
     * @param socialId 社会化关系 ID
     * @param by       命中方式
     */
    public record Hit(Long userId, Long socialId, By by) {
    }

    /**
     * 按手机号、unionId、钉钉 userid 的顺序匹配。
     *
     * @param mobileUserId 手机号命中的本地用户
     * @param unionRef     unionId 命中的绑定
     * @param dingUserRef  钉钉 userid 命中的绑定
     * @return 匹配结果
     */
    public static Hit match(Long mobileUserId, SocialRef unionRef, SocialRef dingUserRef) {
        if (mobileUserId != null) {
            return new Hit(mobileUserId, null, By.MOBILE);
        }
        if (unionRef != null && unionRef.userId() != null) {
            return new Hit(unionRef.userId(), unionRef.socialId(), By.UNION_ID);
        }
        if (dingUserRef != null && dingUserRef.userId() != null) {
            return new Hit(dingUserRef.userId(), dingUserRef.socialId(), By.USER_ID);
        }
        return new Hit(null, null, By.NONE);
    }

}
