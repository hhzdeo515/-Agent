<script setup lang="ts">
/**
 * 反馈区（第一次 AI 全量初审）
 *
 * 责任：一批材料的第一次 AI 全量初审 —— 发现风险、分类风险、定位风险。
 * 边界：不管 V1/V2/V3 整改版本、不做 Diff、不做审批与关闭。
 *
 * ── 这一版的交互取舍（为什么不是"卡片列表 + 弹窗操作"）────────────────
 * 法务在这里的真实动作是<b>连续判断几十条风险</b>，所以界面必须为"逐条、快速、
 * 可回溯"服务，而不是为"好看地展示详情"服务：
 *  1. 三分类是<b>同一个整体的划分</b>（三者之和 = 参与初审物料数），
 *     因此用一条构成条表达，而不是四张等大卡片——后者会让人误以为
 *     解析失败也属于那个整体（它恰恰不计入通过率分母）。
 *  2. 工作清单<b>按物料分组</b>：法务要对着那张海报、那段视频做判断，
 *     而不是对着一个脱离材料的风险条目。
 *  3. 每条风险的"等级 / 类型 / 原文 / 位置"<b>直接可见</b>，不需要先展开；
 *     展开只用于看上下文与操作。
 *  4. 判断动作<b>内联展开</b>，不弹模态：模态会遮住刚看过的那句话，
 *     而法务恰恰需要一边看着原文一边写理由。
 *  5. 判断依据是<b>原文上下文</b>而不只是一句位置描述——"删掉这三个字之后
 *     整句还通不通"决定了怎么改（AGENTS.md 第 5 条）。
 */
import { computed, onMounted, ref, watch } from 'vue'
import PanelCard from '@/components/PanelCard.vue'
import GwModal from '@/components/GwModal.vue'
import { ApiError, apiGet, apiPost } from '@/api/client'
import { useActiveCase, useCaseEpoch, type CaseBrief } from '@/stores/activeCase'
import { renderMarkdown } from '@/utils/markdown'

// ── 类型 ──────────────────────────────────────────────────────────────

interface MaterialClass {
  materialId: number
  materialName: string
  materialType: string
  parseStatus: string
  initialReviewClass: string
  riskCount: number
  highRiskCount: number
  openBlockingRiskCount: number
}

interface CaseSummary {
  caseId: number
  caseNo: string
  caseName: string
  caseStatus: string
  reviewedMaterialCount: number
  parseFailedCount: number
  totalMaterialCount: number
  initialPassCount: number
  pendingHumanCount: number
  riskFailCount: number
  highRiskItems: number
  mediumRiskItems: number
  lowRiskItems: number
  openBlockingRiskItems: number
  initialPassRateByMaterial: number | null
}

interface SummaryResp {
  materials: MaterialClass[]
  summary: CaseSummary
  riskTypeDistribution: { riskType: string; riskTypeLabel: string; count: number }[]
  rateDefinition: string
}

interface RiskRow {
  id: number
  riskNo: string
  caseId: number
  materialId: number
  riskType: string
  riskTypeLabel: string
  riskLevel: 'HIGH' | 'MEDIUM' | 'LOW'
  confidence: number
  status: string
  riskText: string
  locationDesc: string
  reason: string
  suggestion: string
  recommendedCopy: string
  requiredEvidence: string
  blocked: number
  assigneeId: number | null
  firstVersionId: number | null
  currentVersionId: number | null
  basisText: string | null
}

interface ContextLine {
  anchorId: string
  anchorType: string
  text: string
  ordinal: number | null
  locator: string
  confidence: number | null
  hit: boolean
}

interface ContextResp {
  canLocate: boolean
  reason?: string
  lines: ContextLine[]
  locatorKind?: string
  regionHintLabel?: string | null
  versionLabel?: string
  hitCount?: number
}

interface Assignee {
  id: number
  username: string
  displayName: string
  roleCodes: string | null
}

interface ReportView {
  exists: boolean
  report?: {
    revisionNo: number
    contentMd: string
    contentHash: string
    modelId: string
    pipelineVersion: string
    promptVersion: string
    generatedAt: string
  }
  rateDefinition: string
}

type ActionKind = 'confirm' | 'false-positive' | 'request-evidence' | 'to-remediation'

// ── 状态 ──────────────────────────────────────────────────────────────

const { resolveActiveCase, activeCase } = useActiveCase()

const summary = ref<CaseSummary | null>(null)
const materials = ref<MaterialClass[]>([])
const risks = ref<RiskRow[]>([])
const distribution = ref<SummaryResp['riskTypeDistribution']>([])
const rateDefinition = ref('')

const loading = ref(false)
const loaded = ref(false)
const errorText = ref<string | null>(null)
const actionError = ref<string | null>(null)
const busy = ref(false)

const openId = ref<number | null>(null)
const context = ref<Record<number, ContextResp>>({})
const contextLoading = ref<number | null>(null)

const filterLevel = ref<'' | 'HIGH' | 'MEDIUM' | 'LOW'>('')
const filterType = ref('')
const filterStatus = ref('')

/** 内联判断区：同一条风险同时只展开一个动作 */
const pending = ref<{ riskId: number; kind: ActionKind } | null>(null)
const actionText = ref('')
const actionAssignee = ref<number | null>(null)
const actionDueAt = ref('')
const assignees = ref<Assignee[]>([])

const report = ref<ReportView | null>(null)
const reportOpen = ref(false)
const reportBusy = ref(false)

// ── 文案映射 ──────────────────────────────────────────────────────────

const LEVEL_RANK: Record<string, number> = { HIGH: 3, MEDIUM: 2, LOW: 1 }
const LEVEL_LABEL: Record<string, string> = { HIGH: '高', MEDIUM: '中', LOW: '低' }
const LEVEL_CLASS: Record<string, string> = {
  HIGH: 'gw-level--high',
  MEDIUM: 'gw-level--medium',
  LOW: 'gw-level--low',
}
const LEVEL_FILTERS: { value: '' | 'HIGH' | 'MEDIUM' | 'LOW'; text: string }[] = [
  { value: '', text: '全部' },
  { value: 'HIGH', text: '高' },
  { value: 'MEDIUM', text: '中' },
  { value: 'LOW', text: '低' },
]
const STATUS_LABEL: Record<string, string> = {
  OPEN: '新建',
  PENDING_LEGAL_DECISION: '待人工判断',
  CONFIRMED: '法务已确认',
  AWAITING_EVIDENCE: '等待补充材料',
  AWAITING_REVISION: '整改中',
  RESUBMITTED: '已重新提交',
  AI_REREVIEW: 'AI 复审中',
  LEGAL_FINAL_REVIEW: '法务终审',
  CLOSED: '已关闭',
  REJECTED_FALSE_POSITIVE: '误判留痕',
}
const STATUS_TONE: Record<string, string> = {
  OPEN: 'gw-status--ink',
  PENDING_LEGAL_DECISION: 'gw-status--brass',
  CONFIRMED: 'gw-status--ink',
  AWAITING_EVIDENCE: 'gw-status--brass',
  AWAITING_REVISION: 'gw-status--muted',
  RESUBMITTED: 'gw-status--ink',
  AI_REREVIEW: 'gw-status--muted',
  LEGAL_FINAL_REVIEW: 'gw-status--wine',
  CLOSED: 'gw-status--success',
  REJECTED_FALSE_POSITIVE: 'gw-status--muted',
}
const CLASS_LABEL: Record<string, string> = {
  INITIAL_PASS: 'AI 初审通过',
  PENDING_HUMAN: '待人工判断',
  RISK_FAIL: '风险未通过',
  NOT_REVIEWED: '未参与初审',
}
const CLASS_TONE: Record<string, string> = {
  INITIAL_PASS: 'gw-status--success',
  PENDING_HUMAN: 'gw-status--brass',
  RISK_FAIL: 'gw-status--danger',
  NOT_REVIEWED: 'gw-status--muted',
}
const TYPE_LABEL: Record<string, string> = {
  IMAGE: '图片',
  VIDEO: '视频',
  TEXT: '文本',
  PPT: 'PPT',
  PDF: 'PDF',
  WORD: 'Word',
}
const LOCATOR_LABEL: Record<string, string> = {
  IMAGE_REGION: '按画面框选区域定位',
  VIDEO_TIMECODE: '按视频时间码定位',
  DOC_LINE: '按文档行定位',
  KEY_FRAME: '按关键帧定位',
  MIXED: '多种定位方式混合',
  UNKNOWN: '定位方式未知',
}
const ACTION_META: Record<
  ActionKind,
  { label: string; cta: string; placeholder: string }
