import test from 'node:test';
import assert from 'node:assert/strict';
import { ZcaAdapter } from '../src/zca-adapter.mjs';

test('đọc vai trò phó nhóm từ đúng người và chỉ kết luận vắng mặt từ danh sách đầy đủ', async () => {
  const adapter = new ZcaAdapter();
  const group = {
    groupId: '222', creatorId: '1000', adminIds: ['111'],
    memberIds: ['1000', '111', '333'], currentMems: [{ id: '333', dName: 'Không trả ra ngoài' }],
    totalMember: 3, hasMoreMember: 0, setting: { addMemberOnly: 1 },
  };
  adapter.api = {
    getAllGroups: async () => ({ gridVerMap: { '222': 1 } }),
    getGroupInfo: async () => ({ gridInfoMap: { '222': group } }),
    getOwnId: () => '1000',
    getGroupLinkDetail: () => assert.fail('chỉ đọc vai trò, không cần link mời'),
  };
  const evidence = (deputyUid) => adapter.inspectGroup('222', deputyUid);
  const admin = await evidence('111');
  assert.equal(admin.owner, true);
  assert.equal(admin.deputyRole, 'ADMIN');
  assert.equal(admin.groupId, '222');
  assert.equal((await evidence('1000')).deputyRole, 'ADMIN');
  assert.equal((await evidence('333')).deputyRole, 'MEMBER');
  assert.equal((await evidence('444')).deputyRole, 'ABSENT');
  group.pendingApprove = { uids: ['444'] };
  assert.equal((await evidence('444')).deputyRole, 'UNKNOWN');
  group.pendingApprove = { uids: null };
  group.hasMoreMember = 1;
  assert.equal((await evidence('444')).deputyRole, 'UNKNOWN');
  assert.equal((await evidence('333')).deputyRole, 'MEMBER');
  group.hasMoreMember = 0;
  group.memberIds = ['1000', '111', '111'];
  assert.equal((await evidence('444')).deputyRole, 'UNKNOWN');
  group.memberIds = [];
  group.currentMems = [];
  assert.equal((await evidence('444')).deputyRole, 'UNKNOWN');
  group.creatorId = '111';
  group.adminIds = ['1000'];
  assert.equal((await evidence('111')).owner, false);
  assert.equal((await evidence('111')).admin, true);
});
