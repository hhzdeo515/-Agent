/**
 * 四个核心模块的元数据 —— 单一事实来源。
 *
 * 模块划分严格依据《广宣法务审核 Agent 项目说明》（AGENTS.md）第 1 条与第 16 条：
 *   「AI 法务助手、接收区、反馈区、终审区」四个核心模块。
 *   判断功能归属的原则：助手管问题、接收管任务、反馈管初审、终审管风险闭环。
 *
 * 左侧导航、模块头部标识、状态条、路由、点缀色全部由此派生，
 * 避免同一概念在多个组件里各写一份（否则改一处必然漏一处）。
 *
 * 点缀色分配：墨青为主色，哑光铜金与暗酒红作区分；助手是旁路入口，
 * 用中性灰蓝以免与三条正式流程抢视觉重量。
 */

export type ModuleKey = 'assistant' | 'intake' | 'feedback' | 'finalReview'

export interface ModuleMeta {
  key: ModuleKey
  /** 模块名（与 AGENTS.md 用词一致，不另造词） */
  name: string
  /** 一句话职责 */
  desc: string
  /** 在流程中的角色，显示于模块头部副标题 */
  role: string
  /** 点缀色调 */
  tone: 'slate' | 'ink' | 'brass' | 'wine'
  /** 该模块的核心职责条目，显示为状态条 */
  statusHints: string[]
  /** 后端接口前缀；助手不走正式流程，故为独立路径 */
  apiPrefix: string
  /** 是否属于正式审核流程（助手不属于） */
  inFormalFlow: boolean
}

export const MODULES: ModuleMeta[] = [
  {
    key: 'assistant',
    name: 'AI 法务助手',
    desc: '零散问题与单条咨询的即时入口',
    role: '轻量咨询 · 不参与正式流程',
    tone: 'slate',
    statusHints: ['单条咨询', '临时上传', '可转正式任务'],
    apiPrefix: '/api/assistant',
    inFormalFlow: false,
  },
  {
    key: 'intake',
    name: '接收区',
    desc: '创建审核任务、接收材料、完成解析并记录审核要求',
    role: '正式审核流程的唯一入口',
    tone: 'ink',
    statusHints: ['任务信息', '解析进度', '审核要求'],
    apiPrefix: '/api/intake',
    inFormalFlow: true,
  },
  {
    key: 'feedback',
    name: '反馈区',
    desc: '第一次 AI 全量初审：发现、分类并定位风险',
    role: '全量初审与人工判断',
    tone: 'brass',
    statusHints: ['物料三分类', '风险定位', '人工判断'],
    apiPrefix: '/api/feedback',
    inFormalFlow: true,
  },
  {
    key: 'finalReview',
    name: '终审区',
    desc: '已确认风险的整改闭环：版本、复审、签名与关闭',
    role: '风险整改闭环',
    tone: 'wine',
    statusHints: ['版本时间线', 'AI 复审', '法务签名'],
    apiPrefix: '/api/final-review',
    inFormalFlow: true,
  },
]

export const MODULE_MAP: Record<ModuleKey, ModuleMeta> = MODULES.reduce(
  (acc, m) => {
    acc[m.key] = m
    return acc
  },
  {} as Record<ModuleKey, ModuleMeta>,
)

/** 默认进入接收区：它是正式流程的起点 */
export const DEFAULT_MODULE: ModuleKey = 'intake'

/** 路由路径映射（短横线形式，便于阅读） */
export const MODULE_PATHS: Record<ModuleKey, string> = {
  assistant: '/assistant',
  intake: '/intake',
  feedback: '/feedback',
  finalReview: '/final-review',
}
