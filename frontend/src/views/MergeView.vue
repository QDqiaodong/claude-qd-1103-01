<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import OperatorBar from '../components/OperatorBar.vue'
import { sharedOperator, ensureOperator, isManager } from '../operator'

const list = ref([])
const members = ref([])
const filter = ref('PENDING')
const loading = ref(false)

const markVisible = ref(false)
const markForm = reactive({ masterId: null, slaveId: null, reason: '' })

const detailVisible = ref(false)
const detail = ref(null)

const resolveVisible = ref(false)
const resolveForm = reactive({
  id: null, version: null, conflictTypes: '', keepPhone: '', pointsStrategy: '', reason: '',
  masterPhone: '', slavePhone: ''
})

const statusTabs = [
  { value: 'PENDING', label: '待处理' },
  { value: 'CONFLICT', label: '冲突待处理' },
  { value: 'MERGED', label: '已合并' },
  { value: 'REVOKED', label: '已撤销' }
]

const activeMembers = computed(() => members.value.filter(m => m.status === '有效'))
const slaveChoices = computed(() => activeMembers.value.filter(m => m.id !== markForm.masterId))

const statusTag = {
  PENDING: { type: 'warning', text: '待处理' },
  CONFLICT: { type: 'danger', text: '冲突待处理' },
  MERGED: { type: 'success', text: '已合并' },
  REVOKED: { type: 'info', text: '已撤销' }
}

async function load() {
  loading.value = true
  try {
    const [ms, mems] = await Promise.all([
      api.listMerges(filter.value || undefined),
      api.listMembers(undefined, undefined)
    ])
    list.value = ms
    members.value = mems
  } finally { loading.value = false }
}

function memberName(id) {
  const m = members.value.find(x => x.id === id)
  return m ? `#${id} ${m.name}（${m.phone}）` : `#${id}`
}

async function openMark() {
  if (!ensureOperator()) return
  markForm.masterId = null; markForm.slaveId = null; markForm.reason = ''
  markVisible.value = true
}

async function submitMark() {
  try {
    if (!markForm.masterId || !markForm.slaveId) return ElMessage.warning('请选择主档和重复档')
    if (!markForm.reason.trim()) return ElMessage.warning('请填写合并原因')
    const r = await api.markMerge({ ...markForm, ...sharedOperator })
    if (r.status === 'CONFLICT') {
      ElMessage.warning('发现手机号/积分来源冲突，已转入「冲突待处理」，须由负责人处理后才能合并')
    } else {
      ElMessage.success('已标记为待合并')
    }
    markVisible.value = false
    await load()
  } catch (e) { ElMessage.error(e.message) }
}

async function openDetail(row) {
  detail.value = await api.mergeDetail(row.id)
  detailVisible.value = true
}

async function executeMerge(row) {
  try {
    await ElMessageBox.confirm(
      `确认把 ${memberName(row.slaveId)} 并入 ${memberName(row.masterId)}？\n历史订单与积分流水将统一归到主档，原档保留为只读别名。`,
      '执行合并', { type: 'warning', confirmButtonText: '确认合并', cancelButtonText: '取消' })
  } catch { return }
  try {
    const d = await api.mergeDetail(row.id)
    const r = await api.executeMerge(row.id, { expectedVersion: d.version, ...sharedOperator })
    ElMessage.success('合并完成：' + (r.resultSummary || ''))
    await load()
  } catch (e) { ElMessage.error(e.message) }
}

function openResolve(row) {
  Promise.resolve(api.mergeDetail(row.id)).then(d => {
    const types = (d.conflictTypes || '').split(',').filter(Boolean)
    Object.assign(resolveForm, {
      id: d.id, version: d.version, conflictTypes: d.conflictTypes,
      keepPhone: '', pointsStrategy: '', reason: '',
      masterPhone: d.master?.phone, slavePhone: d.slave?.phone
    })
    resolveForm._hasPhone = types.includes('PHONE_CONFLICT')
    resolveForm._hasPoints = types.includes('POINTS_SOURCE')
    resolveVisible.value = true
  }).catch(e => ElMessage.error(e.message))
}

