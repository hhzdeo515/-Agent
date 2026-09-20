/**
 * 三个技能模块的元数据 —— 单一事实来源。
 *
 * 左侧导航、模块头部标识、状态条、路由、点缀色全部由此派生，
 * 避免同一概念在多个组件里各写一份（否则改一处必然漏一处）。
 *
 * 关于模块命名：GASP / react / tast 沿用参考图中的技能标识，
 * 括号内是它们在广宣法务审核业务中实际承载的流程段，
 * 由「接收任务 → 全量初审 → 风险整改闭环」三段构成。
 */

export type ModuleKey = 'gasp' | 'react' | 'tast'

export interface ModuleMeta {
  key: ModuleKey
  /** 技能标识（导航与模块头部主标识） */
  code: string
  /** 中文技能名 */
  name: string
  /** 承载的业务流程段 */
  business: string
  /** 一句话职责说明，用于导航悬浮与空态 */
  desc: string
  /** 点缀色 CSS 变量前缀：ink / brass / wine */
  tone: 'ink' | 'brass' | 'wine'
  /** 后端接口前缀 */
  apiPrefix: string
  /** 该模块默认展示的状态条项 */
  statusHints: string[]
}

export const MODULES: ModuleMeta[] = [
  {
    key: 'gasp',
    code: 'GASP',
    name: '信息研判',
    business: '反馈区 · AI 全量初审',
    desc: '一批材料的第一次 AI 全量扫描：风险识别、分类与定位',
    tone: 'ink',
    apiPrefix: '/api/feedback',
    statusHints: ['物料三分类', '风险定位', '人工判断'],
  },
  {
    key: 'react',
    code: 'react',
    name: '事件响应',
    business: '终审区 · 整改与处置',
    desc: '已确认风险的整改闭环：版本推进、复审、签名与关闭',
    tone: 'brass',
    apiPrefix: '/api/final-review',
    statusHints: ['版本时间线', 'AI 复审', '法务签名'],
  },
  {
    key: 'tast',
    code: 'tast',
    name: '任务调度',
    business: '接收区 · 任务与材料',
    desc: '审核任务的创建、材料接收、解析调度与审核要求',
    tone: 'wine',
    apiPrefix: '/api/intake',
    statusHints: ['任务信息', '解析进度', '审核要求'],
  },
]

export const MODULE_MAP: Record<ModuleKey, ModuleMeta> = MODULES.reduce(
  (acc, m) => {
    acc[m.key] = m
    return acc
  },
  {} as Record<ModuleKey, ModuleMeta>,
)

export const DEFAULT_MODULE: ModuleKey = 'tast'
