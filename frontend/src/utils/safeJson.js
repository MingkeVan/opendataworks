/**
 * 安全解析 JSON，解析失败时返回回退值而非抛出异常。
 *
 * 用于 localStorage、接口字符串字段、日志等可能含非法 JSON 的场景，
 * 避免单点解析失败导致页面崩溃。新代码应优先使用本工具，统一容错写法。
 *
 * @template T
 * @param {unknown} raw 待解析内容；非字符串将直接返回回退值
 * @param {T} [fallback=null] 解析失败或入参非字符串时的回退值
 * @returns {T|any} 解析结果或回退值
 */
export function safeJsonParse(raw, fallback = null) {
  if (typeof raw !== 'string' || raw.trim() === '') {
    return fallback
  }
  try {
    return JSON.parse(raw)
  } catch {
    return fallback
  }
}

export default safeJsonParse
