<script setup lang="ts">
/**
 * 模块头部：独立标识 + 状态条。
 *
 * 设计意图：三个模块各自有明确的「我是谁」与「我现在什么状态」，
 * 使决策者一进入模块就能确认上下文，而不用从内容反推。
 * 状态条走的是「描边胶囊」编码，与风险等级的「实心色块」刻意区分（需求第 13 条）。
 */
import type { ModuleMeta } from '@/config/modules'

defineProps<{
  meta: ModuleMeta
  /** 状态条右侧的补充统计，如 "12 份物料 · 3 项待处理" */
  summary?: string
}>()
</script>

<template>
  <header class="mh">
    <div class="mh__identity">
      <span class="mh__code">{{ meta.code }}</span>
      <span class="mh__sep" aria-hidden="true" />
      <div class="mh__text">
        <h1 class="mh__name">{{ meta.name }}</h1>
        <p class="mh__business">{{ meta.business }}</p>
      </div>
    </div>

    <div class="mh__status">
      <span v-for="hint in meta.statusHints" :key="hint" class="gw-status" :class="`gw-status--${meta.tone}`">
        <span class="gw-status__dot" />
        {{ hint }}
      </span>
      <span v-if="summary" class="mh__summary">{{ summary }}</span>
    </div>
  </header>
</template>

<style scoped>
.mh {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--gw-s6);
  height: var(--gw-header-h);
  flex: none;
  padding: 0 var(--gw-s7);
  background: var(--gw-surface);
  border-bottom: 1px solid var(--gw-line);
}

.mh__identity {
  display: flex;
  align-items: center;
  gap: var(--gw-s3);
  min-width: 0;
}

/* 模块主标识：用点缀色，让三个模块一眼可辨 */
.mh__code {
  font-size: var(--gw-fs-lg);
  font-weight: 700;
  letter-spacing: 0.02em;
  color: var(--gw-accent-strong);
  line-height: 1;
}

.mh__sep {
  width: 1px;
  height: 20px;
  background: var(--gw-line-strong);
  flex: none;
}

.mh__text {
  min-width: 0;
}

.mh__name {
  font-size: var(--gw-fs-md);
  font-weight: 600;
  color: var(--gw-text);
  line-height: 1.25;
  letter-spacing: -0.01em;
}

.mh__business {
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-tertiary);
  line-height: 1.35;
  margin-top: 1px;
}

.mh__status {
  display: flex;
  align-items: center;
  gap: var(--gw-s2);
  flex: none;
}

.mh__summary {
  padding-left: var(--gw-s2);
  margin-left: var(--gw-s1);
  border-left: 1px solid var(--gw-line);
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-secondary);
  font-variant-numeric: tabular-nums;
}

@media (max-width: 1180px) {
  .mh {
    padding: 0 var(--gw-s5);
  }
  .mh__status {
    display: none;
  }
}
</style>