async function submitResolve() {
  try {
    if (!isManager()) return ElMessage.error('只有负责人可以处理冲突')
    const payload = { reason: resolveForm.reason, ...sharedOperator }
    if (resolveForm._hasPhone) {
      if (!resolveForm.keepPhone) return ElMessage.warning('必须明确选择保留哪边的手机号，不能静默覆盖')
      payload.keepPhone = resolveForm.keepPhone
    }
    if (resolveForm._hasPoints) {
      if (!resolveForm.pointsStrategy) return ElMessage.warning('必须选择积分来源处理策略')
      payload.pointsStrategy = resolveForm.pointsStrategy
    }
    if (!payload.reason?.trim()) return ElMessage.warning('请填写处理说明')
    await api.resolveMerge(resolveForm.id, payload)
    ElMessage.success('冲突已解决，工单回到待处理，可执行合并')
    resolveVisible.value = false
    await load()
  } catch (e) { ElMessage.error(e.message) }
}

async function revoke(row) {
  if (!isManager()) return ElMessage.error('撤销合并只能由负责人发起')
  let reason = ''
  try {
    const r = await ElMessageBox.prompt('请填写撤销原因（订单与积分将按原始归属还原，不重复计算）',
      '撤销合并', {
        confirmButtonText: '确认撤销', cancelButtonText: '取消',
        inputValidator: v => (v && v.trim() ? true : '必须填写撤销原因')
      })
    reason = r.value
  } catch { return }
  try {
    const d = await api.mergeDetail(row.id)
    const res = await api.revokeMerge(row.id, {
      expectedVersion: d.version, reason, ...sharedOperator
    })
    ElMessage.success('已撤销：' + (res.revokeSummary || ''))
    await load()
  } catch (e) { ElMessage.error(e.message) }
}

onMounted(load)
</script>