> = {
  confirm: {
    label: '确认理由',
    cta: '确认风险',
    placeholder: '例如：该表述属于绝对化用语且无第三方依据，确认存在风险。',
  },
  'false-positive': {
    label: '误判理由（必填，记录不删除）',
    cta: '标记误判',
    placeholder: '例如：此处"第一"指企业内部评选，且同页已标注依据。',
  },
  'request-evidence': {
    label: '需要补充的证明材料',
    cta: '要求补充材料',
    placeholder: '例如：近 12 个月第三方检测报告，需含检测机构与报告编号。',
  },
  'to-remediation': {
    label: '整改说明',
    cta: '转入整改',
    placeholder: '例如：请品牌部 3 个工作日内替换主标题并回传。',
  },
}
/** 尚未作出法务判断的状态：反馈区的工作量就是这些 */
const UNHANDLED = new Set(['OPEN', 'PENDING_LEGAL_DECISION'])

// ── 派生 ──────────────────────────────────────────────────────────────

const materialById = computed(() => {
  const m = new Map<number, MaterialClass>()
  for (const x of materials.value) m.set(x.materialId, x)
  return m
})

const pendingCount = computed(() => risks.value.filter((r) => UNHANDLED.has(r.status)).length)
const handleRate = computed(() => {
  const total = risks.value.length
  return total === 0 ? 0 : Math.round(((total - pendingCount.value) / total) * 100)
})
const maxLevelCount = computed(() =>
  Math.max(1, summary.value?.highRiskItems ?? 0, summary.value?.mediumRiskItems ?? 0,
    summary.value?.lowRiskItems ?? 0),
)

const typeOptions = computed(() => {
  const seen = new Map<string, string>()
  for (const r of risks.value) seen.set(r.riskType, r.riskTypeLabel)
  return [...seen.entries()].map(([value, label]) => ({ value, label }))
})

const statusOptions = computed(() => {
  const seen = new Map<string, string>()
  for (const r of risks.value) {
    if (!seen.has(r.status)) seen.set(r.status, STATUS_LABEL[r.status] ?? r.status)
  }
  return [...seen.entries()].map(([value, label]) => ({ value, label }))
})

const filteredRisks = computed(() =>
  risks.value.filter(
    (r) =>
      (!filterLevel.value || r.riskLevel === filterLevel.value) &&
      (!filterType.value || r.riskType === filterType.value) &&
      (!filterStatus.value || r.status === filterStatus.value),
  ),
)

/**
 * 按物料分组，风险最高的物料排在最前。
 *
 * 组内按风险等级降序：法务从最严重的看起，不用在一屏里自己找。
 * 物料内部再按风险编号，保证同一份材料的顺序稳定、可对账。
 */
const groups = computed(() => {
  const byMaterial = new Map<number, RiskRow[]>()
  for (const r of filteredRisks.value) {
    const list = byMaterial.get(r.materialId)
    if (list) list.push(r)
    else byMaterial.set(r.materialId, [r])
  }
  return [...byMaterial.entries()]
    .map(([materialId, list]) => {
      const sorted = [...list].sort((a, b) => {
        const d = (LEVEL_RANK[b.riskLevel] ?? 0) - (LEVEL_RANK[a.riskLevel] ?? 0)
        return d !== 0 ? d : a.riskNo.localeCompare(b.riskNo)
      })
      return {
        materialId,
        material: materialById.value.get(materialId) ?? null,
        risks: sorted,
        topRank: Math.max(...sorted.map((r) => LEVEL_RANK[r.riskLevel] ?? 0)),
        highCount: sorted.filter((r) => r.riskLevel === 'HIGH').length,
        pendingCount: sorted.filter((r) => UNHANDLED.has(r.status)).length,
      }
    })
    .sort((a, b) => b.topRank - a.topRank || a.materialId - b.materialId)
})

/** 三分类构成：三类之和 = 参与初审物料数（解析失败不在此列，单独一行） */
const composition = computed(() => {
  const s = summary.value
  if (!s) return []
  const total = s.reviewedMaterialCount || 0
  return [
    { key: 'pass', label: 'AI 初审通过', count: s.initialPassCount, cls: 'ok' },
    { key: 'pending', label: '待人工判断', count: s.pendingHumanCount, cls: 'warn' },
    { key: 'fail', label: '风险未通过', count: s.riskFailCount, cls: 'err' },
  ].map((r) => ({ ...r, pct: total === 0 ? 0 : (r.count / total) * 100 }))
})

const passRateText = computed(() => {
  const v = summary.value?.initialPassRateByMaterial
  return v == null ? '—' : `${(v * 100).toFixed(1)}%`
})

const distMax = computed(() => Math.max(1, ...distribution.value.map((d) => d.count)))

const reportHtml = computed(() =>
  report.value?.report ? renderMarkdown(report.value.report.contentMd) : '',
)

const canGenerateReport = computed(
  () => materials.value.length > 0 && materials.value.some((m) => m.parseStatus !== 'FAILED'),
)

const emptyRisksHint = computed(() => {
  if (materials.value.length === 0) {
    return '本任务还没有材料。请到「接收区」上传物料并点击「提交并开始 AI 初审」。'
  }
  if (risks.value.length > 0) return '当前筛选条件下没有风险记录。'
  if (summary.value?.reviewedMaterialCount === 0) {
    return '本批材料尚未完成解析，暂时无法初审。请在接收区查看解析失败的物料。'
  }
  return '本批材料第一次 AI 初审未发现风险。这不等于法务最终批准。'
})

