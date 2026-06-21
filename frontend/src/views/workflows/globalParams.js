// WorkflowDetail 全局参数行的纯转换工具（W2）。
// 纯函数、无 Vue 依赖、无副作用，便于单测。从 WorkflowDetail.vue 逐字抽出，行为不变。

export const cloneGlobalParamCore = (param = {}) => {
  return {
    prop: String(param?.prop ?? '').trim(),
    direct: param?.direct || 'IN',
    type: param?.type || 'VARCHAR',
    value: param?.value ?? ''
  }
}

export const createGlobalParamRow = (param = {}, options = {}) => {
  return {
    ...cloneGlobalParamCore(param),
    __editing: Boolean(options.editing),
    __isNew: Boolean(options.isNew),
    __backup: options.backup || null
  }
}

export const normalizeGlobalParams = (params) => {
  if (!Array.isArray(params)) {
    return []
  }
  return params.map(item => createGlobalParamRow(item))
}

export const isGlobalParamEmpty = (value) => {
  return value === null || value === undefined || value === ''
}

export const formatGlobalParamDisplay = (value) => {
  return isGlobalParamEmpty(value) ? '-' : String(value)
}