<template>
  <div>
    <OperatorBar v-model="sharedOperator" />

    <div class="bar">
      <el-radio-group v-model="filter" @change="load">
        <el-radio-button v-for="t in statusTabs" :key="t.value" :value="t.value">{{ t.label }}</el-radio-button>
      </el-radio-group>
      <el-button type="primary" @click="openMark">标记重复会员为待合并</el-button>
    </div>

    <el-table v-loading="loading" :data="list" border stripe>
      <el-table-column prop="id" label="工单号" width="80" />
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="statusTag[row.status]?.type">{{ statusTag[row.status]?.text }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="主档（保留）" min-width="180">
        <template #default="{ row }">{{ memberName(row.masterId) }}</template>
      </el-table-column>
      <el-table-column label="重复档（并入）" min-width="180">
        <template #default="{ row }">
          <span>{{ memberName(row.slaveId) }}</span>
          <el-tooltip v-if="row.conflictTypes" :content="row.conflictDetail" placement="top">
            <el-tag type="danger" size="small" class="conflict-tag">冲突</el-tag>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column prop="reason" label="合并原因" min-width="140" show-overflow-tooltip />
      <el-table-column prop="operator" label="发起人" width="100" />
      <el-table-column label="操作" width="260" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情/差异/审计</el-button>
          <el-button v-if="row.status === 'PENDING'" link type="success" @click="executeMerge(row)">执行合并</el-button>
          <el-button v-if="row.status === 'CONFLICT'" link type="danger" @click="openResolve(row)">处理冲突</el-button>
          <el-button v-if="row.status === 'MERGED'" link type="warning" @click="revoke(row)">撤销</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 标记待合并 -->
    <el-dialog v-model="markVisible" title="标记重复会员为待合并" width="480px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px"
        title="选择保留的主档与待并入的重复档并填写原因。系统会立即检查手机号冲突与积分来源，冲突时进入「冲突待处理」，不会静默覆盖。" />
      <el-form label-width="92px">
        <el-form-item label="保留主档" required>
          <el-select v-model="markForm.masterId" filterable placeholder="选择保留的档案" style="width:100%">
            <el-option v-for="m in activeMembers" :key="m.id"
              :label="`#${m.id} ${m.name}（${m.phone}）积分${m.points}`" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="重复档" required>
          <el-select v-model="markForm.slaveId" filterable placeholder="选择待并入的档案" style="width:100%">
            <el-option v-for="m in slaveChoices" :key="m.id"
              :label="`#${m.id} ${m.name}（${m.phone}）积分${m.points}`" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="合并原因" required>
          <el-input v-model="markForm.reason" type="textarea" :rows="2"
            placeholder="如：顾客出示两张卡，核实为同一人" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="markVisible = false">取消</el-button>
        <el-button type="primary" @click="submitMark">标记并检查冲突</el-button>
      </template>
    </el-dialog>

    <!-- 冲突处理 -->
    <el-dialog v-model="resolveVisible" title="冲突待处理（仅负责人）" width="520px">
      <el-alert type="error" :closable="false" show-icon style="margin-bottom:12px"
        :title="'冲突类型：' + resolveForm.conflictTypes" />
      <el-form label-width="120px">
        <el-form-item v-if="resolveForm._hasPhone" label="保留手机号">
          <el-radio-group v-model="resolveForm.keepPhone">
            <el-radio :value="resolveForm.masterPhone">主档号码 {{ resolveForm.masterPhone }}</el-radio>
            <el-radio :value="resolveForm.slavePhone" :disabled="resolveForm.slavePhone === resolveForm.masterPhone">
              重复档号码 {{ resolveForm.slavePhone }}
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="resolveForm._hasPoints" label="积分来源策略">
          <el-radio-group v-model="resolveForm.pointsStrategy">
            <el-radio value="TRUST_BALANCE">以当前余额为准（补调平流水）</el-radio>
            <el-radio value="REBUILD_FROM_LEDGER">以积分流水合计重建余额</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="处理说明" required>
          <el-input v-model="resolveForm.reason" type="textarea" :rows="2"
            placeholder="说明核实过程与选择依据" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resolveVisible = false">取消</el-button>
        <el-button type="danger" @click="submitResolve">解决冲突</el-button>
      </template>
    </el-dialog>

    <!-- 详情 / 合并前后差异 / 审计 -->
    <el-drawer v-model="detailVisible" title="合并工单详情 · 前后差异与审计" size="760px" destroy-on-close>
      <div v-if="detail" class="wrap">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="工单号">{{ detail.id }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTag[detail.status]?.type">{{ statusTag[detail.status]?.text }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="发起人">{{ detail.operator }}（{{ detail.createdAt }}）</el-descriptions-item>
          <el-descriptions-item label="合并原因">{{ detail.reason }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.mergeOperator" label="执行合并">
            {{ detail.mergeOperator }} · {{ detail.mergedAt }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.revokeOperator" label="撤销（负责人）">
            {{ detail.revokeOperator }} · {{ detail.revokedAt }} · {{ detail.revokeReason }}
          </el-descriptions-item>
        </el-descriptions>

        <el-alert v-if="detail.conflictTypes" type="error" :closable="false" show-icon style="margin:12px 0">
          <div>冲突类型：{{ detail.conflictTypes }}</div>
          <div>{{ detail.conflictDetail }}</div>
          <div v-if="detail.resolvedPhone">解决：主档保留手机号 {{ detail.resolvedPhone }}</div>
          <div v-if="detail.pointsStrategy">解决：积分策略 {{ detail.pointsStrategy }}</div>
        </el-alert>
        <el-alert v-if="detail.precheckConflicts?.length" type="warning" :closable="false" show-icon style="margin:12px 0">
          <div v-for="(c, i) in detail.precheckConflicts" :key="i">{{ c }}</div>
        </el-alert>

        <h4>合并前两边档案对照（手机号 / 姓名 / 积分 / 历史订单）</h4>
        <el-table :data="[detail.master, detail.slave].filter(Boolean)" size="small" border>
          <el-table-column label="角色" width="80">
            <template #default="{ row }">
              <el-tag :type="row.id === detail.masterId ? 'success' : 'info'">
                {{ row.id === detail.masterId ? '主档' : '重复档' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="id" label="ID" width="60" />
          <el-table-column prop="phone" label="手机号" width="140" />
          <el-table-column prop="name" label="姓名" width="110" />
          <el-table-column prop="points" label="当前积分" width="80" />
          <el-table-column prop="ledgerSum" label="流水合计" width="80" />
          <el-table-column prop="level" label="等级" width="70" />
          <el-table-column prop="orderCount" label="历史订单" width="80" />
          <el-table-column prop="status" label="档案状态" width="90" />
        </el-table>

        <template v-if="detail.masterSnapshot || detail.slaveSnapshot">
          <h4>合并前后差异（快照）</h4>
          <div class="snap">
            <div><b>合并前主档：</b>{{ detail.masterSnapshot }}</div>
            <div><b>合并前重复档：</b>{{ detail.slaveSnapshot }}</div>
          </div>
          <el-alert v-if="detail.resultSummary" type="success" :closable="false" :title="detail.resultSummary"
            style="margin-top:8px" />
          <el-alert v-if="detail.revokeSummary" type="info" :closable="false" :title="detail.revokeSummary"
            style="margin-top:8px" />
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.bar { display: flex; justify-content: space-between; align-items: center; margin: 10px 0 14px; }
.wrap h4 { margin: 16px 0 8px; color: #7a5230; }
.conflict-icon { color: #f56c6c; margin-left: 6px; }
.snap { background: #faf3ec; border: 1px solid #e8d5c2; border-radius: 6px; padding: 10px;
  font-size: 12px; line-height: 1.9; word-break: break-all; }
</style>
