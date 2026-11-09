package org.dromara.system.dingtalk.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.system.dingtalk.client.DingTalkModels.DingDepartment;
import org.dromara.system.dingtalk.client.DingTalkModels.DingUserPage;
import org.dromara.system.dingtalk.config.DingTalkProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 钉钉通讯录客户端：缓存 access_token，拉取部门树和部门用户。
 * 凭证只从配置读取。
 *
 * @author dromara
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DingTalkClient {

    /**
     * 钉钉根部门 ID。
     */
    public static final long ROOT_DEPT_ID = 1L;

    private static final String TOKEN_KEY = "dingtalk:contact:access_token";

    private static final int MAX_DEPARTMENTS = 5000;

    private static final int TOKEN_SKEW_SECONDS = 200;

    private static final int RATE_LIMIT_CODE = 88;

    private final DingTalkProperties properties;

    private final Environment environment;

    private final DingTalkHttp http;

    private final DingTalkTokenCache tokenCache;

    private final Object paceLock = new Object();

    private long nextAllowedAt;

    /**
     * 获取通讯录接口使用的 access_token，优先读缓存。
     *
     * @return access_token
     */
    public String getAccessToken() {
        String cached = tokenCache.get(TOKEN_KEY);
        if (StringUtils.isNotBlank(cached)) {
            return cached;
        }
        Credential credential = credential();
        String url = properties.resolvedApiBaseUrl()
            + "/gettoken?appkey=" + encode(credential.appKey())
            + "&appsecret=" + encode(credential.appSecret());
        JsonNode node = invoke(url, null, credential);
        String token = DingTalkJson.text(node, "access_token");
        if (StringUtils.isBlank(token)) {
            throw new ServiceException("钉钉未返回 access_token");
        }
        int expiresIn = DingTalkJson.intVal(node, "expires_in", 7200);
        long ttl = Math.max(60, expiresIn - TOKEN_SKEW_SECONDS);
        tokenCache.set(TOKEN_KEY, token, Duration.ofSeconds(ttl));
        return token;
    }

    /**
     * 从根部门开始按层级拉取全部部门，父部门排在子部门前面。
     *
     * @return 部门列表
     */
    public List<DingDepartment> listAllDepartments() {
        List<DingDepartment> all = new ArrayList<>();
        ArrayDeque<DingDepartment> queue = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        queue.add(getDepartment(ROOT_DEPT_ID));
        while (!queue.isEmpty()) {
            DingDepartment current = queue.removeFirst();
            if (!seen.add(current.deptId())) {
                continue;
            }
            if (all.size() >= MAX_DEPARTMENTS) {
                throw new ServiceException("钉钉部门数量超过上限，已中止同步");
            }
            all.add(current);
            queue.addAll(listSubDepartments(current.deptId()));
        }
        return all;
    }

    /**
     * 查询单个部门。
     *
     * @param deptId 钉钉部门 ID
     * @return 部门
     */
    public DingDepartment getDepartment(long deptId) {
        JsonNode node = postTopApi("/topapi/v2/department/get", Map.of("dept_id", deptId));
        JsonNode result = node.get("result");
        if (result == null || result.isNull()) {
            throw new ServiceException("钉钉部门不存在：" + deptId);
        }
        return DingTalkJson.department(result);
    }

    /**
     * 查询直接子部门。
     *
     * @param deptId 钉钉父部门 ID
     * @return 子部门
     */
    public List<DingDepartment> listSubDepartments(long deptId) {
        JsonNode node = postTopApi("/topapi/v2/department/listsub", Map.of("dept_id", deptId));
        return DingTalkJson.departments(node.get("result"));
    }

    /**
     * 分页查询部门下的用户。
     *
     * @param deptId 钉钉部门 ID
     * @param cursor 游标
     * @param size   分页大小
     * @return 用户页
     */
    public DingUserPage listUsers(long deptId, long cursor, int size) {
        int pageSize = Math.min(Math.max(size, 1), 100);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dept_id", deptId);
        body.put("cursor", cursor);
        body.put("size", pageSize);
        JsonNode node = postTopApi("/topapi/v2/user/list", body);
        return DingTalkJson.users(node.get("result"));
    }

    private JsonNode postTopApi(String path, Map<String, Object> body) {
        Credential credential = credential();
        DingTalkApiException expired = null;
        for (int round = 0; round < 2; round++) {
            String token = getAccessToken();
            String url = properties.resolvedApiBaseUrl() + path + "?access_token=" + encode(token);
            try {
                return invoke(url, DingTalkJson.write(body), credential);
            } catch (DingTalkApiException ex) {
                if (round == 0 && (ex.getErrCode() == 40014 || ex.getErrCode() == 42001)) {
                    tokenCache.delete(TOKEN_KEY);
                    expired = ex;
                    continue;
                }
                throw ex;
            }
        }
        throw expired;
    }

    private JsonNode invoke(String url, String json, Credential credential) {
        int attempts = 3;
        DingTalkApiException last = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            pace();
            String raw;
            try {
                raw = http.exchange(json == null ? "GET" : "POST", url, json);
            } catch (RuntimeException ex) {
                log.warn("钉钉接口请求失败: {}", ex.getClass().getSimpleName());
                throw new ServiceException("钉钉接口请求失败");
            }
            JsonNode node = DingTalkJson.read(raw);
            int errCode = DingTalkJson.errCode(node);
            if (errCode == 0) {
                return node;
            }
            String message = "钉钉接口调用失败：" + errCode + " " + safe(DingTalkJson.text(node, "errmsg"), credential);
            last = new DingTalkApiException(errCode, message);
            if (errCode == RATE_LIMIT_CODE && attempt < attempts - 1) {
                sleepRetry(attempt);
                continue;
            }
            throw last;
        }
        throw last;
    }

    private Credential credential() {
        String appKey = firstConfigured(properties.getClientId(), environment.getProperty("justauth.type.dingtalk.client-id"));
        String appSecret = firstConfigured(properties.getClientSecret(), environment.getProperty("justauth.type.dingtalk.client-secret"));
        if (StringUtils.isBlank(appKey) || StringUtils.isBlank(appSecret)) {
            throw new ServiceException("未配置钉钉应用凭证，请设置 dingtalk.client-id/client-secret 或 justauth.type.dingtalk");
        }
        return new Credential(appKey, appSecret);
    }

    private String firstConfigured(String preferred, String fallback) {
        if (StringUtils.isNotBlank(preferred)) {
            return preferred.trim();
        }
        return StringUtils.trimToEmpty(fallback);
    }

    private void pace() {
        long interval = Math.max(properties.getSync().getMinIntervalMs(), 0L);
        if (interval == 0L) {
            return;
        }
        synchronized (paceLock) {
            long now = System.currentTimeMillis();
            long wait = nextAllowedAt - now;
            if (wait > 0) {
                sleep(wait);
            }
            nextAllowedAt = System.currentTimeMillis() + interval;
        }
    }

    private void sleepRetry(int attempt) {
        int base = Math.max(properties.getSync().getRetryDelayMs(), 0);
        if (base == 0) {
            return;
        }
        sleep(Math.min(2000L, (long) base * (attempt + 1)));
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ServiceException("钉钉同步被中断");
        }
    }

    private String safe(String message, Credential credential) {
        String value = StringUtils.blankToDefault(message, "");
        if (StringUtils.isNotBlank(credential.appSecret()) && value.contains(credential.appSecret())) {
            value = value.replace(credential.appSecret(), "***");
        }
        if (StringUtils.isNotBlank(credential.appKey()) && value.contains(credential.appKey())) {
            value = value.replace(credential.appKey(), "***");
        }
        return StringUtils.substring(value, 0, 180);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record Credential(String appKey, String appSecret) {
    }

}
