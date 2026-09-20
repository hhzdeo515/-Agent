<script setup lang="ts">
/**
 * 通用弹层。
 *
 * 用于承载两类内容：AI 初审报告（长文档）与高风险动作的二次确认。
 * 刻意不用组件库的 Dialog：项目的视觉语言是自定义的墨青体系，
 * 引一套自带样式的弹层会在这一处打破一致性，还要额外覆盖主题变量。
 *
 * 交互约束（AGENTS.md 第 13 条「高风险动作需要二次确认并展示影响范围」）：
 * 该弹层只负责呈现与收集，不做任何授权判断——授权在后端与领域层。
 */
import { onBeforeUnmount, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    title: string
    /** 标题下的补充说明 */
    subtitle?: string
    /** wide 用于报告这类长文档 */
    size?: 'normal' | 'wide'
  }>(),
  { size: 'normal', subtitle: undefined },
)

const emit = defineEmits<{ 'update:modelValue': [boolean] }>()

function close() {
  emit('update:modelValue', false)
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') close()
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) window.addEventListener('keydown', onKey)
    else window.removeEventListener('keydown', onKey)
  },
)

onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <Teleport to="body">
    <Transition name="gw-modal">
      <div v-if="modelValue" class="gm" @click.self="close">
        <div class="gm__panel" :class="{ 'gm__panel--wide': size === 'wide' }" role="dialog" aria-modal="true">
          <header class="gm__head">
            <div class="gm__titles">
              <h2 class="gm__title">{{ title }}</h2>
              <p v-if="subtitle" class="gm__subtitle">{{ subtitle }}</p>
            </div>
            <div class="gm__tools">
              <slot name="tools" />
              <button class="gm__close" type="button" aria-label="关闭" @click="close">
                <svg viewBox="0 0 14 14" width="13" height="13" fill="none">
                  <path d="M3.5 3.5 10.5 10.5M10.5 3.5 3.5 10.5" stroke="currentColor"
                    stroke-width="1.5" stroke-linecap="round" />
                </svg>
              </button>
            </div>
          </header>
          <div class="gm__body">
            <slot />
          </div>
          <footer v-if="$slots.footer" class="gm__foot">
            <slot name="footer" />
          </footer>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.gm {
  position: fixed;
  inset: 0;
  z-index: 2000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--gw-s6);
  background: rgba(22, 38, 43, 0.38);
  backdrop-filter: blur(2px);
}

.gm__panel {
  display: flex;
  flex-direction: column;
  width: min(680px, 100%);
  max-height: min(84vh, 900px);
  border-radius: var(--gw-r-lg, 10px);
  background: var(--gw-surface);
  box-shadow: 0 24px 60px rgba(22, 38, 43, 0.24), 0 2px 8px rgba(22, 38, 43, 0.08);
  overflow: hidden;
}

.gm__panel--wide {
  width: min(940px, 100%);
}

.gm__head {
  display: flex;
  align-items: flex-start;
  gap: var(--gw-s4);
  padding: var(--gw-s5) var(--gw-s6);
  border-bottom: 1px solid var(--gw-line);
  background: var(--gw-accent-faint);
}

.gm__titles {
  min-width: 0;
  flex: 1;
}

.gm__title {
  font-size: var(--gw-fs-lg);
  font-weight: 600;
  color: var(--gw-text);
  letter-spacing: -0.01em;
}

.gm__subtitle {
  margin-top: 3px;
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-tertiary);
  line-height: var(--gw-lh);
}

.gm__tools {
  display: flex;
  align-items: center;
  gap: var(--gw-s2);
  flex: none;
}

.gm__close {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border: 1px solid var(--gw-line);
  border-radius: var(--gw-r-sm);
  background: var(--gw-surface);
  color: var(--gw-text-tertiary);
  transition: color var(--gw-dur-fast) var(--gw-ease),
    border-color var(--gw-dur-fast) var(--gw-ease);
}

.gm__close:hover {
  color: var(--gw-text);
  border-color: var(--gw-line-strong);
}

.gm__body {
  flex: 1;
  overflow: auto;
  padding: var(--gw-s6);
}

.gm__foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--gw-s3);
  padding: var(--gw-s4) var(--gw-s6);
  border-top: 1px solid var(--gw-line);
  background: var(--gw-bg-sunken);
}

.gw-modal-enter-active,
.gw-modal-leave-active {
  transition: opacity var(--gw-dur) var(--gw-ease);
}

.gw-modal-enter-from,
.gw-modal-leave-to {
  opacity: 0;
}
</style>
