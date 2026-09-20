<script setup lang="ts">
/**
 * AI 法务助手（AGENTS.md 第 3 条）
 *
 * 这是真正可用的对话入口：输入内容 → 后端 /api/assistant/chat → 返回风险分析、
 * 推理过程与改写建议，并可把本次咨询转为正式审核任务。
 *
 * 边界（由后端从依赖关系上保证，前端也如实呈现）：
 *  不能创建正式审核任务、不能批次审核、不能生成通过率、不能改变风险状态、
 *  不能执行终审/签名/批准/关闭。
 */
import { ref } from 'vue'
import { ApiError, apiPost, apiUpload } from '@/api/client'
import ConversationThread, { type Message } from '@/components/ConversationThread.vue'
import type { TraceStep } from '@/components/ReasoningTrace.vue'
import Composer from '@/components/Composer.vue'

interface RiskItem {
  riskType: string
  riskLevel: string
  riskText: string
  reason: string
  suggestion?: string
  recommendedCopy?: string
  requiredEvidence?: string
}

const messages = ref<Message[]>([])
const sending = ref(false)
const uploading = ref(false)
const sessionId = ref<string | null>(null)
const errorText = ref<string | null>(null)

const attachment = ref<{ name: string; text: string } | null>(null)

/** 本次会话里累积识别出的风险，用于右侧面板 */
const risks = ref<RiskItem[]>([])
const needsFormal = ref(false)

function nowLabel() {
  const d = new Date()
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

// 开场白：让用户知道这里能做什么、不能做什么
messages.value.push({
  id: 'm0',
  role: 'agent',
  text:
    '我是广宣法务审核助手，可以直接问我一句宣传语、一段文案，或上传一张图片。\n\n' +
    '我会指出其中的风险点、说明原因与依据、给出改写建议。' +
    '涉及要对外发布的一批材料时，建议转为正式审核任务，由法务逐条确认并留痕。',
  time: nowLabel(),
})

async function onSend(text: string) {
  errorText.value = null
  messages.value.push({
    id: 'u' + Date.now(),
    role: 'user',
    text,
    time: nowLabel(),
  })

  sending.value = true
  try {
    const data = await apiPost<{
      sessionId: string
      reply: string
      needsFormalReview: boolean
      risks: RiskItem[]
      trace: TraceStep[]
    }>('/api/assistant/chat', {
      message: text,
      sessionId: sessionId.value,
      attachmentName: attachment.value?.name,
      attachmentText: attachment.value?.text,
    })

    sessionId.value = data.sessionId
    needsFormal.value = data.needsFormalReview || needsFormal.value
    if (data.risks?.length) {
      // 同一风险文案只保留一条，避免多轮对话里重复累积
      for (const r of data.risks) {
        if (!risks.value.some((x) => x.riskText === r.riskText)) risks.value.push(r)
      }
    }

    const hasHigh = (data.risks ?? []).some((r) => r.riskLevel === 'HIGH')
    messages.value.push({
      id: 'a' + Date.now(),
      role: 'agent',
      text: data.reply,
      time: nowLabel(),
      tone: hasHigh ? 'danger' : data.risks?.length ? 'warning' : 'neutral',
      trace: data.trace,
    })
    attachment.value = null
  } catch (err) {
    const msg = err instanceof ApiError ? err.message : '请求失败，请稍后重试'
    errorText.value = msg
    messages.value.push({
      id: 'e' + Date.now(),
      role: 'agent',
      text: `抱歉，本次咨询没有成功：${msg}`,
      time: nowLabel(),
      tone: 'warning',
    })
  } finally {
    sending.value = false
  }
}

async function onAttach(file: File) {
  uploading.value = true
  errorText.value = null
  try {
    const form = new FormData()
    form.append('file', file)
    const r = await apiUpload<{ fileName: string; extractedText: string; textExtracted: boolean }>(
      '/api/assistant/upload',
      form,
    )
    attachment.value = { name: r.fileName, text: r.extractedText }
    if (!r.textExtracted) {
      errorText.value = '该文件已上传，但当前无法提取文字内容（图片/PDF 需要解析能力），请补充文字说明'
    }
  } catch (err) {
    errorText.value = err instanceof ApiError ? err.message : '附件上传失败'
  } finally {
    uploading.value = false
  }
}

async function onPromote() {
  const lastUser = [...messages.value].reverse().find((m) => m.role === 'user')
  if (!lastUser) return
  try {
    await apiPost('/api/assistant/promote', {
      sessionId: sessionId.value,
      message: lastUser.text,
    })
    window.location.hash = '#/intake'
  } catch (err) {
    errorText.value = err instanceof ApiError ? err.message : '转换失败'
  }
}
</script>

<template>
  <div class="view">
    <ConversationThread :messages="messages" />

    <p v-if="errorText" class="err">{{ errorText }}</p>

    <Composer
      :sending="sending"
      :uploading="uploading"
      :attachment-name="attachment?.name"
      @send="onSend"
      @attach="onAttach"
      @remove-attachment="attachment = null"
    />

    <!--
      转为正式审核：助手不设右侧分栏，因此把这个唯一的跨模块动作放在对话流末尾。
      它只在本次咨询确实识别出风险、或输入较长时才出现，避免成为常驻噪音。
    -->
    <div v-if="needsFormal" class="promote">
      <div class="promote__text">
        <span class="promote__title">本次咨询涉及对外发布内容</span>
        <span class="promote__sub">
          可转为正式审核任务，由法务逐条确认并留痕（不会自动创建任务）
        </span>
      </div>
      <button class="gw-btn gw-btn--primary" type="button" @click="onPromote">
        转为正式审核任务
      </button>
    </div>
  </div>
</template>

<style scoped>
.view {
  display: contents;
}

.err {
  max-width: 920px;
  margin-top: var(--gw-s4);
  padding: var(--gw-s3) var(--gw-s4);
  border-radius: var(--gw-r-sm);
  border-left: 3px solid var(--gw-warning);
  background: var(--gw-warning-bg);
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-secondary);
  line-height: var(--gw-lh);
}

