package org.dromara.system.dingtalk;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.system.dingtalk.client.DingTalkApiException;
import org.dromara.system.dingtalk.client.DingTalkClient;
import org.dromara.system.dingtalk.client.DingTalkHttp;
import org.dromara.system.dingtalk.client.DingTalkModels.DingDepartment;
import org.dromara.system.dingtalk.client.DingTalkModels.DingUserPage;
import org.dromara.system.dingtalk.client.DingTalkTokenCache;
import org.dromara.system.dingtalk.config.DingTalkProperties;
import org.dromara.system.dingtalk.support.DingTalkAccounts;
import org.dromara.system.dingtalk.support.DingTalkSocials;
import org.dromara.system.dingtalk.support.DingTalkUserMatch;
import org.dromara.system.dingtalk.support.DingTalkUserMatch.SocialRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉钉客户端、账号归一和匹配优先级。不访问真实钉钉。
 */
class DingTalkClientTest {

    @Test
    @DisplayName("凭证优先使用 dingtalk 配置，token 会缓存")
    void tokenUsesConfiguredCredentialAndCache() {
        List<String> urls = new ArrayList<>();
        DingTalkClient client = client(properties("app-key", "app-secret"), new MockEnvironment(), (method, url, body) -> {
            urls.add(url);
            return "{\"errcode\":0,\"access_token\":\"token-1\",\"expires_in\":7200}";
        });

        assertEquals("token-1", client.getAccessToken());
        assertEquals("token-1", client.getAccessToken());

        assertEquals(1, urls.size());
        assertTrue(urls.getFirst().contains("appkey=app-key"));
        assertFalse(urls.getFirst().contains("just-key"));
    }

    @Test
    @DisplayName("dingtalk 凭证为空时回退 justauth")
    void tokenFallsBackToJustAuth() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("justauth.type.dingtalk.client-id", "just-key")
            .withProperty("justauth.type.dingtalk.client-secret", "just-secret");
        List<String> urls = new ArrayList<>();
        DingTalkClient client = client(properties("", ""), environment, (method, url, body) -> {
            urls.add(url);
            return "{\"errcode\":0,\"access_token\":\"token-2\",\"expires_in\":7200}";
        });

        client.getAccessToken();

