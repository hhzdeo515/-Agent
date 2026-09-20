<script setup lang="ts">
/**
 * 设置面板：AI 调用 / 个人信息 / 个性化。
 *
 * <h3>为什么这三件事放在同一个入口</h3>
 * 它们都是"我现在以什么身份、用什么能力在看这个工作台"，
 * 但归属完全不同，所以是三个分区而不是一张大表单：
 *   · AI 调用是运维级配置（改模型型号会影响所有审核结论的可比性），
 *     因此后端要求 admin.config，界面上也必须显示当前生效的到底是什么；
 *   · 个人信息是账号属性，只能改显示名与部门；
 *   · 个性化只作用于这台设备，即时生效、不入库。
 * 把三者混在一起最容易造成的误解是"我改了字号，是不是也改了全局配置"。
 *
 * <h3>AI 分区的核心风险不是"能不能改"，而是"改了以后系统变成了什么"</h3>
 * 本项目同时有规则实现与千问大模型实现，两者在界面上的表现可能完全一样。
 * 因此这里保留能力清单，并把 `verified === false` 的能力显式标成「未实测」——
 * 尚未验证的能力不得伪装成已经可靠可用（AGENTS.md 第 15 条）。
 */
import { computed, reactive, ref, watch } from 'vue'
import GwModal from '@/components/GwModal.vue'
import { ApiError, apiGet, apiPost, apiPut } from '@/api/client'
import {
  DENSITY_OPTIONS,
  FONT_SCALE_OPTIONS,
  usePreferences,
  type DensityKey,
  type FontScaleKey,
} from '@/stores/preferences'

// ── 类型（与后端 SettingsController / AiStatusController 的返回结构对应）────

interface AiModels {
  text: string
  vision: string
  ocrPrimary: string
  ocrFallback: string
  asr: string
  embedding: string
  rerank: string
}

interface AiHttp {
  connectTimeoutMs: number
  readTimeoutMs: number
  maxAttempts: number
}

interface AiConfig {
  provider: string
  region: string
  apiKeyConfigured: boolean
  apiKeyHint: string | null
  baseUrl: string
  compatibleBaseUrl: string
  models: AiModels
  http: AiHttp
  switchNote?: string
  restartRequired?: boolean
}

interface Capability {
  name: string
  status: 'AVAILABLE' | 'UNAVAILABLE' | 'PARTIAL' | 'RULE_BASED'
  detail: string
  verified: boolean
  verificationNote: string | null
}

interface AiStatus {
  provider: string
  providerLabel: string
  region: string
  apiKeyConfigured: boolean
  apiKeyHint: string | null
  pipelineVersion: string
  promptVersion: string
  rulesetVersion: string
  capabilities: Capability[]
  note: string
}

interface AiTestResult {
  ok: boolean
  message: string
  apiKeyHint?: string
  provider: string
}

interface Profile {
  id: number
  username: string
  displayName: string
  dept: string | null
  roleCodes: string | null
}

type TabKey = 'ai' | 'profile' | 'ui'

// ── 组件契约 ──────────────────────────────────────────────────────────

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    /** 左栏已缓存的身份，先渲染它再向后端核对，避免打开面板时闪一下空白 */
    profile?: Profile | null
  }>(),
  { profile: null },
)

const emit = defineEmits<{
  'update:modelValue': [boolean]
  /** 身份保存成功：左栏据此同步显示名，不必自己再请求一次 */
  'profile-saved': [Profile]
}>()

const TABS: { key: TabKey; label: string }[] = [
  { key: 'ai', label: 'AI 调用' },
  { key: 'profile', label: '个人信息' },
  { key: 'ui', label: '个性化' },
]

const tab = ref<TabKey>('ai')

const STATUS_LABEL: Record<string, string> = {
  AVAILABLE: '可用',
  UNAVAILABLE: '不可用',
  PARTIAL: '部分可用',
  RULE_BASED: '规则实现',
}