/** 该风险当前状态下允许哪些人工动作——与后端 @PreAuthorize 对应，但**不是**安全边界 */
function canAct(r: RiskRow, kind: ActionKind) {
  if (r.status === 'CLOSED') return false
  switch (kind) {
    case 'confirm':
      return r.status === 'OPEN' || r.status === 'PENDING_LEGAL_DECISION'
    case 'false-positive':
      return r.status !== 'REJECTED_FALSE_POSITIVE'
    case 'request-evidence':
      return r.status !== 'AWAITING_EVIDENCE'
    case 'to-remediation':
      return r.status === 'CONFIRMED' || r.status === 'AWAITING_EVIDENCE'
  }
}

// ── 上下文高亮 ────────────────────────────────────────────────────────

function escapeHtml(s: string) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

/**
 * 在上下文行里高亮风险原文。
 *
 * 先转义再替换，因此物料原文里的尖括号不可能变成可执行节点——
 * 这段内容来自用户上传的文件，是最不该信任的输入。
 */
function highlight(line: ContextLine, riskText: string): string {
  const safe = escapeHtml(line.text ?? '')
  const needle = (riskText ?? '').trim()
  if (!needle) return safe
  const safeNeedle = escapeHtml(needle)
  if (!safe.includes(safeNeedle)) return safe
  return safe.split(safeNeedle).join(`<mark class="hl">${safeNeedle}</mark>`)
}

// ── 数据加载 ──────────────────────────────────────────────────────────

onMounted(() => {
  void load()
})

watch(useCaseEpoch(), () => {
  openId.value = null
  context.value = {}
  pending.value = null
  void load()
})

async function load() {
  loading.value = true
  errorText.value = null
  try {
    const c = await resolveActiveCase()
    if (!c) {
      errorText.value = '还没有任何审核任务。请先到「接收区」提交材料并启动 AI 初审。'
      loaded.value = true
      return
    }
    await loadCase(c)
  } catch (e) {
    errorText.value = describe(e, '加载初审结果失败')
  } finally {
    loading.value = false
    loaded.value = true
  }
}

async function loadCase(c: CaseBrief) {
  const [s, r] = await Promise.all([
    apiGet<SummaryResp>(`/api/feedback/cases/${c.id}/summary`),
    apiGet<RiskRow[]>(`/api/feedback/risks?caseId=${c.id}`),
  ])
  summary.value = s.summary
  materials.value = s.materials ?? []
  distribution.value = s.riskTypeDistribution ?? []
  rateDefinition.value = s.rateDefinition ?? ''
  risks.value = r ?? []
  void openRiskFromUrl()
}

/**
 * 深链打开某条风险：{@code #/feedback?case=16&risk=42}。
 *
 * 用途是把"这一条需要你确认"直接发给同事，对方打开就落在同一条风险上，
 * 而不是自己在一屏里找。也让"我当时看的是哪条"在排查时可复现。
 */
async function openRiskFromUrl() {
  const m = /[?&]risk=(\d+)/.exec(window.location.hash)
  if (!m) return
  const id = Number(m[1])
  const target = risks.value.find((x) => x.id === id)
  if (target) await toggle(target)
}

async function refresh() {
  actionError.value = null
  const c = activeCase.value
  if (!c) {
    await load()
    return
  }
  loading.value = true
  try {
    await loadCase(c)
  } catch (e) {
    errorText.value = describe(e, '刷新失败')
  } finally {
    loading.value = false
  }
}

function describe(e: unknown, fallback: string): string {
  if (e instanceof ApiError) return `${fallback}：${e.message}`
  if (e instanceof Error) return `${fallback}：${e.message}`
  return fallback
}

// ── 行展开与上下文 ────────────────────────────────────────────────────

async function toggle(r: RiskRow) {
  pending.value = null
  actionError.value = null
  if (openId.value === r.id) {
    openId.value = null
    return
  }
  openId.value = r.id
  if (!context.value[r.id]) {
    contextLoading.value = r.id
    try {
      context.value[r.id] = await apiGet<ContextResp>(`/api/feedback/risks/${r.id}/context`)
    } catch (e) {
      context.value[r.id] = {
        canLocate: false,
        reason: describe(e, '定位信息加载失败'),
        lines: [],
      }
    } finally {
      contextLoading.value = null
    }
  }
}

// ── 内联判断动作 ──────────────────────────────────────────────────────

async function startAction(r: RiskRow, kind: ActionKind) {
  actionError.value = null
  if (pending.value?.riskId === r.id && pending.value.kind === kind) {
    pending.value = null
    return
  }
  pending.value = { riskId: r.id, kind }
  actionText.value = kind === 'request-evidence' ? r.requiredEvidence ?? '' : ''
  actionDueAt.value = ''
  if (kind === 'to-remediation' && assignees.value.length === 0) {
    try {
      assignees.value = await apiGet<Assignee[]>('/api/feedback/assignees')
    } catch {
      /* 取不到责任方列表时仍允许提交，由后端校验 id */
    }
  }
  if (kind === 'to-remediation') actionAssignee.value = assignees.value[0]?.id ?? null
}

async function submitAction(r: RiskRow) {
  const p = pending.value
  if (!p || p.riskId !== r.id) return
  const text = actionText.value.trim()
  if (!text) {
    actionError.value = '请填写理由后再提交'
    return
  }
  busy.value = true
  actionError.value = null
  try {
    switch (p.kind) {
      case 'confirm':
        await apiPost(`/api/feedback/risks/${r.id}/confirm`, { opinion: text })
        break
      case 'false-positive':
        await apiPost(`/api/feedback/risks/${r.id}/false-positive`, { opinion: text })
        break
      case 'request-evidence':
        await apiPost(`/api/feedback/risks/${r.id}/request-evidence`, { requiredEvidence: text })
        break
      case 'to-remediation':
        if (!actionAssignee.value) {
          actionError.value = '请选择整改责任方'
          busy.value = false
          return
        }
        await apiPost(`/api/feedback/risks/${r.id}/to-remediation`, {
          assigneeId: actionAssignee.value,
          dueAt: actionDueAt.value ? `${actionDueAt.value}T18:00:00` : null,
          note: text,
        })
        break
    }
    pending.value = null
    await refresh()
  } catch (e) {
    actionError.value = describe(e, '操作失败')
  } finally {
    busy.value = false
  }
}

// ── AI 初审报告 ───────────────────────────────────────────────────────

async function openReport(generate: boolean) {
  const c = activeCase.value
  if (!c) return
  reportBusy.value = true
  actionError.value = null
  try {
    report.value = generate
      ? await apiPost<ReportView>(`/api/feedback/cases/${c.id}/report`)
      : await apiGet<ReportView>(`/api/feedback/cases/${c.id}/report`)
    reportOpen.value = true
  } catch (e) {
    actionError.value = describe(e, generate ? '报告生成失败' : '报告获取失败')
  } finally {
    reportBusy.value = false
  }
}

function copyReport() {
  const md = report.value?.report?.contentMd
  if (md) void navigator.clipboard?.writeText(md)
}

