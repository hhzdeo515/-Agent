<script setup lang="ts">
/**
 * AI 能力状态（设置入口）。
 *
 * 存在的理由不是"展示技术栈"，而是一个真实风险：本项目同时有规则实现与千问大模型实现，
 * 两者在界面上的表现可能完全一样——都能给出一堆风险、都能引法条。
 * 如果界面上不说清当前用的是哪一个，法务会把关键词匹配的输出当成大模型的判断。
 *
 * 因此这里常驻显示当前供应商，并可展开看到逐项能力与**未验证**标记
 * （AGENTS.md 第 15 条：尚未验证的能力不得伪装成已经可靠可用）。
 */
import { onMounted, ref } from 'vue'
import GwModal from '@/components/GwModal.vue'
import { apiGet } from '@/api/client'

interface Capability {
  name: string
  status: 'AVAILABLE' | 'UNAVAILABLE' | 'PARTIAL' | 'RULE_BASED'
  detail: string
  verified: boolean
  verificationNote: string | null
}

interface AiStatus {
  provider: string
  providerLabel: string
  region: string
  apiKeyConfigured: boolean
  apiKeyHint: string | null
  models?: Record<string, string>
  pipelineVersion: string
  promptVersion: string
  rulesetVersion: string
  capabilities: Capability[]
  note: string
}

const status = ref<AiStatus | null>(null)
const open = ref(false)
const failed = ref(false)

onMounted(() => {
  void load()
})

async function load() {
  try {
    status.value = await apiGet<AiStatus>('/api/ai/status')
    failed.value = false
  } catch {
    failed.value = true
  }
}

const STATUS_LABEL: Record<string, string> = {
  AVAILABLE: '可用',
  UNAVAILABLE: '不可用',
  PARTIAL: '部分可用',
  RULE_BASED: '规则实现',
}

const STATUS_TONE: Record<string, string> = {
  AVAILABLE: 'gw-status--success',
  UNAVAILABLE: 'gw-status--muted',
  PARTIAL: 'gw-status--brass',
  RULE_BASED: 'gw-status--brass',
}

const MODEL_LABEL: Record<string, string> = {
  text: '文本主力',
  vision: '画面理解',
  ocrPrimary: '文字识别',
  ocrFallback: '识别兜底',
  asr: '语音转写',
  embedding: '向量化',
  rerank: '重排序',
}
</script>

<template>
  <div class="ai">
    <button class="ai__chip" type="button" @click="open = true; load()">
      <span class="ai__dot" :class="{ 'is-live': status?.provider === 'dashscope' }" />
      <span class="ai__text gw-truncate">
        {{ failed ? 'AI 状态未知' : status?.providerLabel ?? '读取中…' }}
      </span>
      <svg viewBox="0 0 10 6" width="9" height="6" fill="none">
        <path d="M1 1.2 5 4.8 9 1.2" stroke="currentColor" stroke-width="1.4"
          stroke-linecap="round" stroke-linejoin="round" />
      </svg>
    </button>

    <GwModal v-model="open" size="wide" title="AI 能力状态" :subtitle="status?.note">
      <template v-if="status">
        <dl class="gw-kv">
          <div class="gw-kv__row">
            <dt>当前供应商</dt>
            <dd>
              <span class="gw-status" :class="status.provider === 'dashscope' ? 'gw-status--success' : 'gw-status--brass'">
                <span class="gw-status__dot" />{{ status.providerLabel }}
              </span>
            </dd>
          </div>
          <div class="gw-kv__row"><dt>接入区域</dt><dd class="gw-mono">{{ status.region }}</dd></div>
          <div class="gw-kv__row">
            <dt>API Key</dt>
            <dd>
              <template v-if="status.apiKeyConfigured">
                已配置 <span class="gw-mono">{{ status.apiKeyHint }}</span>
              </template>
              <template v-else>未配置</template>
            </dd>
          </div>
          <div class="gw-kv__row">
            <dt>版本</dt>
            <dd class="gw-mono">
              流水线 {{ status.pipelineVersion }} · 提示词 {{ status.promptVersion }} ·
              规则集 {{ status.rulesetVersion }}
            </dd>
          </div>
        </dl>

        <div v-if="status.models" class="models">
          <div v-for="(v, k) in status.models" :key="k" class="models__item">
            <span class="models__label">{{ MODEL_LABEL[k] ?? k }}</span>
            <span class="models__value gw-mono">{{ v }}</span>
          </div>
        </div>

        <h3 class="cap__title">能力清单</h3>
        <ul class="cap">
          <li v-for="c in status.capabilities" :key="c.name" class="cap__item">
            <div class="cap__head">
              <span class="cap__name">{{ c.name }}</span>
              <span class="gw-status" :class="STATUS_TONE[c.status] ?? 'gw-status--muted'">
                {{ STATUS_LABEL[c.status] ?? c.status }}
              </span>
              <span v-if="!c.verified" class="cap__unverified">未实测</span>
            </div>
            <p class="cap__detail">{{ c.detail }}</p>
            <p v-if="!c.verified && c.verificationNote" class="cap__note">{{ c.verificationNote }}</p>
          </li>
        </ul>
      </template>
      <p v-else class="cap__detail">读取中…</p>
    </GwModal>
  </div>