const STATUS_TONE: Record<string, string> = {
  AVAILABLE: 'gw-status--success',
  UNAVAILABLE: 'gw-status--muted',
  PARTIAL: 'gw-status--brass',
  RULE_BASED: 'gw-status--brass',
}

const MODEL_FIELDS: { key: ModelFormKey; label: string }[] = [
  { key: 'textModel', label: '文本主力' },
  { key: 'visionModel', label: '画面理解' },
  { key: 'ocrPrimaryModel', label: '文字识别' },
  { key: 'ocrFallbackModel', label: '识别兜底' },
  { key: 'asrModel', label: '语音转写' },
  { key: 'embeddingModel', label: '向量化' },
  { key: 'rerankModel', label: '重排序' },
]

const ROLE_LABEL: Record<string, string> = {
  LEGAL: '法务',
  BRAND: '品牌',
  DESIGN: '设计',
  BIZ: '业务',
  ADMIN: '管理员',
  AI_SERVICE: 'AI 服务',
}

// ── AI 调用 ───────────────────────────────────────────────────────────

type ModelFormKey =
  | 'textModel'
  | 'visionModel'
  | 'ocrPrimaryModel'
  | 'ocrFallbackModel'
  | 'asrModel'
  | 'embeddingModel'
  | 'rerankModel'

interface AiForm extends Record<ModelFormKey, string> {
  provider: string
  connectTimeoutMs: number
  readTimeoutMs: number
  maxAttempts: number
}

const form = reactive<AiForm>({
  provider: 'mock',
  textModel: '',
  visionModel: '',
  ocrPrimaryModel: '',
  ocrFallbackModel: '',
  asrModel: '',
  embeddingModel: '',
  rerankModel: '',
  connectTimeoutMs: 10000,
  readTimeoutMs: 120000,
  maxAttempts: 3,
})

const config = ref<AiConfig | null>(null)
const status = ref<AiStatus | null>(null)
const aiLoading = ref(false)
const aiError = ref<string | null>(null)

const saving = ref(false)
const savedNote = ref<string | null>(null)
const testing = ref(false)
const testResult = ref<AiTestResult | null>(null)

/**
 * API Key 的三态（后端语义，必须原样传递）：
 *   不带字段 = 不改动 · 空字符串 = 清除 · 非空字符串 = 设置为该值
 * 界面因此需要两个独立信号：输入框内容，以及"我确实想清除"的显式勾选。
 * 只用输入框的话，"留空"就无法区分"不改"和"清除"——用户每改一次模型型号
 * 都会把 Key 抹掉一次。
 */
const apiKeyInput = ref('')
const clearApiKey = ref(false)

const apiKeyPlaceholder = computed(() => {
  if (clearApiKey.value) return '保存后将清除已配置的 Key'
  if (config.value?.apiKeyConfigured) {
    return `已配置 ${config.value.apiKeyHint ?? '****'}，留空表示不修改`
  }
  return '未配置，填写后保存立即生效'
})

/** 顶部展示用：优先用 /api/ai/status 的口径，它才是"当前真正生效"的那个 */
const effectiveProviderLabel = computed(() => {
  if (status.value) return status.value.providerLabel
  return form.provider === 'dashscope' ? '阿里云百炼（千问大模型）' : '规则实现（Mock）'
})

const keyConfigured = computed(() => status.value?.apiKeyConfigured ?? config.value?.apiKeyConfigured ?? false)
const keyHint = computed(() => status.value?.apiKeyHint ?? config.value?.apiKeyHint ?? null)

function fillForm(cfg: AiConfig) {
  form.provider = cfg.provider
  form.textModel = cfg.models.text
  form.visionModel = cfg.models.vision
  form.ocrPrimaryModel = cfg.models.ocrPrimary
  form.ocrFallbackModel = cfg.models.ocrFallback
  form.asrModel = cfg.models.asr
  form.embeddingModel = cfg.models.embedding
  form.rerankModel = cfg.models.rerank
  form.connectTimeoutMs = cfg.http.connectTimeoutMs
  form.readTimeoutMs = cfg.http.readTimeoutMs
  form.maxAttempts = cfg.http.maxAttempts
}

