<script setup lang="ts">
/**
 * 终审区（风险整改闭环）
 *
 * 责任：已确认风险的整改闭环 —— 版本推进、差异比较、AI 复审、法务终审、签名与关闭。
 * 边界：不重新对所有物料做一次完整初审；复审范围由服务端强制校验（拒绝 FULL）。
 *
 * 本视图不持有硬编码 caseId：当前任务来自跨模块共享的 activeCase store。
 * 否则用户在接收区建的任务到这里就找不到，只能靠写死 id 演示——那不是 Agent。
 *
 * 视觉编码严格分两套（AGENTS.md 第 13 条）：
 *   风险等级 = 实心色块 gw-level--*；流程状态 = 描边胶囊 gw-status--*。两者不混用。
 */
import { computed, onMounted, ref } from 'vue'
import { ApiError, apiGet, apiPost, apiUpload } from '@/api/client'
import { useActiveCase } from '@/stores/activeCase'
import GwModal from '@/components/GwModal.vue'
import PanelCard from '@/components/PanelCard.vue'
import { renderMarkdown } from '@/utils/markdown'
import type { TraceStep } from '@/components/ReasoningTrace.vue'

const { resolveActiveCase, activeCase } = useActiveCase()

// ── 领域类型（字段与后端视图逐一对齐，不做臆测）───────────────────────

interface RiskRow {
  id: number
  riskNo: string
  caseId: number
  materialId: number
  riskType: string
  riskLevel: string
  confidence: number
  status: string
  riskText: string
  locationDesc?: string
  blocked?: boolean
  assigneeId?: number
  remediationDueAt?: string
  firstVersionId: number
  currentVersionId: number
  closedAt?: string
}

interface AnchorRow {
  anchorId: string
  anchorType: string
  text?: string
  locator?: string
}

interface RecordRow {
  id: number
  actorType: string
  action: string
  opinion?: string
  fromStatus?: string
  toStatus?: string
  createdAt: string
}

interface VersionRow {
  id: number
  versionNo: number
  versionLabel: string
  fileSha256: string
  fileSize: number
  mimeType?: string
  uploadReason?: string
  uploaderId: number
  parentVersionId?: number
  createdAt: string
}

interface ApprovalRow {
  id: number
  versionId: number
  fileSha256: string
  status: string
  approvedAt?: string
  revokedAt?: string
  revokeReason?: string
}

// ── 状态 ──────────────────────────────────────────────────────────────

const loading = ref(false)
const busy = ref(false)
const errorText = ref<string | null>(null)
const noticeText = ref<string | null>(null)

const risks = ref<RiskRow[]>([])
const selectedId = ref<number | null>(null)
const detail = ref<{ risk: RiskRow; anchors: AnchorRow[]; records: RecordRow[] } | null>(null)
const versions = ref<VersionRow[]>([])
const approvals = ref<ApprovalRow[]>([])

// ── 展示映射 ──────────────────────────────────────────────────────────

const LEVEL_TEXT: Record<string, string> = { HIGH: '高', MEDIUM: '中', LOW: '低' }
const LEVEL_CLASS: Record<string, string> = {
  HIGH: 'gw-level gw-level--high',
  MEDIUM: 'gw-level gw-level--medium',
  LOW: 'gw-level gw-level--low',
}

const TYPE_TEXT: Record<string, string> = {
  ABSOLUTE_CLAIM: '绝对化宣传',
  EVIDENCE_MISSING: '无依据数据',
  SAFETY_PROMISE: '安全承诺',
  COMPETITOR_COMPARISON: '竞品比较',
  PRICE_CLAIM: '价格宣传',
  DISCLAIMER_MISSING: '免责声明缺失',
  MISLEADING: '可能误导',
  OTHER: '其他',
}

/** 状态措辞与后端枚举一一对应，不做美化改写 */
const STATUS_META: Record<string, { text: string; tone: string }> = {
  OPEN: { text: '新建', tone: 'ink' },
  PENDING_LEGAL_DECISION: { text: '待人工判断', tone: 'muted' },
  CONFIRMED: { text: '已确认', tone: 'brass' },
  AWAITING_EVIDENCE: { text: '等待补充材料', tone: 'muted' },
  AWAITING_REVISION: { text: '整改中', tone: 'brass' },
  RESUBMITTED: { text: '已重新提交', tone: 'ink' },
  AI_REREVIEW: { text: 'AI 复审中', tone: 'ink' },
  LEGAL_FINAL_REVIEW: { text: '法务终审', tone: 'wine' },
  CLOSED: { text: '已关闭', tone: 'success' },
  REJECTED_FALSE_POSITIVE: { text: '误判留痕', tone: 'muted' },
}

const statusOf = (s: string) => STATUS_META[s] ?? { text: s, tone: 'muted' }
const selected = computed(() => risks.value.find((r) => r.id === selectedId.value) ?? null)
const approvedVersionId = computed(
  () => approvals.value.find((a) => a.status === 'APPROVED')?.versionId ?? null,
)

/**
 * 按钮可用性由状态推导。
 * 目的不是美化，而是避免出现"点了必然返回 422"的按钮——
 * 那种按钮会让用户以为是系统坏了。
 */
const can = computed(() => {
  const s = selected.value?.status
  return {
    startRereview: s === 'RESUBMITTED',
    finishRereview: s === 'AI_REREVIEW',
    legalFinalReview: s === 'LEGAL_FINAL_REVIEW',
    sign: s === 'LEGAL_FINAL_REVIEW',
    close: s === 'LEGAL_FINAL_REVIEW',
  }
})

/** 复审范围固定为「变化区域 + 必要上下文」。FULL 会被服务端直接拒绝 */
const REVIEW_SCOPE = 'CHANGED_REGION_WITH_CONTEXT'

function reportError(e: unknown, fallback: string) {
  errorText.value = e instanceof ApiError ? e.message : fallback
}

// ── 加载 ──────────────────────────────────────────────────────────────