/** 导出为 .md 文件：导出内容就是落库的那一版，否则事后无法还原当时看到的报告 */
function exportReport() {
  const r = report.value?.report
  if (!r) return
  const blob = new Blob([r.contentMd], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${summary.value?.caseNo ?? 'case'}-AI初审报告-r${r.revisionNo}.md`
  a.click()
  URL.revokeObjectURL(url)
}
</script>

<template>
  <div class="view">
    <!-- 加载骨架：不用转圈占位。骨架让人知道"马上会出现什么形状的内容" -->
    <div v-if="loading && !loaded" class="sk">
      <div class="sk__bar" />
      <div v-for="i in 4" :key="i" class="sk__row" />
    </div>

    <!-- 空态：告诉用户下一步去哪，而不是只说"没有数据" -->
    <section v-else-if="errorText && !summary" class="empty">
      <p class="empty__text">{{ errorText }}</p>
      <button class="gw-btn gw-btn--ghost" type="button" @click="load">重新加载</button>
    </section>

    <template v-else>
      <!-- ── 初审构成 ─────────────────────────────────────────────────
           三分类是同一个整体的划分，用一条构成条表达。
           解析失败不属于这个整体（不计入通过率分母），因此单列。 -->
      <section class="board">
        <header class="board__head">
          <h2 class="board__title">初审构成</h2>
          <span class="board__case gw-mono">{{ summary?.caseNo }}</span>
          <button
            class="gw-btn gw-btn--ghost gw-btn--xs board__refresh"
            type="button"
            :disabled="loading"
            @click="refresh"
          >
            {{ loading ? '刷新中…' : '刷新' }}
          </button>
        </header>

        <div class="board__body">
          <div class="geo">
            <div class="geo__bar" role="img" aria-label="物料初审分类构成">
              <span
                v-for="c in composition"
                :key="c.key"
                class="geo__seg"
                :class="`geo__seg--${c.cls}`"
                :style="{ width: `${c.pct}%` }"
                :title="`${c.label} ${c.count} 份`"
              />
              <span
                v-if="!summary?.reviewedMaterialCount"
                class="geo__seg geo__seg--none"
                style="width: 100%"
              />
            </div>
            <dl class="geo__legend">
              <div v-for="c in composition" :key="c.key" class="geo__item">
                <span class="geo__dot" :class="`geo__dot--${c.cls}`" />
                <dt>{{ c.label }}</dt>
                <dd>
                  <b class="gw-mono">{{ c.count }}</b>
                  <span class="geo__pct gw-mono">{{ c.pct.toFixed(0) }}%</span>
                </dd>
              </div>
            </dl>
          </div>

          <div class="rate">
            <span class="rate__label">物料层初审通过率</span>
            <span class="rate__value gw-mono">{{ passRateText }}</span>
            <span class="rate__sub gw-mono">
              {{ summary?.initialPassCount ?? 0 }} / {{ summary?.reviewedMaterialCount ?? 0 }} 份
            </span>
          </div>
        </div>

        <p class="board__meta">
          <span>
            参与初审 <b class="gw-mono">{{ summary?.reviewedMaterialCount ?? 0 }}</b> 份（通过率分母）
          </span>
          <span class="board__sep">·</span>
          <span>
            解析失败 <b class="gw-mono">{{ summary?.parseFailedCount ?? 0 }}</b> 份，不计入分母
          </span>
          <span class="board__sep">·</span>
          <span>风险记录 <b class="gw-mono">{{ risks.length }}</b> 条</span>
        </p>
        <p class="board__def">{{ rateDefinition }}</p>
      </section>

      <!-- ── 工作清单 ─────────────────────────────────────────────── -->
      <section class="work">
        <header class="work__head">
          <h2 class="work__title">风险工作清单</h2>
          <div v-if="risks.length" class="work__prog">
            <span class="work__prog-bar"><i :style="{ width: `${handleRate}%` }" /></span>
            <span class="work__prog-text gw-mono">
              已处理 {{ risks.length - pendingCount }} / {{ risks.length }}
            </span>
            <span v-if="pendingCount" class="gw-status gw-status--brass">
              <span class="gw-status__dot" />待处理 {{ pendingCount }}
            </span>
            <span v-else class="gw-status gw-status--success">
              <span class="gw-status__dot" />本批已全部判断
            </span>
          </div>
        </header>

        <!-- 三组独立筛选：分别对应等级、类型、状态三个维度（不得混用） -->
        <div class="filters">
          <div class="filters__group">
            <span class="filters__label">等级</span>
            <button
              v-for="lv in LEVEL_FILTERS"
              :key="lv.value"
              class="chip"
              :class="{ 'is-on': filterLevel === lv.value }"
              type="button"
              @click="filterLevel = lv.value"
            >
              {{ lv.text }}
            </button>
          </div>
          <div class="filters__group">
            <span class="filters__label">类型</span>
            <button
              class="chip"
              :class="{ 'is-on': !filterType }"
              type="button"
              @click="filterType = ''"
            >
              全部
            </button>
            <button
              v-for="t in typeOptions"
              :key="t.value"
              class="chip"
              :class="{ 'is-on': filterType === t.value }"
              type="button"
              @click="filterType = t.value"
            >
              {{ t.label }}
            </button>
          </div>
          <div class="filters__group">
            <span class="filters__label">状态</span>
            <button
              class="chip"
              :class="{ 'is-on': !filterStatus }"
              type="button"
              @click="filterStatus = ''"
            >
              全部
            </button>
            <button
              v-for="s in statusOptions"
              :key="s.value"
              class="chip"
              :class="{ 'is-on': filterStatus === s.value }"
              type="button"
              @click="filterStatus = s.value"
            >
              {{ s.label }}
            </button>
          </div>
        </div>

        <p v-if="actionError && !pending" class="err">{{ actionError }}</p>

        <!-- 按物料分组：法务是对着材料做判断的 -->
        <div v-if="groups.length" class="groups">
          <section v-for="g in groups" :key="g.materialId" class="grp">
            <header class="grp__head">
              <span class="grp__name gw-truncate">
                {{ g.material?.materialName ?? `物料 #${g.materialId}` }}
              </span>
              <span class="tag">
                {{ TYPE_LABEL[g.material?.materialType ?? ''] ?? g.material?.materialType }}
              </span>
              <span
                class="gw-status"
                :class="CLASS_TONE[g.material?.initialReviewClass ?? ''] ?? 'gw-status--muted'"
              >
                {{ CLASS_LABEL[g.material?.initialReviewClass ?? ''] ?? g.material?.initialReviewClass }}
              </span>
              <span class="grp__count gw-mono">
                {{ g.risks.length }} 条<template v-if="g.highCount"> · 高 {{ g.highCount }}</template>
              </span>
              <span v-if="g.pendingCount" class="grp__todo gw-mono">待处理 {{ g.pendingCount }}</span>
            </header>

            <ul class="rk">
              <li
                v-for="r in g.risks"
                :key="r.id"
                class="rk__item"
                :class="{ 'is-open': openId === r.id, 'is-done': !UNHANDLED.has(r.status) }"
              >
                <!-- 概要行：等级 / 类型 / 原文 / 位置 直接可见，不用先展开 -->
                <button class="rk__row" type="button" @click="toggle(r)">
                  <span class="gw-level" :class="LEVEL_CLASS[r.riskLevel]">
                    {{ LEVEL_LABEL[r.riskLevel] }}
                  </span>
                  <span class="rk__type">{{ r.riskTypeLabel }}</span>
                  <span class="rk__text gw-truncate">{{ r.riskText }}</span>
                  <span
                    v-if="!r.basisText"
                    class="rk__flag"
                    title="未检索到可引用依据，已强制转人工判断"
                  >
                    依据不足
                  </span>
                  <span class="rk__loc gw-truncate">{{ r.locationDesc ?? '未记录位置' }}</span>
                  <span class="gw-status" :class="STATUS_TONE[r.status] ?? 'gw-status--muted'">
                    <span class="gw-status__dot" />{{ STATUS_LABEL[r.status] ?? r.status }}
                  </span>
                  <svg
                    class="rk__caret"
                    :class="{ 'is-open': openId === r.id }"
                    viewBox="0 0 10 6"
                    width="9"
                    height="6"
                    fill="none"
                  >
                    <path d="M1 1.2 5 4.8 9 1.2" stroke="currentColor" stroke-width="1.4"
                      stroke-linecap="round" stroke-linejoin="round" />
                  </svg>
                </button>

                <!-- 展开：原文上下文 → 判断依据 → 建议 → 动作 -->
                <div v-if="openId === r.id" class="rk__detail">
                  <div class="sect">
                    <div class="sect__head">
                      <span class="sect__title">原文定位</span>
                      <span v-if="context[r.id]?.versionLabel" class="sect__hint gw-mono">
                        {{ context[r.id]?.versionLabel }}
                      </span>
                      <span v-if="context[r.id]?.locatorKind" class="sect__hint">
                        {{ LOCATOR_LABEL[context[r.id]?.locatorKind ?? ''] ?? context[r.id]?.locatorKind }}
                      </span>
                    </div>

                    <p v-if="contextLoading === r.id" class="sect__loading">正在读取原文…</p>
                    <template v-else-if="context[r.id]?.canLocate">
                      <ol class="ctx">
                        <li
                          v-for="l in context[r.id]!.lines"
                          :key="l.anchorId"
                          class="ctx__line"
                          :class="{ 'is-hit': l.hit }"
                        >
                          <span class="ctx__no gw-mono">{{ l.ordinal ?? '–' }}</span>
                          <span class="ctx__text" v-html="highlight(l, r.riskText)" />
                        </li>
                      </ol>
                      <p class="sect__note">
                        命中 <b class="gw-mono">{{ context[r.id]?.hitCount }}</b> 处；
                        上下各展开了 2 行，便于判断删改后整句是否仍通顺。
                      </p>
                    </template>
                    <p v-else class="sect__note sect__note--warn">
                      {{ context[r.id]?.reason ?? '暂无锚点信息' }}
                    </p>
                  </div>

                  <dl class="gw-kv">
                    <div class="gw-kv__row">
                      <dt>风险原因</dt>
                      <dd>{{ r.reason ?? '—' }}</dd>
                    </div>
                    <div class="gw-kv__row">
                      <dt>审核依据</dt>
                      <dd>
                        <span v-if="r.basisText" class="basis">{{ r.basisText }}</span>
                        <span v-else class="basis basis--none">
                          未检索到可引用的现行规则，本条目为待人工判断，不能据此直接下结论。
                        </span>
                      </dd>
                    </div>
                    <div class="gw-kv__row">
                      <dt>修改建议</dt>
                      <dd>{{ r.suggestion ?? '—' }}</dd>
                    </div>
                    <div class="gw-kv__row">
                      <dt>推荐表达</dt>
                      <dd>{{ r.recommendedCopy ?? '—' }}</dd>
                    </div>
                    <div class="gw-kv__row">
                      <dt>所需材料</dt>
                      <dd>{{ r.requiredEvidence ?? '—' }}</dd>
                    </div>
                  </dl>

                  <!-- 动作内联展开：不弹模态，法务可以一边看原文一边写理由 -->
                  <div class="acts">
                    <button
                      v-if="canAct(r, 'confirm')"
                      class="gw-btn"
                      :class="pending?.riskId === r.id && pending?.kind === 'confirm'
                        ? 'gw-btn--primary' : 'gw-btn--ghost'"
                      type="button"
                      @click="startAction(r, 'confirm')"
                    >
                      确认风险
                    </button>
                    <button
                      v-if="canAct(r, 'false-positive')"
                      class="gw-btn"
                      :class="pending?.riskId === r.id && pending?.kind === 'false-positive'
                        ? 'gw-btn--primary' : 'gw-btn--ghost'"
                      type="button"
                      @click="startAction(r, 'false-positive')"
                    >
                      标记误判
                    </button>
                    <button
                      v-if="canAct(r, 'request-evidence')"
                      class="gw-btn"
                      :class="pending?.riskId === r.id && pending?.kind === 'request-evidence'
                        ? 'gw-btn--primary' : 'gw-btn--ghost'"
                      type="button"
                      @click="startAction(r, 'request-evidence')"
                    >
                      要求补充材料
                    </button>
                    <button
                      v-if="canAct(r, 'to-remediation')"
                      class="gw-btn"
                      :class="pending?.riskId === r.id && pending?.kind === 'to-remediation'
                        ? 'gw-btn--primary' : 'gw-btn--ghost'"
                      type="button"
                      @click="startAction(r, 'to-remediation')"
                    >
                      转入整改
                    </button>
                    <span v-if="r.status === 'AWAITING_REVISION'" class="acts__tip">
                      已转入终审区，版本与复审在终审区处理
                    </span>
                  </div>

                  <div v-if="pending?.riskId === r.id" class="form">
                    <div v-if="pending.kind === 'to-remediation'" class="form__grid">
                      <label class="form__field">
                        <span class="form__label">整改责任方</span>
                        <select v-model="actionAssignee" class="form__select">
                          <option v-for="u in assignees" :key="u.id" :value="u.id">
                            {{ u.displayName }}（{{ u.username }}<template v-if="u.roleCodes"> · {{ u.roleCodes }}</template>）
                          </option>
                        </select>
                      </label>
                      <label class="form__field">
                        <span class="form__label">要求完成时间</span>
                        <input v-model="actionDueAt" class="form__input" type="date" />
                      </label>
                    </div>

                    <label class="form__field">
                      <span class="form__label">{{ ACTION_META[pending.kind].label }}</span>
                      <textarea
                        v-model="actionText"
                        class="form__textarea"
                        rows="3"
                        :placeholder="ACTION_META[pending.kind].placeholder"
                      />
                    </label>

                    <p v-if="actionError" class="err err--inline">{{ actionError }}</p>

                    <div class="form__acts">
                      <button class="gw-btn gw-btn--ghost" type="button" @click="pending = null">
                        取消
                      </button>
                      <button
                        class="gw-btn gw-btn--primary"
                        type="button"
                        :disabled="busy"
                        @click="submitAction(r)"
                      >
                        {{ busy ? '提交中…' : ACTION_META[pending.kind].cta }}
                      </button>
                    </div>
                  </div>
                </div>
              </li>
            </ul>
          </section>
        </div>

        <p v-else class="none">{{ emptyRisksHint }}</p>
      </section>
    </template>

    <Teleport defer to="#wb-aside">
      <PanelCard title="任务信息" :hint="summary?.caseNo">
        <dl class="gw-kv">
          <div class="gw-kv__row"><dt>任务名称</dt><dd>{{ summary?.caseName ?? '—' }}</dd></div>
          <div class="gw-kv__row">
            <dt>任务状态</dt><dd class="gw-mono">{{ summary?.caseStatus ?? '—' }}</dd>
          </div>
          <div class="gw-kv__row"><dt>物料总数</dt><dd>{{ summary?.totalMaterialCount ?? 0 }} 份</dd></div>
          <div class="gw-kv__row"><dt>参与初审</dt><dd>{{ summary?.reviewedMaterialCount ?? 0 }} 份</dd></div>
          <div class="gw-kv__row">
            <dt>未关闭阻断</dt>
            <dd>
              <span :class="{ 'is-alert': (summary?.openBlockingRiskItems ?? 0) > 0 }">
                {{ summary?.openBlockingRiskItems ?? 0 }} 条
              </span>
            </dd>
          </div>
        </dl>
      </PanelCard>

      <PanelCard title="风险等级" :hint="`${risks.length} 条`">
        <ul class="dist">
          <li
            v-for="d in [
              { key: 'high', label: '高', count: summary?.highRiskItems ?? 0 },
              { key: 'medium', label: '中', count: summary?.mediumRiskItems ?? 0 },
              { key: 'low', label: '低', count: summary?.lowRiskItems ?? 0 },
            ]"
            :key="d.key"
            class="dist__row"
          >
            <span class="gw-level" :class="`gw-level--${d.key}`">{{ d.label }}</span>
            <span class="dist__bar">
              <i
                :style="{
                  width: `${(d.count / maxLevelCount) * 100}%`,
                  background: `var(--gw-risk-${d.key})`,
                }"
              />
            </span>
            <span class="dist__num gw-mono">{{ d.count }}</span>
          </li>
        </ul>
      </PanelCard>

      <PanelCard title="风险类型" :hint="`${distribution.length} 类`">
        <ul v-if="distribution.length" class="dist">
          <li v-for="d in distribution" :key="d.riskType" class="dist__row dist__row--type">
            <span class="dist__type gw-truncate">{{ d.riskTypeLabel }}</span>
            <span class="dist__bar">
              <i :style="{ width: `${(d.count / distMax) * 100}%`, background: 'var(--gw-accent)' }" />
            </span>
            <span class="dist__num gw-mono">{{ d.count }}</span>
          </li>
        </ul>
        <p v-else class="none none--sm">未识别出风险</p>
      </PanelCard>

      <PanelCard title="AI 初审报告">
        <div class="gw-btn-row">
          <button
            class="gw-btn gw-btn--ghost gw-btn--block"
            type="button"
            :disabled="reportBusy || !canGenerateReport"
            @click="openReport(true)"
          >
            {{ reportBusy ? '处理中…' : '生成初审报告' }}
          </button>
        </div>
        <div class="gw-btn-row">
          <button
            class="gw-btn gw-btn--ghost gw-btn--block"
            type="button"
            :disabled="reportBusy || !canGenerateReport"
            @click="openReport(false)"
          >
            查看最新报告
          </button>
        </div>
      </PanelCard>
    </Teleport>

    <!-- 报告需要专心的阅读空间与复制导出，属于"需要受保护焦点"的场景，用弹层 -->
    <GwModal
      v-model="reportOpen"
      size="wide"
      :title="`${summary?.caseNo ?? ''} · AI 初审报告`"
      :subtitle="
        report?.report
          ? `第 ${report.report.revisionNo} 版 · ${report.report.modelId} · 生成于 ${report.report.generatedAt}`
          : undefined
      "
    >
      <template #tools>
        <button class="gw-btn gw-btn--ghost gw-btn--xs" type="button" @click="copyReport">复制</button>
        <button class="gw-btn gw-btn--ghost gw-btn--xs" type="button" @click="exportReport">
          导出 .md
        </button>
      </template>
      <div v-if="report?.report" class="md" v-html="reportHtml" />
      <p v-else class="none">尚未生成报告。</p>
    </GwModal>
  </div>