async function loadAi() {
  aiLoading.value = true
  aiError.value = null
  try {
    const [cfg, st] = await Promise.all([
      apiGet<AiConfig>('/api/settings/ai'),
      // 能力清单单独取：即使配置读取失败，也要能如实告诉用户当前具备什么能力
      apiGet<AiStatus>('/api/ai/status').catch(() => null),
    ])
    config.value = cfg
    fillForm(cfg)
    apiKeyInput.value = ''
    clearApiKey.value = false
    if (st) status.value = st
  } catch (e) {
    aiError.value = e instanceof ApiError ? e.message : 'AI 配置读取失败'
  } finally {
    aiLoading.value = false
  }
}

async function saveAi() {
  savedNote.value = null
  testResult.value = null

  const nums: [string, number][] = [
    ['连接超时', form.connectTimeoutMs],
    ['读取超时', form.readTimeoutMs],
    ['重试次数', form.maxAttempts],
  ]
  const bad = nums.find(([, v]) => !Number.isFinite(v) || v <= 0)
  if (bad) {
    aiError.value = `${bad[0]}必须是大于 0 的数字`
    return
  }
  // 后端对空字符串是"跳过不写"而不是"改回默认"，留空保存会看起来什么都没发生，
  // 因此在前面就拦住，而不是让用户对着一个没反应的输入框猜
  const blank = MODEL_FIELDS.find((m) => !form[m.key].trim())
  if (blank) {
    aiError.value = `${blank.label}模型型号不能为空`
    return
  }

  const payload: Record<string, unknown> = {
    provider: form.provider,
    textModel: form.textModel,
    visionModel: form.visionModel,
    ocrPrimaryModel: form.ocrPrimaryModel,
    ocrFallbackModel: form.ocrFallbackModel,
    asrModel: form.asrModel,
    embeddingModel: form.embeddingModel,
    rerankModel: form.rerankModel,
    connectTimeoutMs: form.connectTimeoutMs,
    readTimeoutMs: form.readTimeoutMs,
    maxAttempts: form.maxAttempts,
  }
  // 三态按优先级落地：勾了"清除"就以清除为准（此时输入框是禁用的），
  // 否则只有用户真的写了内容才发这个字段，留空一律等同于"不改动"
  const typedKey = apiKeyInput.value.trim()
  if (clearApiKey.value) payload.apiKey = ''
  else if (typedKey) payload.apiKey = typedKey

  saving.value = true
  aiError.value = null
  try {
    const next = await apiPut<AiConfig>('/api/settings/ai', payload)
    config.value = next
    fillForm(next)
    apiKeyInput.value = ''
    clearApiKey.value = false
    savedNote.value = `已保存。当前生效：${next.provider === 'dashscope' ? '阿里云百炼（千问大模型）' : '规则实现（Mock）'} · Key ${next.apiKeyConfigured ? `已配置 ${next.apiKeyHint ?? ''}` : '未配置'}`
    // 供应商切换后能力清单会整份变化，必须重新取，否则顶部标签与清单自相矛盾
    status.value = await apiGet<AiStatus>('/api/ai/status').catch(() => status.value)
  } catch (e) {
    aiError.value = e instanceof ApiError ? e.message : '保存失败'
  } finally {
    saving.value = false
  }
}

/**
 * 连通性自检。
 * 后端用的是<b>已保存</b>的配置（内存中生效值），不是表单里还没保存的草稿，
 * 所以界面上要说清顺序：先保存，再自检。
 */
async function runTest() {
  testing.value = true
  testResult.value = null
  aiError.value = null
  try {
    testResult.value = await apiPost<AiTestResult>('/api/settings/ai/test')
  } catch (e) {
    aiError.value = e instanceof ApiError ? e.message : '自检请求失败'
  } finally {
    testing.value = false
  }
}

