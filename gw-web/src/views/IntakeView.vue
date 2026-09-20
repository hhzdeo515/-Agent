<script setup lang="ts">
/**
 * 接收区（正式审核流程入口）
 *
 * 责任：审核任务的创建、材料接收、解析调度与审核要求。
 * 边界：本模块只把任务与材料规范地送入审核系统，
 *      **不提前展示最终风险结论** —— 因此右栏只有任务/进度/要求，没有风险条目。
 */
import { ref } from 'vue'
import ConversationThread, { type Message } from '@/components/ConversationThread.vue'
import PanelCard from '@/components/PanelCard.vue'

const messages = ref<Message[]>([
  {
    id: 'm1',
    role: 'user',
    text: '创建任务：2026 秋季新品上市宣传物料审核，截止本周五。',
    time: '09:12',
  },
  {
    id: 'm2',
    role: 'agent',
    text:
      '任务已建立，编号 GX-20260920-0001。\n' +
      '已接收 12 份材料，其中图片 6 份、视频 2 份、PPT 2 份、PDF 1 份、纯文案 1 份。\n\n' +
      '需要你确认本次审核要求后，我才能启动 AI 初审——模板内容不会被自动当作正式要求。',
    time: '09:12',
    tone: 'neutral',
    trace: [
      {
        stage: '任务建档',
        summary: '生成业务编号并绑定项目与负责人',
        details: ['项目：秋季新品上市', '负责人：林砚（法务审核）', '截止：2026-09-25 18:00'],
        costMs: 120,
      },
      {
        stage: '材料接收',
        summary: '12 份材料已入库，按内容判定格式（非扩展名）',
        details: ['图片 6 · 视频 2 · PPT 2 · PDF 1 · 文本 1', '全部完成 SHA-256 计算，用于重复上传识别'],
        costMs: 860,
      },
      {
        stage: '解析调度',
        summary: '10 份解析完成，2 份需重传',
        details: [
          '图片走 OCR 文本定位 + 画面语义',
          '视频走 ASR 口播 + 关键帧 + 硬字幕',
          '失败：物料 #7 文件受损、物料 #11 未提取到可审核内容',
        ],
        costMs: 41200,
      },
      {
        stage: '审核要求',
        summary: '等待法务确认审核要求',
        details: ['模板 DEFAULT 已选用，但未确认前不生效'],
        state: 'running',
      },
    ],
    metrics: [
      { label: '材料总数', value: 12 },
      { label: '解析完成', value: 10, hint: '2 份需重传' },
      { label: '待确认项', value: 1, hint: '审核要求' },
    ],
  },
])

interface MaterialRow {
  id: number
  name: string
  type: string
  status: 'done' | 'failed' | 'running' | 'pending'
  statusText: string
  note?: string
}

const materials = ref<MaterialRow[]>([
  { id: 1, name: '秋季主视觉海报-A.png', type: '图片', status: 'done', statusText: '解析完成' },
  { id: 2, name: '秋季主视觉海报-B.png', type: '图片', status: 'done', statusText: '解析完成' },
  { id: 3, name: '新品卖点长图.png', type: '图片', status: 'done', statusText: '解析完成' },
  { id: 4, name: '门店易拉宝.png', type: '图片', status: 'done', statusText: '解析完成' },
  { id: 5, name: '社媒方图-01.png', type: '图片', status: 'done', statusText: '解析完成' },
  { id: 6, name: '社媒方图-02.png', type: '图片', status: 'done', statusText: '解析完成' },
  { id: 7, name: '发布会宣传片.mp4', type: '视频', status: 'failed', statusText: '文件受损', note: '请重新上传' },
  { id: 8, name: '产品演示短片.mp4', type: '视频', status: 'running', statusText: '解析中 62%' },
  { id: 9, name: '上市方案.pptx', type: 'PPT', status: 'done', statusText: '解析完成' },
  { id: 10, name: '渠道政策.pptx', type: 'PPT', status: 'done', statusText: '解析完成' },
  { id: 11, name: '产品说明书.pdf', type: 'PDF', status: 'failed', statusText: '未提取到内容', note: '疑为扫描件，建议提供可复制版本' },
  { id: 12, name: '主推文案.txt', type: '文本', status: 'done', statusText: '解析完成' },
])

const statusClass: Record<MaterialRow['status'], string> = {
  done: 'gw-status--success',
  failed: 'gw-status--danger',
  running: 'gw-status--brass',
  pending: 'gw-status--muted',
}

const requirements = [
  { label: '绝对化宣传', on: true },
  { label: '安全承诺', on: true },
  { label: '无依据数据', on: true },
  { label: '竞品相关表达', on: false },
  { label: '价格宣传', on: true },
  { label: '免责声明缺失', on: true },
]
</script>