        assertTrue(urls.getFirst().contains("appkey=just-key"));
    }

    @Test
    @DisplayName("未配置凭证时不发请求，异常不含密钥")
    void missingCredentialDoesNotCallHttp() {
        AtomicInteger calls = new AtomicInteger();
        DingTalkClient client = client(properties("", ""), new MockEnvironment(), (method, url, body) -> {
            calls.incrementAndGet();
            return "{}";
        });

        ServiceException ex = assertThrows(ServiceException.class, client::getAccessToken);

        assertEquals(0, calls.get());
        assertFalse(ex.getMessage().contains("top-secret-value"));
        assertTrue(ex.getMessage().contains("未配置"));
    }

    @Test
    @DisplayName("错误信息中的密钥会被抹掉，限流会重试")
    void secretIsRedactedAndRateLimitRetries() {
        AtomicInteger calls = new AtomicInteger();
        DingTalkProperties properties = properties("app-key", "top-secret-value");
        properties.getSync().setRetryDelayMs(0);
        DingTalkClient client = client(properties, new MockEnvironment(), (method, url, body) -> {
            if (calls.incrementAndGet() == 1) {
                return "{\"errcode\":88,\"errmsg\":\"qps top-secret-value\"}";
            }
            return "{\"errcode\":0,\"access_token\":\"token-3\",\"expires_in\":7200}";
        });

        assertEquals("token-3", client.getAccessToken());
        assertEquals(2, calls.get());

        DingTalkClient failing = client(properties, new MockEnvironment(), (method, url, body) ->
            "{\"errcode\":400, \"errmsg\":\"bad top-secret-value\"}");
        DingTalkApiException ex = assertThrows(DingTalkApiException.class, failing::getAccessToken);
        assertFalse(ex.getMessage().contains("top-secret-value"));
        assertTrue(ex.getMessage().contains("***"));
    }

    @Test
    @DisplayName("按层级拉取部门并解析部门用户")
    void listDepartmentsAndUsers() {
        DingTalkClient client = client(properties("app-key", "app-secret"), new MockEnvironment(), (method, url, body) -> {
            if (url.contains("/gettoken")) {
                return "{\"errcode\":0,\"access_token\":\"token-1\",\"expires_in\":7200}";
            }
            if (url.contains("/department/get")) {
                return "{\"errcode\":0,\"result\":{\"dept_id\":1,\"name\":\"总部\",\"parent_id\":0,\"order\":1}}";
            }
            if (url.contains("/department/listsub")) {
                if (body != null && body.contains("\"dept_id\":1")) {
                    return "{\"errcode\":0,\"result\":[{\"dept_id\":2,\"name\":\"研发中心\",\"parent_id\":1,\"order\":3}]}";
                }
                return "{\"errcode\":0,\"result\":[]}";
            }
            return "{\"errcode\":0,\"result\":{\"has_more\":false,\"next_cursor\":0,\"list\":[{\"userid\":\"uid-1\",\"unionid\":\"union-1\",\"name\":\"张三\",\"mobile\":\"+86 138-0013-8000\",\"org_email\":\"a@example.com\",\"job_number\":\"E001\",\"active\":true,\"leader\":true,\"dept_id_list\":[2]}]}}";
        });

        List<DingDepartment> departments = client.listAllDepartments();
        DingUserPage users = client.listUsers(2L, 0L, 100);

        assertEquals(List.of(1L, 2L), departments.stream().map(DingDepartment::deptId).toList());
        assertEquals("研发中心", departments.get(1).name());
        assertEquals(1, users.users().size());
        assertEquals("uid-1", users.users().getFirst().userId());
        assertEquals("union-1", users.users().getFirst().unionId());
        assertEquals("a@example.com", users.users().getFirst().email());
        assertTrue(users.users().getFirst().leader());
        assertFalse(users.hasMore());
    }

    @Test
    @DisplayName("手机号归一，匹配顺序为手机号、unionId、userid")
    void normalizeMobileAndMatchPriority() {
        assertEquals("13800138000", DingTalkAccounts.normalizeMobile("+86 138-0013-8000"));
        assertEquals("13800138000", DingTalkAccounts.normalizeMobile("8613800138000"));
        assertEquals(null, DingTalkAccounts.normalizeMobile("12345"));

        SocialRef union = new SocialRef(2L, 20L);
        SocialRef dingUser = new SocialRef(3L, 30L);
        assertEquals(DingTalkUserMatch.By.MOBILE, DingTalkUserMatch.match(10L, union, dingUser).by());
        assertEquals(10L, DingTalkUserMatch.match(10L, union, dingUser).userId());
        assertEquals(DingTalkUserMatch.By.UNION_ID, DingTalkUserMatch.match(null, union, dingUser).by());
        assertEquals(20L, DingTalkUserMatch.match(null, union, dingUser).userId());
        assertEquals(DingTalkUserMatch.By.USER_ID, DingTalkUserMatch.match(null, null, dingUser).by());
        assertEquals(DingTalkUserMatch.By.NONE, DingTalkUserMatch.match(null, null, null).by());
        assertEquals("DINGTALK_V2union-1", DingTalkSocials.authId("union-1"));
    }

    @Test
    @DisplayName("登录名优先工号，冲突时加后缀")
    void buildUserNameAvoidsCollision() {
        assertEquals("E001", DingTalkAccounts.buildUserName("E001", "13800138000", "abc", name -> false));
        assertEquals("u001", DingTalkAccounts.buildUserName("001", null, "abc", name -> false));
        assertEquals("E001_2", DingTalkAccounts.buildUserName("E001", null, "abc", "E001"::equals));
    }

    private DingTalkClient client(DingTalkProperties properties, MockEnvironment environment, DingTalkHttp http) {
        return new DingTalkClient(properties, environment, http, new MemoryTokenCache());
    }

    private DingTalkProperties properties(String clientId, String clientSecret) {
        DingTalkProperties properties = new DingTalkProperties();
        properties.setClientId(clientId);
        properties.setClientSecret(clientSecret);
        properties.getSync().setMinIntervalMs(0L);
        properties.getSync().setRetryDelayMs(0);
        return properties;
    }

    private static final class MemoryTokenCache implements DingTalkTokenCache {

        private final Map<String, String> data = new HashMap<>();

        @Override
        public String get(String key) {
            return data.get(key);
        }

        @Override
        public void set(String key, String value, Duration ttl) {
            data.put(key, value);
        }

        @Override
        public void delete(String key) {
            data.remove(key);
        }

        @Override
        public boolean tryLock(String key, String value, Duration ttl) {
            return data.putIfAbsent(key, value) == null;
        }

        @Override
        public void unlock(String key, String expect) {
            if (expect.equals(data.get(key))) {
                data.remove(key);
            }
        }

    }

}
