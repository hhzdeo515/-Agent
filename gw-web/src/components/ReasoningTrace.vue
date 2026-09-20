<script setup lang="ts">
/**
 * 推理轨迹：Agent 的思考过程，可折叠。
 *
 * 为什么单独成块而不是混在回复正文里：
 * 法务场景下「结论」与「结论怎么来的」必须能分开看——法务要审的是依据，
 * 不是文采。折叠态只占一行，展开后逐步骤显示检索、比对、判定过程。
 * 这对应需求第 11 条的「内容解析 → 候选识别 → 规则检索 → 风险判断 → 结构化输出」五阶段。
 */
import { ref } from 'vue'

export interface TraceStep {
  /** 阶段名，如「规则与证据检索」 */
  stage: string
  /** 一句话结果 */
  summary: string
  /** 关键细节，逐条列示 */
  details?: string[]
  /** 耗时毫秒 */
  costMs?: number
  state?: 'done' | 'running' | 'skipped'
}

const props = defineProps<{
  steps: TraceStep[]
  /** 默认是否展开 */
  defaultOpen?: boolean
}>()

const open = ref(props.defaultOpen ?? false)
</script>

<template>
  <div class="trace">
    <button class="trace__head" type="button" :aria-expanded="open" @click="open = !open">
      <svg
        class="trace__chevron"
        :class="{ 'is-open': open }"
        viewBox="0 0 16 16"
        width="12"
        height="12"
        fill="none"
        aria-hidden="true"
      >
        <path
          d="M6 3.5 10.5 8 6 12.5"
          stroke="currentColor"
          stroke-width="1.5"
          stroke-linecap="round"
          stroke-linejoin="round"
        />
      </svg>
      <span class="trace__title">推理过程</span>
      <span class="trace__count">{{ steps.length }} 步</span>
      <span class="trace__rule" />
      <span v-if="!open" class="trace__peek gw-truncate">
        {{ steps.map((s) => s.stage).join(' · ') }}
      </span>
    </button>

    <ol v-show="open" class="trace__list">
      <li v-for="(s, i) in steps" :key="i" class="trace__step">
        <span class="trace__rail" aria-hidden="true">
          <span class="trace__node" :class="`is-${s.state ?? 'done'}`" />
          <span v-if="i < steps.length - 1" class="trace__line" />
        </span>

        <div class="trace__body">
          <div class="trace__row">
            <span class="trace__stage">{{ s.stage }}</span>
            <span v-if="s.costMs != null" class="trace__cost gw-mono">{{ s.costMs }}ms</span>
          </div>
          <p class="trace__summary">{{ s.summary }}</p>
          <ul v-if="s.details?.length" class="trace__details">
            <li v-for="(d, j) in s.details" :key="j">{{ d }}</li>
          </ul>
        </div>
      </li>
    </ol>
  </div>
</template>

<style scoped>
.trace {
  border: 1px solid var(--gw-line);
  border-radius: var(--gw-r);
  background: var(--gw-bg-sunken);
  overflow: hidden;
}

.trace__head {
  display: flex;
  align-items: center;
  gap: var(--gw-s2);
  width: 100%;
  padding: 9px var(--gw-s4);
  border: 0;
  background: transparent;
  color: var(--gw-text-secondary);
  text-align: left;
}

.trace__head:hover {
  background: rgba(27, 46, 51, 0.02);
}

.trace__chevron {
  flex: none;
  color: var(--gw-text-tertiary);
  transition: transform var(--gw-dur-fast) var(--gw-ease);
}

.trace__chevron.is-open {
  transform: rotate(90deg);
}

.trace__title {
  font-size: var(--gw-fs-sm);
  font-weight: 600;
  color: var(--gw-text);
  flex: none;
}

.trace__count {
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
  font-variant-numeric: tabular-nums;
  flex: none;
}

.trace__rule {
  width: 1px;
  height: 10px;
  background: var(--gw-line-strong);
  flex: none;
}

.trace__peek {
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
  min-width: 0;
}

.trace__list {
  padding: 0 var(--gw-s4) var(--gw-s4);
}

.trace__step {
  display: flex;
  gap: var(--gw-s3);
}

.trace__rail {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 8px;
  flex: none;
  padding-top: 4px;
}

.trace__node {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex: none;
  background: var(--gw-accent);
  box-shadow: 0 0 0 3px var(--gw-accent-faint);
}

.trace__node.is-running {
  background: var(--gw-warning);
  box-shadow: 0 0 0 3px var(--gw-warning-bg);
}

.trace__node.is-skipped {
  background: var(--gw-line-strong);
  box-shadow: none;
}

.trace__line {
  flex: 1;
  width: 1px;
  background: var(--gw-line-strong);
  margin: 3px 0 0;
}

.trace__body {
  flex: 1;
  min-width: 0;
  padding-bottom: var(--gw-s4);
}

.trace__step:last-child .trace__body {
  padding-bottom: 0;
}

.trace__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--gw-s3);
}

.trace__stage {
  font-size: var(--gw-fs-sm);
  font-weight: 600;
  color: var(--gw-text);
}

.trace__cost {
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
  flex: none;
}

.trace__summary {
  font-size: var(--gw-fs-base);
  color: var(--gw-text-secondary);
  line-height: var(--gw-lh);
  margin-top: 2px;
}

.trace__details {
  margin-top: var(--gw-s2);
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.trace__details li {
  position: relative;
  padding-left: var(--gw-s3);
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-tertiary);
  line-height: var(--gw-lh);
}

.trace__details li::before {
  content: '';
  position: absolute;
  left: 0;
  top: 8px;
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: var(--gw-line-strong);
}
</style>
