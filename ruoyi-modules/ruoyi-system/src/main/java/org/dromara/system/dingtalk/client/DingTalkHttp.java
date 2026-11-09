package org.dromara.system.dingtalk.client;

/**
 * 钉钉 HTTP 调用抽象，便于测试时替换真实网络。
 *
 * @author dromara
 */
@FunctionalInterface
public interface DingTalkHttp {

    /**
     * 发起一次 HTTP 调用。
     *
     * @param method   GET 或 POST
     * @param url      完整地址
     * @param jsonBody POST 的 JSON 正文，GET 时为 null
     * @return 响应正文
     */
    String exchange(String method, String url, String jsonBody);

}
