<script setup lang="ts">
/**
 * 当前审核任务切换器。
 *
 * 存在的理由：四个模块围绕同一个 Case 流转，而"最近的任务"未必是"正在处理的任务"——
 * 没有切换器，用户只能被迫看最新创建的那个（往往是刚建的空任务），
 * 或者被迫重新上传一遍材料。
 *
 * 只负责切换与展示，不做任何权限判断：能不能看某个任务由后端决定。
 */
import { computed, onMounted, ref } from 'vue'
import { useActiveCase, switchCase } from '@/stores/activeCase'

const { activeCase, recentCases, loadRecentCases } = useActiveCase()

const open = ref(false)
const loadFailed = ref(false)

const current = computed(() => activeCase.value)

onMounted(() => {
  void refreshList()
})

async function refreshList() {
  try {
    await loadRecentCases()
    loadFailed.value = false
  } catch {
    loadFailed.value = true
  }
}

function pick(id: number) {
  open.value = false
  switchCase(id)
}

const STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿',
  PARSING: '解析中',
  READY_FOR_REVIEW: '待启动初审',
  AI_REVIEWING: 'AI 初审中',
  FEEDBACK_PENDING: '反馈区待处理',
  REMEDIATION: '整改中',
  FINAL_REVIEW: '终审中',
  APPROVED: '已批准',
  REJECTED: '已驳回',
  ARCHIVED: '已归档',
}
</script>

<template>
  <div class="cs">
    <button class="cs__btn" type="button" :aria-expanded="open" @click="open = !open; if (open) refreshList()">
      <span class="cs__label">任务</span>
      <span class="cs__no gw-mono">{{ current?.caseNo ?? '未选择' }}</span>
      <svg class="cs__caret" :class="{ 'is-open': open }" viewBox="0 0 10 6" width="9" height="6" fill="none">
        <path d="M1 1.2 5 4.8 9 1.2" stroke="currentColor" stroke-width="1.4"
          stroke-linecap="round" stroke-linejoin="round" />
      </svg>
    </button>

    <div v-if="open" class="cs__mask" @click="open = false" />

    <div v-if="open" class="cs__menu">
      <div class="cs__head">
        <span>切换审核任务</span>
        <button class="cs__refresh" type="button" @click="refreshList">刷新</button>
      </div>
      <p v-if="loadFailed" class="cs__err">任务列表加载失败</p>
      <ul v-else-if="recentCases.length" class="cs__list">
        <li v-for="c in recentCases" :key="c.id">
          <button
            class="cs__item"
            :class="{ 'is-current': c.id === current?.id }"
            type="button"
            @click="pick(c.id)"
          >
            <span class="cs__item-no gw-mono">{{ c.caseNo }}</span>
            <span class="cs__item-name gw-truncate">{{ c.name }}</span>
            <span class="cs__item-meta">
              {{ c.materialCount }} 份 · {{ STATUS_LABEL[c.status] ?? c.status }}
            </span>
          </button>
        </li>
      </ul>
      <p v-else class="cs__err">还没有审核任务，请先到接收区提交材料。</p>
    </div>
  </div>
</template>

<style scoped>
.cs {
  position: relative;
  flex: none;
}

.cs__btn {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 10px;
  border: 1px solid var(--gw-line);
  border-radius: var(--gw-r-sm);
  background: var(--gw-surface);
  transition: border-color var(--gw-dur-fast) var(--gw-ease);
}

.cs__btn:hover {
  border-color: var(--gw-line-strong);
}

.cs__label {
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
}

.cs__no {
  font-size: var(--gw-fs-sm);
  color: var(--gw-text);
}

.cs__caret {
  color: var(--gw-text-tertiary);
  transition: transform var(--gw-dur-fast) var(--gw-ease);
}

.cs__caret.is-open {
  transform: rotate(180deg);
}

.cs__mask {
  position: fixed;
  inset: 0;
  z-index: 40;
}

.cs__menu {
  position: absolute;
  top: calc(100% + 6px);
  right: 0;
  z-index: 50;
  width: 340px;
  max-height: 380px;
  overflow-y: auto;
  border: 1px solid var(--gw-line);
  border-radius: var(--gw-r);
  background: var(--gw-surface);
  box-shadow: var(--gw-elev-3, 0 12px 32px rgba(22, 38, 43, 0.16));
}

.cs__head {
  position: sticky;
  top: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background: var(--gw-accent-faint);
  border-bottom: 1px solid var(--gw-line);
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-secondary);
}

.cs__refresh {
  font-size: var(--gw-fs-xs);
  color: var(--gw-accent-text);
}

.cs__list {
  padding: 4px;
}

.cs__item {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 2px 8px;
  width: 100%;
  padding: 7px 8px;
  border-radius: var(--gw-r-sm);
  text-align: left;
  transition: background var(--gw-dur-fast) var(--gw-ease);
}

.cs__item:hover {
  background: var(--gw-surface-hover);
}

.cs__item.is-current {
  background: var(--gw-accent-soft);
}

.cs__item-no {
  grid-column: 1;
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
}

.cs__item-meta {
  grid-column: 2;
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
  white-space: nowrap;
}

.cs__item-name {
  grid-column: 1 / -1;
  font-size: var(--gw-fs-base);
  color: var(--gw-text);
  min-width: 0;
}

.cs__err {
  padding: 14px 12px;
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-tertiary);
  text-align: center;
}
</style>
