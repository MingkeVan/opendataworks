<template>
  <div class="cron-builder">
    <el-tabs v-model="activeTab" class="tabs">
      <el-tab-pane label="每N分钟" name="minute" />
      <el-tab-pane label="每N小时" name="hour" />
      <el-tab-pane label="每天" name="day" />
      <el-tab-pane label="每周" name="week" />
      <el-tab-pane label="每月" name="month" />
      <el-tab-pane label="高级" name="custom" />
    </el-tabs>

    <div class="content">
      <div v-if="activeTab === 'minute'" class="panel">
        <div class="row">
          <div class="label">秒</div>
          <el-input-number v-model="minuteState.second" :min="0" :max="59" :controls="false" />
          <div class="label">每</div>
          <el-input-number v-model="minuteState.interval" :min="1" :max="59" :controls="false" />
          <div class="label">分钟</div>
        </div>
      </div>

      <div v-else-if="activeTab === 'hour'" class="panel">
        <div class="row">
          <div class="label">秒</div>
          <el-input-number v-model="hourState.second" :min="0" :max="59" :controls="false" />
          <div class="label">分</div>
          <el-input-number v-model="hourState.minute" :min="0" :max="59" :controls="false" />
          <div class="label">每</div>
          <el-input-number v-model="hourState.interval" :min="1" :max="23" :controls="false" />
          <div class="label">小时</div>
        </div>
      </div>

      <div v-else-if="activeTab === 'day'" class="panel">
        <div class="row">
          <div class="label">每天</div>
          <el-input-number v-model="dayState.hour" :min="0" :max="23" :controls="false" />
          <div class="label">时</div>
          <el-input-number v-model="dayState.minute" :min="0" :max="59" :controls="false" />
          <div class="label">分</div>
          <el-input-number v-model="dayState.second" :min="0" :max="59" :controls="false" />
          <div class="label">秒</div>
        </div>
      </div>

      <div v-else-if="activeTab === 'week'" class="panel">
        <div class="row">
          <div class="label">时间</div>
          <el-input-number v-model="weekState.hour" :min="0" :max="23" :controls="false" />
          <div class="label">:</div>
          <el-input-number v-model="weekState.minute" :min="0" :max="59" :controls="false" />
          <div class="label">:</div>
          <el-input-number v-model="weekState.second" :min="0" :max="59" :controls="false" />
        </div>
        <div class="row wrap">
          <div class="label">星期</div>
          <el-checkbox-group v-model="weekState.days">
            <el-checkbox v-for="d in weekDays" :key="d.value" :label="d.value">
              {{ d.label }}
            </el-checkbox>
          </el-checkbox-group>
        </div>
        <div class="hint">Quartz: 周期调度需将“日”设为 ?，并在“周”里选择。</div>
      </div>

      <div v-else-if="activeTab === 'month'" class="panel">
        <div class="row">
          <div class="label">每月</div>
          <el-input-number v-model="monthState.dayOfMonth" :min="1" :max="31" :controls="false" />
          <div class="label">日</div>
        </div>
        <div class="row">
          <div class="label">时间</div>
          <el-input-number v-model="monthState.hour" :min="0" :max="23" :controls="false" />
          <div class="label">:</div>
          <el-input-number v-model="monthState.minute" :min="0" :max="59" :controls="false" />
          <div class="label">:</div>
          <el-input-number v-model="monthState.second" :min="0" :max="59" :controls="false" />
        </div>
        <div class="row wrap">
          <div class="label">月份</div>
          <el-select v-model="monthState.months" multiple clearable filterable placeholder="不选=每月" style="width: 320px;">
            <el-option v-for="m in 12" :key="m" :label="String(m)" :value="String(m)" />
          </el-select>
        </div>
      </div>

      <div v-else class="panel">
        <el-input
          v-model="customCron"
          placeholder="秒 分 时 日 月 周 年，例如：0 0 * * * ? *"
          clearable
        />
        <div class="hint">必须为 Quartz 7 段；日/周至少有一个为 ?。</div>
      </div>

      <div class="row result">
        <div class="label">生成</div>
        <el-input :model-value="generatedCron" readonly />
      </div>

      <div v-if="errorMessage" class="error">{{ errorMessage }}</div>
    </div>

    <div class="actions">
      <el-button size="small" @click="$emit('cancel')">关闭</el-button>
      <el-button size="small" type="primary" @click="applyCron">应用</el-button>
    </div>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'

