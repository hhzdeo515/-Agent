<script setup lang="ts">
/**
 * Agent 对话与推理过程（中间主区域）。
 *
 * 结构上刻意做成「消息流 + 附件式结构化卡片」：
 * 纯文本对话无法承载法务需要的结构化信息（风险条目、依据、定位），
 * 因此 Agent 的回复里可以嵌入指标块、风险条目、操作按钮等真实信息组件，
 * 而不是把一切塞进一段话里。
 */
import ReasoningTrace, { type TraceStep } from './ReasoningTrace.vue'

export interface Metric {
  label: string
  value: string | number
  hint?: string
}

export interface Message {
  id: string
  role: 'user' | 'agent'
  /** 正文；支持简单换行 */
  text: string
  time: string
  /** 仅 agent：推理轨迹 */
  trace?: TraceStep[]
  /** 仅 agent：关键指标 */
  metrics?: Metric[]
  /** 仅 agent：结论语气，用于左侧竖条着色 */
  tone?: 'neutral' | 'warning' | 'danger' | 'success'
}

defineProps<{ messages: Message[] }>()
</script>

<template>
  <div class="thread">
    <article
      v-for="m in messages"
      :key="m.id"
      class="msg"
      :class="`msg--${m.role}`"
      :data-tone="m.tone ?? 'neutral'"
    >
      <!-- 角色标识：不用头像图片，保持 2D 矢量与商务感 -->
      <div class="msg__gutter" aria-hidden="true">
        <span v-if="m.role === 'agent'" class="msg__badge">AI</span>
        <span v-else class="msg__badge msg__badge--user">我</span>
      </div>

      <div class="msg__main">
        <div class="msg__head">
          <span class="msg__who">{{ m.role === 'agent' ? '法务审核助手' : '我' }}</span>
          <span class="msg__time gw-mono">{{ m.time }}</span>
        </div>

        <div class="msg__bubble">
          <p class="msg__text">{{ m.text }}</p>

          <ReasoningTrace
            v-if="m.trace?.length"
            class="msg__trace"
            :steps="m.trace"
          />

          <div v-if="m.metrics?.length" class="msg__metrics">
            <div v-for="k in m.metrics" :key="k.label" class="metric">
              <div class="metric__label">{{ k.label }}</div>
              <div class="metric__value gw-mono">{{ k.value }}</div>
              <div v-if="k.hint" class="metric__hint">{{ k.hint }}</div>
            </div>
          </div>

          <slot :name="`actions-${m.id}`" />
        </div>
      </div>
    </article>
  </div>
</template>

<style scoped>
.thread {
  display: flex;
  flex-direction: column;
  gap: var(--gw-s7);
  max-width: 920px;
}

.msg {
  display: flex;
  gap: var(--gw-s3);
}

.msg__gutter {
  flex: none;
  width: 28px;
  padding-top: 2px;
}

/* 角色标识：小色块，不用圆形头像，避免卡通感 */
.msg__badge {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: var(--gw-r-sm);
  background: var(--gw-accent-soft);
  color: var(--gw-accent-strong);
  font-size: var(--gw-fs-xs);
  font-weight: 700;
  letter-spacing: 0.02em;
}

.msg__badge--user {
  background: var(--gw-bg-sunken);
  color: var(--gw-text-secondary);
}

.msg__main {
  flex: 1;
  min-width: 0;
}

.msg__head {
  display: flex;
  align-items: baseline;
  gap: var(--gw-s3);
  margin-bottom: var(--gw-s2);
}

.msg__who {
  font-size: var(--gw-fs-sm);
  font-weight: 600;
  color: var(--gw-text);
}

.msg__time {
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
}

/* 用户消息用底色区分，Agent 消息保持白底——因为 Agent 的回复才是主要阅读对象 */
.msg--user .msg__bubble {
  background: var(--gw-bg-sunken);
  border-color: transparent;
}

.msg__bubble {
  position: relative;
  padding: var(--gw-s4) var(--gw-s5);
  background: var(--gw-surface);
  border: 1px solid var(--gw-line);
  border-radius: var(--gw-r);
  box-shadow: var(--gw-shadow-sm);
}

/* 结论语气：左侧细竖条，克制但可辨 */
.msg[data-tone='warning'] .msg__bubble {
  border-left: 3px solid var(--gw-warning);
}
.msg[data-tone='danger'] .msg__bubble {
  border-left: 3px solid var(--gw-danger);
}
.msg[data-tone='success'] .msg__bubble {
  border-left: 3px solid var(--gw-success);
}

.msg__text {
  font-size: var(--gw-fs-md);
  line-height: var(--gw-lh-loose);
  color: var(--gw-text);
  white-space: pre-line;
}

.msg__trace {
  margin-top: var(--gw-s4);
}

.msg__metrics {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(132px, 1fr));
  gap: 1px;
  margin-top: var(--gw-s4);
  background: var(--gw-line);
  border: 1px solid var(--gw-line);
  border-radius: var(--gw-r);
  overflow: hidden;
}

.metric {
  padding: var(--gw-s3) var(--gw-s4);
  background: var(--gw-surface);
}

.metric__label {
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
  letter-spacing: 0.02em;
}

.metric__value {
  font-size: var(--gw-fs-xl);
  font-weight: 600;
  color: var(--gw-text);
  line-height: 1.3;
  margin-top: 2px;
}

.metric__hint {
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
  margin-top: 1px;
}
</style>
