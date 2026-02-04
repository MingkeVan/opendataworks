<template>
  <div :class="['tab-demo', `tab-demo--${variant}`]">
    <div class="tab-demo__bar">
      <div class="tab-demo__scroller" role="tablist" aria-label="Query tabs">
        <button
          v-for="tab in tabs"
          :key="tab.id"
          type="button"
          :class="['tab-demo__tab', { 'is-active': tab.id === activeTabId }]"
          role="tab"
          :aria-selected="tab.id === activeTabId"
          @click="activeTabId = tab.id"
        >
          <span class="tab-demo__tab-main">
            <span class="tab-demo__title" :title="tab.title">{{ tab.title }}</span>
            <span v-if="tab.subtitle" class="tab-demo__sub" :title="tab.subtitle">{{ tab.subtitle }}</span>
          </span>

          <span class="tab-demo__close" title="关闭" @click.stop="closeTab(tab.id)">
            <el-icon><Close /></el-icon>
          </span>
        </button>

        <button type="button" class="tab-demo__add" title="新建查询" @click="addTab">
          <el-icon><Plus /></el-icon>
        </button>

        <div v-if="!tabs.length" class="tab-demo__empty">
          <span>暂无 Tab</span>
          <button type="button" class="tab-demo__empty-add" @click="addTab" title="新建查询">
            <el-icon><Plus /></el-icon>
          </button>
        </div>
      </div>
    </div>

    <div class="tab-demo__content">
      <div class="tab-demo__content-top">
        <div class="tab-demo__content-title">
          当前：<span class="tab-demo__content-strong">{{ activeTab?.title || '—' }}</span>
        </div>
        <div class="tab-demo__content-meta">
          <el-tag v-if="activeTab?.subtitle" size="small" type="info">{{ activeTab.subtitle }}</el-tag>
        </div>
      </div>

      <div class="tab-demo__editor">
        <div class="tab-demo__editor-line comment">-- SQL Editor Placeholder</div>
        <div class="tab-demo__editor-line">SELECT * FROM some_table</div>
        <div class="tab-demo__editor-line">LIMIT 100;</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'

defineProps({
  variant: {
    type: String,
    default: 'pills'
  }
})

let counter = 4

const tabs = ref([
  { id: '1', title: '查询 1', subtitle: 'Doris · dwd' },
  { id: '2', title: '订单明细', subtitle: 'MySQL · ecommerce' },
  { id: '3', title: '用户画像', subtitle: 'Doris · ads' }
])

const activeTabId = ref(tabs.value[0]?.id ?? '')

const activeTab = computed(() => tabs.value.find((tab) => tab.id === activeTabId.value))

const addTab = () => {
  const id = String(counter++)
  tabs.value = [
    ...tabs.value,
    {
      id,
      title: `查询 ${id}`,
      subtitle: 'Doris · dwd'
    }
  ]
  activeTabId.value = id
}

const closeTab = (id) => {
  const idx = tabs.value.findIndex((tab) => tab.id === id)
  if (idx === -1) return

  const next = tabs.value.slice()
  next.splice(idx, 1)
  tabs.value = next

  if (activeTabId.value !== id) return

  const fallback = next[idx] || next[idx - 1] || next[0]
  activeTabId.value = fallback?.id ?? ''
}
</script>

<style scoped lang="scss">
.tab-demo {
  --accent: #4f46e5;
  --text: #303133;
  --muted: #909399;
  --border: #dcdfe6;
  --surface: #ffffff;
  --hover: #f5f7fa;

  display: flex;
  flex-direction: column;
  gap: 10px;
}

.tab-demo__bar {
  display: flex;
  align-items: stretch;
  gap: 6px;
  padding: 6px 8px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--surface);
}

.tab-demo__scroller {
  flex: 1;
  display: flex;
  gap: 6px;
  overflow-x: auto;
  overflow-y: hidden;
  padding: 1px;
  scrollbar-width: none;
}

.tab-demo__scroller::-webkit-scrollbar {
  display: none;
}

.tab-demo__tab {
  flex: 0 0 auto;
  max-width: 220px;
  border: 0;
  background: transparent;
  border-bottom: 2px solid transparent;
  border-radius: 8px;
  padding: 6px 10px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  color: var(--text);
  user-select: none;
  transition: background 0.18s ease, color 0.18s ease;
}

.tab-demo__tab:hover {
  background: var(--hover);
}

.tab-demo__tab.is-active {
  color: var(--accent);
  border-bottom-color: var(--accent);
}

