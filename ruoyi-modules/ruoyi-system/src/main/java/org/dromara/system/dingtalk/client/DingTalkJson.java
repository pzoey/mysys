package org.dromara.system.dingtalk.client;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.system.dingtalk.client.DingTalkModels.DingDepartment;
import org.dromara.system.dingtalk.client.DingTalkModels.DingEmployee;
import org.dromara.system.dingtalk.client.DingTalkModels.DingUserPage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 钉钉响应解析。只保留通讯录同步需要的字段。
 *
 * @author dromara
 */
final class DingTalkJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private DingTalkJson() {
    }

    static JsonNode read(String body) {
        if (StringUtils.isBlank(body)) {
            throw new ServiceException("钉钉接口返回空响应");
        }
        try {
            return MAPPER.readTree(body);
        } catch (RuntimeException ex) {
            throw new ServiceException("钉钉接口返回无法解析");
        }
    }

    static String write(Map<String, Object> body) {
        return MAPPER.writeValueAsString(body);
    }

    static int errCode(JsonNode node) {
        return intVal(node, "errcode", -1);
    }

    static String text(JsonNode node, String field) {
        JsonNode child = node == null ? null : node.get(field);
        if (child == null || child.isNull()) {
            return null;
        }
        String value = child.asString();
        return StringUtils.isBlank(value) ? null : value.trim();
    }

    static int intVal(JsonNode node, String field, int defaultValue) {
        JsonNode child = node == null ? null : node.get(field);
        if (child == null || child.isNull()) {
            return defaultValue;
        }
        if (child.isNumber()) {
            return child.asInt();
        }
        String text = child.asString();
        if (StringUtils.isBlank(text)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    static long longVal(JsonNode node, String field) {
        JsonNode child = node == null ? null : node.get(field);
        if (child == null || child.isNull()) {
            return 0L;
        }
        if (child.isNumber()) {
            return child.asLong();
        }
        String text = child.asString();
        if (StringUtils.isBlank(text)) {
            return 0L;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    static boolean boolVal(JsonNode node, String field, boolean defaultValue) {
        JsonNode child = node == null ? null : node.get(field);
        if (child == null || child.isNull()) {
            return defaultValue;
        }
        if (child.isBoolean()) {
            return child.asBoolean();
        }
        return child.asBoolean(defaultValue);
    }

    static DingDepartment department(JsonNode node) {
        return new DingDepartment(
            longVal(node, "dept_id"),
            longVal(node, "parent_id"),
            text(node, "name"),
            intVal(node, "order", 0)
        );
    }

    static List<DingDepartment> departments(JsonNode result) {
        List<DingDepartment> list = new ArrayList<>();
        if (result == null || !result.isArray()) {
            return list;
        }
        for (JsonNode item : result) {
            DingDepartment department = department(item);
            if (department.deptId() > 0) {
                list.add(department);
            }
        }
        return list;
    }

    static DingUserPage users(JsonNode result) {
        List<DingEmployee> users = new ArrayList<>();
        if (result == null || result.isNull()) {
            return new DingUserPage(users, false, 0L);
        }
        JsonNode listNode = result.get("list");
        if (listNode != null && listNode.isArray()) {
            for (JsonNode item : listNode) {
                String userId = text(item, "userid");
                if (StringUtils.isBlank(userId)) {
                    continue;
                }
                users.add(new DingEmployee(
                    userId,
                    text(item, "unionid"),
                    text(item, "name"),
                    text(item, "mobile"),
                    firstText(item, "org_email", "email"),
                    text(item, "title"),
                    text(item, "job_number"),
                    text(item, "avatar"),
                    boolVal(item, "active", true),
                    boolVal(item, "leader", false),
                    deptIds(item.get("dept_id_list"))
                ));
            }
        }
        return new DingUserPage(users, boolVal(result, "has_more", false), longVal(result, "next_cursor"));
    }

    private static List<Long> deptIds(JsonNode node) {
        List<Long> ids = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return ids;
        }
        for (JsonNode item : node) {
            long id = item.isNumber() ? item.asLong() : longVal(item, "dept_id");
            if (!item.isNumber() && item.isString()) {
                try {
                    id = Long.parseLong(item.asString().trim());
                } catch (NumberFormatException ex) {
                    id = 0L;
                }
            }
            if (id > 0) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

}