async function loadRisks() {
  const c = activeCase.value
  if (!c) return
  loading.value = true
  errorText.value = null
  try {
    risks.value = await apiGet<RiskRow[]>(`/api/final-review/risks?caseId=${c.id}`)
    if (selectedId.value && !risks.value.some((r) => r.id === selectedId.value)) {
      selectedId.value = null
      detail.value = null
      versions.value = []
    }
    if (!selectedId.value && risks.value.length > 0) {
      await selectRisk(risks.value[0].id)
    }
  } catch (e) {
    reportError(e, '风险列表加载失败')
  } finally {
    loading.value = false
  }
}

async function selectRisk(id: number) {
  selectedId.value = id
  const risk = risks.value.find((r) => r.id === id)
  if (!risk) return
  // 详情失败不应让整页不可用：列表与操作按钮仍基于列表状态可用
  try {
    detail.value = await apiGet(`/api/feedback/risks/${id}`)
  } catch {
    detail.value = null
  }
  await Promise.all([loadVersions(risk.materialId), loadApprovals(risk.materialId)])
}

async function loadVersions(materialId: number) {
  try {
    versions.value = await apiGet<VersionRow[]>(`/api/intake/materials/${materialId}/versions`)
  } catch {
    versions.value = []
  }
}

async function loadApprovals(materialId: number) {
  try {
    approvals.value = await apiGet<ApprovalRow[]>(
      `/api/final-review/materials/${materialId}/approvals`,
    )
  } catch {
    approvals.value = []
  }
}

async function bootstrap() {
  loading.value = true
  try {
    await resolveActiveCase()
    if (!activeCase.value) {
      errorText.value = '尚无审核任务。请先在接收区创建任务并上传材料。'
      return
    }
    await loadRisks()
  } finally {
    loading.value = false
  }
}

onMounted(bootstrap)

// ── 上传整改版本 ──────────────────────────────────────────────────────

const uploadOpen = ref(false)
const uploadReason = ref('')
const uploadFile = ref<File | null>(null)

function pickFile() {
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = '.jpg,.jpeg,.png,.gif,.webp,.bmp,.mp4,.mov,.avi,.mkv,.webm,.ppt,.pptx,.pdf,.doc,.docx,.txt,.md,.csv'
  input.onchange = () => {
    uploadFile.value = input.files?.[0] ?? null
  }
  input.click()
}

/**
 * 提交整改版本。
 *
 * V2 及以后必须填写修改说明——数据库 CHECK 约束 ck_version_reason 会兜底。
 * 前端提前拦一次只是为了让用户看到明确提示，而不是一个数据库层报错。
 */
async function submitUpload() {
  const risk = selected.value
  const c = activeCase.value
  if (!risk || !c) return
  if (!uploadFile.value) {
    errorText.value = '请选择要上传的文件'
    return
  }
  const nextNo = (versions.value.at(-1)?.versionNo ?? 1) + 1
  if (nextNo >= 2 && !uploadReason.value.trim()) {
    errorText.value = '上传整改版本必须填写修改说明'
    return
  }

  busy.value = true
  errorText.value = null
  try {
    const form = new FormData()
    form.append('caseId', String(c.id))
    form.append('materialId', String(risk.materialId))
    form.append('file', uploadFile.value)
    form.append('uploadReason', uploadReason.value.trim() || '首次上传')

    const up = await apiUpload<{ materialId: number; versionId: number; versionLabel: string }>(
      '/api/intake/materials/upload',
      form,
    )
    // 上传后立即解析：没有新锚点，复审就无从比对
    await apiPost(`/api/intake/materials/${up.materialId}/parse`)

    uploadOpen.value = false
    uploadFile.value = null
    uploadReason.value = ''
    noticeText.value = `${up.versionLabel} 已上传并解析完成`
    await loadRisks()
    await Promise.all([loadVersions(risk.materialId), loadApprovals(risk.materialId)])
  } catch (e) {
    reportError(e, '版本上传失败')
  } finally {
    busy.value = false
  }
}

// ── 版本差异 ──────────────────────────────────────────────────────────

interface DiffChange {
  changeType: string
  anchorType: string
  baseAnchorId?: string
  targetAnchorId?: string
  baseText?: string
  targetText?: string
}

interface DiffNotSupported {
  anchorType: string
  status: string
  reason: string
}

interface DiffPayload {
  baseVersionLabel?: string
  targetVersionLabel?: string
  sameFileHash?: boolean
  firstVersion?: boolean
  note?: string
  changes?: DiffChange[]
  notSupported?: DiffNotSupported[]
  stats?: Record<string, number>
}

const diffOpen = ref(false)
const diffLoading = ref(false)
const diffSummary = ref('')
const diffType = ref('')
const diffPayload = ref<DiffPayload | null>(null)

async function openDiff(fromVersionId?: number, toVersionId?: number) {
  const risk = selected.value
  if (!risk) return
  diffOpen.value = true
  diffLoading.value = true
  diffPayload.value = null
  diffSummary.value = ''
  try {
    const q: string[] = []
    if (fromVersionId) q.push(`fromVersionId=${fromVersionId}`)
    if (toVersionId) q.push(`toVersionId=${toVersionId}`)
    const d = await apiGet<{ diffType: string; summary: string; payload: DiffPayload }>(
      `/api/final-review/materials/${risk.materialId}/diff${q.length ? `?${q.join('&')}` : ''}`,
    )
    diffType.value = d.diffType
    diffSummary.value = d.summary
    diffPayload.value = d.payload
  } catch (e) {
    reportError(e, '版本差异加载失败')
    diffOpen.value = false
  } finally {
    diffLoading.value = false
  }
}

const changedList = computed(() =>
  (diffPayload.value?.changes ?? []).filter((c) => c.changeType !== 'UNCHANGED'),
)

const CHANGE_TEXT: Record<string, string> = {
  DELETED: '删除',
  INSERTED: '新增',
  REPLACED: '替换',
  UNCHANGED: '未变',
}