/* ── 转为正式审核（对话流末尾的跨模块动作）──────────────────────────── */
.promote {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--gw-s5);
  max-width: 920px;
  margin-top: var(--gw-s5);
  padding: var(--gw-s4) var(--gw-s5);
  border: 1px solid var(--gw-line);
  border-left: 3px solid var(--gw-accent);
  border-radius: var(--gw-r);
  background: var(--gw-surface);
  box-shadow: var(--gw-elev-1);
}

.promote__text {
  min-width: 0;
}

.promote__title {
  display: block;
  font-size: var(--gw-fs-md);
  font-weight: 600;
  color: var(--gw-text);
  line-height: 1.4;
}

.promote__sub {
  display: block;
  margin-top: 2px;
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-tertiary);
  line-height: var(--gw-lh);
}

/* ── 风险列表 ─────────────────────────────────────────────────────── */
.rl__item {
  padding: var(--gw-s4) var(--gw-s5);
  border-bottom: 1px solid var(--gw-line-soft);
  border-left: 3px solid transparent;
}

.rl__item[data-level='HIGH'] {
  border-left-color: var(--gw-risk-high);
}
.rl__item[data-level='MEDIUM'] {
  border-left-color: var(--gw-risk-medium);
}
.rl__item[data-level='LOW'] {
  border-left-color: var(--gw-risk-low);
}

.rl__top {
  display: flex;
  align-items: center;
  gap: var(--gw-s2);
  margin-bottom: var(--gw-s2);
}

.rl__type {
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
  padding: 0 5px;
  border: 1px solid var(--gw-line);
  border-radius: var(--gw-r-sm);
  line-height: 16px;
}

.rl__text {
  font-size: var(--gw-fs-md);
  font-weight: 500;
  color: var(--gw-text);
  line-height: 1.45;
}

.rl__reason {
  margin-top: 3px;
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-secondary);
  line-height: var(--gw-lh);
}

.rl__fix {
  margin-top: var(--gw-s3);
  padding: var(--gw-s3);
  border-radius: var(--gw-r-sm);
  background: var(--gw-accent-faint);
}

.rl__fix-label {
  display: block;
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
}

.rl__fix-text {
  display: block;
  margin-top: 2px;
  font-size: var(--gw-fs-sm);
  color: var(--gw-accent-text);
  line-height: var(--gw-lh);
}

.empty {
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-tertiary);
  line-height: var(--gw-lh);
}

/* ── 能力边界 ─────────────────────────────────────────────────────── */
.limits {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.limits li {
  position: relative;
  padding-left: var(--gw-s4);
  font-size: var(--gw-fs-base);
  color: var(--gw-text-secondary);
  line-height: var(--gw-lh);
}

.limits li::before {
  content: '';
  position: absolute;
  left: 0;
  top: 9px;
  width: 6px;
  height: 1.5px;
  border-radius: 1px;
  background: var(--gw-slate-400);
}
</style>
