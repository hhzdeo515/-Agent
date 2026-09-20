<script setup lang="ts">
/**
 * 对话输入框。
 *
 * 设计取舍：
 *  · 自动增高而非固定高度——单行宣传语和多段文案都能自然输入；
 *  · Enter 发送、Shift+Enter 换行（与主流对话产品一致，但输入多行文案时不会误发）；
 *  · 附件按钮与输入框同层：助手场景下"贴一段话"和"传一张图"是同一个动作的两条路径。
 */
import { computed, nextTick, ref } from 'vue'

const props = defineProps<{
  /** 发送中时禁用输入 */
  sending?: boolean
  placeholder?: string
  /** 是否允许附件（助手允许；某些场景不需要） */
  allowAttach?: boolean
  /** 已附加的文件名 */
  attachmentName?: string
  /** 附件上传中 */
  uploading?: boolean
}>()

const emit = defineEmits<{
  (e: 'send', text: string): void
  (e: 'attach', file: File): void
  (e: 'remove-attachment'): void
}>()

const text = ref('')
const ta = ref<HTMLTextAreaElement | null>(null)

const canSend = computed(() => text.value.trim().length > 0 && !props.sending)

function autoGrow() {
  const el = ta.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 200) + 'px'
}

async function onInput() {
  await nextTick()
  autoGrow()
}

function submit() {
  if (!canSend.value) return
  const v = text.value.trim()
  text.value = ''
  nextTick(autoGrow)
  emit('send', v)
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    submit()
  }
}

function pickFile() {
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = '.txt,.md,.csv,.json,image/*,.pdf,.docx,.pptx'
  input.onchange = () => {
    const f = input.files?.[0]
    if (f) emit('attach', f)
  }
  input.click()
}
</script>

<template>
  <div class="composer">
    <div v-if="attachmentName" class="composer__chip">
      <svg viewBox="0 0 14 14" width="11" height="11" fill="none" aria-hidden="true">
        <path d="M8.4 4.2 4.9 7.7a1.6 1.6 0 0 0 2.3 2.3l3.8-3.8a2.8 2.8 0 0 0-4-4L3.2 6.1a4 4 0 0 0 5.6 5.6l3-3"
          stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
      <span class="composer__chip-name gw-truncate">{{ attachmentName }}</span>
      <span v-if="uploading" class="composer__chip-state">上传中…</span>
      <button class="composer__chip-x" type="button" aria-label="移除附件" @click="emit('remove-attachment')">
        <svg viewBox="0 0 12 12" width="10" height="10" fill="none">
          <path d="M3 3l6 6M9 3l-6 6" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
        </svg>
      </button>
    </div>

    <div class="composer__box">
      <textarea
        ref="ta"
        v-model="text"
        class="composer__input"
        rows="1"
        :placeholder="placeholder ?? '输入一句宣传语、一段文案，或描述你的问题…'"
        :disabled="sending"
        @input="onInput"
        @keydown="onKeydown"
      />

      <div class="composer__bar">
        <button
          v-if="allowAttach !== false"
          class="composer__icon"
          type="button"
          title="附加文件"
          aria-label="附加文件"
          :disabled="sending || uploading"
          @click="pickFile"
        >
          <svg viewBox="0 0 18 18" width="15" height="15" fill="none" aria-hidden="true">
            <path d="M10.8 5.4 6.3 9.9a2 2 0 0 0 2.9 2.9l4.8-4.8a3.5 3.5 0 0 0-5-5L4.1 7.9a5 5 0 0 0 7 7l3.7-3.7"
              stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </button>
        <span class="composer__hint">Enter 发送 · Shift+Enter 换行</span>
        <button class="composer__send" type="button" :disabled="!canSend" @click="submit">
          <span v-if="sending" class="composer__spin" aria-hidden="true" />
          <template v-else>
            发送
            <svg viewBox="0 0 14 14" width="12" height="12" fill="none" aria-hidden="true">
              <path d="M2.2 7h9.2M8 3.6 11.4 7 8 10.4" stroke="currentColor" stroke-width="1.5"
                stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </template>
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.composer {
  position: sticky;
  bottom: 0;
  padding-top: var(--gw-s4);
  margin-top: var(--gw-s6);
  background: linear-gradient(to bottom, rgba(247, 248, 249, 0), var(--gw-bg) 22%);
}

