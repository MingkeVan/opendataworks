import axios from 'axios'
import { ElMessage } from 'element-plus'

const request = axios.create({
  baseURL: '/api',
  timeout: 60000  // 增加到60秒，支持较长时间的操作（如工作流执行）
})

const shouldSkipErrorMessage = (config) => {
  return !!(config && (config.skipErrorMessage || config.silent))
}

// 请求拦截器
request.interceptors.request.use(
  config => {
    return config
  },
  error => {
    return Promise.reject(error)
  }
)

// 响应拦截器
request.interceptors.response.use(
  response => {
    const res = response.data
    // 后端统一返回格式 {code, data, message}
    if (res.code !== 200) {
      if (!shouldSkipErrorMessage(response.config)) {
        ElMessage.error(res.message || '请求失败')
      }
      return Promise.reject(new Error(res.message || '请求失败'))
    }
    return res.data
  },
  error => {
    const config = error?.config
    const responseMessage = error?.response?.data?.message
    const message = responseMessage || error.message || '网络错误'
    if (!shouldSkipErrorMessage(config)) {
      ElMessage.error(message)
    }
    if (responseMessage) {
      error.message = responseMessage
    }
    return Promise.reject(error)
  }
)

export default request
