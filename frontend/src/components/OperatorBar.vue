<script setup>
import { ensureOperator } from '../operator'

const operator = defineModel({ type: Object, required: true })

function setName(v) {
  operator.value.operator = v
}
</script>

<template>
  <div class="op-bar">
    <span class="label">当前操作人</span>
    <el-input
      :model-value="operator.operator"
      @update:model-value="setName"
      placeholder="点击输入姓名"
      style="width:160px"
      @focus="ensureOperator()"
    />
    <el-radio-group v-model="operator.operatorRole" size="small">
      <el-radio-button label="店员">店员</el-radio-button>
      <el-radio-button label="负责人">负责人</el-radio-button>
    </el-radio-group>
    <el-tag v-if="operator.operatorRole === '负责人'" type="danger" size="small">
      可解决冲突 / 撤销合并 / 导入历史档
    </el-tag>
  </div>
</template>

<style scoped>
.op-bar {
  display: flex; align-items: center; gap: 10px;
  padding: 8px 12px; margin-bottom: 8px;
  background: #faf3ec; border: 1px solid #e8d5c2; border-radius: 6px;
}
.label { font-size: 13px; color: #7a5230; }
</style>