/** 差异弹层里展示的两版对比，默认取最后两个版本 */
const diffPairHint = computed(() => {
  const n = versions.value.length
  if (n < 2) return ''
  return `${versions.value[n - 2].versionLabel} → ${versions.value[n - 1].versionLabel}`
})

// ── AI 复审 ───────────────────────────────────────────────────────────

interface RereviewResult {
  originalResolved: boolean
  remainingRisk: boolean
  pass: boolean
  newRiskIds: number[]
  newRisks: { riskId: number; anchorId: string; text: string; hitKeywords: string[] }[]
  evidence: Record<string, unknown>
  summary: string
}

const rereviewResult = ref<RereviewResult | null>(null)
const rereviewOpen = ref(false)

/**
 * 执行 AI 复审。
 *
 * 两步是分开的：`/rereview` 让状态进入 AI_REREVIEW，`/rereview/result` 才真正做判定。
 * 若风险已处于 AI_REREVIEW（例如上次只完成了第一步），则跳过启动步骤，
 * 否则会因重复跃迁被判非法。
 */
async function runRereview() {
  const risk = selected.value
  if (!risk) return
  busy.value = true
  errorText.value = null
  try {
    if (risk.status === 'RESUBMITTED') {
      await apiPost(`/api/final-review/risks/${risk.id}/rereview`, { reviewScope: REVIEW_SCOPE })
    }
    const r = await apiPost<RereviewResult>(
      `/api/final-review/risks/${risk.id}/rereview/result`,
      { reviewScope: REVIEW_SCOPE },
    )
    rereviewResult.value = r
    rereviewOpen.value = true
    await loadRisks()
    if (risk.materialId) await loadVersions(risk.materialId)
  } catch (e) {
    reportError(e, 'AI 复审失败')
  } finally {
    busy.value = false
  }
}

const rereviewTrace = computed<TraceStep[]>(() => {
  const r = rereviewResult.value
  if (!r) return []
  const ev = r.evidence as {
    baseVersionId?: number
    targetVersionId?: number
    reviewedAnchorCount?: number
    insertedAnchors?: number
  }
  return [
    {
      stage: '版本比对',
      summary: `比对新版本锚点 ${ev.reviewedAnchorCount ?? 0} 个，整改后新增 ${ev.insertedAnchors ?? 0} 个`,
      details: [`基准版本 #${ev.baseVersionId ?? '-'} → 目标版本 #${ev.targetVersionId ?? '-'}`],
      state: 'done',
    },
    {
      stage: '原风险核查',
      summary: r.originalResolved ? '原风险原文已从新版本中消失' : '原风险原文在新版本中仍然存在',
      state: 'done',
    },
    {
      stage: '剩余风险核查',
      summary: r.remainingRisk ? '新版本中仍命中同类关键词' : '新版本中未再命中同类关键词',
      state: 'done',
    },
    {
      stage: '新增风险核查',
      summary:
        r.newRisks.length > 0
          ? `发现 ${r.newRisks.length} 处新增风险，已新建关联记录`
          : '未发现新增风险',
      details: r.newRisks.map((n) => `${n.text}（锚点 ${n.anchorId}）`),
      state: 'done',
    },
  ]
})

const rereviewSummaryHtml = computed(() =>
  rereviewResult.value ? renderMarkdown(rereviewResult.value.summary) : '',
)

// ── 高风险动作：二次确认 + 影响范围 ───────────────────────────────────

const confirmOpen = ref(false)
const confirmTitle = ref('')
const confirmSubtitle = ref('')
const confirmImpact = ref<{ label: string; value: string }[]>([])
const confirmOpinion = ref('')
const confirmNeedOpinion = ref(false)
const confirmOkText = ref('确认执行')
const confirmAction = ref<(() => Promise<void>) | null>(null)

function askConfirm(opts: {
  title: string
  subtitle: string
  impact: { label: string; value: string }[]
  needOpinion?: boolean
  okText?: string
  onOk: () => Promise<void>
}) {
  confirmTitle.value = opts.title
  confirmSubtitle.value = opts.subtitle
  confirmImpact.value = opts.impact
  confirmNeedOpinion.value = opts.needOpinion ?? false
  confirmOkText.value = opts.okText ?? '确认执行'
  confirmOpinion.value = ''
  confirmAction.value = opts.onOk
  confirmOpen.value = true
}

async function runConfirmed() {
  if (!confirmAction.value) return
  if (confirmNeedOpinion.value && !confirmOpinion.value.trim()) {
    errorText.value = '请填写理由，该内容将写入审核记录'
    return
  }
  busy.value = true
  errorText.value = null
  const action = confirmAction.value
  try {
    await action()
    confirmOpen.value = false
    const mid = selected.value?.materialId
    await loadRisks()
    if (mid) await loadApprovals(mid)
  } catch (e) {
    reportError(e, '操作失败')
  } finally {
    busy.value = false
  }
}

function askLegalFinalReview() {
  const risk = selected.value
  if (!risk) return
  askConfirm({
    title: '通过法务终审',
    subtitle: '通过后仍需完成法务签名才能关闭风险',
    impact: [
      { label: '风险编号', value: risk.riskNo },
      { label: '风险原文', value: risk.riskText },
      { label: '影响范围', value: '记录本次终审意见；风险保持「法务终审」状态，等待签名关闭' },
    ],
    needOpinion: true,
    okText: '确认通过',
    onOk: async () => {
      await apiPost(`/api/final-review/risks/${risk.id}/legal-final-review`, {
        opinion: confirmOpinion.value.trim(),
        decision: 'ACCEPT_REREVIEW',
      })
      noticeText.value = `${risk.riskNo} 已通过法务终审，待签名关闭`
    },
  })
}