</template>

<style scoped>
.ai {
  padding: 0 var(--gw-s4) var(--gw-s3);
}

.ai__chip {
  display: flex;
  align-items: center;
  gap: 7px;
  width: 100%;
  padding: 6px 9px;
  border: 1px solid var(--gw-line);
  border-radius: var(--gw-r-sm);
  background: var(--gw-surface);
  transition: border-color var(--gw-dur-fast) var(--gw-ease);
}

.ai__chip:hover {
  border-color: var(--gw-line-strong);
}

.ai__chip svg {
  flex: none;
  color: var(--gw-text-tertiary);
}

/* 用一个小圆点区分"真模型"与"规则实现"：这是整条链路上最需要一眼看清的信息 */
.ai__dot {
  width: 6px;
  height: 6px;
  flex: none;
  border-radius: 50%;
  background: var(--gw-brass-500);
}

.ai__dot.is-live {
  background: var(--gw-success);
  box-shadow: 0 0 0 2px var(--gw-success-bg);
}

.ai__text {
  flex: 1;
  min-width: 0;
  text-align: left;
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-secondary);
}

.models {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 6px var(--gw-s4);
  margin: var(--gw-s4) 0 var(--gw-s5);
  padding: var(--gw-s4);
  border: 1px solid var(--gw-line);
  border-radius: var(--gw-r-sm);
  background: var(--gw-bg);
}

.models__item {
  display: flex;
  align-items: baseline;
  gap: var(--gw-s3);
  min-width: 0;
}

.models__label {
  flex: none;
  width: 66px;
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
}

.models__value {
  font-size: var(--gw-fs-sm);
  color: var(--gw-text);
  min-width: 0;
  overflow-wrap: anywhere;
}

.cap__title {
  margin-bottom: var(--gw-s2);
  font-size: var(--gw-fs-md);
  font-weight: 600;
  color: var(--gw-text);
}

.cap {
  display: flex;
  flex-direction: column;
}

.cap__item {
  padding: var(--gw-s3) 0;
  border-bottom: 1px solid var(--gw-line-soft);
}

.cap__item:last-child {
  border-bottom: 0;
}

.cap__head {
  display: flex;
  align-items: center;
  gap: var(--gw-s3);
}

.cap__name {
  font-size: var(--gw-fs-base);
  font-weight: 500;
  color: var(--gw-text);
}

.cap__unverified {
  padding: 0 6px;
  border: 1px solid var(--gw-warning);
  border-radius: var(--gw-r-sm);
  font-size: var(--gw-fs-xs);
  line-height: 16px;
  color: var(--gw-warning);
}

.cap__detail {
  margin-top: 3px;
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-secondary);
  line-height: var(--gw-lh);
}

.cap__note {
  margin-top: 3px;
  font-size: var(--gw-fs-xs);
  color: var(--gw-warning);
  line-height: var(--gw-lh);
}
</style>
