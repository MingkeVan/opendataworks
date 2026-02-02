<template>
  <div class="persistent-tabs">
    <el-tabs
      v-bind="forwardedAttrs"
      v-model="localValue"
      :type="type"
      :closable="closable"
      :addable="addable"
      :editable="editable"
      @tab-remove="(name) => emit('tab-remove', name)"
      @tab-add="() => emit('tab-add')"
      @edit="handleEdit"
    >
      <el-tab-pane v-for="tab in tabs" :key="String(tab[keyField])" :name="String(tab[keyField])">
        <template #label>
          <div class="persistent-tabs__label" @contextmenu.prevent="openMenu($event, tab)">
            <slot name="label" :tab="tab">
              <span>{{ tab?.label ?? tab?.title ?? tab?.name ?? String(tab?.[props.keyField] ?? '') }}</span>
            </slot>
          </div>
        </template>

        <slot :tab="tab" />
      </el-tab-pane>
    </el-tabs>

    <Teleport to="body">
      <div
        v-if="menu.visible"
        ref="menuRef"
        class="persistent-tabs__menu"
        :style="{ left: `${menu.x}px`, top: `${menu.y}px` }"
        @contextmenu.prevent
      >
        <button class="persistent-tabs__menu-item" :disabled="!menu.canCloseLeft" @click="emitAndClose('close-left')">
          关闭左侧
        </button>
        <button class="persistent-tabs__menu-item" :disabled="!menu.canCloseRight" @click="emitAndClose('close-right')">
          关闭右侧
        </button>
        <button class="persistent-tabs__menu-item" :disabled="!menu.canCloseAll" @click="emitAndClose('close-all')">
          关闭所有
        </button>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, reactive, ref, useAttrs } from 'vue'

defineOptions({ inheritAttrs: false })

const attrs = useAttrs()

const props = defineProps({
  modelValue: {
    type: String,
    default: ''
  },
  tabs: {
    type: Array,
    default: () => []
  },
  keyField: {
    type: String,
    default: 'id'
  },
  type: {
    type: String,
    default: 'card'
  },
  closable: {
    type: Boolean,
    default: true
  },
  addable: {
    type: Boolean,
    default: false
  },
  editable: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits([
  'update:modelValue',
  'tab-remove',
  'tab-add',
  'close-left',
  'close-right',
  'close-all'
])

const localValue = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value)
})

const forwardedAttrs = attrs

const menuRef = ref(null)

const menu = reactive({
  visible: false,
  x: 0,
  y: 0,
  tabKey: '',
  canCloseLeft: false,
  canCloseRight: false,
  canCloseAll: false
})

let outsideHandler = null

const closeMenu = () => {
  menu.visible = false
  if (outsideHandler) {
    window.removeEventListener('mousedown', outsideHandler, true)
    window.removeEventListener('scroll', outsideHandler, true)
    window.removeEventListener('resize', outsideHandler, true)
    outsideHandler = null
  }
}

const openMenu = async (event, tab) => {
  closeMenu()
  const key = String(tab?.[props.keyField] ?? '')
  if (!key) return

  const idx = props.tabs.findIndex((item) => String(item?.[props.keyField]) === key)
  const total = props.tabs.length

  menu.tabKey = key
  menu.canCloseLeft = idx > 0
  menu.canCloseRight = idx !== -1 && idx < total - 1
  menu.canCloseAll = total > 0

  menu.x = event.clientX
  menu.y = event.clientY
  menu.visible = true

  await nextTick()
  const menuEl = menuRef.value
  if (menuEl) {
    const rect = menuEl.getBoundingClientRect()
    const maxX = window.innerWidth - rect.width - 8
    const maxY = window.innerHeight - rect.height - 8
    menu.x = Math.max(8, Math.min(menu.x, maxX))
    menu.y = Math.max(8, Math.min(menu.y, maxY))
  }

  outsideHandler = (e) => {
    const target = e?.target
    if (target && target.closest?.('.persistent-tabs__menu')) return
    closeMenu()
  }
  window.addEventListener('mousedown', outsideHandler, true)
  window.addEventListener('scroll', outsideHandler, true)
  window.addEventListener('resize', outsideHandler, true)
}

const emitAndClose = (action) => {
  const key = menu.tabKey
  closeMenu()
  emit(action, key)
}

const handleEdit = (targetName, action) => {
  if (action === 'add') {
    if (!props.addable) emit('tab-add')
    return
  }
  if (action === 'remove') {
    if (!props.closable) emit('tab-remove', targetName)
  }
}

onBeforeUnmount(() => closeMenu())
</script>

<style scoped lang="scss">
.persistent-tabs {
  position: relative;
}

.persistent-tabs__label {
  height: 100%;
  display: flex;
  align-items: center;
}

.persistent-tabs__menu {
  position: fixed;
  z-index: 3000;
  display: flex;
  flex-direction: column;
  padding: 6px;
  min-width: 130px;
  background: #ffffff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.14);
}

.persistent-tabs__menu-item {
  padding: 8px 10px;
  text-align: left;
  border: 0;
  background: transparent;
  border-radius: 6px;
  cursor: pointer;
  color: #1f2f3d;
  font-size: 13px;
}

.persistent-tabs__menu-item:hover:not(:disabled) {
  background: #f5f7fa;
}

.persistent-tabs__menu-item:disabled {
  cursor: not-allowed;
  color: #c0c4cc;
}
</style>
