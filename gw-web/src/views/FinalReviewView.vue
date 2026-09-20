<script setup lang="ts">
/**
 * 终审区（风险整改闭环）
 *
 * 责任：已确认风险的整改闭环 —— 版本推进、差异比较、AI 复审、法务终审、签名与关闭。
 * 边界：不重新对所有物料执行一次完整初审。
 *      复审范围强制为「变化区域 + 必要上下文」，全量复审会被服务端直接拒绝。
 */
import { ref } from 'vue'
import ConversationThread, { type Message } from '@/components/ConversationThread.vue'
import PanelCard from '@/components/PanelCard.vue'

const messages = ref<Message[]>([
  {
    id: 'r1',
    role: 'agent',
    text:
      '品牌方已上传 V2 并解析完成，3 条整改中的风险已自动进入待复审。\n' +
      '按你的设置，本次复审只覆盖变化区域及其必要上下文，不做全量扫描。',
    time: '16:02',
    tone: 'neutral',
    trace: [
      {
        stage: '版本接收',
        summary: 'V2 入库，与 V1 建立父子关系并留存哈希',
        details: ['版本链：V1 → V2（不可覆盖）', '修改说明：已删除绝对化用语，补充数据来源'],
        costMs: 920,
      },
      {
        stage: '版本差异',
        summary: '检出 6 处变化：删除 2 · 替换 3 · 新增 1',
        details: ['主标题「行业第一」→ 已删除', '底部新增免责声明一行'],
        costMs: 2140,
      },
      {
        stage: 'AI 复审',
        summary: '原风险 3 条：2 条已解决，1 条仍存在；另发现 1 条新增风险',
        details: [
          '复审范围：变化区域 + 必要上下文（已记录，可追溯）',
          '新增风险已新建关联记录，未改写原风险含义',
        ],
        costMs: 3870,
      },
    ],
    metrics: [
      { label: '待复审', value: 3 },
      { label: '已解决', value: 2, hint: '待法务确认' },
      { label: '新增风险', value: 1, hint: '已关联原记录' },
    ],
  },
  {
    id: 'r2',
    role: 'user',
    text: '仍存在的那条我看过，确实没改干净。先签名关闭已解决的两条。',
    time: '16:08',
  },
  {
    id: 'r3',
    role: 'agent',
    text:
      '已理解。关闭风险需要你先完成法务签名——签名后我会校验原风险、新版本内容与复审结论，' +
      '再执行关闭。关闭后该风险视为已解决并留痕，历史记录不会被删除。',
    time: '16:08',
    tone: 'warning',
  },
])

interface TimelineItem {
  title: string
  time: string
  desc?: string
  state: 'ok' | 'warn' | 'accent' | 'muted'
}

const timeline = ref<TimelineItem[]>([
  { title: 'V1 上传并完成初审', time: '09:12', desc: '建立 7 条风险记录', state: 'muted' },
  { title: '法务确认 3 条风险', time: '11:40', desc: '另 2 条标记误判并留痕', state: 'ok' },
  { title: '转入整改', time: '11:52', desc: '责任方：品牌部 周琦', state: 'ok' },
  { title: 'V2 上传', time: '15:48', desc: '修改说明：已删除绝对化用语', state: 'accent' },
  { title: 'AI 复审完成', time: '16:02', desc: '2 条已解决 · 1 条仍存在 · 1 条新增', state: 'warn' },
  { title: '等待法务终审与签名', time: '—', state: 'muted' },
])

const events = ref([
  { text: 'RK-1-003 复审判定：原风险已解决', kind: 'ok', time: '16:02' },
  { text: 'RK-1-001 复审判定：原风险已解决', kind: 'ok', time: '16:02' },
  { text: 'RK-1-002 复审判定：仍有剩余风险，已退回整改', kind: 'warn', time: '16:02' },
  { text: '新建关联风险 RK-1-008（父：RK-1-002）', kind: 'accent', time: '16:02' },
])
</script>