</template>

<style scoped>
.view {
  display: contents;
}

/* ── 骨架 ─────────────────────────────────────────────────────────── */
.sk {
  display: flex;
  flex-direction: column;
  gap: var(--gw-s3);
}

.sk__bar,
.sk__row {
  border-radius: var(--gw-r);
  background: linear-gradient(
    90deg,
    var(--gw-bg-sunken) 0%,
    var(--gw-line-soft) 50%,
    var(--gw-bg-sunken) 100%
  );
  background-size: 200% 100%;
  animation: sk 1.4s var(--gw-ease) infinite;
}

.sk__bar {
  height: 118px;
}

.sk__row {
  height: 46px;
}

@keyframes sk {
  from {
    background-position: 140% 0;
  }
  to {
    background-position: -40% 0;
  }
}

/* ── 空态 ─────────────────────────────────────────────────────────── */
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--gw-s4);
  padding: var(--gw-s9) var(--gw-s6);
  max-width: 660px;
  margin: 0 auto;
}

.empty__text {
  font-size: var(--gw-fs-md);
  color: var(--gw-text-secondary);
  text-align: center;
  line-height: var(--gw-lh);
}

/* ── 初审构成 ─────────────────────────────────────────────────────── */
.board {
  margin-bottom: var(--gw-s7);
}