// ── 个人信息 ──────────────────────────────────────────────────────────

const profile = ref<Profile | null>(props.profile)
const displayName = ref('')
const dept = ref('')
const profileSaving = ref(false)
const profileError = ref<string | null>(null)
const profileNote = ref<string | null>(null)

const roleItems = computed(() =>
  (profile.value?.roleCodes ?? '')
    .split(',')
    .map((c) => c.trim())
    .filter(Boolean)
    .map((code) => ({ code, label: ROLE_LABEL[code] ?? code })),
)

function seedProfileForm(p: Profile) {
  displayName.value = p.displayName ?? ''
  dept.value = p.dept ?? ''
}

async function loadProfile() {
  profileError.value = null
  try {
    const p = await apiGet<Profile>('/api/settings/profile')
    profile.value = p
    seedProfileForm(p)
  } catch (e) {
    profileError.value = e instanceof ApiError ? e.message : '身份读取失败'
  }
}

async function saveProfile() {
  profileError.value = null
  profileNote.value = null
  if (!displayName.value.trim()) {
    profileError.value = '显示名不能为空'
    return
  }
  profileSaving.value = true
  try {
    const p = await apiPut<Profile>('/api/settings/profile', {
      displayName: displayName.value.trim(),
      dept: dept.value.trim(),
    })
    profile.value = p
    seedProfileForm(p)
    profileNote.value = '已保存'
    emit('profile-saved', p)
  } catch (e) {
    profileError.value = e instanceof ApiError ? e.message : '保存失败'
  } finally {
    profileSaving.value = false
  }
}

// ── 个性化 ────────────────────────────────────────────────────────────

const { prefs, isDefault, setDensity, setFontScale, setReducedMotion, resetPreferences } =
  usePreferences()

function pickDensity(v: DensityKey) {
  setDensity(v)
}
function pickFontScale(v: FontScaleKey) {
  setFontScale(v)
}

// ── 打开时按需加载 ────────────────────────────────────────────────────

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    profile.value = props.profile ?? profile.value
    if (profile.value) seedProfileForm(profile.value)
    void loadProfile()
    if (!config.value) void loadAi()
  },
  { immediate: true },
)
</script>

