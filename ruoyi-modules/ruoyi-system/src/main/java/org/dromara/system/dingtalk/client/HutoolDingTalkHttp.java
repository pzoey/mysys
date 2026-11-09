package org.dromara.system.dingtalk.client;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import org.springframework.stereotype.Component;

/**
 * 使用 Hutool 调用钉钉开放平台。
 *
 * @author dromara
 */
@Component
public class HutoolDingTalkHttp implements DingTalkHttp {

    private static final int TIMEOUT_MILLIS = 15_000;

    /**
     * {@inheritDoc}
     */
    @Override
    public String exchange(String method, String url, String jsonBody) {
        HttpRequest request = "GET".equalsIgnoreCase(method) ? HttpRequest.get(url) : HttpRequest.post(url);
        request.timeout(TIMEOUT_MILLIS);
        if (jsonBody != null) {
            request.body(jsonBody).contentType("application/json");
        }
        try (HttpResponse response = request.execute()) {
            return response.body();
        }
    }

}
