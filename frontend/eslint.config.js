import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import configPrettier from 'eslint-config-prettier'
import globals from 'globals'

// 渐进式引入：首版以告警为主、不强制失败，先建立基线再逐步收敛。
// 详见 docs/plans/2026-06-16-code-review-remediation-plan.md (P1-1)。
export default [
  {
    ignores: [
      'dist/**',
      'node_modules/**',
      'public/**',
      // 大型 demo mock 数据，非业务源码，暂不纳入静态检查
      'src/demo/mockServer.js',
    ],
  },
  js.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  configPrettier,
  {
    files: ['**/*.{js,vue}'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: {
        ...globals.browser,
      },
    },
    rules: {
      // 遗留 console 较多，先告警，按区域逐步清理后再升级为 error
      'no-console': 'warn',
      'no-debugger': 'warn',
      // 历史代码存在未使用变量，告警而非阻断；忽略下划线前缀的占位参数
      'no-unused-vars': ['warn', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
      // 现有大量单词组件名（如 Login、Workflows），不强制多词命名
      'vue/multi-word-component-names': 'off',
      // 暂不强制属性顺序/换行等纯风格项，交给 Prettier 与后续收敛
      'vue/attributes-order': 'warn',
      'vue/require-default-prop': 'off',
      // DataStudioNew.vue 中存在 finally 内 return 的历史写法（P2 巨型组件拆分时一并修复），
      // 先降级为告警以建立绿色基线，避免在 6000+ 行混合缩进文件上做高风险手改
      'no-unsafe-finally': 'warn',
    },
  },
  {
    // 构建/配置脚本运行在 Node 环境
    files: ['*.config.js', 'vite.config.js'],
    languageOptions: {
      globals: {
        ...globals.node,
      },
    },
  },
  {
    // Vitest 测试文件提供测试全局变量
    files: ['**/__tests__/**/*.{js,vue}', '**/*.{spec,test}.{js,vue}'],
    languageOptions: {
      globals: {
        ...globals.node,
        describe: 'readonly',
        it: 'readonly',
        test: 'readonly',
        expect: 'readonly',
        beforeEach: 'readonly',
        afterEach: 'readonly',
        beforeAll: 'readonly',
        afterAll: 'readonly',
        vi: 'readonly',
      },
    },
  },
]
