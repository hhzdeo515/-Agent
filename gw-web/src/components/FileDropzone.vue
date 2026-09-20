<script setup lang="ts">
/**
 * 物料上传区：拖拽或点击选择，上传到后端并登记物料版本。
 *
 * 与助手内临时上传的区别（AGENTS.md 第 4 条）：
 *  · 这里的文件是**正式物料**，会建立 Material 与不可覆盖的 Version；
 *  · 上传成功后自动触发解析，产出可审核内容与定位锚点；
 *  · 解析失败的材料会被阻止进入正式初审，并明确提示需要重传什么。
 */
import { computed, ref } from 'vue'
import { apiPost, apiUpload } from '@/api/client'

const props = defineProps<{
  caseId: number | null
}>()

const emit = defineEmits<{
  (e: 'uploaded', materialId: number, versionId: number): void
}>()

interface UploadItem {
  id: string
  name: string
  size: number
  state: 'uploading' | 'parsing' | 'done' | 'failed'
  message?: string
  materialId?: number
  versionId?: number
  anchorCount?: number
}

const items = ref<UploadItem[]>([])
const dragging = ref(false)
const busy = computed(() => items.value.some((i) => i.state === 'uploading' || i.state === 'parsing'))

const ACCEPT = '.jpg,.jpeg,.png,.gif,.webp,.bmp,.mp4,.mov,.avi,.mkv,.webm,.ppt,.pptx,.pdf,.doc,.docx,.txt,.md,.csv'