<template>
  <GwModal
    :model-value="props.modelValue"
    size="wide"
    title="设置"
    subtitle="AI 调用与个人偏好。AI 配置需要系统参数权限，改动会影响所有审核结论。"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="tabs" role="tablist">
      <button
        v-for="t in TABS"
        :key="t.key"
        class="tabs__btn"
        :class="{ 'is-on': tab === t.key }"
        type="button"
        role="tab"
        :aria-selected="tab === t.key"
        @click="tab = t.key"
      >
        {{ t.label }}
      </button>
    </div>

    <!-- ══ 1. AI 调用 ══════════════════════════════════════════════ -->
    <section v-if="tab === 'ai'" class="sec">
      <p v-if="aiError" class="err">{{ aiError }}</p>
      <p v-if="aiLoading && !config" class="muted">读取中…</p>

      <template v-if="config">
        <div class="head">
          <span class="gw-status" :class="config.provider === 'dashscope' ? 'gw-status--success' : 'gw-status--brass'">
            <span class="gw-status__dot" />{{ effectiveProviderLabel }}
          </span>
          <span class="gw-status" :class="keyConfigured ? 'gw-status--success' : 'gw-status--muted'">
            API Key {{ keyConfigured ? keyHint : '未配置' }}
          </span>
          <span class="head__region gw-mono">{{ config.region }}</span>
        </div>

        <h3 class="sec__title">调用参数</h3>
        <div class="grid">
          <label class="fld">
            <span class="fld__label">供应商</span>
            <select v-model="form.provider" class="fld__ctl">
              <option value="mock">规则实现（Mock）</option>
              <option value="dashscope">阿里云百炼（千问大模型）</option>
            </select>
          </label>
          <label class="fld">
            <span class="fld__label">API Key</span>
            <input
              v-model="apiKeyInput"
              class="fld__ctl gw-mono"
              type="password"
              autocomplete="off"
              :placeholder="apiKeyPlaceholder"
              :disabled="clearApiKey"
            />
          </label>
        </div>

        <label v-if="config.apiKeyConfigured" class="chk">
          <input v-model="clearApiKey" type="checkbox" />
          <span>清除已保存的 API Key</span>
        </label>

        <p v-if="config.switchNote" class="gw-note">{{ config.switchNote }}</p>

        <h3 class="sec__title">模型型号</h3>
        <div class="grid grid--models">
          <label v-for="m in MODEL_FIELDS" :key="m.key" class="fld">
            <span class="fld__label">{{ m.label }}</span>
            <input v-model="form[m.key]" class="fld__ctl gw-mono" type="text" spellcheck="false" />
          </label>
        </div>

        <h3 class="sec__title">超时与重试</h3>
        <div class="grid grid--3">
          <label class="fld">
            <span class="fld__label">连接超时（毫秒）</span>
            <input v-model.number="form.connectTimeoutMs" class="fld__ctl gw-mono" type="number" min="1" />
          </label>
          <label class="fld">
            <span class="fld__label">读取超时（毫秒）</span>
            <input v-model.number="form.readTimeoutMs" class="fld__ctl gw-mono" type="number" min="1" />
          </label>
          <label class="fld">
            <span class="fld__label">重试次数</span>
            <input v-model.number="form.maxAttempts" class="fld__ctl gw-mono" type="number" min="1" />
          </label>
        </div>

        <div v-if="config.baseUrl" class="gw-kv urls">
          <div class="gw-kv__row"><dt>原生接口</dt><dd class="gw-mono">{{ config.baseUrl }}</dd></div>
          <div class="gw-kv__row"><dt>兼容接口</dt><dd class="gw-mono">{{ config.compatibleBaseUrl }}</dd></div>
        </div>

        <!-- 能力清单：让"当前到底具备什么能力"一眼可查，而不是靠配置文件推断 -->
        <h3 class="sec__title">
          能力清单
          <span v-if="status" class="sec__hint gw-mono">
            流水线 {{ status.pipelineVersion }} · 提示词 {{ status.promptVersion }} · 规则集 {{ status.rulesetVersion }}
          </span>
        </h3>
        <ul class="cap">
          <li v-for="c in status?.capabilities ?? []" :key="c.name" class="cap__item">
            <div class="cap__head">
              <span class="cap__name">{{ c.name }}</span>
              <span class="gw-status" :class="STATUS_TONE[c.status] ?? 'gw-status--muted'">
                {{ STATUS_LABEL[c.status] ?? c.status }}
              </span>
              <span v-if="c.verified === false" class="cap__unverified">未实测</span>
            </div>
            <p class="cap__detail">{{ c.detail }}</p>
            <p v-if="c.verified === false && c.verificationNote" class="cap__note">
              {{ c.verificationNote }}
            </p>
          </li>
          <li v-if="!status" class="muted">能力清单读取中…</li>
        </ul>

        <p v-if="status?.note" class="gw-note gw-note--plain">{{ status.note }}</p>

        <p v-if="testResult" class="result">
          <span class="gw-status" :class="testResult.ok ? 'gw-status--success' : 'gw-status--danger'">
            {{ testResult.ok ? '自检通过' : '自检未通过' }}
          </span>
          <span class="result__text">{{ testResult.message }}</span>
        </p>
      </template>
    </section>

    <!-- ══ 2. 个人信息 ═════════════════════════════════════════════ -->
    <section v-else-if="tab === 'profile'" class="sec">
      <p v-if="profileError" class="err">{{ profileError }}</p>
      <p v-if="!profile" class="muted">身份读取中…</p>

      <template v-else>
        <dl class="gw-kv">
          <div class="gw-kv__row">
            <dt>用户名</dt>
            <dd class="gw-mono">{{ profile.username }}</dd>
          </div>
          <div class="gw-kv__row">
            <dt>角色</dt>
            <dd class="roles">
              <span v-for="r in roleItems" :key="r.code" class="gw-status gw-status--muted">
                {{ r.label }} · {{ r.code }}
              </span>
            </dd>
          </div>
        </dl>

        <!-- 这一条不是说明文字，而是授权模型本身：能给自己改角色 = 拥有所有权限 -->
        <p class="gw-note gw-note--plain">
          只能修改显示名与部门。用户名是登录凭据，角色决定权限边界——允许用户自己改角色，
          等于允许他给自己签发系统里的全部权限，因此这两项由管理员维护。
        </p>

        <h3 class="sec__title">可修改</h3>
        <div class="grid">
          <label class="fld">
            <span class="fld__label">显示名</span>
            <input v-model="displayName" class="fld__ctl" type="text" maxlength="64" />
          </label>
          <label class="fld">
            <span class="fld__label">部门</span>
            <input v-model="dept" class="fld__ctl" type="text" maxlength="128" />
          </label>
        </div>
        <p v-if="profileNote" class="muted">{{ profileNote }}</p>
      </template>
    </section>

    <!-- ══ 3. 个性化 ═══════════════════════════════════════════════ -->
    <section v-else class="sec">
      <div class="pref">
        <div class="pref__main">
          <div class="pref__title">界面密度</div>
          <div class="pref__sub">面板留白与整体间距</div>
        </div>
        <div class="seg">
          <button
            v-for="o in DENSITY_OPTIONS"
            :key="o.value"
            class="seg__btn"
            :class="{ 'is-on': prefs.density === o.value }"
            type="button"
            @click="pickDensity(o.value)"
          >
            {{ o.label }}
          </button>
        </div>
      </div>

      <div class="pref">
        <div class="pref__main">
          <div class="pref__title">字号</div>
          <div class="pref__sub">正文、标题与导航字号</div>
        </div>
        <div class="seg">
          <button
            v-for="o in FONT_SCALE_OPTIONS"
            :key="o.value"
            class="seg__btn"
            :class="{ 'is-on': prefs.fontScale === o.value }"
            type="button"
            @click="pickFontScale(o.value)"
          >
            {{ o.label }}
          </button>
        </div>
      </div>

      <div class="pref">
        <div class="pref__main">
          <div class="pref__title">减少动效</div>
          <div class="pref__sub">关闭过渡与动画</div>
        </div>
        <button
          class="switch"
          :class="{ 'is-on': prefs.reducedMotion }"
          type="button"
          role="switch"
          :aria-checked="prefs.reducedMotion"
          @click="setReducedMotion(!prefs.reducedMotion)"
        >
          <span class="switch__knob" />
        </button>
      </div>

      <!-- 主色调不可个性化的原因要说清，而不是给一个改了不生效的下拉 -->
      <p class="gw-note gw-note--plain">
        主色调由当前模块决定，不是可选项：接收区墨青、反馈区铜金、终审区暗酒红、助手石板灰。
        模块色是区分四条流程的编码，覆盖它会让左栏标识与页面内容自相矛盾。
      </p>

      <p class="muted">以上三项即时生效，只保存在这台设备上。</p>
    </section>

    <template #footer>
      <span v-if="tab === 'ai' && savedNote" class="foot__note">{{ savedNote }}</span>
      <span v-else-if="tab === 'ui'" class="foot__note">偏好已自动保存</span>

      <template v-if="tab === 'ai'">
        <button class="gw-btn gw-btn--ghost" type="button" :disabled="testing || !config" @click="runTest">
          {{ testing ? '自检中…' : '连通性自检' }}
        </button>
        <button class="gw-btn gw-btn--primary" type="button" :disabled="saving || !config" @click="saveAi">
          {{ saving ? '保存中…' : '保存' }}
        </button>
      </template>

      <template v-else-if="tab === 'profile'">
        <button class="gw-btn gw-btn--primary" type="button" :disabled="profileSaving || !profile" @click="saveProfile">
          {{ profileSaving ? '保存中…' : '保存' }}
        </button>
      </template>

      <template v-else>
        <button class="gw-btn gw-btn--ghost" type="button" :disabled="isDefault" @click="resetPreferences">
          恢复默认
        </button>
      </template>
    </template>
  </GwModal>
