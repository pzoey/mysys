package org.dromara.system.dingtalk.client;

import lombok.Getter;

import java.io.Serial;

/**
 * 钉钉开放平台返回的业务错误。
 *
 * @author dromara
 */
@Getter
public class DingTalkApiException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 钉钉 errcode。
     */
    private final int errCode;

    /**
     * 使用已经脱敏的提示构造异常。
     *
     * @param errCode 钉钉错误码
     * @param message 可对外展示的提示
     */
    public DingTalkApiException(int errCode, String message) {
        super(message);
        this.errCode = errCode;
    }

}
