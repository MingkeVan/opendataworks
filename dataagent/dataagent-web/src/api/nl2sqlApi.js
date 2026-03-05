/**
 * NL2SQL Python 服务 API 客户端
 * 前端直接对接 Python 后端
 */
import axios from 'axios'

const unwrapResponse = (response) => {
    return response?.data
}

export function createNl2SqlApiClient(options = {}) {
    const baseURL = options.baseURL || 'http://localhost:8900'
    const timeout = options.timeout || 120000

    const request = axios.create({
        baseURL,
        timeout
    })

    request.interceptors.response.use(
        (response) => unwrapResponse(response),
        (error) => {
            const responseMessage = error?.response?.data?.detail
            error.message = responseMessage || error.message || '网络错误'
            return Promise.reject(error)
        }
    )

    return {
        // ---- 核心 NL2SQL ----

        /** 生成 SQL */
        generateSql(data) {
            return request.post('/api/v1/nl2sql/generate', data)
        },

        /** 执行 SQL */
        executeSql(data) {
            return request.post('/api/v1/nl2sql/execute', data)
        },

        /** 一站式: 问题 → SQL → 结果 */
        ask(data) {
            return request.post('/api/v1/nl2sql/ask', data)
        },

        // ---- 会话管理 ----

        createSession(title = '新会话') {
            return request.post('/api/v1/nl2sql/sessions', null, { params: { title } })
        },

        listSessions() {
            return request.get('/api/v1/nl2sql/sessions')
        },

        getSession(sessionId) {
            return request.get(`/api/v1/nl2sql/sessions/${sessionId}`)
        },

        deleteSession(sessionId) {
            return request.delete(`/api/v1/nl2sql/sessions/${sessionId}`)
        },

        sendMessage(sessionId, data) {
            return request.post(`/api/v1/nl2sql/sessions/${sessionId}/messages`, data)
        },

        // ---- 语义层 ----

        reloadSemantic(database) {
            return request.post('/api/v1/nl2sql/reload', null, { params: { database } })
        },

        listSchema(database) {
            return request.get('/api/v1/nl2sql/schema', { params: { database } })
        },

        syncSkills(database) {
            return request.post('/api/v1/nl2sql/skills/sync', null, { params: { database } })
        },

        listTools() {
            return request.get('/api/v1/nl2sql/tools')
        },

        invokeTool(data) {
            return request.post('/api/v1/nl2sql/tools/invoke', data)
        },

        // ---- 设置 ----

        getSettings() {
            return request.get('/api/v1/nl2sql/settings')
        },

        updateSettings(data) {
            return request.put('/api/v1/nl2sql/settings', data)
        },

        // ---- 健康检查 ----

        health() {
            return request.get('/api/v1/nl2sql/health')
        }
    }
}