.board__head {
  display: flex;
  align-items: center;
  gap: var(--gw-s3);
  margin-bottom: var(--gw-s4);
}

.board__title,
.work__title {
  font-size: var(--gw-fs-lg);
  font-weight: 600;
  color: var(--gw-text);
  letter-spacing: -0.01em;
}

.board__case {
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-tertiary);
}

.board__refresh {
  margin-left: auto;
}

.board__body {
  display: flex;
  align-items: center;
  gap: var(--gw-s7);
  padding: var(--gw-s5) var(--gw-s6);
  border: 1px solid var(--gw-line);
  border-radius: var(--gw-r);
  background: var(--gw-surface);
  box-shadow: var(--gw-elev-1);
}

.geo {
  flex: 1;
  min-width: 0;
}

.geo__bar {
  display: flex;
  height: 10px;
  border-radius: var(--gw-r-pill);
  overflow: hidden;
  background: var(--gw-bg-sunken);
}

.geo__seg {
  height: 100%;
  transition: width var(--gw-dur) var(--gw-ease);
}

.geo__seg--ok {
  background: var(--gw-success);
}
.geo__seg--warn {
  background: var(--gw-warning);
}
.geo__seg--err {
  background: var(--gw-danger);
}
.geo__seg--none {
  background: var(--gw-line);
}

.geo__legend {
  display: flex;
  gap: var(--gw-s6);
  margin-top: var(--gw-s3);
}

.geo__item {
  display: flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
}

.geo__item dt {
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-secondary);
  white-space: nowrap;
}

.geo__item dd {
  display: flex;
  align-items: baseline;
  gap: 5px;
}

.geo__item b {
  font-size: var(--gw-fs-md);
  font-weight: 600;
  color: var(--gw-text);
}

.geo__pct {
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
}

.geo__dot {
  width: 7px;
  height: 7px;
  flex: none;
  border-radius: 2px;
  align-self: center;
}

