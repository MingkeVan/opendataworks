// 执行监控页面级的查询参数构造。
// 行级展示映射见 @/components/workflowInstanceDisplay。
export const buildWorkflowExecutionParams = ({
  workflowId,
  pageNum,
  pageSize,
  status,
  refresh = false
}) => {
  const params = {
    pageNum,
    pageSize,
    refresh
  }
  if (workflowId) params.workflowId = Number(workflowId)
  if (status) params.status = status
  return params
}
