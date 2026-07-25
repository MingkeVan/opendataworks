import axios from 'axios'

// 智能问数（DataAgent / FastAPI）NL2SQL 任务链路的前端客户端。
//
// 为什么不复用 @/utils/request：
// - 该实例的响应拦截器按 Java 后端统一信封 {code, data, message} 解包，
//   而 DataAgent 后端返回裸 JSON（TopicDetail / TaskSubmissionResponse 等），
//   套用会把正常响应误判为失败。
// - 这里直接返回 response.data，HTTP 错误原样抛给调用方处理。
//
// 请求来源语义：不携带 `X-ODW-Client: widget` 头 → 后端按 portal（匿名）来源处理，
// 无需站点白名单，也无需显式 agent_id（使用默认助手）。
// 开发态由 vite 代理 /api/v1/nl2sql → dataagent-backend:8900。
const nl2sqlClient = axios.create({
  baseURL: '/api/v1/nl2sql',
  timeout: 30000,
  withCredentials: true
})

nl2sqlClient.interceptors.response.use(
  (response) => response.data,
  (error) => Promise.reject(error)
)

export const nl2sqlApi = {
  // 创建话题；portal 来源可省略 agent_id
  createTopic(payload = {}) {
    return nl2sqlClient.post('/topics', payload)
  },

  // 发送消息并发起一次异步任务，返回 { task_id, ... }
  deliverMessage(payload) {
    return nl2sqlClient.post('/tasks/deliver-message', payload)
  },

  // 轮询任务状态，返回 { task_id, task_status, ... }
  getTask(taskId) {
    return nl2sqlClient.get(`/tasks/${taskId}`)
  },

  // 取任务对应的助手消息（承载格式化内容）
  getTaskMessage(taskId) {
    return nl2sqlClient.get(`/tasks/${taskId}/message`)
  }
}

export default nl2sqlApi
