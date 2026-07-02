<template>
  <div class="login-page">
    <el-card class="login-card" shadow="always">
      <div class="login-title">
        <h2>DataAgent 智能问数</h2>
        <p>请登录后继续</p>
      </div>

      <el-alert
        v-if="errorMessage"
        :title="errorMessage"
        type="error"
        show-icon
        :closable="false"
        class="login-alert"
      />

      <el-form
        v-if="authStore.localLoginEnabled"
        :model="form"
        label-position="top"
        @submit.prevent="handleLocalLogin"
      >
        <el-form-item label="用户名">
          <el-input v-model="form.username" autocomplete="username" placeholder="用户名" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            autocomplete="current-password"
            placeholder="密码"
            show-password
            @keyup.enter="handleLocalLogin"
          />
        </el-form-item>
        <el-button
          type="primary"
          class="login-button"
          :loading="submitting"
          @click="handleLocalLogin"
        >
          登录
        </el-button>
      </el-form>

      <el-divider v-if="authStore.localLoginEnabled && authStore.oauthLoginEnabled">或</el-divider>

      <el-button
        v-if="authStore.oauthLoginEnabled"
        class="login-button oauth-button"
        @click="handleOauthLogin"
      >
        通过 {{ authStore.providerName || 'SSO' }} 登录
      </el-button>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { sanitizeRedirectPath, useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const form = reactive({ username: '', password: '' })
const submitting = ref(false)
const errorMessage = ref('')

const OAUTH_ERROR_MESSAGES = {
  oauth_exchange_failed: 'OAuth 登录失败：令牌交换未成功，请重试或联系管理员',
  oauth_missing_user_id: 'OAuth 登录失败：身份服务未返回用户标识',
  access_denied: 'OAuth 登录已取消'
}

onMounted(async () => {
  await authStore.bootstrap()
  if (!authStore.enabled || authStore.currentUser) {
    router.replace(redirectTarget())
    return
  }
  const error = String(route.query.error || '')
  if (error) {
    errorMessage.value = OAUTH_ERROR_MESSAGES[error] || `登录失败：${error}`
  }
})

function redirectTarget() {
  return sanitizeRedirectPath(route.query.redirect)
}

async function handleLocalLogin() {
  if (!form.username || !form.password || submitting.value) return
  submitting.value = true
  errorMessage.value = ''
  try {
    await authStore.loginLocal(form.username, form.password)
    router.replace(redirectTarget())
  } catch (error) {
    errorMessage.value = error?.message || '登录失败'
  } finally {
    submitting.value = false
  }
}

function handleOauthLogin() {
  window.location.href = authStore.oauthAuthorizeUrl(redirectTarget())
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--el-fill-color-light, #f5f7fa);
}

.login-card {
  width: 380px;
  padding: 8px 12px;
}

.login-title {
  text-align: center;
  margin-bottom: 16px;
}

.login-title h2 {
  margin: 0 0 4px;
  font-size: 20px;
}

.login-title p {
  margin: 0;
  color: var(--el-text-color-secondary, #909399);
  font-size: 13px;
}

.login-alert {
  margin-bottom: 16px;
}

.login-button {
  width: 100%;
}

.oauth-button {
  margin-top: 4px;
}
</style>
