/**
 * 当前审核任务（跨模块共享）。
 *
 * 四个模块围绕同一个 Case 流转：接收区创建 → 反馈区初审 → 终审区闭环。
 * 因此"当前任务是哪一个"必须是共享状态，否则用户在接收区创建的任务
 * 到了反馈区就找不到了，只能靠硬编码 id 演示——那不是 Agent，是演示页。
 *
 * 取值优先级：
 *   1. 本地记录的任务 id（用户上一次操作的上下文）；
 *   2. 后端最近创建的任务（首次进入或本地记录已失效时的兜底）。
 */
import { ref } from 'vue'
import { apiGet } from '@/api/client'

export interface CaseBrief {
  id: number
  caseNo: string
  name: string
  status: string
  materialCount: number
  requirementConfirmed: boolean
  createdAt?: string
}

const STORAGE_KEY = 'gw.activeCaseId'

const activeCaseId = ref<number | null>(readStored())
const activeCase = ref<CaseBrief | null>(null)
const recentCases = ref<CaseBrief[]>([])
const loading = ref(false)
const resolveError = ref<string | null>(null)

/**
 * 任务切换信号。
 *
 * 各模块视图订阅它来重新拉数据。<b>必须与 {@link setActiveCase} 分开</b>：
 * 视图自身在加载时也会写 activeCase（认领当前任务），如果写状态就发信号，
 * 视图会被自己触发的事件再加载一次，形成回环。
 */
const caseEpoch = ref(0)

export function useCaseEpoch() {
  return caseEpoch
}

/** 用户在界面上主动切换任务：更新当前任务并通知各模块重新加载 */
export function switchCase(id: number) {
  if (activeCaseId.value === id) return
  setActiveCase(id)
  activeCase.value = recentCases.value.find((c) => c.id === id) ?? null
  caseEpoch.value++
}

function readStored(): number | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    const n = raw ? Number(raw) : NaN
    return Number.isFinite(n) && n > 0 ? n : null
  } catch {
    return null
  }
}

export function setActiveCase(id: number | null) {
  activeCaseId.value = id
  try {
    if (id == null) localStorage.removeItem(STORAGE_KEY)
    else localStorage.setItem(STORAGE_KEY, String(id))
  } catch {
    /* 隐私模式下 localStorage 不可写，退化为本次会话内有效 */
  }
}

/** 拉取最近任务列表 */
export async function loadRecentCases(): Promise<CaseBrief[]> {
  const list = await apiGet<CaseBrief[]>('/api/intake/cases?limit=20')
  recentCases.value = list ?? []
  return recentCases.value
}

/**
 * 确定当前任务。
 *
 * @param preferred 显式指定时优先使用（例如从其它模块跳转过来）
 */
/**
 * 从地址栏取任务深链：{@code #/feedback?case=13}。
 *
 * 支持它的理由有两类，都不是"为了好看"：
 * 一是把某个任务发给同事时，对方打开就是同一个任务，而不是"最近的那个"；
 * 二是排查问题时能精确复现"我当时看的是哪个任务"。
 */
function caseFromUrl(): number | null {
  try {
    const m = /[?&]case=(\d+)/.exec(window.location.hash)
    const n = m ? Number(m[1]) : NaN
    return Number.isFinite(n) && n > 0 ? n : null
  } catch {
    return null
  }
}

export async function resolveActiveCase(preferred?: number | null): Promise<CaseBrief | null> {
  loading.value = true
  resolveError.value = null
  try {
    const wanted = preferred ?? caseFromUrl() ?? activeCaseId.value
    if (wanted != null) {
      const c = await apiGet<CaseBrief>(`/api/intake/cases/${wanted}`).then(
        // 详情接口返回 { case, materials, parseProgress }，这里只取 case
        (d) => (d as unknown as { case: CaseBrief }).case ?? (d as unknown as CaseBrief),
      )
      // 已归档的任务不再作为"当前任务"：它仍然可查，但继续停在它上面
      // 会让用户对着一份已经结束的材料操作，且任务切换器里也找不到它
      if (c?.id && c.status !== 'ARCHIVED') {
        activeCase.value = c
        setActiveCase(c.id)
        return c
      }
    }
    const list = await loadRecentCases()
    if (list.length > 0) {
      activeCase.value = list[0]
      setActiveCase(list[0].id)
      return list[0]
    }
    activeCase.value = null
    return null
  } catch (e) {
    resolveError.value = e instanceof Error ? e.message : '任务加载失败'
    return null
  } finally {
    loading.value = false
  }
}

export function useActiveCase() {
  return {
    activeCaseId,
    activeCase,
    recentCases,
    loading,
    resolveError,
    setActiveCase,
    switchCase,
    loadRecentCases,
    resolveActiveCase,
  }
}
