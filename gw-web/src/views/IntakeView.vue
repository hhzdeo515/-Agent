<script setup lang="ts">
/**
 * 接收区（正式审核流程入口）
 *
 * 职责：创建审核任务、接收材料、完成解析、记录审核要求。
 * 本模块只把任务与材料规范地送入审核系统，**不提前展示最终风险结论**，
 * 因此这里没有对话区、没有风险条目——那些属于反馈区与终审区。
 *
 * 界面结构：左侧是「材料提交窗口」（唯一的核心操作），右侧是任务状态与后续动作。
 */
import { computed, onMounted, ref, watch } from 'vue'
import FileDropzone from '@/components/FileDropzone.vue'
import PanelCard from '@/components/PanelCard.vue'
import { ApiError, apiGet, apiPost, apiUpload } from '@/api/client'
import {
  loadRecentCases,
  resolveActiveCase,
  setActiveCase,
  useActiveCase,
  useCaseEpoch,
} from '@/stores/activeCase'
import { takePromoteDraft } from '@/stores/promoteDraft'

interface CaseInfo {
  id: number
  caseNo: string
  name: string
  status: string
  deadline?: string
  materialCount: number
  requirementConfirmed: boolean
  requirementConfirmedAt?: string
}

interface MaterialItem {
  id: number
  name: string
  materialType: string
  parseStatus: string
  parseErrorMessage?: string
}

const caseInfo = ref<CaseInfo | null>(null)
const materials = ref<MaterialItem[]>([])
const preparing = ref(false)
const busy = ref(false)
const errorText = ref<string | null>(null)
const submitted = ref(false)

/** 任务切换后 caseInfo 还没重新拉回来时，用共享状态里的 id 兜底 */
const { activeCaseId } = useActiveCase()

/** 审核要求关注点；确认后才生效（AGENTS.md 第 4 条：模板不得默认为正式要求） */
const requirements = ref([
  { label: '绝对化宣传', on: true },
  { label: '安全承诺', on: true },
  { label: '无依据数据', on: true },
  { label: '竞品相关表达', on: false },
  { label: '价格宣传', on: true },
  { label: '免责声明缺失', on: true },
])

const caseId = computed(() => caseInfo.value?.id ?? null)
const uploadedCount = computed(() => materials.value.length)
const parseFailed = computed(() => materials.value.filter((m) => m.parseStatus === 'FAILED'))

const typeLabel: Record<string, string> = {
  IMAGE: '图片', VIDEO: '视频', TEXT: '文本', PPT: 'PPT', PDF: 'PDF', WORD: 'Word',
}
const statusLabel: Record<string, string> = {
  PENDING: '待解析', RUNNING: '解析中', SUCCEEDED: '解析完成',
  FAILED: '解析失败', PARTIAL: '部分解析',
}
const statusTone: Record<string, string> = {
  PENDING: 'gw-status--muted', RUNNING: 'gw-status--brass', SUCCEEDED: 'gw-status--success',
  FAILED: 'gw-status--danger', PARTIAL: 'gw-status--brass',
}

onMounted(() => {
  void boot()
})

/** 用户在头部切换了任务：直接加载那个任务，不要再自动新建 */
watch(useCaseEpoch(), async () => {
  submitted.value = false
  await resolveActiveCase()
  await refresh()
})

/** 进入接收区时先认领一个任务。
 *
 * 优先复用当前任务（可能刚从反馈区跳回来，正在处理同一个 Case）；
 * 只有在没有任何任务时才新建——否则用户每进一次接收区就会多出一个空任务，
 * 后续模块还得让用户从一堆空任务里挑。
 */
