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
      <span class="mh__badge" aria-hidden="true" />
      <div class="mh__text">
        <h1 class="mh__name">{{ meta.name }}</h1>
        <p class="mh__business">{{ meta.role }}</p>
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

/* 模块标识：一块点缀色方块 + 内部高光点，2D 矢量风格，不用 logo 图片 */
.mh__badge {
  position: relative;
  width: 26px;
  height: 26px;
  flex: none;
  border-radius: var(--gw-r-sm);
  background: var(--gw-accent);
  box-shadow: 0 1px 2px rgba(27, 46, 51, 0.12), inset 0 1px 0 rgba(255, 255, 255, 0.22);
}

/* 内部小方块：让标识块不是纯色块，多一个层次 */
.mh__badge::after {
  content: '';
  position: absolute;
  left: 7px;
  top: 7px;
  width: 12px;
  height: 12px;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.9);
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
