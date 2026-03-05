/**
 * NL2SQL 会话状态管理 composable
 */
import { computed, ref } from 'vue'

export function useNl2SqlSession(nl2sqlApi) {
    if (!nl2sqlApi) {
        throw new Error('nl2sqlApi client is required')
    }

    const loading = ref(false)
    const sessions = ref([])
    const activeSessionId = ref('')
    const generating = ref(false)
    const hydratedSessionIds = new Set()

    const activeSession = computed(() => {
        return sessions.value.find(s => s.session_id === activeSessionId.value) || null
    })

    const activeMessages = computed(() => {
        return activeSession.value?.messages || []
    })

    const findSession = (sessionId) => {
        return sessions.value.find(s => s.session_id === sessionId) || null
    }

    const sortSessions = () => {
        sessions.value.sort((a, b) => {
            const ta = new Date(a.updated_at || a.created_at || 0).getTime()
            const tb = new Date(b.updated_at || b.created_at || 0).getTime()
            return tb - ta
        })
    }

    const hydrateSessionMessages = async (sessionId, force = false) => {
        if (!sessionId) return null
        if (!force && hydratedSessionIds.has(sessionId)) {
            return findSession(sessionId)
        }

        const detail = await nl2sqlApi.getSession(sessionId)
        const target = findSession(sessionId)
        if (target) {
            target.messages = Array.isArray(detail?.messages) ? detail.messages : []
            target.title = detail?.title || target.title
            target.created_at = detail?.created_at || target.created_at
            target.updated_at = detail?.updated_at || target.updated_at
        } else if (detail) {
            sessions.value.unshift({
                ...detail,
                messages: Array.isArray(detail.messages) ? detail.messages : []
            })
        }
        hydratedSessionIds.add(sessionId)
        return findSession(sessionId)
    }

    const loadSessions = async () => {
        loading.value = true
        try {
            const list = await nl2sqlApi.listSessions()
            sessions.value = (Array.isArray(list) ? list : []).map((item) => ({
                ...item,
                messages: Array.isArray(item.messages) ? item.messages : []
            }))
            sortSessions()
            if (sessions.value.length && !activeSessionId.value) {
                activeSessionId.value = sessions.value[0].session_id
            }
            if (activeSessionId.value) {
                await hydrateSessionMessages(activeSessionId.value)
            }
        } catch (error) {
            console.error('Failed to load sessions:', error)
        } finally {
            loading.value = false
        }
    }

    const createSession = async (title = '新会话') => {
        try {
            const session = await nl2sqlApi.createSession(title)
            const normalized = {
                ...session,
                messages: Array.isArray(session?.messages) ? session.messages : []
            }
            sessions.value.unshift(normalized)
            hydratedSessionIds.add(normalized.session_id)
            activeSessionId.value = session.session_id
            return normalized
        } catch (error) {
            console.error('Failed to create session:', error)
            throw error
        }
    }

    const selectSession = async (sessionId) => {
        activeSessionId.value = sessionId
        try {
            await hydrateSessionMessages(sessionId)
        } catch (error) {
            console.error('Failed to hydrate session:', error)
        }
    }

    const deleteSession = async (sessionId) => {
        try {
            await nl2sqlApi.deleteSession(sessionId)
            const index = sessions.value.findIndex(s => s.session_id === sessionId)
            if (index >= 0) {
                sessions.value.splice(index, 1)
            }
            if (activeSessionId.value === sessionId) {
                activeSessionId.value = sessions.value.length ? sessions.value[0].session_id : ''
                if (activeSessionId.value) {
                    await hydrateSessionMessages(activeSessionId.value)
                }
            }
            hydratedSessionIds.delete(sessionId)
        } catch (error) {
            console.error('Failed to delete session:', error)
        }
    }

    const buildFallbackAssistantMessage = (error) => {
        return {
            role: 'assistant',
            content: '',
            explanation: `请求失败：${error?.message || '未知错误'}`,
            sql: '',
            thinking_steps: [],
            matched_tables: [],
            matched_rules: [],
            confidence: 0,
            execution: null,
            timestamp: new Date().toISOString()
        }
    }

    const sendMessage = async (content, database = null, model = null) => {
        if (!content?.trim()) return null
        if (!activeSessionId.value) {
            await createSession(content.slice(0, 20))
        }
        await hydrateSessionMessages(activeSessionId.value)

        const session = findSession(activeSessionId.value)
        if (!session) return null
        if (!Array.isArray(session.messages)) {
            session.messages = []
        }

        const userMessage = {
            role: 'user',
            content,
            timestamp: new Date().toISOString()
        }
        session.messages.push(userMessage)

        generating.value = true
        try {
            const response = await nl2sqlApi.sendMessage(activeSessionId.value, {
                question: content,
                database,
                model
            })

            const assistant = response || {
                role: 'assistant',
                content: '',
                explanation: '已完成分析，但未生成可展示结果，请换个问法重试。',
                sql: '',
                thinking_steps: [],
                matched_tables: [],
                matched_rules: [],
                confidence: 0,
                execution: null,
                timestamp: new Date().toISOString()
            }

            session.messages.push(assistant)
            session.updated_at = new Date().toISOString()
            if (session.title === '新会话') {
                session.title = content.length > 30 ? content.slice(0, 30) + '...' : content
            }
            sortSessions()

            return assistant
        } catch (error) {
            session.messages.push(buildFallbackAssistantMessage(error))
            throw error
        } finally {
            generating.value = false
        }
    }

    const executeSql = async (sql, database = null) => {
        try {
            return await nl2sqlApi.executeSql({ sql, database })
        } catch (error) {
            console.error('Failed to execute SQL:', error)
            throw error
        }
    }

    return {
        loading,
        sessions,
        activeSessionId,
        activeSession,
        activeMessages,
        generating,
        loadSessions,
        createSession,
        selectSession,
        deleteSession,
        sendMessage,
        executeSql
    }
}
