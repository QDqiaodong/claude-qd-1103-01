import axios from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 30000 })

http.interceptors.response.use(
  (response) => response,
  (error) => {
    const message = error?.response?.data?.message || error?.message || '请求失败'
    return Promise.reject(new Error(message))
  }
)

async function call(promise) {
  const response = await promise
  const body = response.data
  if (!body || body.ok === false) {
    throw new Error((body && body.message) || '请求失败')
  }
  return body.data
}

const params = (o) => ({ params: o })

export default {
  // ---------------- 原料库存 ----------------
  listIngredients: (category, status) => call(http.get('/ingredients', params(
    category ? { category, ...(status ? { status } : {}) }
              : (status ? { status } : {})))),
  createIngredient: (d) => call(http.post('/ingredients', d)),
  updateIngredient: (id, d) => call(http.put(`/ingredients/${id}`, d)),

  // ---------------- 配方 ----------------
  listRecipes: () => call(http.get('/recipes')),
  createRecipe: (d) => call(http.post('/recipes', d)),
  updateRecipe: (id, d) => call(http.put(`/recipes/${id}`, d)),

  // ---------------- 会员 ----------------
  listMembers: (level, status) =>
    call(http.get('/members', params({
      ...(level ? { level } : {}),
      ...(status ? { status } : {})
    }))),
  createMember: (d) => call(http.post('/members', d)),
  updateMember: (id, d) => call(http.put(`/members/${id}`, d)),
  // 旧积分接口已下线（积分必须带流水/原因），保留兼容映射到新入口
  addMemberPoints: (id, delta, extra = {}) =>
    call(http.post(`/members/${id}/points-adjust`, { delta, reason: '人工调整积分', ...extra })),
  adjustMemberPoints: (id, d) => call(http.post(`/members/${id}/points-adjust`, d)),
  changeMemberContact: (id, d) => call(http.post(`/members/${id}/contact-change`, d)),
  memberProfile: (id) => call(http.get(`/members/${id}/profile`)),
  importLegacyMember: (d) => call(http.post('/members/legacy-import', d)),

  // ---------------- 会员合并工单 ----------------
  listMerges: (status) =>
    call(http.get('/member-merges', params(status ? { status } : {}))),
  mergeDetail: (id) => call(http.get(`/member-merges/${id}`)),
  markMerge: (d) => call(http.post('/member-merges', d)),
  resolveMerge: (id, d) => call(http.post(`/member-merges/${id}/resolve`, d)),
  executeMerge: (id, d) => call(http.post(`/member-merges/${id}/execute`, d)),
  revokeMerge: (id, d) => call(http.post(`/member-merges/${id}/revoke`, d)),

  // ---------------- 出杯记录 ----------------
  listCupOrders: (status) => call(http.get('/cup-orders', params(status ? { status } : {}))),
  listCupOrderFailures: () => call(http.get('/cup-orders/failures')),
  previewCupOrder: (d) => call(http.post('/cup-orders/preview', d)),
  createCupOrder: (d) => call(http.post('/cup-orders', d)),
  refundCupOrder: (id) => call(http.post(`/cup-orders/${id}/refund`))
}