.tab-demo__tab-main {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.tab-demo__title {
  font-size: 13px;
  font-weight: 650;
  line-height: 1.1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tab-demo__sub {
  font-size: 11px;
  line-height: 1.1;
  color: var(--muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tab-demo__close {
  width: 20px;
  height: 20px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  opacity: 0;
  color: var(--muted);
  transition: opacity 0.18s ease, background 0.18s ease, color 0.18s ease;
}

.tab-demo__tab:hover .tab-demo__close,
.tab-demo__tab.is-active .tab-demo__close {
  opacity: 1;
}

.tab-demo__close:hover {
  background: rgba(0, 0, 0, 0.06);
  color: var(--text);
}

.tab-demo__add {
  flex: 0 0 auto;
  width: 32px;
  min-width: 32px;
  height: 32px;
  border-radius: 8px;
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  display: inline-flex;
  align-items: center;
  cursor: pointer;
  color: var(--muted);
  justify-content: center;
  transition: background 0.18s ease, color 0.18s ease;
}

.tab-demo__add:hover {
  background: var(--hover);
  color: var(--accent);
}

.tab-demo__empty {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--muted);
  padding: 6px 8px;
}

.tab-demo__empty-add {
  width: 28px;
  height: 28px;
  border: 1px solid var(--border);
  background: #fff;
  color: var(--text);
  border-radius: 8px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: background 0.18s ease, border-color 0.18s ease;
}

.tab-demo__empty-add:hover {
  background: var(--hover);
  border-color: #c0c4cc;
}

.tab-demo__content {
  border: 1px solid var(--border);
  border-radius: 12px;
  background: #fff;
  padding: 10px;
}

.tab-demo__content-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.tab-demo__content-title {
  font-size: 13px;
  color: #1f2f3d;
}

.tab-demo__content-strong {
  font-weight: 700;
  color: #0f172a;
}

.tab-demo__editor {
  margin-top: 10px;
  border-radius: 10px;
  border: 1px dashed #dbe2ef;
  background: linear-gradient(180deg, #f8fafc 0%, #ffffff 100%);
  padding: 10px 12px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace;
  font-size: 12px;
  color: #0f172a;
}

.tab-demo__editor-line {
  line-height: 1.7;
}

.tab-demo__editor-line.comment {
  color: #64748b;
}

/* Variant: Line (default) */
.tab-demo--line {
  .tab-demo__bar {
    border-radius: 0;
    border: 0;
    border-bottom: 1px solid var(--border);
    padding: 0 4px 6px;
    background: transparent;
  }

  .tab-demo__tab,
  .tab-demo__add {
    border-radius: 6px;
  }
}

/* Variant: Navicat */
.tab-demo--navicat {
  .tab-demo__bar {
    background: linear-gradient(180deg, #f6f7f9 0%, #eef0f3 100%);
    border: 1px solid var(--border);
    border-bottom: 0;
    border-radius: 10px 10px 0 0;
    padding: 6px 8px 0;
    gap: 4px;
  }

  .tab-demo__tab,
  .tab-demo__add {
    background: #e9ecf1;
    border: 1px solid var(--border);
    border-bottom: 0;
    border-radius: 8px 8px 0 0;
    margin-bottom: -1px;
    color: #303133;
  }

  .tab-demo__tab:hover,
  .tab-demo__add:hover {
    background: #f5f7fa;
  }

  .tab-demo__tab.is-active {
    background: #fff;
    border-bottom-color: #fff;
    color: #303133;
  }

  .tab-demo__sub {
    display: none;
  }

  .tab-demo__content {
    border: 1px solid var(--border);
    border-top: 0;
    border-radius: 0 0 10px 10px;
    margin-top: -1px;
  }
}

/* Variant: Minimal */
.tab-demo--minimal {
  .tab-demo__bar {
    border-radius: 0;
    border: 0;
    border-bottom: 1px solid var(--border);
    padding: 0 2px 6px;
    background: transparent;
  }

  .tab-demo__tab:hover,
  .tab-demo__add:hover {
    background: transparent;
  }
}

/* Variant: Card */
.tab-demo--card {
  .tab-demo__bar {
    background: #f5f7fa;
    border-radius: 10px 10px 0 0;
    border-bottom-left-radius: 0;
    border-bottom-right-radius: 0;
    padding: 6px 8px 0;
  }

  .tab-demo__tab,
  .tab-demo__add {
    border: 1px solid var(--border);
    border-bottom: 0;
    background: #f5f7fa;
    border-radius: 8px 8px 0 0;
    margin-bottom: -1px;
    color: var(--text);
  }

  .tab-demo__tab:hover,
  .tab-demo__add:hover {
    background: #eef2ff;
  }

  .tab-demo__tab.is-active {
    background: #fff;
    color: var(--text);
    border-bottom-color: #fff;
  }
}

/* Variant: Flat Card */
.tab-demo--flat-card {
  .tab-demo__bar {
    border-radius: 0;
    border: 0;
    border-bottom: 1px solid var(--border);
    padding: 0 4px 0;
    background: transparent;
  }

  .tab-demo__tab,
  .tab-demo__add {
    border: 1px solid transparent;
    border-bottom: 0;
    border-radius: 8px 8px 0 0;
    margin-bottom: -1px;
  }

  .tab-demo__tab:hover,
  .tab-demo__add:hover {
    border-color: var(--border);
    background: var(--hover);
  }

  .tab-demo__tab.is-active {
    border-color: var(--border);
    background: #fff;
    color: var(--text);
  }
}

/* Variant: Boxed */
.tab-demo--boxed {
  .tab-demo__bar {
    background: transparent;
    border-radius: 0;
    border: 0;
    padding: 0;
  }

  .tab-demo__tab,
  .tab-demo__add {
    border: 1px solid var(--border);
    background: #fff;
    border-radius: 8px;
    border-bottom: 1px solid var(--border);
  }

  .tab-demo__tab.is-active {
    border-color: var(--accent);
    color: var(--accent);
  }
}

/* Variant: Pill */
.tab-demo--pill {
  .tab-demo__bar {
    background: transparent;
    border-radius: 0;
    border: 0;
    padding: 0;
  }

  .tab-demo__tab,
  .tab-demo__add {
    border-radius: 999px;
    background: #f5f7fa;
    border: 1px solid transparent;
    border-bottom: 1px solid transparent;
  }

  .tab-demo__tab:hover,
  .tab-demo__add:hover {
    border-color: var(--border);
  }

  .tab-demo__tab.is-active {
    background: #fff;
    border-color: var(--border);
    color: var(--text);
  }
}

/* Variant: Separator */
.tab-demo--separator {
  .tab-demo__bar {
    border-radius: 0;
    border: 0;
    border-bottom: 1px solid var(--border);
    padding: 0 2px 6px;
    background: transparent;
  }

  .tab-demo__tab {
    border-radius: 6px;
    padding: 6px 12px;
    position: relative;
  }

  .tab-demo__tab::after {
    content: '';
    position: absolute;
    right: -3px;
    top: 8px;
    bottom: 8px;
    width: 1px;
    background: var(--border);
  }

  .tab-demo__tab.is-active {
    color: var(--accent);
    border-bottom-color: transparent;
  }

  .tab-demo__add {
    border-radius: 6px;
  }
}

/* Variant: VSCode */
.tab-demo--vscode {
  .tab-demo__bar {
    border-radius: 10px;
    background: #f3f3f3;
    border: 1px solid var(--border);
    padding: 4px;
  }

  .tab-demo__tab,
  .tab-demo__add {
    border-radius: 6px;
    border-bottom: 0;
    background: transparent;
    color: #3c3c3c;
  }

  .tab-demo__tab:hover,
  .tab-demo__add:hover {
    background: #e7e7e7;
    color: #1f1f1f;
  }

  .tab-demo__tab.is-active {
    background: #ffffff;
    color: #1f1f1f;
    border-bottom-color: transparent;
    box-shadow: 0 1px 0 rgba(0, 0, 0, 0.06);
    position: relative;
  }

  .tab-demo__tab.is-active::before {
    content: '';
    position: absolute;
    left: 8px;
    right: 8px;
    top: 2px;
    height: 2px;
    border-radius: 999px;
    background: var(--accent);
  }
}

/* Variant: Chrome */
.tab-demo--chrome {
  .tab-demo__bar {
    background: #f1f3f4;
    border: 1px solid #e0e3e7;
    border-radius: 12px 12px 0 0;
    border-bottom-left-radius: 0;
    border-bottom-right-radius: 0;
    padding: 8px 8px 0;
  }

  .tab-demo__tab,
  .tab-demo__add {
    border: 1px solid #e0e3e7;
    border-bottom: 0;
    background: #e8eaed;
    border-radius: 12px 12px 0 0;
    margin-bottom: -1px;
    color: #202124;
  }

  .tab-demo__tab:hover,
  .tab-demo__add:hover {
    background: #dfe1e5;
  }

  .tab-demo__tab.is-active {
    background: #fff;
    color: #202124;
  }
}

/* Variant: Dense */
.tab-demo--dense {
  .tab-demo__bar {
    border-radius: 0;
    border: 0;
    border-bottom: 1px solid var(--border);
    padding: 0 2px 4px;
    background: transparent;
  }

  .tab-demo__tab {
    padding: 4px 8px;
    border-radius: 6px;
  }

  .tab-demo__title {
    font-size: 12px;
  }

  .tab-demo__sub {
    display: none;
  }

  .tab-demo__close {
    width: 18px;
    height: 18px;
    border-radius: 6px;
  }

  .tab-demo__add {
    width: 28px;
    min-width: 28px;
    height: 28px;
    border-radius: 6px;
  }
}
</style>