async function boot() {
  preparing.value = true
  errorText.value = null
  try {
    // 助手带来的预填内容优先：此时应直接进入"新建任务"状态，
    // 而不是先认领一个旧任务再让用户自己去点新建
    const d = takePromoteDraft()
    if (d) {
      promoteSource.value = d.sourceText
      newName.value = d.proposedCaseName
      showNew.value = true
      if (d.suggestedRequirements.length) {
        applySuggestedRequirements(d.suggestedRequirements)
      }
      const existing = await resolveActiveCase()
      if (existing) await refresh()
      return
    }
    const existing = await resolveActiveCase()
    if (existing) {
      await refresh()
      return
    }
    await ensureCase()
  } catch (e) {
    errorText.value = e instanceof ApiError
      ? `任务准备失败：${e.message}`
      : '任务准备失败，请确认后端服务已启动（http://localhost:8080）'
  } finally {
    preparing.value = false
  }
}

/**
 * 按助手建议的關注点调整勾选。
 *
 * 只把建议里出现的项勾上、其余取消——助手建议是"本批要重点看什么"，
 * 若沿用默认全选，用户就分不清哪些是助手根据他的问题推出来的。
 */
function applySuggestedRequirements(suggested: string[]) {
  const set = new Set(suggested)
  requirements.value = requirements.value.map((r) => ({ ...r, on: set.has(r.label) }))
}

/** 新建任务：显式动作，不再隐式发生 */
async function ensureCase() {
  if (caseInfo.value || preparing.value) return
  preparing.value = true
  errorText.value = null
  try {
    const c = await apiPost<CaseInfo>('/api/intake/cases', {
      projectId: 1,
      name: '新一批宣传物料审核',
    })
    caseInfo.value = c
    // 让反馈区、终审区能接着处理这个任务
    setActiveCase(c.id)
    await loadRecentCases().catch(() => undefined)
  } catch (e) {
    errorText.value = e instanceof ApiError
      ? `任务准备失败：${e.message}`
      : '任务准备失败，请确认后端服务已启动（http://localhost:8080）'
  } finally {
    preparing.value = false
  }
}

async function refresh() {
  const id = caseId.value ?? activeCaseId.value
  if (!id) return
  try {
    const d = await apiGet<{ case: CaseInfo; materials: MaterialItem[] }>(
      `/api/intake/cases/${id}`,
    )
    caseInfo.value = d.case
    materials.value = d.materials ?? []
  } catch {
    /* 刷新失败不打断主流程，界面保留上一次状态 */
  }
}

function onUploaded() {
  void refresh()
}

/** 确认审核要求：这一步之后才能启动 AI 初审 */
async function confirmRequirement() {
  if (!caseId.value) return
  busy.value = true
  errorText.value = null
  try {
    await apiPost('/api/intake/requirements', {
      caseId: caseId.value,
      templateCode: 'DEFAULT',
      requirement: {
        focusPoints: requirements.value.filter((r) => r.on).map((r) => r.label),
      },
    })
    await refresh()
  } catch (e) {
    errorText.value = e instanceof ApiError ? e.message : '确认失败'
  } finally {
    busy.value = false
  }
}

/** 启动 AI 初审，任务进入反馈区 */
async function startReview() {
  if (!caseId.value) return
  busy.value = true
  errorText.value = null
  try {
    await apiPost(`/api/intake/cases/${caseId.value}/initial-review`)
    await refresh()
    submitted.value = true
  } catch (e) {
    errorText.value = e instanceof ApiError ? e.message : '启动初审失败'
  } finally {
    busy.value = false
  }
}

const canStartReview = computed(() =>
  !!caseInfo.value?.requirementConfirmed && uploadedCount.value > 0 && !busy.value)

/** 新建任务：显式动作。默认名可直接用，也可改成"9 月社交媒体宣传内容审核"这类可识别名称 */
const showNew = ref(false)
const newName = ref('')

/** 来自「AI 法务助手 → 转为正式审核任务」的预填内容（AGENTS.md 第 3 条） */
const promoteSource = ref<string | null>(null)
const attachSource = ref(true)

