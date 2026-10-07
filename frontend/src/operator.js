import { reactive } from 'vue'
import { ElMessageBox } from 'element-plus'

// 全局操作人上下文（无鉴权中间件，由店员在页面选择身份并随每次写请求提交）
export const sharedOperator = reactive({ operator: '', operatorRole: '店员' })

export function isManager() {
  return sharedOperator.operatorRole === '负责人'
}

export async function ensureOperator() {
  if (sharedOperator.operator) return true
  try {
    const { value } = await ElMessageBox.prompt('请先输入当前操作人姓名', '操作人', {
      confirmButtonText: '确定', cancelButtonText: '取消',
      inputValidator: (v) => (v && v.trim() ? true : '操作人不能为空')
    })
    sharedOperator.operator = value.trim()
    return true
  } catch {
    return false
  }
}
