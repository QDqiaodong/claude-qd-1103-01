<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import OperatorBar from '../components/OperatorBar.vue'
import MemberProfileDrawer from '../components/MemberProfileDrawer.vue'
import { sharedOperator, ensureOperator } from '../operator'

const list = ref([])
const createVisible = ref(false)
const contactVisible = ref(false)
const pointsVisible = ref(false)
const profileVisible = ref(false)
const profile = ref(null)
const form = reactive({ phone: '', name: '', points: 0, level: '普通' })
const contactForm = reactive({
  id: null, version: null, phone: '', name: '', originalPhone: '', originalName: '', reason: ''
})
const pointsForm = reactive({ id: null, version: null, delta: 0, reason: '' })
const filter = reactive({ level: '', status: '有效' })

const levels = ['普通', '银卡', '金卡']
const emit = defineEmits(['changed'])

async function load() {
  list.value = await api.listMembers(filter.level || undefined, filter.status || undefined)
}

function resetForm() {
  form.phone = ''; form.name = ''; form.points = 0; form.level = '普通'
}

function openCreate() {
  if (!ensureOperator(sharedOperator)) return
  resetForm()
  createVisible.value = true
}

async function submitCreate() {
  try {
    await api.createMember({ ...form, ...sharedOperator })
    ElMessage.success('已新增会员')
    createVisible.value = false
    await load(); emit('changed')
  } catch (e) { ElMessage.error(e.message) }
}

function openContact(row) {
  if (!ensureOperator(sharedOperator)) return
  Object.assign(contactForm, {
    id: row.id, version: row.version, phone: row.phone, name: row.name,
    originalPhone: row.phone, originalName: row.name, reason: ''
  })
  contactVisible.value = true
}

async function submitContact() {
  try {
    const payload = { reason: contactForm.reason, expectedVersion: contactForm.version, ...sharedOperator }
    if (contactForm.phone !== contactForm.originalPhone) payload.phone = contactForm.phone
    if (contactForm.name !== contactForm.originalName) payload.name = contactForm.name
    await api.changeMemberContact(contactForm.id, payload)
    ElMessage.success('联系方式已变更并留痕')
    contactVisible.value = false
    await load(); emit('changed')
  } catch (e) { ElMessage.error(e.message) }
}

function openPoints(row) {
  if (!ensureOperator(sharedOperator)) return
  Object.assign(pointsForm, { id: row.id, version: row.version, delta: 0, reason: '' })
  pointsVisible.value = true
}

async function submitPoints() {
  try {
    await api.adjustMemberPoints(pointsForm.id, {
      delta: pointsForm.delta, reason: pointsForm.reason,
      expectedVersion: pointsForm.version, ...sharedOperator
    })
    ElMessage.success('积分已调整并记账')
    pointsVisible.value = false
    await load(); emit('changed')
  } catch (e) { ElMessage.error(e.message) }
}

async function openProfile(row) {
  profile.value = await api.memberProfile(row.id)
  profileVisible.value = true
}

const statusTagType = (s) => s === '已合并' ? 'info' : 'success'

onMounted(load)
</script>

<template>
  <div>
    <OperatorBar v-model="sharedOperator" />

    <div class="bar">
      <div class="filters">
        <el-select v-model="filter.level" placeholder="等级" clearable @change="load" style="width:130px">
          <el-option v-for="l in levels" :key="l" :label="l" :value="l" />
        </el-select>
        <el-select v-model="filter.status" placeholder="档案状态" clearable @change="load" style="width:140px">
          <el-option label="有效" value="有效" />
          <el-option label="已合并（只读别名）" value="已合并" />
        </el-select>
      </div>
      <el-button type="primary" @click="openCreate">新增会员</el-button>
    </div>

    <el-table :data="list" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="phone" label="手机号" width="160" />
      <el-table-column prop="name" label="昵称" min-width="120" />
      <el-table-column prop="points" label="积分" width="90" sortable />
      <el-table-column prop="level" label="等级" width="90">
        <template #default="{ row }">
          <el-tag :type="row.level === '金卡' ? 'warning' : (row.level === '银卡' ? 'success' : 'info')">
            {{ row.level }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="130">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status)">{{ row.status }}</el-tag>
          <el-link v-if="row.mergedIntoId" type="primary" :underline="false" class="trace"
                   @click="openProfile(row)">→ 主档#{{ row.mergedIntoId }}</el-link>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="260">
        <template #default="{ row }">
          <el-button link type="primary" @click="openProfile(row)">档案/审计</el-button>
          <el-button v-if="row.status === '有效'" link type="primary" @click="openContact(row)">改联系方式</el-button>
          <el-button v-if="row.status === '有效'" link type="primary" @click="openPoints(row)">调积分</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新增会员 -->
    <el-dialog v-model="createVisible" title="新增会员" width="420px">
      <el-form label-width="80px">
        <el-form-item label="手机号"><el-input v-model="form.phone" placeholder="11 位手机号" /></el-form-item>
        <el-form-item label="昵称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="开档积分"><el-input-number v-model="form.points" :min="0" /></el-form-item>
        <el-form-item label="等级">
          <el-select v-model="form.level" style="width:100%">
            <el-option v-for="l in levels" :key="l" :label="l" :value="l" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">保存</el-button>
      </template>
    </el-dialog>

    <!-- 联系方式变更（带原因 + 审计） -->
    <el-dialog v-model="contactVisible" title="联系方式变更（将保留审计记录）" width="460px">
      <el-alert type="warning" :closable="false" show-icon style="margin-bottom:12px"
        title="修改手机号或姓名会记录变更前后值、原因、操作人与生效时间；同一手机号不能绑定两个有效会员。" />
      <el-form label-width="80px">
        <el-form-item label="手机号"><el-input v-model="contactForm.phone" /></el-form-item>
        <el-form-item label="昵称"><el-input v-model="contactForm.name" /></el-form-item>
        <el-form-item label="变更原因" required>
          <el-input v-model="contactForm.reason" type="textarea" :rows="2"
            placeholder="如：顾客换号 / 顾客要求更正姓名" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="contactVisible = false">取消</el-button>
        <el-button type="primary" @click="submitContact">确认变更</el-button>
      </template>
    </el-dialog>

    <!-- 积分调整（带流水 + 原因） -->
    <el-dialog v-model="pointsVisible" title="调整积分（记入积分流水）" width="420px">
      <el-form label-width="80px">
        <el-form-item label="变动积分"><el-input-number v-model="pointsForm.delta" /></el-form-item>
        <el-form-item label="原因" required>
          <el-input v-model="pointsForm.reason" type="textarea" :rows="2" placeholder="如：活动赠送 / 纠错扣减" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pointsVisible = false">取消</el-button>
        <el-button type="primary" @click="submitPoints">确认调整</el-button>
      </template>
    </el-dialog>

    <MemberProfileDrawer v-model="profileVisible" :profile="profile" />
  </div>
</template>

<style scoped>
.bar { display: flex; justify-content: space-between; align-items: center; margin: 10px 0 14px; }
.filters { display: flex; gap: 10px; }
.trace { margin-left: 6px; font-size: 12px; }
</style>
