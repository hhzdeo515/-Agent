/**
 * 助手咨询 → 正式审核任务 的预填草稿。
 *
 * AGENTS.md 第 3 条：助手不能创建正式审核任务，但用户希望把临时分析转成正式审核时，
 * 应提供「转为正式审核任务」入口，<b>并把输入材料和已填写信息带入接收区</b>，
 * 由用户确认后再创建任务。
 *
 * 因此这里传的是"草稿"而不是"结果"：接收区必须原样呈现它、允许用户改，
 * 且创建动作仍然只发生在接收区。
 *
 * 用内存变量而不是 localStorage：草稿只服务于"刚点完跳转"这一次交接，
 * 存到磁盘会让用户下次打开系统时莫名其妙看到一段上次的咨询文案。
 */
import { ref } from 'vue'

export interface PromoteDraft {
  /** 助手建议的任务名 */
  proposedCaseName: string
  /** 用户在助手里的原始输入，可作为一份文本材料带入 */
  sourceText: string
  /** 助手建议的关注点 */
  suggestedRequirements: string[]
}

const draft = ref<PromoteDraft | null>(null)

export function setPromoteDraft(d: PromoteDraft | null) {
  draft.value = d
}

/** 取走草稿并清空：同一次咨询只应被带入一次，否则用户每进一次接收区都被重新预填 */
export function takePromoteDraft(): PromoteDraft | null {
  const d = draft.value
  draft.value = null
  return d
}

export function usePromoteDraft() {
  return { draft, setPromoteDraft, takePromoteDraft }
}
