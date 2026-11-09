<template>
  <div class="p-2">
    <el-card shadow="hover">
      <template #header>
        <span>钉钉组织同步</span>
      </template>
      <el-alert
        class="mb-4"
        type="info"
        :closable="false"
        title="按钉钉通讯录全量写入本地部门和用户，并绑定 sys_social（DINGTALK_V2）。不会先删本地数据。"
      />
      <el-button v-hasPermi="['system:dingtalk:sync']" type="primary" :loading="syncing" @click="handleSync">全量同步</el-button>
      <el-button :loading="loading" @click="loadLatest">刷新结果</el-button>
      <el-descriptions v-if="latest" class="mt-4" :column="2" border>
        <el-descriptions-item label="批次">{{ latest.batchNo }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ latest.statusName }}</el-descriptions-item>
        <el-descriptions-item label="部门成功 / 失败">{{ latest.deptSuccess }} / {{ latest.deptFail }}</el-descriptions-item>
        <el-descriptions-item label="用户成功 / 失败 / 跳过">{{ latest.userSuccess }} / {{ latest.userFail }} / {{ latest.userSkip }}</el-descriptions-item>
        <el-descriptions-item label="开始">{{ latest.startTime }}</el-descriptions-item>
        <el-descriptions-item label="结束">{{ latest.finishTime }}</el-descriptions-item>
        <el-descriptions-item label="摘要" :span="2">{{ latest.summary }}</el-descriptions-item>
        <el-descriptions-item label="失败明细" :span="2">
          <pre class="fail-detail">{{ latest.failDetail || '无' }}</pre>
        </el-descriptions-item>
      </el-descriptions>
      <el-empty v-else class="mt-4" description="还没有同步记录" />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="DingtalkSync">
import { getCurrentInstance, onMounted, ref } from 'vue';
import type { ComponentInternalInstance } from 'vue';
import { getLatestDingtalkSync, syncDingtalkOrg } from '@/api/system/dingtalk';
import type { DingtalkSyncLogVO } from '@/api/system/dingtalk';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const loading = ref(false);
const syncing = ref(false);
const latest = ref<DingtalkSyncLogVO>();

const loadLatest = async () => {
  loading.value = true;
  try {
    const res = await getLatestDingtalkSync();
    latest.value = res.data;
  } finally {
    loading.value = false;
  }
};

const handleSync = async () => {
  await proxy?.$modal.confirm('确认从钉钉全量同步组织和人员？');
  syncing.value = true;
  try {
    const res = await syncDingtalkOrg();
    latest.value = res.data;
    proxy?.$modal.msgSuccess(res.data?.summary || '同步已完成');
  } finally {
    syncing.value = false;
  }
};

onMounted(() => {
  loadLatest();
});
</script>

<style scoped>
.fail-detail {
  margin: 0;
  white-space: pre-wrap;
}
</style>
