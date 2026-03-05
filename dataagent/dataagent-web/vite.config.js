import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig(({ mode }) => {
  const isLib = mode === 'lib'

  if (isLib) {
    return {
      plugins: [vue()],
      build: {
        lib: {
          entry: resolve(__dirname, 'src/index.js'),
          name: 'DataAgentWeb',
          fileName: 'dataagent-web'
        },
        rollupOptions: {
          external: ['vue', 'element-plus', 'echarts', 'dayjs', 'axios', '@element-plus/icons-vue'],
          output: {
            exports: 'named',
            globals: {
              vue: 'Vue',
              'element-plus': 'ElementPlus',
              echarts: 'echarts',
              dayjs: 'dayjs',
              axios: 'axios',
              '@element-plus/icons-vue': 'ElementPlusIconsVue'
            }
          }
        }
      }
    }
  }

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': resolve(__dirname, 'src')
      }
    },
    server: {
      port: 3100
    }
  }
})
