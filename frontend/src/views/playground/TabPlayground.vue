<template>
  <div class="tab-playground">
    <el-card class="intro">
      <template #header>
        <div class="intro__header">
          <div class="intro__title">
            <div class="intro__title-main">Tab 栏样式 Playground</div>
            <div class="intro__title-sub">用于 Data Studio 查询编辑器顶部 Tab 的样式对比（11 种）</div>
          </div>
          <div class="intro__actions">
            <el-button size="small" @click="resetDemos">重置示例</el-button>
          </div>
        </div>
      </template>

      <div class="intro__controls">
        <div class="control-label">主预览</div>
        <el-radio-group v-model="selectedVariant" size="small" class="variant-group">
          <el-radio-button
            v-for="variant in variants"
            :key="variant.key"
            :label="variant.key"
          >
            {{ variant.label }}
          </el-radio-button>
        </el-radio-group>
      </div>

      <TabBarDemo :key="`main-${selectedVariant}-${resetVersion}`" :variant="selectedVariant" />
    </el-card>

    <div class="preview-grid">
      <el-card v-for="variant in variants" :key="variant.key" class="preview-card">
        <template #header>
          <div class="preview-card__header">
            <div class="preview-card__title">
              <span>{{ variant.label }}</span>
              <el-tag size="small" type="info">{{ variant.key }}</el-tag>
            </div>
            <el-button
              size="small"
              :type="selectedVariant === variant.key ? 'primary' : 'default'"
              plain
              @click="selectedVariant = variant.key"
            >
              设为主预览
            </el-button>
          </div>
        </template>

        <div class="preview-card__desc">{{ variant.desc }}</div>
        <TabBarDemo :key="`${variant.key}-${resetVersion}`" :variant="variant.key" />
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import TabBarDemo from '@/views/playground/components/TabBarDemo.vue'

const variants = [
  { key: 'navicat', label: 'Navicat', desc: '参考 Navicat：灰底标签条、激活 Tab 融合内容区' },
  { key: 'line', label: 'Line 线条', desc: '主流：下划线高亮，整体最“干净”' },
  { key: 'minimal', label: 'Minimal 极简', desc: '更克制：几乎无背景，仅强调当前 Tab' },
  { key: 'card', label: 'Card 卡片', desc: '主流：浏览器/控制台常见的卡片式标签页' },
  { key: 'flat-card', label: 'Flat Card 扁平卡片', desc: '卡片但更扁平，层级更弱、更“工具型”' },
  { key: 'boxed', label: 'Boxed 方框', desc: '每个 Tab 都是独立小框，识别清晰' },
  { key: 'pill', label: 'Pill 胶囊', desc: '圆角更大，视觉更柔和（仍保持简洁）' },
  { key: 'separator', label: 'Separator 分隔线', desc: '用竖线分隔，信息密度高、很常见' },
  { key: 'vscode', label: 'VSCode IDE', desc: '接近 VSCode / JetBrains 的编辑器 Tab 观感' },
  { key: 'chrome', label: 'Chrome 浏览器', desc: '接近 Chrome 标签页的曲线造型' },
  { key: 'dense', label: 'Dense 紧凑', desc: '最省高度：单行标题 + 更小的间距' }
]

const selectedVariant = ref('navicat')
const resetVersion = ref(0)

const resetDemos = () => {
  resetVersion.value += 1
}
</script>

<style scoped lang="scss">
.tab-playground {
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.intro__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.intro__title-main {
  font-weight: 750;
  font-size: 16px;
  color: #0f172a;
}

.intro__title-sub {
  margin-top: 2px;
  font-size: 12px;
  color: #64748b;
}

.intro__controls {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}

.control-label {
  font-size: 12px;
  color: #64748b;
  font-weight: 650;
}

.variant-group {
  flex: 1;
  min-width: 240px;
}

.preview-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(420px, 1fr));
  gap: 12px;
}

.preview-card__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.preview-card__title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-weight: 700;
  color: #0f172a;
}

.preview-card__desc {
  margin-bottom: 10px;
  color: #64748b;
  font-size: 12px;
}
</style>