async function createNew() {
  const name = newName.value.trim() || '新一批宣传物料审核'
  busy.value = true
  errorText.value = null
  try {
    const c = await apiPost<CaseInfo>('/api/intake/cases', { projectId: 1, name })
    caseInfo.value = c
    materials.value = []
    submitted.value = false
    setActiveCase(c.id)
    showNew.value = false
    newName.value = ''

    // 把助手带过来的文案作为一份真实文本材料提交。
    // 走浏览器构造 File + multipart：后端不需要为"一段字符串"单开接口，
    // 而且它确实会像普通上传一样落到对象存储、被解析、被定位。
    const text = promoteSource.value
    if (text && attachSource.value) {
      try {
        const form = new FormData()
        form.append('caseId', String(c.id))
        form.append('file', new File([text], '助手咨询文案.txt', { type: 'text/plain' }))
        form.append('uploadReason', '来自 AI 法务助手的咨询内容')
        const up = await apiUpload<{ materialId: number }>('/api/intake/materials/upload', form)
        await apiPost(`/api/intake/materials/${up.materialId}/parse`)
      } catch (e) {
        errorText.value = e instanceof ApiError
          ? `任务已创建，但带入助手文案失败：${e.message}`
          : '任务已创建，但带入助手文案失败，请手动上传'
      }
    }
    promoteSource.value = null
    await refresh()
  } catch (e) {
    errorText.value = e instanceof ApiError ? `新建任务失败：${e.message}` : '新建任务失败'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="view">
    <!-- ── 材料提交窗口：本模块唯一的核心操作 ───────────────────────── -->
    <section class="submit">
      <div class="submit__head">
        <div class="submit__title-row">
          <h2 class="submit__title">材料接收</h2>
          <span v-if="caseInfo" class="gw-status gw-status--ink">
            <span class="gw-status__dot" />{{ caseInfo.caseNo }} · 已提交 {{ uploadedCount }} 份
          </span>
          <span v-else-if="preparing" class="gw-status gw-status--muted">正在准备任务…</span>
        </div>
        <p class="submit__hint">
          支持图片、视频、PPT、PDF、Word 与纯文本；上传后自动解析并生成定位锚点。
        </p>
      </div>

      <FileDropzone :case-id="caseId" @uploaded="onUploaded" />

      <p v-if="errorText" class="err">{{ errorText }}</p>

      <!-- 已提交材料 -->
      <div v-if="materials.length" class="list">
        <div class="list__head">
          <span class="list__title">已提交材料</span>
          <span class="list__count">{{ materials.length }} 份</span>
        </div>
        <ul class="gw-rows">
          <li v-for="m in materials" :key="m.id" class="gw-row">
            <div class="gw-row__main">
              <span class="gw-row__title gw-truncate">{{ m.name }}</span>
              <span v-if="m.parseErrorMessage" class="gw-row__sub">{{ m.parseErrorMessage }}</span>
            </div>
            <div class="gw-row__side">
              <span class="tag">{{ typeLabel[m.materialType] ?? m.materialType }}</span>
              <span class="gw-status" :class="statusTone[m.parseStatus] ?? 'gw-status--muted'">
                {{ statusLabel[m.parseStatus] ?? m.parseStatus }}
              </span>
            </div>
          </li>
        </ul>
      </div>

      <p v-if="parseFailed.length" class="warn">
        有 {{ parseFailed.length }} 份材料解析失败，这些材料不会进入正式初审，
        请重新上传或补充内容。
      </p>

      <div v-if="submitted" class="done">
        <span class="gw-status gw-status--success"><span class="gw-status__dot" />已提交初审</span>
        <span class="done__text">材料已进入反馈区，请前往<span class="done__link">反馈区</span>处理风险。</span>
      </div>
    </section>

    <Teleport defer to="#wb-aside">
      <!-- 任务信息 -->
      <PanelCard title="任务信息" :hint="caseInfo?.caseNo">
        <dl class="gw-kv">
          <div class="gw-kv__row"><dt>任务名称</dt><dd>{{ caseInfo?.name ?? '—' }}</dd></div>
          <div class="gw-kv__row"><dt>任务编号</dt><dd class="gw-mono">{{ caseInfo?.caseNo ?? '—' }}</dd></div>
          <div class="gw-kv__row"><dt>材料数量</dt><dd>{{ uploadedCount }} 份</dd></div>
          <div class="gw-kv__row">
            <dt>当前状态</dt>
            <dd>
              <span class="gw-status gw-status--wine">
                <span class="gw-status__dot" />{{ caseInfo?.status ?? '—' }}
              </span>
            </dd>
          </div>
        </dl>

        <div v-if="showNew" class="new">
          <div v-if="promoteSource" class="new__from">
            <span class="new__from-title">来自 AI 法务助手的咨询</span>
            <p class="new__from-text">{{ promoteSource }}</p>
            <label class="new__from-check">
              <input v-model="attachSource" type="checkbox" />
              <span>把这段文案作为一份文本材料一并提交</span>
            </label>
          </div>

          <input
            v-model="newName"
            class="new__input"
            type="text"
            placeholder="例如：9 月社交媒体宣传内容审核"
            @keyup.enter="createNew"
          />
          <div class="gw-btn-row">
            <button class="gw-btn gw-btn--ghost" type="button" @click="showNew = false; promoteSource = null">
              取消
            </button>
            <button class="gw-btn gw-btn--primary" type="button" :disabled="busy" @click="createNew">
              创建
            </button>
          </div>
        </div>
        <div v-else class="gw-btn-row">
          <button
            class="gw-btn gw-btn--ghost gw-btn--block"
            type="button"
            @click="showNew = true"
          >
            新建审核任务
          </button>
        </div>
      </PanelCard>

      <!-- 审核要求：确认后才生效 -->
      <PanelCard title="审核要求" :hint="caseInfo?.requirementConfirmed ? '已确认' : '待确认'">
        <ul class="req">
          <li v-for="r in requirements" :key="r.label" class="req__item">
            <button
              class="req__box"
              :class="{ 'is-on': r.on }"
              type="button"
              :aria-pressed="r.on"
              @click="r.on = !r.on"
            >
              <svg v-if="r.on" viewBox="0 0 12 12" width="9" height="9" fill="none">
                <path d="M2.5 6.2 4.8 8.5 9.5 3.6" stroke="currentColor" stroke-width="1.8"
                  stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </button>
            <span class="req__label">{{ r.label }}</span>
          </li>
        </ul>
        <div class="gw-btn-row">
          <button
            class="gw-btn gw-btn--ghost gw-btn--block"
            type="button"
            :disabled="busy || caseInfo?.requirementConfirmed"
            @click="confirmRequirement"
          >
            {{ caseInfo?.requirementConfirmed ? '要求已确认' : '确认审核要求' }}
          </button>
        </div>
      </PanelCard>

      <!-- 提交初审 -->
      <PanelCard title="提交初审">
        <dl class="gw-kv">
          <div class="gw-kv__row"><dt>可审材料</dt><dd>{{ uploadedCount - parseFailed.length }} 份</dd></div>
          <div class="gw-kv__row"><dt>解析失败</dt><dd>{{ parseFailed.length }} 份</dd></div>
        </dl>
        <div class="gw-btn-row">
          <button
            class="gw-btn gw-btn--primary gw-btn--block"
            type="button"
            :disabled="!canStartReview"
            @click="startReview"
          >
            {{ busy ? '提交中…' : '提交并开始 AI 初审' }}
          </button>
        </div>
        <p v-if="!caseInfo?.requirementConfirmed" class="tip">需先确认审核要求</p>
        <p v-else-if="uploadedCount === 0" class="tip">需先上传至少一份材料</p>
      </PanelCard>
    </Teleport>
  </div>
</template>

<style scoped>
.view {
  display: contents;
}

/* ── 材料提交窗口 ─────────────────────────────────────────────────── */
.submit {
  max-width: 920px;
  margin: 0 auto;
}

.submit__head {
  margin-bottom: var(--gw-s4);
}

.submit__title-row {
  display: flex;
  align-items: center;
  gap: var(--gw-s4);
}

.submit__title {
  font-size: var(--gw-fs-lg);
  font-weight: 600;
  color: var(--gw-text);
  letter-spacing: -0.01em;
}

.submit__hint {
  margin-top: 6px;
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-tertiary);
  line-height: var(--gw-lh);
}

.err {
  margin-top: var(--gw-s4);
  padding: var(--gw-s3) var(--gw-s4);
  border-radius: var(--gw-r-sm);
  border-left: 3px solid var(--gw-danger);
  background: var(--gw-danger-bg);
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-secondary);
  line-height: var(--gw-lh);
}