<template>
  <div class="view">
    <ConversationThread :messages="messages" />

    <Teleport defer to="#wb-aside">
      <!-- 任务信息 -->
      <PanelCard title="任务信息" hint="GX-20260920-0001">
        <dl class="kv">
          <div class="kv__row"><dt>任务名称</dt><dd>2026 秋季新品上市宣传物料审核</dd></div>
          <div class="kv__row"><dt>所属项目</dt><dd>秋季新品上市</dd></div>
          <div class="kv__row"><dt>提交人</dt><dd>周琦（品牌）</dd></div>
          <div class="kv__row"><dt>负责人</dt><dd>林砚（法务审核）</dd></div>
          <div class="kv__row"><dt>截止时间</dt><dd class="gw-mono">2026-09-25 18:00</dd></div>
          <div class="kv__row">
            <dt>当前状态</dt>
            <dd><span class="gw-status gw-status--wine"><span class="gw-status__dot" />解析中</span></dd>
          </div>
        </dl>
      </PanelCard>

      <!-- 解析进度：用可计量单位，不用凭感觉的百分比 -->
      <PanelCard title="解析进度" hint="10 / 12">
        <div class="prog">
          <div class="prog__bar" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="83">
            <span class="prog__fill" style="width: 83%" />
          </div>
          <div class="prog__legend">
            <span><i class="dot dot--ok" />完成 10</span>
            <span><i class="dot dot--run" />进行中 1</span>
            <span><i class="dot dot--err" />失败 2</span>
          </div>
        </div>

        <ul class="mats">
          <li v-for="m in materials" :key="m.id" class="mats__row">
            <div class="mats__main">
              <span class="mats__name gw-truncate" :title="m.name">{{ m.name }}</span>
              <span class="mats__note" v-if="m.note">{{ m.note }}</span>
            </div>
            <span class="mats__type">{{ m.type }}</span>
            <span class="gw-status" :class="statusClass[m.status]">{{ m.statusText }}</span>
          </li>
        </ul>
      </PanelCard>

      <!-- 审核要求 -->
      <PanelCard title="审核要求" hint="未确认">
        <ul class="req__list">
          <li v-for="r in requirements" :key="r.label" class="req__item">
            <span class="req__box" :class="{ 'is-on': r.on }" aria-hidden="true">
              <svg v-if="r.on" viewBox="0 0 12 12" width="9" height="9" fill="none">
                <path d="M2.5 6.2 4.8 8.5 9.5 3.6" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </span>
            <span class="req__label">{{ r.label }}</span>
          </li>
        </ul>
        <div class="req__actions">
          <button class="btn btn--ghost" type="button">使用其他模板</button>
          <button class="btn btn--primary" type="button">确认审核要求</button>
        </div>
      </PanelCard>
    </Teleport>
  </div>
</template>

<style scoped>
.view {
  display: contents;
}

/* ── 键值对 ───────────────────────────────────────────────────────── */
.kv__row {
  display: flex;
  align-items: baseline;
  gap: var(--gw-s4);
  padding: 5px 0;
}

.kv__row dt {
  width: 68px;
  flex: none;
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-tertiary);
}

.kv__row dd {
  flex: 1;
  min-width: 0;
  font-size: var(--gw-fs-base);
  color: var(--gw-text);
  line-height: var(--gw-lh);
}

/* ── 进度 ─────────────────────────────────────────────────────────── */
.prog__bar {
  height: 4px;
  border-radius: var(--gw-r-pill);
  background: var(--gw-bg-sunken);
  overflow: hidden;
}

.prog__fill {
  display: block;
  height: 100%;
  border-radius: var(--gw-r-pill);
  background: var(--gw-accent);
}

.prog__legend {
  display: flex;
  gap: var(--gw-s4);
  margin-top: var(--gw-s3);
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
}

.prog__legend span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex: none;
}
.dot--ok {
  background: var(--gw-success);
}
.dot--run {
  background: var(--gw-accent);
}
.dot--err {
  background: var(--gw-danger);
}

/* ── 物料清单 ─────────────────────────────────────────────────────── */
.mats {
  margin-top: var(--gw-s4);
  border-top: 1px solid var(--gw-line);
}

.mats__row {
  display: flex;
  align-items: center;
  gap: var(--gw-s2);
  padding: var(--gw-s3) 0;
  border-bottom: 1px solid var(--gw-line-soft);
}

.mats__row:last-child {
  border-bottom: 0;
}

.mats__main {
  flex: 1;
  min-width: 0;
}

.mats__name {
  display: block;
  font-size: var(--gw-fs-base);
  color: var(--gw-text);
  line-height: 1.35;
}

.mats__note {
  display: block;
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
  margin-top: 1px;
}

.mats__type {
  flex: none;
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
  padding: 0 5px;
  border: 1px solid var(--gw-line);
  border-radius: var(--gw-r-sm);
  line-height: 16px;
}

/* ── 审核要求 ─────────────────────────────────────────────────────── */
.req__list {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.req__item {
  display: flex;
  align-items: center;
  gap: var(--gw-s3);
  padding: 6px 0;
}

.req__box {
  display: grid;
  place-items: center;
  width: 15px;
  height: 15px;
  flex: none;
  border: 1px solid var(--gw-line-strong);
  border-radius: 4px;
  color: #fff;
  transition: background var(--gw-dur-fast) var(--gw-ease), border-color var(--gw-dur-fast) var(--gw-ease);
}

.req__box.is-on {
  background: var(--gw-accent);
  border-color: var(--gw-accent);
}

.req__label {
  font-size: var(--gw-fs-base);
  color: var(--gw-text);
}

.req__actions {
  display: flex;
  gap: var(--gw-s2);
  margin-top: var(--gw-s4);
}

/* ── 按钮：克制，无渐变 ────────────────────────────────────────────── */
.btn {
  flex: 1;
  height: 30px;
  border-radius: var(--gw-r-sm);
  font-size: var(--gw-fs-base);
  font-weight: 500;
  transition: background var(--gw-dur-fast) var(--gw-ease), border-color var(--gw-dur-fast) var(--gw-ease);
}

.btn--primary {
  border: 1px solid var(--gw-accent);
  background: var(--gw-accent);
  color: #fff;
}

.btn--primary:hover {
  background: var(--gw-accent-strong);
  border-color: var(--gw-accent-strong);
}

.btn--ghost {
  border: 1px solid var(--gw-line-strong);
  background: transparent;
  color: var(--gw-text-secondary);
}

.btn--ghost:hover {
  background: var(--gw-bg-sunken);
  color: var(--gw-text);
}
</style>
