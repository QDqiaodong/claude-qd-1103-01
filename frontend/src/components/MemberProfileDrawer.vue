<script setup>
import { computed } from 'vue'

const visible = defineModel({ type: Boolean })
const props = defineProps({ profile: { type: Object, default: null } })

const ledgerText = (t) => ({
  OPENING: '开档积分', EARN: '消费累计', REFUND: '退款回退', MANUAL: '人工调整',
  MERGE_MARK: '合并归档', REVOKE_MARK: '撤销合并'
}[t] || t)

const fieldText = (f) => f === 'PHONE' ? '手机号' : '姓名'

const ledgerSum = computed(() =>
  props.profile ? props.profile.ledger.reduce((s, l) => s + l.pointsDelta, 0) : 0)
</script>

<template>
  <el-drawer v-model="visible" title="会员档案与审计" size="640px" destroy-on-close>
    <div v-if="profile" class="wrap">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="ID">{{ profile.id }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="profile.status === '已合并' ? 'info' : 'success'">{{ profile.status }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="手机号">{{ profile.phone }}</el-descriptions-item>
        <el-descriptions-item label="昵称">{{ profile.name }}</el-descriptions-item>
        <el-descriptions-item label="积分">
          {{ profile.points }}
          <span class="muted">（流水合计 {{ ledgerSum }}）</span>
        </el-descriptions-item>
        <el-descriptions-item label="等级">{{ profile.level }}</el-descriptions-item>
      </el-descriptions>

      <el-alert v-if="profile.master" type="info" :closable="false" show-icon style="margin:12px 0">
        本档案已合并归档（只读别名），当前归属主档：
        <b>#{{ profile.master.id }} {{ profile.master.name }}（{{ profile.master.phone }}）</b>
      </el-alert>

      <template v-if="profile.aliases?.length">
        <h4>并入的只读别名</h4>
        <el-table :data="profile.aliases" size="small" border>
          <el-table-column prop="id" label="原档ID" width="70" />
          <el-table-column prop="phone" label="原手机号" width="150" />
          <el-table-column prop="name" label="原昵称" width="110" />
          <el-table-column prop="reason" label="合并原因" min-width="140" />
          <el-table-column prop="mergeOperator" label="操作人" width="90" />
          <el-table-column prop="mergedAt" label="合并时间" width="160" />
        </el-table>
      </template>

      <h4>历史订单（合并后统一归本档，含原始归属标记）</h4>
      <el-table :data="profile.orders" size="small" border max-height="220">
        <el-table-column prop="id" label="单号" width="70" />
        <el-table-column prop="payType" label="支付" width="70" />
        <el-table-column prop="total" label="金额" width="70" />
        <el-table-column prop="status" label="状态" width="80" />
        <el-table-column prop="pointsEarned" label="积分" width="60" />
        <el-table-column label="原始归属" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.originMemberId !== profile.id" size="small" type="warning">
              #{{ row.originMemberId }}
            </el-tag>
            <span v-else class="muted">本档</span>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="时间" width="160" />
      </el-table>

      <h4>积分流水</h4>
      <el-table :data="profile.ledger" size="small" border max-height="240">
        <el-table-column prop="id" label="#" width="60" />
        <el-table-column label="类型" width="90">
          <template #default="{ row }">{{ ledgerText(row.changeType) }}</template>
        </el-table-column>
        <el-table-column prop="pointsDelta" label="变动" width="70" />
        <el-table-column prop="balanceAfter" label="余额" width="70" />
        <el-table-column prop="remark" label="说明" min-width="160" />
        <el-table-column label="来源" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.originMemberId !== profile.id" size="small" type="warning">
              原#{{ row.originMemberId }}
            </el-tag>
            <span v-else class="muted">本档</span>
          </template>
        </el-table-column>
        <el-table-column prop="operator" label="操作人" width="80" />
        <el-table-column prop="createdAt" label="时间" width="160" />
      </el-table>

      <h4>联系方式变更审计</h4>
      <el-table :data="profile.contactChanges" size="small" border max-height="220">
        <el-table-column label="字段" width="70">
          <template #default="{ row }">{{ fieldText(row.fieldName) }}</template>
        </el-table-column>
        <el-table-column prop="beforeValue" label="变更前" width="130" />
        <el-table-column prop="afterValue" label="变更后" width="130" />
        <el-table-column prop="reason" label="原因" min-width="140" />
        <el-table-column prop="operator" label="操作人" width="90" />
        <el-table-column prop="effectiveAt" label="生效时间" width="160" />
      </el-table>
    </div>
  </el-drawer>
</template>

<style scoped>
.wrap h4 { margin: 16px 0 8px; color: #7a5230; }
.muted { color: #aaa; font-size: 12px; }
</style>
