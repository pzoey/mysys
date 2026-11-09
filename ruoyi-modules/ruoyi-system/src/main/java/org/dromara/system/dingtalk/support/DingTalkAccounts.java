package org.dromara.system.dingtalk.support;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;

import java.util.function.Predicate;

/**
 * 把钉钉账号字段转换成系统用户可用的手机号和登录名。
 *
 * @author dromara
 */
public final class DingTalkAccounts {

    private DingTalkAccounts() {
    }

    /**
     * 归一化成 11 位大陆手机号。无法识别时返回 null，不写入 sys_user.phone_number。
     *
     * @param raw 钉钉手机号
     * @return 11 位手机号或 null
     */
    public static String normalizeMobile(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch >= '0' && ch <= '9') {
                digits.append(ch);
            }
        }
        String value = digits.toString();
        if (value.startsWith("86") && value.length() == 13) {
            value = value.substring(2);
        }
        if (value.length() == 11 && value.startsWith("1")) {
            return value;
        }
        return null;
    }

    /**
     * 截断并去掉空白。空串返回 null。
     *
     * @param value 原文本
     * @param max   最大长度
     * @return 截断后的文本
     */
    public static String cut(String value, int max) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    /**
     * 生成不重复的登录名。优先工号，其次手机号，最后钉钉 userid。
     *
     * @param jobNumber 工号
     * @param mobile    已归一化的手机号
     * @param dingUserId 钉钉 userid
     * @param exists    登录名是否已存在
     * @return 登录名
     */
    public static String buildUserName(String jobNumber, String mobile, String dingUserId, Predicate<String> exists) {
        String base = sanitize(jobNumber);
        if (base == null) {
            base = sanitize(mobile);
        }
        if (base == null) {
            base = sanitize(dingUserId);
            if (base != null) {
                base = "d" + base;
            }
        }
        if (base == null) {
            base = "dinguser";
        }
        if (Character.isDigit(base.charAt(0))) {
            base = "u" + base;
        }
        base = cut(base, 26);
        String candidate = base;
        int seq = 2;
        while (exists.test(candidate)) {
            if (seq > 100) {
                throw new ServiceException("无法为钉钉用户生成唯一登录名");
            }
            String suffix = "_" + seq++;
            candidate = cut(base, 30 - suffix.length()) + suffix;
        }
        return candidate;
    }

    private static String sanitize(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '_') {
                builder.append(ch);
            }
        }
        return builder.isEmpty() ? null : builder.toString();
    }

}