/* ── 附件条 ───────────────────────────────────────────────────────── */
.composer__chip {
  display: flex;
  align-items: center;
  gap: var(--gw-s2);
  max-width: 320px;
  padding: 5px var(--gw-s2) 5px var(--gw-s3);
  margin-bottom: var(--gw-s2);
  border-radius: var(--gw-r-pill);
  background: var(--gw-accent-soft);
  color: var(--gw-accent-text);
  font-size: var(--gw-fs-sm);
}

.composer__chip-name {
  min-width: 0;
}

.composer__chip-state {
  flex: none;
  font-size: var(--gw-fs-xs);
  opacity: 0.75;
}

.composer__chip-x {
  display: grid;
  place-items: center;
  width: 16px;
  height: 16px;
  flex: none;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: inherit;
  opacity: 0.7;
}

.composer__chip-x:hover {
  opacity: 1;
  background: rgba(0, 0, 0, 0.06);
}

/* ── 输入框 ───────────────────────────────────────────────────────── */
.composer__box {
  background: var(--gw-surface);
  border-radius: var(--gw-r-lg);
  box-shadow: var(--gw-elev-2), var(--gw-hairline);
  transition: box-shadow var(--gw-dur) var(--gw-ease);
}

.composer__box:focus-within {
  box-shadow: var(--gw-elev-3), inset 0 0 0 1.5px var(--gw-accent);
}

.composer__input {
  display: block;
  width: 100%;
  max-height: 200px;
  padding: var(--gw-s4) var(--gw-s4) var(--gw-s2);
  border: 0;
  background: transparent;
  resize: none;
  outline: none;
  font-family: inherit;
  font-size: var(--gw-fs-md);
  line-height: var(--gw-lh);
  color: var(--gw-text);
}

.composer__input::placeholder {
  color: var(--gw-text-tertiary);
}

/* ── 工具条 ───────────────────────────────────────────────────────── */
.composer__bar {
  display: flex;
  align-items: center;
  gap: var(--gw-s3);
  padding: var(--gw-s2) var(--gw-s3) var(--gw-s3);
}

.composer__icon {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  flex: none;
  border: 0;
  border-radius: var(--gw-r-sm);
  background: transparent;
  color: var(--gw-text-tertiary);
  transition: background var(--gw-dur-fast) var(--gw-ease), color var(--gw-dur-fast) var(--gw-ease);
}

.composer__icon:hover:not(:disabled) {
  background: var(--gw-bg-sunken);
  color: var(--gw-text-secondary);
}

.composer__icon:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.composer__hint {
  flex: 1;
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
}

.composer__send {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 30px;
  padding: 0 var(--gw-s4);
  flex: none;
  border: 1px solid var(--gw-accent);
  border-radius: var(--gw-r-sm);
  background: var(--gw-accent);
  color: #fff;
  font-size: var(--gw-fs-base);
  font-weight: 500;
  transition: background var(--gw-dur-fast) var(--gw-ease);
}

.composer__send:hover:not(:disabled) {
  background: var(--gw-accent-strong);
  border-color: var(--gw-accent-strong);
}

.composer__send:disabled {
  background: var(--gw-line-strong);
  border-color: var(--gw-line-strong);
  cursor: not-allowed;
}

/* 加载指示：一个克制的旋转描边 */
.composer__spin {
  width: 12px;
  height: 12px;
  border: 1.5px solid rgba(255, 255, 255, 0.4);
  border-top-color: #fff;
  border-radius: 50%;
  animation: gw-spin 0.7s linear infinite;
}

@keyframes gw-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