function askSign() {
  const risk = selected.value
  if (!risk) return
  askConfirm({
    title: '法务签名',
    subtitle: '签名会记录你的身份与时间，是关闭风险的前置条件',
    impact: [
      { label: '风险编号', value: risk.riskNo },
      { label: '风险原文', value: risk.riskText },
      { label: '影响范围', value: '生成不可撤销的签名记录，签名后该风险才允许被关闭' },
    ],
    needOpinion: true,
    okText: '确认签名',
    onOk: async () => {
      await apiPost(`/api/final-review/risks/${risk.id}/signatures`, {
        comment: confirmOpinion.value.trim(),
      })
      noticeText.value = `${risk.riskNo} 签名已写入`
    },
  })
}

function askClose() {
  const risk = selected.value
  if (!risk) return
  askConfirm({
    title: '关闭风险',
    subtitle: '关闭后该风险视为已解决并留痕，历史记录不可删除',
    impact: [
      { label: '风险编号', value: risk.riskNo },
      { label: '风险原文', value: risk.riskText },
      { label: '影响范围', value: '该风险的确认、整改与复审记录将永久保留，不可删除' },
    ],
    needOpinion: true,
    okText: '确认关闭',
    onOk: async () => {
      await apiPost(`/api/final-review/risks/${risk.id}/close`, {
        closeReason: confirmOpinion.value.trim(),
      })
      noticeText.value = `${risk.riskNo} 已关闭`
    },
  })
}

// ── 物料批准 / 撤销批准 ───────────────────────────────────────────────

interface ApprovalContext {
  materialId: number
  materialName: string
  versionId: number
  versionLabel: string
  fileSha256: string
  closedRiskCount: number
  openBlockingRiskCount: number
  blockingRiskOpen: boolean
}

async function askApprove(version: VersionRow) {
  const risk = selected.value
  if (!risk) return
  errorText.value = null
  busy.value = true
  try {
    const ctx = await apiGet<ApprovalContext>(
      `/api/final-review/materials/${risk.materialId}/approval-context?versionId=${version.id}`,
    )
    // 有阻断性风险未关闭时先说明原因，而不是让用户点下去再收到一个错误
    if (ctx.blockingRiskOpen) {
      errorText.value = `该版本仍有 ${ctx.openBlockingRiskCount} 条阻断性风险未关闭，无法批准`
      return
    }
    askConfirm({
      title: `批准 ${ctx.versionLabel} 为最终版本`,
      subtitle: '批准后该版本即作为对外发布版本，此后变更需先撤销批准',
      impact: [
        { label: '物料', value: ctx.materialName },
        { label: '版本', value: ctx.versionLabel },
        { label: '文件哈希', value: `${ctx.fileSha256.slice(0, 16)}…` },
        { label: '已关闭风险', value: `${ctx.closedRiskCount} 条` },
        { label: '未关闭阻断性风险', value: `${ctx.openBlockingRiskCount} 条` },
      ],
      okText: '确认批准',
      onOk: async () => {
        await apiPost(`/api/final-review/materials/${risk.materialId}/approvals`, {
          versionId: version.id,
          comment: '终审区批准',
        })
        noticeText.value = `${ctx.versionLabel} 已标记为最终批准版本`
      },
    })
  } catch (e) {
    reportError(e, '获取批准影响范围失败')
  } finally {
    busy.value = false
  }
}

function askRevoke(approval: ApprovalRow) {
  const risk = selected.value
  if (!risk) return
  askConfirm({
    title: '撤销批准',
    subtitle: '撤销后该物料回到未批准状态，需要重新走终审流程',
    impact: [
      { label: '批准记录', value: `#${approval.id}` },
      { label: '文件哈希', value: `${approval.fileSha256.slice(0, 16)}…` },
      { label: '影响范围', value: '该物料将不再处于已批准状态，不能作为对外发布版本' },
    ],
    needOpinion: true,
    okText: '确认撤销',
    onOk: async () => {
      await apiPost(
        `/api/final-review/materials/${risk.materialId}/approvals/${approval.id}/revoke`,
        { revokeReason: confirmOpinion.value.trim() },
      )
      noticeText.value = '批准已撤销'
    },
  })
}

const approvedLabel = computed(() => {
  const id = approvedVersionId.value
  if (id == null) return null
  return versions.value.find((v) => v.id === id)?.versionLabel ?? `#${id}`
})
</script>

