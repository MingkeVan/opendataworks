import axios from 'axios'

// 智能问数（DataAgent / FastAPI）NL2SQL 任务链路的前端客户端。
//
// 为什么不复用 @/utils/request：
// - 该实例的响应拦截器按 Java 后端统一信封 {code, data, message} 解包，
//   而 DataAgent 后端返回裸 JSON（TopicDetail / TaskSubmissionResponse 等），
//   套用会把正常响应误判为失败。
// - 这里直接返回 response.data，HTTP 错误原样抛给调用方处理。
//
// 请求来源语义：不携带 `X-ODW-Client` 头 → 后端按 portal（匿名）来源处理，无需站点白名单。
// 注意 agent_id 不能省略：省略时后端回落到 DEFAULT_AGENT_ID，若该助手的可见范围是
// authenticated/selected，匿名调用会被 `agent_visible_to` 拦下并返回 400 "agent not found"。
// 因此需先取 /dataagent/agents 目录——它对匿名调用用同一套可见性过滤，
// 返回的即是建话题时会被接受的助手集合。
//
// baseURL 取 /api/v1，覆盖 /nl2sql/* 与 /dataagent/*：两者在 vite 代理与 nginx 中
// 都有独立 location 指向 dataagent-backend:8900，不会落到 Java 后端的 /api 规则。
const nl2sqlClient = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
  withCredentials: true
})

nl2sqlClient.interceptors.response.use(
  (response) => response.data,
  (error) => Promise.reject(error)
)

/**
 * 提取 FastAPI 的错误文案。
 *
 * DataAgent 后端以 `{"detail": "..."}` 表达错误；axios 默认只给
 * "Request failed with status code 400"，会掩盖真实原因（如 "agent not found"）。
 */
export function nl2sqlErrorMessage(error, fallback = '请求失败') {
  const detail = error?.response?.data?.detail
  if (typeof detail === 'string' && detail.trim()) {
    return detail.trim()
  }
  if (detail && typeof detail === 'object') {
    const message = detail.message || detail.msg
    if (message) return String(message)
  }
  return error?.message || fallback
}

export const nl2sqlApi = {
  // 当前调用方可见的助手目录（可见性过滤与建话题一致）
  listAgents() {
    return nl2sqlClient.get('/dataagent/agents')
  },

  // 创建话题；agent_id 必须来自 listAgents 的结果
  createTopic(payload = {}) {
    return nl2sqlClient.post('/nl2sql/topics', payload)
  },

  // 发送消息并发起一次异步任务，返回 { task_id, ... }
  deliverMessage(payload) {
    return nl2sqlClient.post('/nl2sql/tasks/deliver-message', payload)
  },

  // 轮询任务状态，返回 { task_id, task_status, ... }
  getTask(taskId) {
    return nl2sqlClient.get(`/nl2sql/tasks/${taskId}`)
  },

  // 取任务对应的助手消息（承载格式化内容）
  getTaskMessage(taskId) {
    return nl2sqlClient.get(`/nl2sql/tasks/${taskId}/message`)
  }
}

export default nl2sqlApi
