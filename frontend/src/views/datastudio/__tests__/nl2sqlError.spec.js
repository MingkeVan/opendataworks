import { describe, it, expect } from 'vitest'
import { nl2sqlErrorMessage } from '@/api/nl2sql'

describe('nl2sqlErrorMessage', () => {
  it('优先取 FastAPI 的 detail 字符串', () => {
    const error = {
      message: 'Request failed with status code 400',
      response: { data: { detail: 'agent not found' } }
    }
    expect(nl2sqlErrorMessage(error)).toBe('agent not found')
  })

  it('支持 detail 为对象的情况', () => {
    const error = {
      message: 'Request failed with status code 401',
      response: { data: { detail: { code: 'WIDGET_LOGIN_REQUIRED', message: '请先登录后使用智能问数' } } }
    }
    expect(nl2sqlErrorMessage(error)).toBe('请先登录后使用智能问数')
  })

  it('无 detail 时回落到 axios 消息，再回落到兜底文案', () => {
    expect(nl2sqlErrorMessage({ message: 'Network Error' })).toBe('Network Error')
    expect(nl2sqlErrorMessage({}, '智能生成元数据失败')).toBe('智能生成元数据失败')
    expect(nl2sqlErrorMessage(null, '兜底')).toBe('兜底')
  })

  it('忽略空白 detail', () => {
    const error = { message: 'boom', response: { data: { detail: '   ' } } }
    expect(nl2sqlErrorMessage(error)).toBe('boom')
  })
})