<template>
  <div class="view">
    <!-- 当前任务 -->
    <section class="band">
      <div class="band__main">
        <h2 class="band__title">终审区</h2>
        <p v-if="activeCase" class="band__sub">{{ activeCase.caseNo }} · {{ activeCase.name }}</p>
        <p v-else class="band__sub">正在确定当前审核任务…</p>
      </div>
      <div class="band__side">
        <span v-if="approvedLabel" class="gw-status gw-status--success">
          <span class="gw-status__dot" />已批准 {{ approvedLabel }}
        </span>
        <span class="gw-status gw-status--muted">{{ risks.length }} 条风险</span>
        <button class="gw-btn gw-btn--ghost" type="button" :disabled="loading" @click="loadRisks">
          刷新
        </button>
      </div>
    </section>

    <p v-if="errorText" class="msg msg--err">{{ errorText }}</p>
    <p v-if="noticeText" class="msg msg--ok">{{ noticeText }}</p>

    <!-- 风险列表 -->
    <section class="list">
      <div v-if="loading" class="empty">加载中…</div>
      <div v-else-if="risks.length === 0" class="empty">
        当前任务没有处于整改闭环的风险。风险需先在反馈区被确认并转入整改。
      </div>
      <ul v-else class="cards">
        <li
          v-for="r in risks"
          :key="r.id"
          class="card"
          :class="{ 'is-active': r.id === selectedId }"
          :data-level="r.riskLevel"
          @click="selectRisk(r.id)"
        >
          <div class="card__top">
            <span :class="LEVEL_CLASS[r.riskLevel] ?? 'gw-level gw-level--low'">
              {{ LEVEL_TEXT[r.riskLevel] ?? '低' }}
            </span>
            <span class="card__no gw-mono">{{ r.riskNo }}</span>
            <span
              class="gw-status"
              :class="`gw-status--${statusOf(r.status).tone}`"
              style="margin-left: auto"
            >
              <span class="gw-status__dot" />{{ statusOf(r.status).text }}
            </span>
          </div>
          <p class="card__text">{{ r.riskText }}</p>
          <div class="card__meta">
            <span class="card__tag">{{ TYPE_TEXT[r.riskType] ?? r.riskType }}</span>
            <span v-if="r.blocked" class="card__tag card__tag--block">阻断</span>
            <span v-if="r.locationDesc" class="card__loc gw-truncate">{{ r.locationDesc }}</span>
          </div>
        </li>
      </ul>
    </section>

    <Teleport defer to="#wb-aside">
      <PanelCard v-if="selected" title="风险详情" :hint="selected.riskNo">
        <dl class="gw-kv">
          <div class="gw-kv__row"><dt>风险原文</dt><dd>{{ selected.riskText }}</dd></div>
          <div class="gw-kv__row">
            <dt>风险类型</dt>
            <dd>{{ TYPE_TEXT[selected.riskType] ?? selected.riskType }}</dd>
          </div>
          <div class="gw-kv__row">
            <dt>风险等级</dt>
            <dd><span :class="LEVEL_CLASS[selected.riskLevel]">{{ LEVEL_TEXT[selected.riskLevel] }}</span></dd>
          </div>
          <div class="gw-kv__row"><dt>置信度</dt><dd class="gw-mono">{{ selected.confidence }}</dd></div>
          <div class="gw-kv__row"><dt>定位</dt><dd>{{ selected.locationDesc || '—' }}</dd></div>
          <div v-if="selected.remediationDueAt" class="gw-kv__row">
            <dt>整改期限</dt><dd class="gw-mono">{{ selected.remediationDueAt }}</dd>
          </div>
          <div v-if="selected.closedAt" class="gw-kv__row">
            <dt>关闭时间</dt><dd class="gw-mono">{{ selected.closedAt }}</dd>
          </div>
        </dl>

        <ul v-if="detail?.anchors?.length" class="anchors">
          <li v-for="a in detail.anchors" :key="a.anchorId" class="anchors__row">
            <span class="anchors__id gw-mono">{{ a.anchorId }}</span>
            <span class="anchors__text gw-clamp-2">{{ a.text }}</span>
          </li>
        </ul>
      </PanelCard>

      <PanelCard v-else title="风险详情">
        <p class="empty empty--inline">选择左侧任一条风险查看详情与操作。</p>
      </PanelCard>

      <PanelCard v-if="selected" title="版本" :hint="`${versions.length} 个版本`" flush>
        <div v-if="versions.length === 0" class="pad">
          <span class="muted">暂无版本记录</span>
        </div>
        <div v-for="v in versions" :key="v.id" class="ver">
          <div class="ver__main">
            <span class="ver__label">{{ v.versionLabel }}</span>
            <span v-if="v.id === approvedVersionId" class="gw-status gw-status--success">
              <span class="gw-status__dot" />已批准
            </span>
            <span class="ver__reason gw-truncate">{{ v.uploadReason || '首次上传' }}</span>
            <span class="ver__hash gw-mono">{{ v.fileSha256.slice(0, 10) }}…</span>
          </div>
          <div class="ver__side">
            <button
              v-if="v.id !== approvedVersionId"
              class="gw-btn gw-btn--ghost gw-btn--xs"
              type="button"
              :disabled="busy"
              @click="askApprove(v)"
            >
              批准
            </button>
          </div>
        </div>
        <div class="pad pad--actions">
          <button class="gw-btn gw-btn--ghost" type="button" :disabled="!selected" @click="openDiff()">
            查看版本差异
          </button>
          <button class="gw-btn gw-btn--primary" type="button" @click="uploadOpen = true">
            上传整改版本
          </button>
        </div>
      </PanelCard>

      <!-- 批准记录：撤销批准需要 approvalId，没有它这条路径无法执行 -->
      <PanelCard
        v-if="selected && approvals.length"
        title="批准记录"
        :hint="`${approvals.length} 条`"
        flush
      >
        <div v-for="a in approvals" :key="a.id" class="apr">
          <div class="apr__main">
            <span class="apr__id gw-mono">#{{ a.id }}</span>
            <span
              class="gw-status"
              :class="a.status === 'APPROVED' ? 'gw-status--success' : 'gw-status--muted'"
            >
              <span class="gw-status__dot" />{{ a.status === 'APPROVED' ? '已批准' : '已撤销' }}
            </span>
            <span class="apr__time gw-mono">{{ a.approvedAt || a.revokedAt }}</span>
          </div>
          <button
            v-if="a.status === 'APPROVED'"
            class="gw-btn gw-btn--ghost gw-btn--xs"
            type="button"
            :disabled="busy"
            @click="askRevoke(a)"
          >
            撤销
          </button>
        </div>
      </PanelCard>

      <PanelCard v-if="selected" title="操作">
        <div class="ops">
          <button
            v-if="can.startRereview || can.finishRereview"
            class="gw-btn gw-btn--primary gw-btn--block"
            type="button"
            :disabled="busy"
            @click="runRereview"
          >
            执行 AI 复审
          </button>
          <button
            v-if="can.legalFinalReview"
            class="gw-btn gw-btn--primary gw-btn--block"
            type="button"
            :disabled="busy"
            @click="askLegalFinalReview"
          >
            通过法务终审
          </button>
          <button
            v-if="can.sign"
            class="gw-btn gw-btn--ghost gw-btn--block"
            type="button"
            :disabled="busy"
            @click="askSign"
          >
            法务签名
          </button>
          <button
            v-if="can.close"
            class="gw-btn gw-btn--ghost gw-btn--block"
            type="button"
            :disabled="busy"
            @click="askClose"
          >
            关闭风险
          </button>
          <p v-if="!can.startRereview && !can.finishRereview && !can.legalFinalReview" class="ops__tip">
            当前状态为「{{ statusOf(selected.status).text }}」，暂无可执行的终审动作。
          </p>
        </div>
      </PanelCard>
    </Teleport>

    <!-- 上传整改版本 -->
    <GwModal
      v-model="uploadOpen"
      title="上传整改版本"
      subtitle="版本不会被覆盖，既有风险与审核记录都会保留"
    >
      <div class="file">
        <button class="gw-btn gw-btn--ghost" type="button" @click="pickFile">选择文件</button>
        <span class="file__name gw-truncate">{{ uploadFile?.name || '未选择文件' }}</span>
      </div>
      <label class="field">
        <span class="field__label">修改说明（V2 及以后必填）</span>
        <textarea
          v-model="uploadReason"
          class="field__input"
          rows="3"
          placeholder="例如：已删除绝对化用语，补充续航测试工况"
        />
      </label>
      <template #footer>
        <button class="gw-btn gw-btn--ghost" type="button" @click="uploadOpen = false">取消</button>
        <button class="gw-btn gw-btn--primary" type="button" :disabled="busy" @click="submitUpload">
          上传并解析
        </button>
      </template>
    </GwModal>

    <!-- 版本差异 -->
    <GwModal v-model="diffOpen" title="版本差异" :subtitle="diffSummary" size="wide">
      <template #tools>
        <span v-if="diffPairHint" class="tool-hint gw-mono">{{ diffPairHint }}</span>
      </template>
      <div v-if="diffLoading" class="empty empty--inline">正在计算差异…</div>
      <template v-else-if="diffPayload">
        <p v-if="diffPayload.firstVersion" class="empty empty--inline">{{ diffPayload.note }}</p>
        <template v-else>
          <div class="diff__stats" v-if="diffPayload.stats">
            <div><span class="diff__stat-label">替换</span><span class="diff__stat-value gw-mono">{{ diffPayload.stats.replaced ?? 0 }}</span></div>
            <div><span class="diff__stat-label">删除</span><span class="diff__stat-value gw-mono">{{ diffPayload.stats.deleted ?? 0 }}</span></div>
            <div><span class="diff__stat-label">新增</span><span class="diff__stat-value gw-mono">{{ diffPayload.stats.inserted ?? 0 }}</span></div>
            <div><span class="diff__stat-label">未变</span><span class="diff__stat-value gw-mono">{{ diffPayload.stats.unchanged ?? 0 }}</span></div>
          </div>

          <ul v-if="changedList.length" class="diff__list">
            <li v-for="(c, i) in changedList" :key="i" class="diff__item" :data-kind="c.changeType">
              <div class="diff__tag">
                <span class="diff__kind">{{ CHANGE_TEXT[c.changeType] ?? c.changeType }}</span>
                <span class="diff__anchor gw-mono">{{ c.anchorType }}</span>
              </div>
              <div class="diff__body">
                <p v-if="c.baseText" class="diff__old">{{ c.baseText }}</p>
                <p v-if="c.targetText" class="diff__new">{{ c.targetText }}</p>
              </div>
            </li>
          </ul>
          <p v-else class="empty empty--inline">文本内容无变化。</p>

          <div v-if="diffPayload.notSupported?.length" class="diff__unsupported">
            <p class="diff__unsupported-title">以下内容无法自动比对</p>
            <ul>
              <li v-for="(u, i) in diffPayload.notSupported" :key="i">
                <span class="gw-mono">{{ u.anchorType }}</span>
                <span>{{ u.reason }}</span>
              </li>
            </ul>
          </div>
        </template>
      </template>
    </GwModal>

    <!-- AI 复审结论 -->
    <GwModal
      v-model="rereviewOpen"
      title="AI 复审结论"
      subtitle="结论来自版本文本比对，不是法律判断；最终以法务意见为准"
      size="wide"
    >
      <template v-if="rereviewResult">
        <div class="rr__verdict" :data-pass="rereviewResult.pass">
          {{ rereviewResult.pass ? '原风险已解决，且未发现剩余风险' : '仍需处理' }}
        </div>

        <ol class="rr__steps">
          <li v-for="(t, i) in rereviewTrace" :key="i" class="rr__step">
            <span class="rr__stage">{{ t.stage }}</span>
            <span class="rr__summary">{{ t.summary }}</span>
            <ul v-if="t.details?.length" class="rr__details">
              <li v-for="(d, j) in t.details" :key="j">{{ d }}</li>
            </ul>
          </li>
        </ol>

        <div v-if="rereviewResult.newRisks.length" class="rr__new">
          <p class="rr__new-title">已新建关联风险</p>
          <ul>
            <li v-for="n in rereviewResult.newRisks" :key="n.riskId">
              <span class="gw-mono">#{{ n.riskId }}</span>
              <span>{{ n.text }}</span>
            </li>
          </ul>
        </div>

        <div class="rr__md" v-html="rereviewSummaryHtml" />
      </template>
    </GwModal>

    <!-- 二次确认：展示影响范围 -->
    <GwModal v-model="confirmOpen" :title="confirmTitle" :subtitle="confirmSubtitle">
      <dl class="impact">
        <div v-for="(i, idx) in confirmImpact" :key="idx" class="impact__row">
          <dt>{{ i.label }}</dt>
          <dd>{{ i.value }}</dd>
        </div>
      </dl>
      <label v-if="confirmNeedOpinion" class="field">
        <span class="field__label">理由（必填）</span>
        <textarea
          v-model="confirmOpinion"
          class="field__input"
          rows="3"
          placeholder="请说明判断依据，该内容将写入审核记录"
        />
      </label>
      <template #footer>
        <button class="gw-btn gw-btn--ghost" type="button" @click="confirmOpen = false">取消</button>
        <button class="gw-btn gw-btn--primary" type="button" :disabled="busy" @click="runConfirmed">
          {{ confirmOkText }}
        </button>
      </template>
    </GwModal>
  </div>