</template>

<style scoped>
/* ── 分区切换：下划线式标签，沿用工作台的细分割线语言 ────────────────── */
.tabs {
  display: flex;
  gap: var(--gw-s5);
  margin-bottom: var(--gw-s5);
  border-bottom: 1px solid var(--gw-line);
}

.tabs__btn {
  position: relative;
  padding: 0 0 10px;
  border: 0;
  background: transparent;
  font-size: var(--gw-fs-md);
  font-weight: 500;
  color: var(--gw-text-tertiary);
  transition: color var(--gw-dur-fast) var(--gw-ease);
}

.tabs__btn:hover {
  color: var(--gw-text-secondary);
}

.tabs__btn.is-on {
  color: var(--gw-text);
}

.tabs__btn.is-on::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: -1px;
  height: 2px;
  border-radius: var(--gw-r-pill);
  background: var(--gw-accent);
}

.sec__title {
  display: flex;
  align-items: baseline;
  gap: var(--gw-s3);
  margin: var(--gw-s5) 0 var(--gw-s3);
  font-size: var(--gw-fs-md);
  font-weight: 600;
  color: var(--gw-text);
}

.sec__title:first-of-type {
  margin-top: 0;
}

.sec__hint {
  margin-left: auto;
  font-size: var(--gw-fs-xs);
  font-weight: 400;
  color: var(--gw-text-tertiary);
}

