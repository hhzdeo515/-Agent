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
import { computed, onMounted, ref } from 'vue'
import FileDropzone from '@/components/FileDropzone.vue'
import PanelCard from '@/components/PanelCard.vue'
import { ApiError, apiGet, apiPost } from '@/api/client'

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
  void ensureCase()
})

/** 准备任务：进入即可上传，不需要用户先手动点"新建" */
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
  } catch (e) {
    errorText.value = e instanceof ApiError
      ? `任务准备失败：${e.message}`
      : '任务准备失败，请确认后端服务已启动（http://localhost:8080）'
  } finally {
    preparing.value = false
  }
}

async function refresh() {
  if (!caseId.value) return
  try {
    const d = await apiGet<{ case: CaseInfo; materials: MaterialItem[] }>(
      `/api/intake/cases/${caseId.value}`,
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
</style>