</template>

<style scoped>
.view {
  display: contents;
}

/* ── 顶部任务条 ───────────────────────────────────────────────────── */
.band {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--gw-s5);
  max-width: 920px;
  padding-bottom: var(--gw-s4);
  border-bottom: 1px solid var(--gw-line);
}

.band__title {
  font-size: var(--gw-fs-md);
  font-weight: 600;
  color: var(--gw-text);
  letter-spacing: -0.01em;
}

.band__sub {
  margin-top: 2px;
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-tertiary);
}

.band__side {
  display: flex;
  align-items: center;
  gap: var(--gw-s2);
  flex: none;
}

/* ── 提示条 ───────────────────────────────────────────────────────── */
.msg {
  max-width: 920px;
  margin-top: var(--gw-s4);
  padding: var(--gw-s3) var(--gw-s4);
  border-radius: var(--gw-r-sm);
  font-size: var(--gw-fs-sm);
  line-height: var(--gw-lh);
}

.msg--err {
  border-left: 3px solid var(--gw-danger);
  background: var(--gw-danger-bg);
  color: var(--gw-text-secondary);
}

.msg--ok {
  border-left: 3px solid var(--gw-success);
  background: var(--gw-success-bg);
  color: var(--gw-text-secondary);
}

/* ── 风险卡片 ─────────────────────────────────────────────────────── */
.list {
  max-width: 920px;
  margin-top: var(--gw-s5);
}

