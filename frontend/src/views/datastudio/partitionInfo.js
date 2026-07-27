// 分区信息的纯函数工具（无 Vue 依赖、无副作用，便于单测）。

/**
 * 客户端分页切片；页码越界时回落到最后一页，避免出现空白页。
 */
export function paginate(rows, currentPage, pageSize) {
  const list = Array.isArray(rows) ? rows : []
  const size = Math.max(1, Number(pageSize) || 1)
  const totalPages = Math.max(1, Math.ceil(list.length / size))
  const page = Math.min(Math.max(1, Number(currentPage) || 1), totalPages)
  const start = (page - 1) * size
  return list.slice(start, start + size)
}