const props = defineProps({
  modelValue: {
    type: String,
    default: ''
  }
})
const emit = defineEmits(['update:modelValue', 'applied', 'cancel'])

const activeTab = ref('minute')
const errorMessage = ref('')

const minuteState = reactive({ second: 0, interval: 1 })
const hourState = reactive({ second: 0, minute: 0, interval: 1 })
const dayState = reactive({ second: 0, minute: 0, hour: 0 })
const weekState = reactive({ second: 0, minute: 0, hour: 0, days: ['MON'] })
const monthState = reactive({ second: 0, minute: 0, hour: 0, dayOfMonth: 1, months: [] })

const customCron = ref(props.modelValue || '')

watch(
  () => props.modelValue,
  (val) => {
    if (activeTab.value === 'custom') {
      customCron.value = val || ''
    }
  }
)

const weekDays = [
  { label: '一', value: 'MON' },
  { label: '二', value: 'TUE' },
  { label: '三', value: 'WED' },
  { label: '四', value: 'THU' },
  { label: '五', value: 'FRI' },
  { label: '六', value: 'SAT' },
  { label: '日', value: 'SUN' }
]

const generatedCron = computed(() => {
  if (activeTab.value === 'minute') {
    const minuteField = minuteState.interval === 1 ? '*' : `0/${minuteState.interval}`
    return `${minuteState.second} ${minuteField} * * * ? *`
  }
  if (activeTab.value === 'hour') {
    const hourField = hourState.interval === 1 ? '*' : `0/${hourState.interval}`
    return `${hourState.second} ${hourState.minute} ${hourField} * * ? *`
  }
  if (activeTab.value === 'day') {
    return `${dayState.second} ${dayState.minute} ${dayState.hour} * * ? *`
  }
  if (activeTab.value === 'week') {
    const days = Array.isArray(weekState.days) && weekState.days.length ? weekState.days.join(',') : 'MON'
    return `${weekState.second} ${weekState.minute} ${weekState.hour} ? * ${days} *`
  }
  if (activeTab.value === 'month') {
    const months = Array.isArray(monthState.months) && monthState.months.length ? monthState.months.join(',') : '*'
    return `${monthState.second} ${monthState.minute} ${monthState.hour} ${monthState.dayOfMonth} ${months} ? *`
  }
  return String(customCron.value || '').trim()
})

const validateCron = (cron) => {
  const parts = String(cron || '')
    .trim()
    .split(/\s+/)
    .filter(Boolean)
  if (parts.length !== 7) {
    return 'Cron 需为 Quartz 7 段：秒 分 时 日 月 周 年'
  }
  const day = parts[3]
  const week = parts[5]
  if (day !== '?' && week !== '?') {
    return 'Quartz Cron 要求：日/周至少有一个为 ?'
  }
  return ''
}

const applyCron = () => {
  const cron = generatedCron.value
  const err = validateCron(cron)
  errorMessage.value = err
  if (err) {
    return
  }
  emit('update:modelValue', cron)
  emit('applied', cron)
}
</script>

<style scoped>
.cron-builder {
  width: 520px;
}

.tabs :deep(.el-tabs__header) {
  margin: 0 0 8px;
}

.content {
  padding: 6px 2px 10px;
}

.panel {
  margin-bottom: 10px;
}

.row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.row.wrap {
  align-items: flex-start;
}

.row.result {
  margin-top: 4px;
}

.label {
  color: #606266;
  font-size: 12px;
  white-space: nowrap;
}

.hint {
  color: #909399;
  font-size: 12px;
  margin-top: -4px;
}

.error {
  color: #f56c6c;
  font-size: 12px;
  margin-top: -2px;
}

.actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding-top: 6px;
  border-top: 1px solid #ebeef5;
}
</style>