.cards {
  display: flex;
  flex-direction: column;
  gap: var(--gw-s3);
}

.card {
  position: relative;
  padding: var(--gw-s4) var(--gw-s5);
  border-radius: var(--gw-r);
  background: var(--gw-surface);
  box-shadow: var(--gw-elev-1), var(--gw-hairline);
  border-left: 3px solid transparent;
  cursor: pointer;
  transition: box-shadow var(--gw-dur) var(--gw-ease);
}

.card:hover {
  box-shadow: var(--gw-elev-2), var(--gw-hairline);
}

.card.is-active {
  box-shadow: var(--gw-elev-3), inset 0 0 0 1.5px var(--gw-accent);
}

.card[data-level='HIGH'] {
  border-left-color: var(--gw-risk-high);
}
.card[data-level='MEDIUM'] {
  border-left-color: var(--gw-risk-medium);
}
.card[data-level='LOW'] {
  border-left-color: var(--gw-risk-low);
}

.card__top {
  display: flex;
  align-items: center;
  gap: var(--gw-s2);
}

.card__no {
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
}

.card__text {
  margin-top: var(--gw-s2);
  font-size: var(--gw-fs-md);
  font-weight: 500;
  color: var(--gw-text);
  line-height: 1.45;
}

.card__meta {
  display: flex;
  align-items: center;
  gap: var(--gw-s2);
  margin-top: var(--gw-s3);
  min-width: 0;
}

.card__tag {
  flex: none;
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
  padding: 0 5px;
  border: 1px solid var(--gw-line);
  border-radius: var(--gw-r-sm);
  line-height: 16px;
}

.card__tag--block {
  color: var(--gw-risk-high);
  border-color: color-mix(in srgb, var(--gw-risk-high) 35%, transparent);
}

.card__loc {
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
  min-width: 0;
}

.empty {
  padding: var(--gw-s6);
  border-radius: var(--gw-r);
  background: var(--gw-surface);
  box-shadow: var(--gw-hairline);
  font-size: var(--gw-fs-base);
  color: var(--gw-text-tertiary);
  line-height: var(--gw-lh);
}

.empty--inline {
  padding: var(--gw-s4) 0;
  background: transparent;
  box-shadow: none;
}

.muted {
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-tertiary);
}

.pad {
  padding: var(--gw-s4) var(--gw-s5);
}

.pad--actions {
  display: flex;
  gap: var(--gw-s2);
}

/* ── 锚点 ─────────────────────────────────────────────────────────── */
.anchors {
  margin-top: var(--gw-s4);
  padding-top: var(--gw-s3);
  border-top: 1px solid var(--gw-line-soft);
}

.anchors__row {
  display: flex;
  gap: var(--gw-s3);
  padding: 5px 0;
}

.anchors__id {
  flex: none;
  font-size: var(--gw-fs-xs);
  color: var(--gw-accent-text);
}

.anchors__text {
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-secondary);
  line-height: var(--gw-lh);
}

/* ── 版本 ─────────────────────────────────────────────────────────── */
.ver {
  display: flex;
  align-items: center;
  gap: var(--gw-s3);
  padding: var(--gw-s3) var(--gw-s5);
  border-bottom: 1px solid var(--gw-line-soft);
}

.ver__main {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: var(--gw-s2);
  flex-wrap: wrap;
}

.ver__label {
  font-size: var(--gw-fs-base);
  font-weight: 600;
  color: var(--gw-text);
}

.ver__reason,
.ver__hash {
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
}

.ver__reason {
  min-width: 0;
  max-width: 100%;
}

.ver__side {
  flex: none;
}

/* ── 批准记录 ─────────────────────────────────────────────────────── */
.apr {
  display: flex;
  align-items: center;
  gap: var(--gw-s3);
  padding: var(--gw-s3) var(--gw-s5);
  border-bottom: 1px solid var(--gw-line-soft);
}

.apr__main {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: var(--gw-s2);
}

.apr__id,
.apr__time {
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
}

/* ── 操作 ─────────────────────────────────────────────────────────── */
.ops {
  display: flex;
  flex-direction: column;
  gap: var(--gw-s2);
}

.ops__tip {
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-tertiary);
  line-height: var(--gw-lh);
}

/* ── 弹层表单 ─────────────────────────────────────────────────────── */
.file {
  display: flex;
  align-items: center;
  gap: var(--gw-s3);
  margin-bottom: var(--gw-s4);
}

.file__name {
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-secondary);
  min-width: 0;
}

.field {
  display: block;
}