/* ── 顶部当前状态 ─────────────────────────────────────────────────── */
.head {
  display: flex;
  align-items: center;
  gap: var(--gw-s2);
  padding: var(--gw-s3) var(--gw-s4);
  border: 1px solid var(--gw-line);
  border-radius: var(--gw-r);
  background: var(--gw-bg);
}

.head__region {
  margin-left: auto;
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
}

/* ── 表单 ─────────────────────────────────────────────────────────── */
.grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--gw-s3) var(--gw-s4);
}

.grid--models {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.grid--3 {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.fld {
  display: block;
  min-width: 0;
}

.fld__label {
  display: block;
  margin-bottom: 5px;
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-secondary);
}

.fld__ctl {
  width: 100%;
  padding: 7px 10px;
  border: 1px solid var(--gw-line-strong);
  border-radius: var(--gw-r-sm);
  background: var(--gw-surface);
  font-family: inherit;
  font-size: var(--gw-fs-base);
  color: var(--gw-text);
  transition: border-color var(--gw-dur-fast) var(--gw-ease);
}

.fld__ctl:focus {
  outline: none;
  border-color: var(--gw-accent);
}

.fld__ctl:disabled {
  background: var(--gw-bg-sunken);
  color: var(--gw-text-tertiary);
}

.chk {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: var(--gw-s3);
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-secondary);
}

.chk input {
  accent-color: var(--gw-accent);
}

.urls {
  margin-top: var(--gw-s3);
}

/* ── 能力清单 ─────────────────────────────────────────────────────── */
.cap {
  display: flex;
  flex-direction: column;
}

.cap__item {
  padding: var(--gw-s3) 0;
  border-bottom: 1px solid var(--gw-line-soft);
}

.cap__item:last-child {
  border-bottom: 0;
}

.cap__head {
  display: flex;
  align-items: center;
  gap: var(--gw-s3);
}

.cap__name {
  font-size: var(--gw-fs-base);
  font-weight: 500;
  color: var(--gw-text);
}

