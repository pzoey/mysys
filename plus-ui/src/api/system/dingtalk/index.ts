import request from '@/utils/request';
import type { AxiosPromise } from '@/utils/api-types';

export interface DingtalkSyncLogVO {
  id: string | number;
  batchNo: string;
  status: string;
  statusName: string;
  deptSuccess: number;
  deptFail: number;
  userSuccess: number;
  userFail: number;
  userSkip: number;
  summary: string;
  failDetail: string;
  startTime: string;
  finishTime: string;
}

export const syncDingtalkOrg = (): AxiosPromise<DingtalkSyncLogVO> => {
  return request({
    url: '/system/dingtalk/sync',
    method: 'post'
  });
};

export const getLatestDingtalkSync = (): AxiosPromise<DingtalkSyncLogVO> => {
  return request({
    url: '/system/dingtalk/sync/latest',
    method: 'get'
  });
};