.field__label {
  display: block;
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-secondary);
  margin-bottom: var(--gw-s2);
}

.field__input {
  width: 100%;
  padding: var(--gw-s3);
  border: 1px solid var(--gw-line-strong);
  border-radius: var(--gw-r-sm);
  font-family: inherit;
  font-size: var(--gw-fs-base);
  color: var(--gw-text);
  resize: vertical;
}

.field__input:focus {
  outline: none;
  border-color: var(--gw-accent);
}

/* ── 影响范围 ─────────────────────────────────────────────────────── */
.impact__row {
  display: flex;
  gap: var(--gw-s4);
  padding: 6px 0;
  border-bottom: 1px solid var(--gw-line-soft);
}

.impact__row:last-child {
  border-bottom: 0;
}

.impact__row dt {
  width: 96px;
  flex: none;
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-tertiary);
}

.impact__row dd {
  flex: 1;
  min-width: 0;
  font-size: var(--gw-fs-base);
  color: var(--gw-text);
  line-height: var(--gw-lh);
}

/* ── 差异 ─────────────────────────────────────────────────────────── */
.tool-hint {
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
}

.diff__stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--gw-s3);
  padding-bottom: var(--gw-s4);
  border-bottom: 1px solid var(--gw-line);
}

.diff__stat-label {
  display: block;
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
}

.diff__stat-value {
  display: block;
  margin-top: 2px;
  font-size: var(--gw-fs-lg);
  font-weight: 600;
  color: var(--gw-text);
}

.diff__list {
  margin-top: var(--gw-s5);
  display: flex;
  flex-direction: column;
  gap: var(--gw-s3);
}

.diff__item {
  display: flex;
  gap: var(--gw-s4);
  padding: var(--gw-s3) var(--gw-s4);
  border-radius: var(--gw-r-sm);
  background: var(--gw-bg-sunken);
  border-left: 3px solid var(--gw-line-strong);
}

.diff__item[data-kind='REPLACED'] {
  border-left-color: var(--gw-warning);
}
.diff__item[data-kind='INSERTED'] {
  border-left-color: var(--gw-success);
}
.diff__item[data-kind='DELETED'] {
  border-left-color: var(--gw-danger);
}

.diff__tag {
  width: 84px;
  flex: none;
}

.diff__kind {
  display: block;
  font-size: var(--gw-fs-sm);
  font-weight: 600;
  color: var(--gw-text);
}

.diff__anchor {
  display: block;
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
  margin-top: 2px;
}

.diff__body {
  flex: 1;
  min-width: 0;
}

.diff__old,
.diff__new {
  font-size: var(--gw-fs-base);
  line-height: var(--gw-lh);
  word-break: break-word;
}

.diff__old {
  color: var(--gw-text-tertiary);
  text-decoration: line-through;
  text-decoration-color: color-mix(in srgb, var(--gw-risk-high) 45%, transparent);
}

.diff__new {
  color: var(--gw-text);
  margin-top: 3px;
}

.diff__unsupported {
  margin-top: var(--gw-s5);
  padding: var(--gw-s4);
  border-radius: var(--gw-r-sm);
  background: var(--gw-warning-bg);
}

.diff__unsupported-title {
  font-size: var(--gw-fs-sm);
  font-weight: 600;
  color: var(--gw-text);
  margin-bottom: var(--gw-s2);
}

.diff__unsupported li {
  display: flex;
  gap: var(--gw-s3);
  padding: 3px 0;
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-secondary);
  line-height: var(--gw-lh);
}

/* ── 复审结论 ─────────────────────────────────────────────────────── */
.rr__verdict {
  padding: var(--gw-s4) var(--gw-s5);
  border-radius: var(--gw-r-sm);
  border-left: 3px solid var(--gw-accent);
  background: var(--gw-accent-faint);
  font-size: var(--gw-fs-md);
  font-weight: 600;
  color: var(--gw-text);
}

.rr__verdict[data-pass='false'] {
  border-left-color: var(--gw-warning);
  background: var(--gw-warning-bg);
}

.rr__steps {
  margin-top: var(--gw-s5);
  display: flex;
  flex-direction: column;
  gap: var(--gw-s4);
}

.rr__step {
  padding-left: var(--gw-s4);
  border-left: 2px solid var(--gw-line);
}

.rr__stage {
  display: block;
  font-size: var(--gw-fs-sm);
  font-weight: 600;
  color: var(--gw-text);
}

.rr__summary {
  display: block;
  margin-top: 2px;
  font-size: var(--gw-fs-base);
  color: var(--gw-text-secondary);
  line-height: var(--gw-lh);
}

.rr__details {
  margin-top: var(--gw-s2);
}

.rr__details li {
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-tertiary);
  line-height: var(--gw-lh);
}

.rr__new {
  margin-top: var(--gw-s5);
  padding: var(--gw-s4);
  border-radius: var(--gw-r-sm);
  background: var(--gw-bg-sunken);
}

.rr__new-title {
  font-size: var(--gw-fs-sm);
  font-weight: 600;
  color: var(--gw-text);
  margin-bottom: var(--gw-s2);
}

.rr__new li {
  display: flex;
  gap: var(--gw-s3);
  padding: 3px 0;
  font-size: var(--gw-fs-base);
  color: var(--gw-text-secondary);
}

.rr__md {
  margin-top: var(--gw-s5);
  padding-top: var(--gw-s4);
  border-top: 1px solid var(--gw-line);
  font-size: var(--gw-fs-base);
  color: var(--gw-text-secondary);
  line-height: var(--gw-lh);
}

/* Markdown 渲染出的元素没有 scoped 属性，需要 :deep 才能命中 */
.rr__md :deep(.md__p) {
  margin: 0 0 var(--gw-s2);
}

.rr__md :deep(.md__code) {
  padding: var(--gw-s3);
  border-radius: var(--gw-r-sm);
  background: var(--gw-bg-sunken);
  font-size: var(--gw-fs-sm);
  overflow-x: auto;
}
</style>
