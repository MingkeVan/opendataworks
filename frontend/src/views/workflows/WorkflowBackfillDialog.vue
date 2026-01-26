<template>
  <el-dialog
    v-model="visible"
    title="工作流补数"
    width="640px"
    :close-on-click-modal="false"
  >
    <el-alert
      type="info"
      :closable="false"
      style="margin-bottom: 12px"
      title="参数兼容 DolphinScheduler 补数据（COMPLEMENT_DATA）"
      description="时间格式：YYYY-MM-DD HH:mm:ss；范围模式会提交 complementStartDate/complementEndDate；列表模式会提交 complementScheduleDateList。"
    />

    <el-form :model="form" label-width="120px">
      <el-form-item label="工作流">
        <span>{{ workflow?.workflowName || workflow?.name || '-' }}</span>
        <el-tag v-if="workflow?.workflowCode" size="small" style="margin-left: 8px">
          code: {{ workflow.workflowCode }}
        </el-tag>
      </el-form-item>

      <el-form-item label="补数方式">
        <el-radio-group v-model="form.mode">
          <el-radio value="range">时间范围</el-radio>
          <el-radio value="list">时间列表</el-radio>
        </el-radio-group>
      </el-form-item>

      <el-form-item v-if="form.mode === 'range'" label="时间范围" required>
        <el-date-picker
          v-model="form.dateRange"
          type="datetimerange"
          range-separator="-"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          value-format="YYYY-MM-DD HH:mm:ss"
          style="width: 420px"
        />
      </el-form-item>

      <el-form-item v-else label="时间列表" required>
        <el-input
          v-model="form.scheduleDateList"
          type="textarea"
          :rows="3"
          placeholder="例如：2022-01-01 00:00:00,2022-01-02 00:00:00"
        />
      </el-form-item>

      <el-form-item label="运行模式">
        <el-select v-model="form.runMode" style="width: 220px">
          <el-option label="串行（RUN_MODE_SERIAL）" value="RUN_MODE_SERIAL" />
          <el-option label="并行（RUN_MODE_PARALLEL）" value="RUN_MODE_PARALLEL" />
        </el-select>
      </el-form-item>

      <el-form-item v-if="form.runMode === 'RUN_MODE_PARALLEL'" label="并行度">
        <el-input-number
          v-model="form.expectedParallelismNumber"
          :min="1"
          :max="1000"
          controls-position="right"
        />
      </el-form-item>

      <el-form-item label="失败策略">
        <el-select v-model="form.failureStrategy" style="width: 220px">
          <el-option label="继续（CONTINUE）" value="CONTINUE" />
          <el-option label="终止（END）" value="END" />
        </el-select>
      </el-form-item>

      <el-form-item label="依赖模式">
        <el-select v-model="form.complementDependentMode" style="width: 220px">
          <el-option label="关闭（OFF_MODE）" value="OFF_MODE" />
          <el-option label="全依赖（ALL_DEPENDENT）" value="ALL_DEPENDENT" />
        </el-select>
      </el-form-item>

      <el-form-item label="全层级依赖">
        <el-switch v-model="form.allLevelDependent" />
      </el-form-item>

      <el-form-item label="执行顺序">
        <el-radio-group v-model="form.executionOrder">
          <el-radio value="DESC_ORDER">倒序</el-radio>
          <el-radio value="ASC_ORDER">正序</el-radio>
        </el-radio-group>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">
        确定补数
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { workflowApi } from '@/api/workflow'

const props = defineProps({
  modelValue: {
    type: Boolean,
    default: false
  },
  workflow: {
    type: Object,
    default: null
  }
})

const emit = defineEmits(['update:modelValue', 'submitted'])

const visible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val)
})

const submitting = ref(false)

const defaultForm = () => ({
  mode: 'range',
  dateRange: [],
  scheduleDateList: '',
  runMode: 'RUN_MODE_SERIAL',
  expectedParallelismNumber: 8,
  complementDependentMode: 'OFF_MODE',
  allLevelDependent: false,
  executionOrder: 'DESC_ORDER',
  failureStrategy: 'CONTINUE'
})

const form = reactive(defaultForm())

watch(
  () => visible.value,
  (open) => {
    if (!open) return
    Object.assign(form, defaultForm())
  }
)

const getErrorMessage = (error) => {
  return error?.response?.data?.message || error?.message || '操作失败，请稍后重试'
}

const validateForm = () => {
  if (!props.workflow?.id) {
    ElMessage.warning('未找到工作流信息')
    return false
  }
  if (props.workflow?.status !== 'online') {
    ElMessage.warning('工作流未上线，请先上线后再补数')
    return false
  }
  if (form.mode === 'list') {
    if (!form.scheduleDateList.trim()) {
      ElMessage.warning('请填写时间列表')
      return false
    }
  } else {
    if (!form.dateRange || form.dateRange.length !== 2) {
      ElMessage.warning('请选择补数时间范围')
      return false
    }
  }
  if (form.runMode === 'RUN_MODE_PARALLEL' && (!form.expectedParallelismNumber || form.expectedParallelismNumber < 1)) {
    ElMessage.warning('并行度必须大于 0')
    return false
  }
  return true
}

const handleSubmit = async () => {
  if (!validateForm()) return

  submitting.value = true
  try {
    const payload = {
      mode: form.mode,
      startTime: form.mode === 'range' ? form.dateRange[0] : null,
      endTime: form.mode === 'range' ? form.dateRange[1] : null,
      scheduleDateList: form.mode === 'list' ? form.scheduleDateList.trim() : null,
      runMode: form.runMode,
      expectedParallelismNumber: form.runMode === 'RUN_MODE_PARALLEL' ? form.expectedParallelismNumber : null,
      complementDependentMode: form.complementDependentMode,
      allLevelDependent: form.allLevelDependent,
      executionOrder: form.executionOrder,
      failureStrategy: form.failureStrategy
    }

    const triggerId = await workflowApi.backfill(props.workflow.id, payload)
    ElMessage.success(`补数已提交，触发码：${triggerId || '-'}`)
    emit('submitted', { workflowId: props.workflow.id, triggerId })
    visible.value = false
  } catch (error) {
    console.error('补数失败', error)
    ElMessage.error(getErrorMessage(error))
  } finally {
    submitting.value = false
  }
}
</script>