.warn {
  margin-top: var(--gw-s4);
  padding: var(--gw-s3) var(--gw-s4);
  border-radius: var(--gw-r-sm);
  border-left: 3px solid var(--gw-warning);
  background: var(--gw-warning-bg);
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-secondary);
  line-height: var(--gw-lh);
}

.done {
  display: flex;
  align-items: center;
  gap: var(--gw-s3);
  margin-top: var(--gw-s5);
  padding: var(--gw-s4) var(--gw-s5);
  border: 1px solid var(--gw-line);
  border-left: 3px solid var(--gw-success);
  border-radius: var(--gw-r);
  background: var(--gw-surface);
  box-shadow: var(--gw-elev-1);
}

.done__text {
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-secondary);
}

.done__link {
  color: var(--gw-accent-text);
  font-weight: 500;
}

/* ── 已提交材料 ───────────────────────────────────────────────────── */
.list {
  margin-top: var(--gw-s6);
}

.list__head {
  display: flex;
  align-items: baseline;
  gap: var(--gw-s3);
  margin-bottom: var(--gw-s2);
}

.list__title {
  font-size: var(--gw-fs-md);
  font-weight: 600;
  color: var(--gw-text);
}

.list__count {
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-tertiary);
  font-variant-numeric: tabular-nums;
}

