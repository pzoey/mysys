package org.dromara.system.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.constant.CacheNames;
import org.dromara.common.core.constant.SystemConstants;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.helper.DataPermissionHelper;
import org.dromara.common.redis.utils.CacheUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.dingtalk.client.DingTalkClient;
import org.dromara.system.dingtalk.client.DingTalkModels.DingDepartment;
import org.dromara.system.dingtalk.client.DingTalkModels.DingEmployee;
import org.dromara.system.dingtalk.client.DingTalkModels.DingUserPage;
import org.dromara.system.dingtalk.client.DingTalkTokenCache;
import org.dromara.system.dingtalk.config.DingTalkProperties;
import org.dromara.system.dingtalk.support.DingTalkAccounts;
import org.dromara.system.dingtalk.support.DingTalkSocials;
import org.dromara.system.dingtalk.support.DingTalkUserMatch;
import org.dromara.system.dingtalk.support.DingTalkUserMatch.Hit;
import org.dromara.system.dingtalk.support.DingTalkUserMatch.SocialRef;
import org.dromara.system.domain.SysDept;
import org.dromara.system.domain.SysDingtalkDept;
import org.dromara.system.domain.SysDingtalkSyncLog;
import org.dromara.system.domain.SysRole;
import org.dromara.system.domain.SysSocial;
import org.dromara.system.domain.SysUser;
import org.dromara.system.domain.SysUserRole;
import org.dromara.system.domain.vo.SysDingtalkSyncLogVo;
import org.dromara.system.mapper.SysDeptMapper;
import org.dromara.system.mapper.SysDingtalkDeptMapper;
import org.dromara.system.mapper.SysDingtalkSyncLogMapper;
import org.dromara.system.mapper.SysRoleMapper;
import org.dromara.system.mapper.SysSocialMapper;
import org.dromara.system.mapper.SysUserMapper;
import org.dromara.system.mapper.SysUserRoleMapper;
import org.dromara.system.service.IDingTalkOrgSyncService;
import org.dromara.system.service.ISysConfigService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 从钉钉全量同步部门、人员和绑定。不删除本地已有数据，也不先清空再拉取。
 *
 * @author dromara
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DingTalkOrgSyncServiceImpl implements IDingTalkOrgSyncService {

    private static final String LOCK_KEY = "dingtalk:contact:sync_lock";

    private static final String RUNNING = "0";

    private static final String SUCCESS = "1";

    private static final String PARTIAL = "2";

    private static final String FAILED = "3";

    private static final DateTimeFormatter BATCH_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final DingTalkClient client;

    private final DingTalkProperties properties;

    private final DingTalkTokenCache tokenCache;

    private final SysDeptMapper deptMapper;

    private final SysDingtalkDeptMapper deptMapMapper;

    private final SysUserMapper userMapper;

    private final SysSocialMapper socialMapper;

    private final SysUserRoleMapper userRoleMapper;

    private final SysRoleMapper roleMapper;

    private final SysDingtalkSyncLogMapper logMapper;

    private final ISysConfigService configService;

    private final TransactionTemplate transactionTemplate;

    /**
     * {@inheritDoc}
     */
    @Override
    public SysDingtalkSyncLogVo syncAll() {
        String lockOwner = newBatchNo();
        if (!tokenCache.tryLock(LOCK_KEY, lockOwner, Duration.ofMinutes(30))) {
            throw new ServiceException("已有钉钉组织同步正在执行");
        }
        try {
            return DataPermissionHelper.ignore(() -> execute(lockOwner));
        } finally {
            tokenCache.unlock(LOCK_KEY, lockOwner);
            clearDeptCache();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SysDingtalkSyncLogVo latest() {
        return DataPermissionHelper.ignore(() -> {
            Page<SysDingtalkSyncLog> page = logMapper.lambda()
                .orderByDesc(SysDingtalkSyncLog::getStartTime)
                .orderByDesc(SysDingtalkSyncLog::getId)
                .page(new Page<>(1, 1));
            if (page.getRecords().isEmpty()) {
                return null;
            }
            return toVo(page.getRecords().getFirst());
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PageResult<SysDingtalkSyncLogVo> queryPage(PageQuery pageQuery) {
        return DataPermissionHelper.ignore(() -> {
            Page<SysDingtalkSyncLog> page = logMapper.lambda()
                .orderByDesc(SysDingtalkSyncLog::getStartTime)
                .orderByDesc(SysDingtalkSyncLog::getId)
                .page(pageQuery.build());
            List<SysDingtalkSyncLogVo> rows = new ArrayList<>();
            for (SysDingtalkSyncLog row : page.getRecords()) {
                rows.add(toVo(row));
            }
            return PageResult.build(rows, page.getTotal());
        });
    }

    private SysDingtalkSyncLogVo execute(String batchNo) {
        Long operatorId = currentUserId();
        SysDingtalkSyncLog row = newLog(batchNo, operatorId);
        logMapper.insert(row);
        SyncReport report = new SyncReport();
        try {
            List<DingDepartment> departments = client.listAllDepartments();
            SyncContext context = prepareContext();
            for (DingDepartment department : departments) {
                try {
                    transactionTemplate.executeWithoutResult(status -> upsertDept(department, context, report, operatorId));
                } catch (RuntimeException ex) {
                    report.failDept("部门 " + label(department) + " 同步失败：" + messageOf(ex));
                }
            }
            Map<String, EmployeeBuf> users = new LinkedHashMap<>();
            for (DingDepartment department : departments) {
                collectUsers(department.deptId(), users, report);
            }
            String password = encodedInitPassword();
            for (EmployeeBuf employee : users.values()) {
                try {
                    transactionTemplate.executeWithoutResult(status -> upsertEmployee(employee, context, report, password, operatorId));
                } catch (RuntimeException ex) {
                    report.failUser("用户 " + employee.userId + " 同步失败：" + messageOf(ex));
                }
            }
            applyLeaders(context);
            report.absentDept = countAbsentDepts(context.seenDingDepts);
            report.absentUser = countAbsentUsers(context.seenDingUsers);
            finish(row, report, null);
        } catch (RuntimeException ex) {
            log.warn("钉钉组织同步中断: {}", ex.getClass().getSimpleName());
            finish(row, report, ex);
        }
        return toVo(logMapper.selectById(row.getId()));
    }

    private SyncContext prepareContext() {
        SyncContext context = new SyncContext();
        Long anchorId = properties.getSync().getAnchorDeptId();
        if (anchorId != null) {
            SysDept anchor = deptMapper.selectById(anchorId);
            if (anchor == null) {
                throw new ServiceException("钉钉同步挂载部门不存在");
            }
            context.anchorLocalId = anchor.getDeptId();
        }
        context.defaultParentId = findDefaultParent();
        for (SysDingtalkDept mapping : deptMapMapper.lambda().list()) {
            context.dingToLocal.put(mapping.getDingDeptId(), mapping.getDeptId());
        }
        return context;
    }

    private Long findDefaultParent() {
        List<SysDept> roots = deptMapper.lambda()
            .eq(SysDept::getParentId, 0L)
            .orderByAsc(SysDept::getOrderNum)
            .orderByAsc(SysDept::getDeptId)
            .list();
        if (roots.isEmpty()) {
            return 0L;
        }
        return roots.getFirst().getDeptId();
    }

    private void upsertDept(DingDepartment ding, SyncContext context, SyncReport report, Long operatorId) {
        context.seenDingDepts.add(ding.deptId());
        if (ding.deptId() == DingTalkClient.ROOT_DEPT_ID && context.anchorLocalId != null) {
            saveMapping(context.anchorLocalId, ding, operatorId);
            context.dingToLocal.put(ding.deptId(), context.anchorLocalId);
            report.okDept();
            return;
        }
        Long parentId = resolveParent(ding, context);
        String name = DingTalkAccounts.cut(ding.name(), 30);
        if (name == null) {
            name = "未命名部门";
        }
        SysDingtalkDept mapping = findMapByDing(ding.deptId());
        SysDept local = mapping == null ? null : deptMapper.selectById(mapping.getDeptId());
        if (local == null) {
            local = findLinkableSibling(parentId, name);
        }
        if (local == null) {
            local = new SysDept();
            local.setDeptName(name);
            local.setParentId(parentId);
            local.setAncestors(ancestorsOf(parentId));
            local.setOrderNum(normalizeOrder(ding.order()));
            local.setStatus(SystemConstants.NORMAL);
            fillOperator(local, operatorId);
            deptMapper.insert(local);
        } else {
            boolean parentChanged = !Objects.equals(local.getParentId(), parentId);
            String oldAncestors = local.getAncestors();
            SysDept patch = new SysDept();
            patch.setDeptId(local.getDeptId());
            patch.setDeptName(name);
            patch.setOrderNum(normalizeOrder(ding.order()));
            patch.setStatus(SystemConstants.NORMAL);
            if (parentChanged) {
                patch.setParentId(parentId);
                patch.setAncestors(ancestorsOf(parentId));
            }
            deptMapper.updateById(patch);
            if (parentChanged) {
                moveChildren(local.getDeptId(), oldAncestors, patch.getAncestors());
            }
            local = deptMapper.selectById(local.getDeptId());
        }
        saveMapping(local.getDeptId(), ding, operatorId);
        context.dingToLocal.put(ding.deptId(), local.getDeptId());
        report.okDept();
    }

    private Long resolveParent(DingDepartment ding, SyncContext context) {
        if (ding.parentId() <= 0 || ding.deptId() == DingTalkClient.ROOT_DEPT_ID) {
            if (context.anchorLocalId != null) {
                return context.anchorLocalId;
            }
            return context.defaultParentId;
        }
        Long parent = context.dingToLocal.get(ding.parentId());
        if (parent == null) {
            throw new ServiceException("父部门尚未映射：" + ding.parentId());
        }
        return parent;
    }

    private SysDept findLinkableSibling(Long parentId, String name) {
        List<SysDept> siblings = deptMapper.lambda()
            .eq(SysDept::getParentId, parentId)
            .eq(SysDept::getDeptName, name)
            .list();
        for (SysDept sibling : siblings) {
            SysDingtalkDept mapped = deptMapMapper.lambda().eq(SysDingtalkDept::getDeptId, sibling.getDeptId()).one();
            if (mapped == null) {
                return sibling;
            }
        }
        return null;
    }

    private void saveMapping(Long deptId, DingDepartment ding, Long operatorId) {
        SysDingtalkDept mapping = findMapByDing(ding.deptId());
        if (mapping == null) {
            SysDingtalkDept created = deptMapMapper.lambda().eq(SysDingtalkDept::getDeptId, deptId).one();
            if (created != null && !Objects.equals(created.getDingDeptId(), ding.deptId())) {
                throw new ServiceException("本地部门已映射到其他钉钉部门");
            }
            mapping = new SysDingtalkDept();
            mapping.setDeptId(deptId);
            mapping.setDingDeptId(ding.deptId());
            mapping.setDingParentId(ding.parentId());
            fillOperator(mapping, operatorId);
            deptMapMapper.insert(mapping);
            return;
        }
        mapping.setDeptId(deptId);
        mapping.setDingParentId(ding.parentId());
        deptMapMapper.updateById(mapping);
    }

    private SysDingtalkDept findMapByDing(long dingDeptId) {
        return deptMapMapper.lambda().eq(SysDingtalkDept::getDingDeptId, dingDeptId).one();
    }

    private String ancestorsOf(Long parentId) {
        if (parentId == null || parentId == 0L) {
            return SystemConstants.ROOT_DEPT_ANCESTORS;
        }
        SysDept parent = deptMapper.selectById(parentId);
        if (parent == null) {
            throw new ServiceException("上级部门不存在");
        }
        return parent.getAncestors() + StringUtils.SEPARATOR + parent.getDeptId();
    }

    private void moveChildren(Long deptId, String oldAncestors, String newAncestors) {
        if (StringUtils.isBlank(oldAncestors) || StringUtils.equals(oldAncestors, newAncestors)) {
            return;
        }
        List<SysDept> children = deptMapper.lambda().findInSet(deptId, SysDept::getAncestors).list();
        for (SysDept child : children) {
            SysDept patch = new SysDept();
            patch.setDeptId(child.getDeptId());
            patch.setAncestors(StringUtils.replaceOnce(child.getAncestors(), oldAncestors, newAncestors));
            deptMapper.updateById(patch);
        }
    }

    private void collectUsers(long dingDeptId, Map<String, EmployeeBuf> users, SyncReport report) {
        long cursor = 0L;
        int pageSize = Math.min(Math.max(properties.getSync().getUserPageSize(), 1), 100);
        for (int page = 0; page < 500; page++) {
            DingUserPage result;
            try {
                result = client.listUsers(dingDeptId, cursor, pageSize);
            } catch (RuntimeException ex) {
                report.failUser("部门 " + dingDeptId + " 拉取人员失败：" + messageOf(ex));
                return;
            }
            for (DingEmployee employee : result.users()) {
                if (StringUtils.isBlank(employee.userId())) {
                    report.failUser("部门 " + dingDeptId + " 存在缺少 userid 的人员");
                    continue;
                }
                users.computeIfAbsent(employee.userId(), key -> new EmployeeBuf(employee)).merge(dingDeptId, employee);
            }
            if (!result.hasMore()) {
                return;
            }
            if (result.nextCursor() == cursor) {
                report.failUser("部门 " + dingDeptId + " 人员分页游标没有前进");
                return;
            }
            cursor = result.nextCursor();
        }
        report.failUser("部门 " + dingDeptId + " 人员分页超过上限");
    }

    private void upsertEmployee(EmployeeBuf employee, SyncContext context, SyncReport report, String password, Long operatorId) {
        context.seenDingUsers.add(employee.userId);
        String mobile = DingTalkAccounts.normalizeMobile(employee.mobile);
        List<SysUser> phoneUsers = StringUtils.isBlank(mobile)
            ? List.of()
            : userMapper.lambda().eq(SysUser::getPhoneNumber, mobile).list();
        if (phoneUsers.size() > 1) {
            report.failUser(employee.display() + " 的手机号对应多个本地用户");
            return;
        }
        SocialRef unionRef = toRef(findByUnion(employee.unionId));
        SocialRef dingRef = toRef(findByDingUser(employee.userId));
        Hit hit = DingTalkUserMatch.match(phoneUsers.isEmpty() ? null : phoneUsers.getFirst().getUserId(), unionRef, dingRef);
        if (hit.by() == DingTalkUserMatch.By.NONE) {
            createEmployee(employee, context, report, password, operatorId, mobile);
            return;
        }
        SysUser user = userMapper.selectById(hit.userId());
        if (user == null) {
            report.failUser(employee.display() + " 绑定的本地用户不存在");
            return;
        }
        if (SystemConstants.SUPER_ADMIN_USER_ID.equals(user.getUserId())) {
            report.skipUser("跳过超级管理员，未改写 " + employee.display());
            return;
        }
        SysSocial bound = findUserSocial(user.getUserId());
        if (bound != null && StringUtils.isNotBlank(employee.unionId) && StringUtils.isNotBlank(bound.getUnionId())
            && !employee.unionId.equals(bound.getUnionId())) {
            report.failUser(employee.display() + " 与本地用户已有的钉钉 unionId 不一致");
            return;
        }
        SysSocial unionOwner = findByUnion(employee.unionId);
        if (unionOwner != null && !user.getUserId().equals(unionOwner.getUserId())) {
            report.failUser(employee.display() + " 的 unionId 已绑定其他用户");
            return;
        }
        updateEmployee(user, employee, context, mobile);
        saveSocial(bound, user.getUserId(), employee, operatorId);
        rememberLeader(employee, user.getUserId(), context);
        report.okUser();
    }

    private void createEmployee(EmployeeBuf employee, SyncContext context, SyncReport report, String password, Long operatorId, String mobile) {
        SysSocial unionOwner = findByUnion(employee.unionId);
        if (unionOwner != null) {
            report.failUser(employee.display() + " 的 unionId 已绑定其他用户");
            return;
        }
        String userName = DingTalkAccounts.buildUserName(employee.jobNumber, mobile, employee.userId, this::userNameExists);
        SysUser user = new SysUser();
        user.setUserName(userName);
        user.setNickName(displayName(employee));
        user.setUserType("sys_user");
        user.setEmail(usableEmail(employee.email));
        user.setPhoneNumber(mobile);
        user.setGender("2");
        user.setPassword(password);
        user.setStatus(employee.active ? SystemConstants.NORMAL : SystemConstants.DISABLE);
        user.setDeptId(usableDept(primaryDept(employee, context)));
        user.setRemark(DingTalkAccounts.cut(employee.title, 500));
        fillOperator(user, operatorId);
        userMapper.insert(user);
        grantDefaultRole(user.getUserId());
        saveSocial(null, user.getUserId(), employee, operatorId);
        rememberLeader(employee, user.getUserId(), context);
        report.okUser();
    }

    private void updateEmployee(SysUser user, EmployeeBuf employee, SyncContext context, String mobile) {
        SysUser patch = new SysUser();
        patch.setUserId(user.getUserId());
        patch.setNickName(displayName(employee));
        patch.setStatus(employee.active ? SystemConstants.NORMAL : SystemConstants.DISABLE);
        Long deptId = usableDept(primaryDept(employee, context));
        if (deptId != null) {
            patch.setDeptId(deptId);
        }
        String email = usableEmail(employee.email);
        if (email != null) {
            patch.setEmail(email);
        }
        if (mobile != null && !mobile.equals(user.getPhoneNumber()) && !phoneTaken(mobile, user.getUserId())) {
            patch.setPhoneNumber(mobile);
        }
        String title = DingTalkAccounts.cut(employee.title, 500);
        if (title != null) {
            patch.setRemark(title);
        }
        userMapper.updateById(patch);
        evictNickname(user.getUserId());
    }

    private void saveSocial(SysSocial existing, Long userId, EmployeeBuf employee, Long operatorId) {
        String identity = StringUtils.isNotBlank(employee.unionId) ? employee.unionId : employee.userId;
        String authId = DingTalkSocials.authId(identity);
        SysSocial social = existing == null ? findUserSocial(userId) : existing;
        if (social == null) {
            social = new SysSocial();
            social.setUserId(userId);
            social.setAuthId(authId);
            social.setSource(DingTalkSocials.SOURCE);
            social.setOpenId(identity);
            social.setUnionId(employee.unionId);
            social.setAccessCode(employee.userId);
            social.setAccessToken(DingTalkSocials.TOKEN_PLACEHOLDER);
            social.setUserName(socialName(employee));
            social.setNickName(DingTalkAccounts.cut(employee.name, 30));
            social.setEmail(StringUtils.blankToDefault(DingTalkAccounts.cut(employee.email, 255), ""));
            social.setAvatar(DingTalkAccounts.cut(employee.avatar, 500));
            fillOperator(social, operatorId);
            socialMapper.insert(social);
            return;
        }
        if (!authId.equals(social.getAuthId())) {
            Long socialId = social.getId();
            List<SysSocial> owners = socialMapper.lambda().eq(SysSocial::getAuthId, authId).list();
            if (owners.stream().anyMatch(item -> !item.getId().equals(socialId))) {
                throw new ServiceException("钉钉登录绑定已被其他用户占用");
            }
            social.setAuthId(authId);
            social.setOpenId(identity);
            social.setSource(DingTalkSocials.SOURCE);
        }
        if (StringUtils.isNotBlank(employee.unionId)) {
            social.setUnionId(employee.unionId);
        }
        social.setAccessCode(employee.userId);
        if (placeholderToken(social.getAccessToken())) {
            social.setAccessToken(DingTalkSocials.TOKEN_PLACEHOLDER);
        }
        social.setUserName(socialName(employee));
        social.setNickName(DingTalkAccounts.cut(employee.name, 30));
        String email = DingTalkAccounts.cut(employee.email, 255);
        if (email != null) {
            social.setEmail(email);
        }
        String avatar = DingTalkAccounts.cut(employee.avatar, 500);
        if (avatar != null) {
            social.setAvatar(avatar);
        }
        socialMapper.updateById(social);
    }

    private void grantDefaultRole(Long userId) {
        Long roleId = properties.getSync().getDefaultRoleId();
        if (roleId == null || SystemConstants.SUPER_ADMIN_ROLE_ID.equals(roleId)) {
            return;
        }
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            return;
        }
        Long count = userRoleMapper.lambda()
            .eq(SysUserRole::getUserId, userId)
            .eq(SysUserRole::getRoleId, roleId)
            .count();
        if (count != null && count > 0) {
            return;
        }
        SysUserRole relation = new SysUserRole();
        relation.setUserId(userId);
        relation.setRoleId(roleId);
        userRoleMapper.insert(relation);
    }

    private void applyLeaders(SyncContext context) {
        for (Map.Entry<Long, Long> entry : context.leaderUserByDingDept.entrySet()) {
            Long localDeptId = context.dingToLocal.get(entry.getKey());
            if (localDeptId == null || localDeptId == 0L) {
                continue;
            }
            SysDept dept = deptMapper.selectById(localDeptId);
            if (dept != null && dept.getLeader() == null) {
                SysDept patch = new SysDept();
                patch.setDeptId(localDeptId);
                patch.setLeader(entry.getValue());
                deptMapper.updateById(patch);
            }
        }
    }

    private void rememberLeader(EmployeeBuf employee, Long userId, SyncContext context) {
        for (Long dingDeptId : employee.leaderDepts) {
            context.leaderUserByDingDept.putIfAbsent(dingDeptId, userId);
        }
    }

    private Long primaryDept(EmployeeBuf employee, SyncContext context) {
        for (Long dingDeptId : employee.deptIds) {
            Long localId = context.dingToLocal.get(dingDeptId);
            if (localId != null && localId != 0L) {
                return localId;
            }
        }
        if (context.anchorLocalId != null) {
            return context.anchorLocalId;
        }
        return context.defaultParentId;
    }

    private int countAbsentDepts(Set<Long> seen) {
        int absent = 0;
        for (SysDingtalkDept mapping : deptMapMapper.lambda().list()) {
            if (!seen.contains(mapping.getDingDeptId())) {
                absent++;
            }
        }
        return absent;
    }

    private int countAbsentUsers(Set<String> seen) {
        int absent = 0;
        List<SysSocial> socials = socialMapper.lambda().in(SysSocial::getSource, DingTalkSocials.SOURCES).list();
        for (SysSocial social : socials) {
            if (StringUtils.isNotBlank(social.getAccessCode()) && !seen.contains(social.getAccessCode())) {
                absent++;
            }
        }
        return absent;
    }

    private SysSocial findByUnion(String unionId) {
        if (StringUtils.isBlank(unionId)) {
            return null;
        }
        String authId = DingTalkSocials.authId(unionId);
        List<SysSocial> list = socialMapper.lambda()
            .in(SysSocial::getSource, DingTalkSocials.SOURCES)
            .and(wrapper -> wrapper.eq(SysSocial::getUnionId, unionId)
                .or().eq(SysSocial::getOpenId, unionId)
                .or().eq(SysSocial::getAuthId, authId))
            .list();
        return single(list, "unionId 命中多条钉钉绑定");
    }

    private SysSocial findByDingUser(String dingUserId) {
        if (StringUtils.isBlank(dingUserId)) {
            return null;
        }
        String authId = DingTalkSocials.authId(dingUserId);
        List<SysSocial> list = socialMapper.lambda()
            .in(SysSocial::getSource, DingTalkSocials.SOURCES)
            .and(wrapper -> wrapper.eq(SysSocial::getAccessCode, dingUserId)
                .or().eq(SysSocial::getAuthId, authId))
            .list();
        return single(list, "钉钉 userid 命中多条绑定");
    }

    private SysSocial findUserSocial(Long userId) {
        List<SysSocial> list = socialMapper.lambda()
            .eq(SysSocial::getUserId, userId)
            .in(SysSocial::getSource, DingTalkSocials.SOURCES)
            .list();
        if (list.isEmpty()) {
            return null;
        }
        return list.getFirst();
    }

    private SysSocial single(List<SysSocial> list, String message) {
        if (list.isEmpty()) {
            return null;
        }
        if (list.size() > 1) {
            throw new ServiceException(message);
        }
        return list.getFirst();
    }

    private SocialRef toRef(SysSocial social) {
        if (social == null) {
            return null;
        }
        return new SocialRef(social.getId(), social.getUserId());
    }

    private boolean userNameExists(String userName) {
        return !userMapper.lambda().eq(SysUser::getUserName, userName).list().isEmpty();
    }

    private boolean phoneTaken(String mobile, Long userId) {
        return userMapper.lambda().eq(SysUser::getPhoneNumber, mobile).list().stream()
            .anyMatch(item -> !item.getUserId().equals(userId));
    }

    private String encodedInitPassword() {
        String raw;
        try {
            raw = configService.selectConfigByKey("sys.user.initPassword");
        } catch (RuntimeException ex) {
            raw = null;
        }
        if (StringUtils.isBlank(raw)) {
            byte[] seed = new byte[12];
            new SecureRandom().nextBytes(seed);
            raw = HexFormat.of().formatHex(seed);
        }
        return BCrypt.hashpw(raw);
    }

    private void finish(SysDingtalkSyncLog row, SyncReport report, RuntimeException error) {
        if (error != null) {
            report.failUser(messageOf(error));
            row.setStatus(FAILED);
        } else {
            row.setStatus(report.status());
        }
        row.setDeptSuccess(report.deptSuccess);
        row.setDeptFail(report.deptFail);
        row.setUserSuccess(report.userSuccess);
        row.setUserFail(report.userFail);
        row.setUserSkip(report.userSkip);
        row.setSummary(DingTalkAccounts.cut(report.summary(), 500));
        row.setFailDetail(DingTalkAccounts.cut(report.detail(), 2000));
        row.setFinishTime(LocalDateTime.now());
        logMapper.updateById(row);
    }

    private SysDingtalkSyncLog newLog(String batchNo, Long operatorId) {
        SysDingtalkSyncLog row = new SysDingtalkSyncLog();
        row.setBatchNo(batchNo);
        row.setStatus(RUNNING);
        row.setDeptSuccess(0);
        row.setDeptFail(0);
        row.setUserSuccess(0);
        row.setUserFail(0);
        row.setUserSkip(0);
        row.setSummary("同步进行中");
        row.setStartTime(LocalDateTime.now());
        fillOperator(row, operatorId);
        return row;
    }

    private void fillOperator(org.dromara.common.mybatis.core.domain.BaseEntity entity, Long operatorId) {
        if (operatorId == null) {
            return;
        }
        if (entity.getCreateBy() == null) {
            entity.setCreateBy(operatorId);
        }
        entity.setUpdateBy(operatorId);
    }

    private Long currentUserId() {
        try {
            return LoginHelper.getUserId();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private void clearDeptCache() {
        try {
            CacheUtils.clear(CacheNames.SYS_DEPT);
            CacheUtils.clear(CacheNames.SYS_DEPT_AND_CHILD);
        } catch (RuntimeException ex) {
            log.warn("清理部门缓存失败: {}", ex.getClass().getSimpleName());
        }
    }

    private void evictNickname(Long userId) {
        try {
            CacheUtils.evict(CacheNames.SYS_NICKNAME, userId);
        } catch (RuntimeException ex) {
            log.debug("清理用户昵称缓存失败");
        }
    }

    private SysDingtalkSyncLogVo toVo(SysDingtalkSyncLog row) {
        if (row == null) {
            return null;
        }
        SysDingtalkSyncLogVo vo = new SysDingtalkSyncLogVo();
        vo.setId(row.getId());
        vo.setBatchNo(row.getBatchNo());
        vo.setStatus(row.getStatus());
        vo.setStatusName(statusName(row.getStatus()));
        vo.setDeptSuccess(row.getDeptSuccess());
        vo.setDeptFail(row.getDeptFail());
        vo.setUserSuccess(row.getUserSuccess());
        vo.setUserFail(row.getUserFail());
        vo.setUserSkip(row.getUserSkip());
        vo.setSummary(row.getSummary());
        vo.setFailDetail(row.getFailDetail());
        vo.setStartTime(row.getStartTime());
        vo.setFinishTime(row.getFinishTime());
        return vo;
    }

    private String statusName(String status) {
        return switch (StringUtils.blankToDefault(status, "")) {
            case RUNNING -> "进行中";
            case SUCCESS -> "成功";
            case PARTIAL -> "部分失败";
            case FAILED -> "失败";
            default -> status;
        };
    }

    private String newBatchNo() {
        return LocalDateTime.now().format(BATCH_FORMAT) + String.format("%03d", ThreadLocalRandom.current().nextInt(1000));
    }

    private int normalizeOrder(int order) {
        return Math.max(order, 0);
    }

    private Long usableDept(Long deptId) {
        if (deptId == null || deptId == 0L) {
            return null;
        }
        return deptId;
    }

    private String usableEmail(String email) {
        String value = DingTalkAccounts.cut(email, 50);
        if (value == null || !value.contains("@")) {
            return null;
        }
        return value;
    }

    private String displayName(EmployeeBuf employee) {
        String name = DingTalkAccounts.cut(employee.name, 30);
        return name == null ? DingTalkAccounts.cut(employee.userId, 30) : name;
    }

    private String socialName(EmployeeBuf employee) {
        String name = displayName(employee);
        return name == null ? "ding" : name;
    }

    private boolean placeholderToken(String token) {
        return StringUtils.isBlank(token) || DingTalkSocials.TOKEN_PLACEHOLDER.equals(token);
    }

    private String label(DingDepartment department) {
        String name = StringUtils.blankToDefault(department.name(), "未命名");
        return name + "(" + department.deptId() + ")";
    }

    private String messageOf(RuntimeException ex) {
        String message = ex.getMessage();
        if (StringUtils.isBlank(message)) {
            message = ex.getClass().getSimpleName();
        }
        return DingTalkAccounts.cut(message, 180);
    }

    private static final class SyncContext {

        private final Map<Long, Long> dingToLocal = new HashMap<>();

        private final Map<Long, Long> leaderUserByDingDept = new HashMap<>();

        private final Set<Long> seenDingDepts = new HashSet<>();

        private final Set<String> seenDingUsers = new HashSet<>();

        private Long anchorLocalId;

        private Long defaultParentId;

    }

    private static final class EmployeeBuf {

        private final String userId;

        private String unionId;

        private String name;

        private String mobile;

        private String email;

        private String title;

        private String jobNumber;

        private String avatar;

        private boolean active;

        private final Set<Long> deptIds = new LinkedHashSet<>();

        private final Set<Long> leaderDepts = new LinkedHashSet<>();

        private EmployeeBuf(DingEmployee employee) {
            this.userId = employee.userId();
            merge(0L, employee);
            if (deptIds.contains(0L)) {
                deptIds.remove(0L);
            }
        }

        private void merge(long dingDeptId, DingEmployee employee) {
            if (dingDeptId > 0) {
                deptIds.add(dingDeptId);
            }
            if (employee.deptIds() != null) {
                deptIds.addAll(employee.deptIds());
            }
            if (employee.leader() && dingDeptId > 0) {
                leaderDepts.add(dingDeptId);
            }
            unionId = first(unionId, employee.unionId());
            name = first(name, employee.name());
            mobile = first(mobile, employee.mobile());
            email = first(email, employee.email());
            title = first(title, employee.title());
            jobNumber = first(jobNumber, employee.jobNumber());
            avatar = first(avatar, employee.avatar());
            active = employee.active();
        }

        private String display() {
            return StringUtils.blankToDefault(name, userId);
        }

        private String first(String current, String incoming) {
            return StringUtils.isNotBlank(current) ? current : incoming;
        }

    }

    private static final class SyncReport {

        private int deptSuccess;

        private int deptFail;

        private int userSuccess;

        private int userFail;

        private int userSkip;

        private int absentDept;

        private int absentUser;

        private final List<String> lines = new ArrayList<>();

        private void okDept() {
            deptSuccess++;
        }

        private void failDept(String message) {
            deptFail++;
            add(message);
        }

        private void okUser() {
            userSuccess++;
        }

        private void failUser(String message) {
            userFail++;
            add(message);
        }

        private void skipUser(String message) {
            userSkip++;
            add(message);
        }

        private void add(String message) {
            if (lines.size() < 40 && StringUtils.isNotBlank(message)) {
                lines.add(message);
            }
        }

        private String status() {
            if (deptFail == 0 && userFail == 0) {
                return SUCCESS;
            }
            if (deptSuccess == 0 && userSuccess == 0) {
                return FAILED;
            }
            return PARTIAL;
        }

        private String summary() {
            return "部门成功 " + deptSuccess + "，失败 " + deptFail
                + "；用户成功 " + userSuccess + "，失败 " + userFail + "，跳过 " + userSkip
                + "；已映射但本次未出现的部门 " + absentDept + " 个、人员 " + absentUser + " 个（未删除、未停用）";
        }

        private String detail() {
            return String.join("\n", lines);
        }

    }

}