<template>
  <div class="view">
    <ConversationThread :messages="messages" />

    <Teleport defer to="#wb-aside">
      <!-- 待办：终审区的主要对象是风险记录，不是物料 -->
      <PanelCard title="待你处理" hint="3 项">
        <ul class="todo">
          <li class="todo__item">
            <span class="gw-status gw-status--wine"><span class="gw-status__dot" />待签名</span>
            <div class="todo__main">
              <span class="todo__title">RK-1-001 · 行业第一</span>
              <span class="todo__sub">复审已通过，等待法务签名后关闭</span>
            </div>
          </li>
          <li class="todo__item">
            <span class="gw-status gw-status--wine"><span class="gw-status__dot" />待签名</span>
            <div class="todo__main">
              <span class="todo__title">RK-1-003 · 续航可达 1000km</span>
              <span class="todo__sub">复审已通过，等待法务签名后关闭</span>
            </div>
          </li>
          <li class="todo__item">
            <span class="gw-status gw-status--brass"><span class="gw-status__dot" />待判断</span>
            <div class="todo__main">
              <span class="todo__title">RK-1-008 · 新增风险（关联 RK-1-002）</span>
              <span class="todo__sub">整改过程中新发现，需你确认是否成立</span>
            </div>
          </li>
        </ul>
        <div class="gw-btn-row">
          <button class="gw-btn gw-btn--ghost" type="button">批量查看</button>
          <button class="gw-btn gw-btn--primary" type="button">进入终审</button>
        </div>
      </PanelCard>

      <!-- 实时事件流 -->
      <PanelCard title="复审事件" hint="刚刚" flush>
        <ul class="ev">
          <li v-for="(e, i) in events" :key="i" class="ev__item" :data-kind="e.kind">
            <span class="ev__dot" />
            <span class="ev__text">{{ e.text }}</span>
            <span class="ev__time gw-mono">{{ e.time }}</span>
          </li>
        </ul>
      </PanelCard>

      <!-- 版本时间线 -->
      <PanelCard title="版本与状态" hint="V1 → V2">
        <ol class="gw-tl">
          <li v-for="(t, i) in timeline" :key="i" class="gw-tl__item">
            <span class="gw-tl__rail">
              <span class="gw-tl__node" :class="`is-${t.state === 'accent' ? '' : t.state}`" />
              <span v-if="i < timeline.length - 1" class="gw-tl__line" />
            </span>
            <div class="gw-tl__body">
              <div class="gw-tl__head">
                <span class="gw-tl__title">{{ t.title }}</span>
                <span class="gw-tl__time gw-mono">{{ t.time }}</span>
              </div>
              <p v-if="t.desc" class="gw-tl__desc">{{ t.desc }}</p>
            </div>
          </li>
        </ol>
      </PanelCard>

      <!-- 法务终审 -->
      <PanelCard title="法务终审">
        <div class="gw-note gw-note--plain">
          签名前请确认：原风险内容、新版本内容、差异、AI 复审结论与置信度。<br />
          全量复审已被服务端拒绝——终审区不会退化为第二次全量初审。
        </div>
        <dl class="gw-kv" style="margin-top: var(--gw-s4)">
          <div class="gw-kv__row"><dt>复审范围</dt><dd>变化区域 + 必要上下文</dd></div>
          <div class="gw-kv__row"><dt>复审结论</dt><dd>2 条已解决 · 1 条仍存在</dd></div>
          <div class="gw-kv__row"><dt>签名人</dt><dd>林砚（法务审核）</dd></div>
        </dl>
        <div class="gw-btn-row">
          <button class="gw-btn gw-btn--ghost" type="button">退回整改</button>
          <button class="gw-btn gw-btn--primary" type="button">法务签名并关闭</button>
        </div>
      </PanelCard>
    </Teleport>
  </div>
</template>

<style scoped>
.view {
  display: contents;
}

/* ── 待办 ─────────────────────────────────────────────────────────── */
.todo {
  display: flex;
  flex-direction: column;
}

.todo__item {
  display: flex;
  align-items: flex-start;
  gap: var(--gw-s3);
  padding: var(--gw-s3) 0;
  border-bottom: 1px solid var(--gw-line-soft);
}

.todo__item:last-child {
  border-bottom: 0;
}

.todo__item .gw-status {
  flex: none;
  margin-top: 1px;
}

.todo__main {
  flex: 1;
  min-width: 0;
}

.todo__title {
  display: block;
  font-size: var(--gw-fs-base);
  font-weight: 500;
  color: var(--gw-text);
  line-height: 1.4;
}

.todo__sub {
  display: block;
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
  margin-top: 1px;
  line-height: 1.45;
}

/* ── 事件流 ───────────────────────────────────────────────────────── */
.ev__item {
  display: flex;
  align-items: baseline;
  gap: var(--gw-s3);
  padding: 7px var(--gw-s5);
}

.ev__dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  flex: none;
  background: var(--gw-line-strong);
  transform: translateY(-2px);
}

.ev__item[data-kind='ok'] .ev__dot {
  background: var(--gw-success);
}
.ev__item[data-kind='warn'] .ev__dot {
  background: var(--gw-warning);
}
.ev__item[data-kind='accent'] .ev__dot {
  background: var(--gw-accent);
}

.ev__text {
  flex: 1;
  min-width: 0;
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-secondary);
  line-height: var(--gw-lh);
}

.ev__time {
  flex: none;
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
}
</style>