.tag {
  flex: none;
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
  padding: 0 5px;
  border: 1px solid var(--gw-line);
  border-radius: var(--gw-r-sm);
  line-height: 16px;
}

/* ── 审核要求勾选 ─────────────────────────────────────────────────── */
.req {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.req__item {
  display: flex;
  align-items: center;
  gap: var(--gw-s3);
  padding: 6px 0;
}

.req__box {
  display: grid;
  place-items: center;
  width: 15px;
  height: 15px;
  flex: none;
  padding: 0;
  border: 1px solid var(--gw-line-strong);
  border-radius: 4px;
  background: transparent;
  color: #fff;
  transition: background var(--gw-dur-fast) var(--gw-ease),
    border-color var(--gw-dur-fast) var(--gw-ease);
}

.req__box.is-on {
  background: var(--gw-accent);
  border-color: var(--gw-accent);
}

.req__label {
  font-size: var(--gw-fs-base);
  color: var(--gw-text);
}

.tip {
  margin-top: var(--gw-s3);
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
  text-align: center;
}

/* ── 新建任务 ─────────────────────────────────────────────────────── */
.new__from {
  margin-bottom: var(--gw-s4);
  padding: var(--gw-s3) var(--gw-s4);
  border-left: 3px solid var(--gw-accent);
  border-radius: var(--gw-r-sm);
  background: var(--gw-accent-faint);
}

.new__from-title {
  display: block;
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
}

.new__from-text {
  margin-top: 4px;
  font-size: var(--gw-fs-sm);
  color: var(--gw-text);
  line-height: 1.5;
  /* 助手输入可能很长：限高滚动，避免把"创建"按钮挤出视野 */
  max-height: 84px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-word;
}

.new__from-check {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: var(--gw-s3);
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-secondary);
  cursor: pointer;
}

.new__input {
  width: 100%;
  margin-bottom: var(--gw-s3);
  padding: 7px 10px;
  border: 1px solid var(--gw-line-strong);
  border-radius: var(--gw-r-sm);
  background: var(--gw-surface);
  font-size: var(--gw-fs-base);
  font-family: inherit;
  color: var(--gw-text);
}

.new__input:focus {
  outline: none;
  border-color: var(--gw-accent);
}

.new .gw-btn-row {
  justify-content: flex-end;
}
</style>