.cap__unverified {
  padding: 0 6px;
  border: 1px solid var(--gw-warning);
  border-radius: var(--gw-r-sm);
  font-size: var(--gw-fs-xs);
  line-height: 16px;
  color: var(--gw-warning);
}

.cap__detail {
  margin-top: 3px;
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-secondary);
  line-height: var(--gw-lh);
}

.cap__note {
  margin-top: 3px;
  font-size: var(--gw-fs-xs);
  color: var(--gw-warning);
  line-height: var(--gw-lh);
}

/* ── 自检结果 ─────────────────────────────────────────────────────── */
.result {
  display: flex;
  align-items: flex-start;
  gap: var(--gw-s3);
  margin-top: var(--gw-s4);
  padding: var(--gw-s3) var(--gw-s4);
  border: 1px solid var(--gw-line);
  border-radius: var(--gw-r-sm);
  background: var(--gw-bg);
}

.result__text {
  flex: 1;
  min-width: 0;
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-secondary);
  line-height: var(--gw-lh);
}

/* ── 个人信息 ─────────────────────────────────────────────────────── */
.roles {
  display: flex;
  flex-wrap: wrap;
  gap: var(--gw-s2);
}

/* ── 个性化 ───────────────────────────────────────────────────────── */
.pref {
  display: flex;
  align-items: center;
  gap: var(--gw-s4);
  padding: var(--gw-s3) 0;
  border-bottom: 1px solid var(--gw-line-soft);
}

.pref:first-child {
  padding-top: 0;
}

.pref__main {
  flex: 1;
  min-width: 0;
}

.pref__title {
  font-size: var(--gw-fs-base);
  color: var(--gw-text);
}

.pref__sub {
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
  margin-top: 2px;
}

.seg {
  display: flex;
  flex: none;
  padding: 2px;
  border: 1px solid var(--gw-line);
  border-radius: var(--gw-r-sm);
  background: var(--gw-bg-sunken);
}

.seg__btn {
  min-width: 52px;
  height: 24px;
  border: 0;
  border-radius: var(--gw-r-sm);
  background: transparent;
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-secondary);
  transition: background var(--gw-dur-fast) var(--gw-ease), color var(--gw-dur-fast) var(--gw-ease);
}

.seg__btn:hover {
  color: var(--gw-text);
}

.seg__btn.is-on {
  background: var(--gw-surface);
  color: var(--gw-text);
  font-weight: 500;
  box-shadow: var(--gw-shadow-xs);
}

.switch {
  position: relative;
  flex: none;
  width: 38px;
  height: 22px;
  padding: 0;
  border: 1px solid var(--gw-line-strong);
  border-radius: var(--gw-r-pill);
  background: var(--gw-bg-sunken);
  transition: background var(--gw-dur-fast) var(--gw-ease),
    border-color var(--gw-dur-fast) var(--gw-ease);
}

.switch.is-on {
  background: var(--gw-accent);
  border-color: var(--gw-accent);
}

.switch__knob {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--gw-surface);
  box-shadow: var(--gw-shadow-xs);
  transition: transform var(--gw-dur-fast) var(--gw-ease);
}

.switch.is-on .switch__knob {
  transform: translateX(16px);
}

/* ── 通用 ─────────────────────────────────────────────────────────── */
.muted {
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-tertiary);
}

.sec > .gw-note {
  margin-top: var(--gw-s4);
}

.err {
  margin-bottom: var(--gw-s4);
  padding: var(--gw-s3) var(--gw-s4);
  border-radius: var(--gw-r-sm);
  background: var(--gw-danger-bg);
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-secondary);
  line-height: var(--gw-lh);
}

.foot__note {
  /* 状态文字靠左、按钮靠右：保存结果与保存动作在同一行，不必上下找 */
  margin-right: auto;
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-secondary);
}

@media (max-width: 720px) {
  .grid,
  .grid--models,
  .grid--3 {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