.geo__dot--ok {
  background: var(--gw-success);
}
.geo__dot--warn {
  background: var(--gw-warning);
}
.geo__dot--err {
  background: var(--gw-danger);
}

.rate {
  flex: none;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  padding-left: var(--gw-s6);
  border-left: 1px solid var(--gw-line);
}

.rate__label {
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
}

.rate__value {
  font-size: 30px;
  font-weight: 600;
  line-height: 1.15;
  color: var(--gw-text);
  letter-spacing: -0.02em;
}

.rate__sub {
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
}

.board__meta {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: var(--gw-s3);
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-secondary);
}

.board__meta b {
  color: var(--gw-text);
  font-weight: 600;
}

.board__sep {
  color: var(--gw-line-strong);
}

.board__def {
  margin-top: 5px;
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
  line-height: var(--gw-lh);
}

/* ── 工作清单 ─────────────────────────────────────────────────────── */
.work__head {
  display: flex;
  align-items: center;
  gap: var(--gw-s4);
  margin-bottom: var(--gw-s3);
}

.work__prog {
  display: flex;
  align-items: center;
  gap: var(--gw-s3);
  margin-left: auto;
}

.work__prog-bar {
  display: block;
  width: 96px;
  height: 4px;
  border-radius: var(--gw-r-pill);
  background: var(--gw-bg-sunken);
  overflow: hidden;
}

.work__prog-bar i {
  display: block;
  height: 100%;
  border-radius: var(--gw-r-pill);
  background: var(--gw-accent);
  transition: width var(--gw-dur) var(--gw-ease);
}

.work__prog-text {
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
}

.filters {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: var(--gw-s3) var(--gw-s4);
  margin-bottom: var(--gw-s4);
  border: 1px solid var(--gw-line);
  border-radius: var(--gw-r);
  background: var(--gw-bg-sunken);
}

.filters__group {
  display: flex;
  align-items: center;
  gap: 5px;
  flex-wrap: wrap;
}

.filters__label {
  flex: none;
  width: 26px;
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
}

.chip {
  flex: none;
  height: 22px;
  padding: 0 9px;
  border: 1px solid var(--gw-line);
  border-radius: var(--gw-r-pill);
  background: var(--gw-surface);
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-secondary);
  transition: border-color var(--gw-dur-fast) var(--gw-ease),
    background var(--gw-dur-fast) var(--gw-ease), color var(--gw-dur-fast) var(--gw-ease);
}

.chip:hover {
  border-color: var(--gw-line-strong);
  color: var(--gw-text);
}

.chip.is-on {
  border-color: var(--gw-accent);
  background: var(--gw-accent-soft);
  color: var(--gw-accent-text);
  font-weight: 500;
}

/* ── 物料分组 ─────────────────────────────────────────────────────── */
.groups {
  display: flex;
  flex-direction: column;
  gap: var(--gw-s5);
}

.grp__head {
  display: flex;
  align-items: center;
  gap: var(--gw-s3);
  padding: 0 2px 6px;
}

/* 组头里每个元素都按内容定宽：一旦让状态胶囊参与伸缩，
   它会被拉成一个宽度与文字无关的色块，"描边胶囊"的编码就失效了 */
.grp__head .gw-status,
.grp__head .tag,
.grp__name {
  flex: none;
}

.grp__name {
  font-size: var(--gw-fs-md);
  font-weight: 600;
  color: var(--gw-text);
  min-width: 0;
}

.grp__count {
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
}

.grp__todo {
  margin-left: auto;
  font-size: var(--gw-fs-xs);
  color: var(--gw-warning);
}

/* ── 风险条目 ─────────────────────────────────────────────────────── */
.rk {
  border: 1px solid var(--gw-line);
  border-radius: var(--gw-r);
  background: var(--gw-surface);
  box-shadow: var(--gw-elev-1);
  overflow: hidden;
}

.rk__item {
  border-bottom: 1px solid var(--gw-line-soft);
  transition: background var(--gw-dur-fast) var(--gw-ease);
}

.rk__item:last-child {
  border-bottom: 0;
}

.rk__item.is-open {
  background: var(--gw-accent-faint);
}

/* 已作出法务判断的条目降低视觉重量：剩下的未处理项自然浮出来 */
.rk__item.is-done .rk__text {
  color: var(--gw-text-secondary);
}

.rk__row {
  display: flex;
  align-items: center;
  gap: var(--gw-s3);
  width: 100%;
  padding: 9px var(--gw-s5);
  background: transparent;
  text-align: left;
}

.rk__row:hover {
  background: var(--gw-surface-hover);
}

.rk__type {
  flex: none;
  padding: 0 6px;
  border: 1px solid var(--gw-line);
  border-radius: var(--gw-r-sm);
  font-size: var(--gw-fs-xs);
  line-height: 16px;
  color: var(--gw-text-tertiary);
}

.rk__text {
  flex: 0 1 auto;
  min-width: 0;
  max-width: 34ch;
  font-size: var(--gw-fs-md);
  font-weight: 500;
  color: var(--gw-text);
}

.rk__flag {
  flex: none;
  padding: 0 6px;
  border: 1px solid var(--gw-warning);
  border-radius: var(--gw-r-sm);
  font-size: var(--gw-fs-xs);
  line-height: 16px;
  color: var(--gw-warning);
}

.rk__loc {
  flex: 1;
  min-width: 0;
  padding-right: var(--gw-s2);
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
  text-align: right;
}

.rk__caret {
  flex: none;
  color: var(--gw-text-tertiary);
  transition: transform var(--gw-dur-fast) var(--gw-ease);
}

.rk__caret.is-open {
  transform: rotate(180deg);
}

.rk__detail {
  padding: 0 var(--gw-s5) var(--gw-s5);
  border-top: 1px dashed var(--gw-line);
}

/* ── 段 ───────────────────────────────────────────────────────────── */
.sect {
  margin: var(--gw-s4) 0;
}

.sect__head {
  display: flex;
  align-items: baseline;
  gap: var(--gw-s3);
  margin-bottom: 6px;
}

.sect__title {
  font-size: var(--gw-fs-sm);
  font-weight: 600;
  color: var(--gw-text-secondary);
}

.sect__hint {
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
}

.sect__loading,
.sect__note {
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
  line-height: var(--gw-lh);
}

.sect__note {
  margin-top: 6px;
}

.sect__note--warn {
  color: var(--gw-warning);
}

/* 上下文按原文顺序读，命中行用底色而不是侧边色条标出 */
.ctx {
  border: 1px solid var(--gw-line);
  border-radius: var(--gw-r-sm);
  background: var(--gw-bg);
  overflow: hidden;
}

.ctx__line {
  display: flex;
  gap: var(--gw-s3);
  padding: 5px var(--gw-s4);
  border-bottom: 1px solid var(--gw-line-soft);
}

.ctx__line:last-child {
  border-bottom: 0;
}

.ctx__line.is-hit {
  background: var(--gw-risk-high-bg);
}

.ctx__no {
  flex: none;
  width: 22px;
  text-align: right;
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
  line-height: 1.7;
}

.ctx__text {
  flex: 1;
  min-width: 0;
  font-size: var(--gw-fs-base);
  color: var(--gw-text);
  line-height: 1.7;
  word-break: break-word;
}