function humanSize(bytes: number) {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

function pickFiles() {
  if (!props.caseId) return
  const input = document.createElement('input')
  input.type = 'file'
  input.multiple = true
  input.accept = ACCEPT
  input.onchange = () => {
    if (input.files) void handleFiles(Array.from(input.files))
  }
  input.click()
}

function onDrop(e: DragEvent) {
  dragging.value = false
  if (!props.caseId) return
  const files = e.dataTransfer?.files
  if (files?.length) void handleFiles(Array.from(files))
}

async function handleFiles(files: File[]) {
  for (const f of files) {
    await uploadOne(f)
  }
}

async function uploadOne(file: File) {
  const id = Math.random().toString(36).slice(2, 10)
  const item: UploadItem = {
    id,
    name: file.name,
    size: file.size,
    state: 'uploading',
  }
  items.value.push(item)

  try {
    // 第 1 步：上传文件本体（后端存 MinIO 并登记版本）
    const form = new FormData()
    form.append('caseId', String(props.caseId))
    form.append('file', file)
    form.append('uploadReason', '首次上传')

    const up = await apiUpload<{
      materialId: number
      versionId: number
      versionLabel: string
      materialType: string
    }>('/api/intake/materials/upload', form)

    item.materialId = up.materialId
    item.versionId = up.versionId
    item.state = 'parsing'

    // 第 2 步：触发解析，产出证据锚点
    const parsed = await apiPost<{ anchorCount: number; parseStatus: string; parseErrorMessage?: string }>(
      `/api/intake/materials/${up.materialId}/parse`,
    )
    item.anchorCount = parsed.anchorCount

    if (parsed.parseStatus === 'FAILED') {
      item.state = 'failed'
      item.message = parsed.parseErrorMessage || '解析失败，请重试或更换文件'
    } else {
      item.state = 'done'
      item.message = `解析完成 · ${parsed.anchorCount} 个锚点`
      emit('uploaded', up.materialId, up.versionId)
    }
  } catch (err) {
    item.state = 'failed'
    item.message = err instanceof Error ? err.message : '上传失败'
  }
}

function removeItem(id: string) {
  items.value = items.value.filter((i) => i.id !== id)
}
</script>

<template>
  <div
    class="dz"
    :class="{ 'is-drag': dragging, 'is-disabled': !caseId }"
    @dragover.prevent="dragging = true"
    @dragleave.prevent="dragging = false"
    @drop.prevent="onDrop"
    @click="pickFiles"
  >
    <svg class="dz__icon" viewBox="0 0 24 24" width="20" height="20" fill="none" aria-hidden="true">
      <path d="M12 15.5V4m0 0L7.8 8.2M12 4l4.2 4.2" stroke="currentColor" stroke-width="1.5"
        stroke-linecap="round" stroke-linejoin="round" />
      <path d="M4 15v2.5A2.5 2.5 0 0 0 6.5 20h11a2.5 2.5 0 0 0 2.5-2.5V15" stroke="currentColor"
        stroke-width="1.5" stroke-linecap="round" />
    </svg>
    <div class="dz__text">
      <span class="dz__title">{{ caseId ? '拖拽文件到此处，或点击选择' : '请先创建审核任务' }}</span>
      <span class="dz__sub">图片 · 视频 · PPT · PDF · Word · 文本，可多选</span>
    </div>
    <span v-if="busy" class="dz__spin" aria-hidden="true" />
  </div>

  <ul v-if="items.length" class="up">
    <li v-for="i in items" :key="i.id" class="up__item" :data-state="i.state">
      <span class="up__dot" aria-hidden="true" />
      <div class="up__main">
        <span class="up__name gw-truncate" :title="i.name">{{ i.name }}</span>
        <span class="up__meta">
          {{ humanSize(i.size) }}
          <template v-if="i.message"> · {{ i.message }}</template>
        </span>
      </div>
      <span class="up__state">
        <template v-if="i.state === 'uploading'">上传中</template>
        <template v-else-if="i.state === 'parsing'">解析中</template>
        <template v-else-if="i.state === 'done'">已完成</template>
        <template v-else>失败</template>
      </span>
      <button
        v-if="i.state === 'done' || i.state === 'failed'"
        class="up__x"
        type="button"
        aria-label="移除记录"
        @click.stop="removeItem(i.id)"
      >
        <svg viewBox="0 0 12 12" width="10" height="10" fill="none">
          <path d="M3 3l6 6M9 3l-6 6" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
        </svg>
      </button>
    </li>
  </ul>
</template>

<style scoped>
.dz {
  display: flex;
  align-items: center;
  gap: var(--gw-s4);
  padding: var(--gw-s5);
  border: 1.5px dashed var(--gw-line-strong);
  border-radius: var(--gw-r);
  background: var(--gw-surface);
  cursor: pointer;
  transition: border-color var(--gw-dur) var(--gw-ease), background var(--gw-dur) var(--gw-ease);
}

.dz:hover:not(.is-disabled) {
  border-color: var(--gw-accent);
  background: var(--gw-accent-faint);
}

.dz.is-drag {
  border-color: var(--gw-accent);
  background: var(--gw-accent-soft);
}

.dz.is-disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.dz__icon {
  flex: none;
  color: var(--gw-accent);
}

.dz__text {
  flex: 1;
  min-width: 0;
}

.dz__title {
  display: block;
  font-size: var(--gw-fs-md);
  font-weight: 500;
  color: var(--gw-text);
  line-height: 1.4;
}

.dz__sub {
  display: block;
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
  margin-top: 2px;
}

.dz__spin {
  width: 14px;
  height: 14px;
  flex: none;
  border: 1.5px solid var(--gw-line-strong);
  border-top-color: var(--gw-accent);
  border-radius: 50%;
  animation: dz-spin 0.7s linear infinite;
}

@keyframes dz-spin {
  to {
    transform: rotate(360deg);
  }
}

/* ── 上传记录 ─────────────────────────────────────────────────────── */
.up {
  margin-top: var(--gw-s3);
  border-top: 1px solid var(--gw-line);
}

.up__item {
  display: flex;
  align-items: center;
  gap: var(--gw-s3);
  padding: 9px 0;
  border-bottom: 1px solid var(--gw-line-soft);
}

.up__dot {
  width: 6px;
  height: 6px;
  flex: none;
  border-radius: 50%;
  background: var(--gw-line-strong);
}

.up__item[data-state='uploading'] .up__dot,
.up__item[data-state='parsing'] .up__dot {
  background: var(--gw-warning);
}

.up__item[data-state='done'] .up__dot {
  background: var(--gw-success);
}

.up__item[data-state='failed'] .up__dot {
  background: var(--gw-danger);
}

.up__main {
  flex: 1;
  min-width: 0;
}

.up__name {
  display: block;
  font-size: var(--gw-fs-base);
  color: var(--gw-text);
  line-height: 1.35;
}

.up__meta {
  display: block;
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
  margin-top: 1px;
}

.up__state {
  flex: none;
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
}

.up__x {
  display: grid;
  place-items: center;
  width: 18px;
  height: 18px;
  flex: none;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--gw-text-tertiary);
}

.up__x:hover {
  background: var(--gw-bg-sunken);
  color: var(--gw-text-secondary);
}
</style>