.ctx__text :deep(.hl) {
  padding: 0 1px;
  border-radius: 2px;
  background: rgba(163, 58, 58, 0.16);
  color: var(--gw-risk-high);
  font-weight: 600;
}

.basis {
  white-space: pre-wrap;
}

.basis--none {
  color: var(--gw-warning);
}

/* ── 内联动作 ─────────────────────────────────────────────────────── */
.acts {
  display: flex;
  align-items: center;
  gap: var(--gw-s2);
  flex-wrap: wrap;
  margin-top: var(--gw-s4);
  padding-top: var(--gw-s4);
  border-top: 1px solid var(--gw-line-soft);
}

.acts__tip {
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
}

.form {
  margin-top: var(--gw-s3);
  padding: var(--gw-s4);
  border: 1px solid var(--gw-line);
  border-radius: var(--gw-r-sm);
  background: var(--gw-bg);
}

.form__grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--gw-s3);
}

.form__field {
  display: block;
  margin-bottom: var(--gw-s3);
}

.form__label {
  display: block;
  margin-bottom: 5px;
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-secondary);
}

.form__input,
.form__select,
.form__textarea {
  width: 100%;
  padding: 7px 10px;
  border: 1px solid var(--gw-line-strong);
  border-radius: var(--gw-r-sm);
  background: var(--gw-surface);
  font-size: var(--gw-fs-base);
  font-family: inherit;
  color: var(--gw-text);
  transition: border-color var(--gw-dur-fast) var(--gw-ease);
}

.form__input:focus,
.form__select:focus,
.form__textarea:focus {
  outline: none;
  border-color: var(--gw-accent);
}

.form__textarea {
  resize: vertical;
  line-height: var(--gw-lh);
}

.form__acts {
  display: flex;
  justify-content: flex-end;
  gap: var(--gw-s2);
}

.err {
  padding: var(--gw-s3) var(--gw-s4);
  border-radius: var(--gw-r-sm);
  background: var(--gw-danger-bg);
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-secondary);
  line-height: var(--gw-lh);
}

.err--inline {
  margin-bottom: var(--gw-s3);
  padding: 6px 10px;
  font-size: var(--gw-fs-xs);
}

.tag {
  flex: none;
  padding: 0 5px;
  border: 1px solid var(--gw-line);
  border-radius: var(--gw-r-sm);
  font-size: var(--gw-fs-xs);
  line-height: 16px;
  color: var(--gw-text-tertiary);
}

.is-alert {
  color: var(--gw-danger);
  font-weight: 600;
}

.none {
  padding: var(--gw-s6);
  border: 1px dashed var(--gw-line);
  border-radius: var(--gw-r);
  text-align: center;
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-tertiary);
}

.none--sm {
  padding: var(--gw-s3) 0 0;
  border: 0;
  text-align: left;
}

/* ── 分布 ─────────────────────────────────────────────────────────── */
.dist {
  display: flex;
  flex-direction: column;
  gap: var(--gw-s3);
}

.dist__row {
  display: flex;
  align-items: center;
  gap: var(--gw-s3);
}

.dist__row--type {
  gap: var(--gw-s2);
}

.dist__type {
  flex: none;
  width: 84px;
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-secondary);
}

.dist__bar {
  flex: 1;
  height: 6px;
  border-radius: var(--gw-r-pill);
  background: var(--gw-bg-sunken);
  overflow: hidden;
}

.dist__bar i {
  display: block;
  height: 100%;
  border-radius: var(--gw-r-pill);
  transition: width var(--gw-dur) var(--gw-ease);
}

.dist__num {
  width: 16px;
  flex: none;
  text-align: right;
  font-size: var(--gw-fs-base);
  font-weight: 600;
  color: var(--gw-text);
}

/* ── Markdown 报告 ────────────────────────────────────────────────── */
.md {
  font-size: var(--gw-fs-base);
  color: var(--gw-text);
  line-height: 1.65;
}

.md :deep(.md__h) {
  font-weight: 600;
  color: var(--gw-text);
  letter-spacing: -0.01em;
}

.md :deep(.md__h--1) {
  font-size: 20px;
  margin-bottom: var(--gw-s4);
}

.md :deep(.md__h--2) {
  font-size: var(--gw-fs-lg);
  margin: var(--gw-s6) 0 var(--gw-s3);
  padding-bottom: 6px;
  border-bottom: 1px solid var(--gw-line);
}

.md :deep(.md__h--3) {
  font-size: var(--gw-fs-md);
  margin: var(--gw-s5) 0 var(--gw-s2);
}

.md :deep(.md__p) {
  margin-bottom: var(--gw-s3);
}

.md :deep(.md__quote) {
  margin: var(--gw-s3) 0;
  padding: var(--gw-s3) var(--gw-s4);
  border-left: 2px solid var(--gw-accent);
  border-radius: var(--gw-r-sm);
  background: var(--gw-accent-faint);
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-secondary);
}

.md :deep(.md__ul) {
  margin: 0 0 var(--gw-s3) 18px;
  list-style: disc;
}

.md :deep(.md__ul li) {
  margin-bottom: 3px;
}

.md :deep(.md__code) {
  margin: var(--gw-s3) 0;
  padding: var(--gw-s4);
  border: 1px solid var(--gw-line);
  border-radius: var(--gw-r-sm);
  background: var(--gw-bg-sunken);
  font-family: var(--gw-font-mono);
  font-size: var(--gw-fs-sm);
  line-height: 1.6;
  white-space: pre-wrap;
  color: var(--gw-text-secondary);
}

.md :deep(.md__table) {
  width: 100%;
  margin: var(--gw-s3) 0;
  border: 1px solid var(--gw-line);
  border-radius: var(--gw-r-sm);
  border-collapse: separate;
  border-spacing: 0;
  overflow: hidden;
  font-size: var(--gw-fs-sm);
}

.md :deep(.md__table th) {
  padding: 6px 10px;
  border-bottom: 1px solid var(--gw-line);
  background: var(--gw-bg-sunken);
  text-align: left;
  font-weight: 600;
  color: var(--gw-text-secondary);
}

.md :deep(.md__table td) {
  padding: 6px 10px;
  border-bottom: 1px solid var(--gw-line-soft);
  vertical-align: top;
}

.md :deep(.md__table tr:last-child td) {
  border-bottom: 0;
}

.md :deep(code) {
  padding: 1px 4px;
  border-radius: 3px;
  background: var(--gw-bg-sunken);
  font-family: var(--gw-font-mono);
  font-size: 0.92em;
}

@media (max-width: 1280px) {
  .board__body {
    flex-direction: column;
    align-items: stretch;
    gap: var(--gw-s4);
  }
  .rate {
    align-items: flex-start;
    padding-top: var(--gw-s4);
    padding-left: 0;
    border-top: 1px solid var(--gw-line);
    border-left: 0;
  }
  .geo__legend {
    flex-wrap: wrap;
    gap: var(--gw-s4);
  }
}

@media (max-width: 1100px) {
  .rk__loc {
    display: none;
  }
  .form__grid {
    grid-template-columns: 1fr;
  }
}
</style>
