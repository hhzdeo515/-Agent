<script setup lang="ts">
/**
 * GASP · 信息研判（反馈区）
 *
 * 责任：一批材料的第一次 AI 全量初审 —— 发现风险、分类风险、定位风险。
 * 边界：不管 V1/V2/V3 整改版本、不做 Diff、不做审批与关闭。
 *
 * 右栏刻意把「风险等级」做成实心色块，把「流程状态」做成描边胶囊 ——
 * 两套视觉编码，避免把「高风险」误读成「流程卡住了」。
 */
import { ref } from 'vue'
import ConversationThread, { type Message } from '@/components/ConversationThread.vue'
import PanelCard from '@/components/PanelCard.vue'

const messages = ref<Message[]>([
  {
    id: 'g1',
    role: 'user',
    text: 'GX-20260920-0001 已完成 AI 初审，先给我看需要重点处理的部分。',
    time: '14:20',
  },
  {
    id: 'g2',
    role: 'agent',
    text:
      '本批 10 份材料完成首轮全量初审：4 份初审通过、3 份待人工判断、3 份风险未通过。\n' +
      '共建立 7 条独立风险记录，其中高风险 3 条、中风险 2 条、低风险 2 条。\n\n' +
      '需要你优先处理的是「行业第一」与「100% 保障安全」两处——它们命中了较明确的规则，' +
      '在修改或补充证明材料之前，相关物料不能进入批准状态。',
    time: '14:20',
    tone: 'danger',
    trace: [
      {
        stage: '内容解析',
        summary: '10 份材料转为可审核内容并生成定位锚点',
        details: [
          '图片：OCR 文本定位 412 行 + 画面语义理解',
          '视频：口播 186 句（句级时间戳）+ 关键帧 64 张',
          '文档：页码/段落级锚点',
        ],
        costMs: 41200,
      },
      {
        stage: '候选风险识别',
        summary: '宽召回得到 19 条候选，允许含噪声',
        details: ['策略：宁可多出待人工判断，不可漏'],
        costMs: 6240,
      },
      {
        stage: '规则与证据检索',
        summary: '命中现行规则 9 条，未命中但模型想引用的 2 条已单独标记',
        details: ['检索：语义 + 关键词混合 → 重排取前 5 条', '未检索到的引用不会作为依据，已强制转人工'],
        costMs: 1830,
      },
      {
        stage: '风险判断',
        summary: '收敛为 7 条独立风险，2 条因依据不足转人工',
        details: ['低置信度且高影响的 2 条优先进入人工判断'],
        costMs: 5120,
      },
      {
        stage: '结构化输出校验',
        summary: '7 条全部通过锚点存在性与依据真实性校验',
        details: ['1 条画面类风险无文字锚点，已标注为「大致区域」'],
        costMs: 240,
      },
    ],
    metrics: [
      { label: '参与初审', value: 10, hint: '2 份解析失败未计入' },
      { label: '风险记录', value: 7, hint: '高 3 · 中 2 · 低 2' },
      { label: '物料通过率', value: '40%', hint: '4 / 10' },
    ],
  },
])

interface RiskRow {
  no: string
  text: string
  level: 'high' | 'medium' | 'low'
  levelText: string
  type: string
  material: string
  location: string
  status: string
  statusTone: string
}

const risks = ref<RiskRow[]>([
  { no: 'RK-1-001', text: '行业第一', level: 'high', levelText: '高', type: '绝对化宣传', material: '秋季主视觉海报-A', location: '主标题第 1 行', status: '待人工判断', statusTone: 'muted' },
  { no: 'RK-1-002', text: '100% 保障安全', level: 'high', levelText: '高', type: '安全承诺', material: '门店易拉宝', location: '底部承诺区', status: '待人工判断', statusTone: 'muted' },
  { no: 'RK-1-003', text: '续航可达 1000km', level: 'high', levelText: '高', type: '无依据数据', material: '新品卖点长图', location: '第 3 段', status: '新建', statusTone: 'ink' },
  { no: 'RK-1-004', text: '同级最优', level: 'medium', levelText: '中', type: '竞品比较', material: '产品演示短片', location: '00:42 – 00:46', status: '新建', statusTone: 'ink' },
  { no: 'RK-1-005', text: '限时立减 5000 元', level: 'medium', levelText: '中', type: '价格宣传', material: '社媒方图-01', location: '右下角', status: '新建', statusTone: 'ink' },
  { no: 'RK-1-006', text: '未标注数据来源', level: 'low', levelText: '低', type: '免责声明缺失', material: '秋季主视觉海报-B', location: '底部小字区', status: '新建', statusTone: 'ink' },
  { no: 'RK-1-007', text: '画面出现未标注依据的对比图', level: 'low', levelText: '低', type: '可能误导', material: '上市方案', location: '第 8 页（大致区域：右中）', status: '新建', statusTone: 'ink' },
])

const classOf = (l: RiskRow['level']) => `gw-level gw-level--${l}`
const statusOf = (t: RiskRow['statusTone']) => `gw-status gw-status--${t}`
</script>

<template>
  <div class="view">
    <ConversationThread :messages="messages" />

    <Teleport to="#wb-aside">
      <!-- 三分类：口径与后端视图一致，不在前端重算 -->
      <PanelCard title="初审三分类" hint="10 份">
        <div class="gw-stats">
          <div class="gw-stat gw-stat--ok">
            <div class="gw-stat__value">4</div>
            <div class="gw-stat__label">初审通过</div>
          </div>
          <div class="gw-stat gw-stat--warn">
            <div class="gw-stat__value">3</div>
            <div class="gw-stat__label">待人工判断</div>
          </div>
          <div class="gw-stat gw-stat--err">
            <div class="gw-stat__value">3</div>
            <div class="gw-stat__label">风险未通过</div>
          </div>
        </div>

        <div class="gw-note" style="margin-top: var(--gw-s4)">
          通过率 = 初审通过物料数 ÷ 参与初审物料数。解析失败的 2 份不计入分母，单独列出。
        </div>

        <div class="gw-disclaimer" style="margin-top: var(--gw-s3)">
          <svg class="gw-disclaimer__icon" viewBox="0 0 16 16" width="14" height="14" fill="none" aria-hidden="true">
            <circle cx="8" cy="8" r="6.4" stroke="currentColor" stroke-width="1.4" />
            <path d="M8 7.2v4M8 4.9v.9" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
          </svg>
          <p class="gw-disclaimer__text">
            「初审通过」仅表示 AI 未发现明显风险，<strong>不等于法务最终批准</strong>。
          </p>
        </div>
      </PanelCard>

      <!-- 风险等级分布 -->
      <PanelCard title="风险等级分布" hint="7 条">
        <ul class="dist">
          <li class="dist__row">
            <span class="gw-level gw-level--high">高</span>
            <span class="dist__bar"><i style="width: 43%; background: var(--gw-risk-high)" /></span>
            <span class="dist__num gw-mono">3</span>
          </li>
          <li class="dist__row">
            <span class="gw-level gw-level--medium">中</span>
            <span class="dist__bar"><i style="width: 29%; background: var(--gw-risk-medium)" /></span>
            <span class="dist__num gw-mono">2</span>
          </li>
          <li class="dist__row">
            <span class="gw-level gw-level--low">低</span>
            <span class="dist__bar"><i style="width: 29%; background: var(--gw-risk-low)" /></span>
            <span class="dist__num gw-mono">2</span>
          </li>
        </ul>
        <p class="dist__tip">等级描述潜在影响；它与「置信度」「流程状态」是三个独立维度，不可互相换算。</p>
      </PanelCard>

      <!-- 风险卡片列表 -->
      <PanelCard title="风险记录" hint="7 条 · 高优先" flush>
        <ul class="rk">
          <li v-for="r in risks" :key="r.no" class="rk__item" :data-level="r.level">
            <div class="rk__top">
              <span :class="classOf(r.level)">{{ r.levelText }}</span>
              <span class="rk__no gw-mono">{{ r.no }}</span>
              <span :class="statusOf(r.statusTone)" style="margin-left: auto">
                <span class="gw-status__dot" />{{ r.status }}
              </span>
            </div>
            <p class="rk__text">{{ r.text }}</p>
            <div class="rk__meta">
              <span class="rk__type">{{ r.type }}</span>
              <span class="rk__sep">·</span>
              <span class="gw-truncate">{{ r.material }}</span>
            </div>
            <div class="rk__loc">
              <svg viewBox="0 0 14 14" width="11" height="11" fill="none" aria-hidden="true">
                <path d="M7 12.6s4.2-3.4 4.2-6.3A4.2 4.2 0 0 0 2.8 6.3C2.8 9.2 7 12.6 7 12.6Z" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round" />
                <circle cx="7" cy="6.2" r="1.5" stroke="currentColor" stroke-width="1.3" />
              </svg>
              <span class="gw-truncate">{{ r.location }}</span>
            </div>
          </li>
        </ul>
      </PanelCard>
    </Teleport>
  </div>
</template>

<style scoped>
.view {
  display: contents;
}

/* ── 等级分布 ─────────────────────────────────────────────────────── */
.dist {
  display: flex;
  flex-direction: column;
  gap: var(--gw-s3);
}

.dist__row {
  display: flex;
  align-items: center;
  gap: var(--gw-s3);
}

.dist__bar {
  flex: 1;
  height: 6px;
  border-radius: var(--gw-r-pill);
  background: var(--gw-bg-sunken);
  overflow: hidden;
}

.dist__bar i {
  display: block;
  height: 100%;
  border-radius: var(--gw-r-pill);
}

.dist__num {
  width: 16px;
  text-align: right;
  font-size: var(--gw-fs-base);
  font-weight: 600;
  color: var(--gw-text);
  flex: none;
}

.dist__tip {
  margin-top: var(--gw-s4);
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
  line-height: var(--gw-lh);
}

/* ── 风险卡片 ─────────────────────────────────────────────────────── */
.rk__item {
  padding: var(--gw-s4) var(--gw-s5);
  border-bottom: 1px solid var(--gw-line-soft);
  border-left: 3px solid transparent;
  transition: background var(--gw-dur-fast) var(--gw-ease);
}

.rk__item:hover {
  background: var(--gw-surface-hover);
}

.rk__item[data-level='high'] {
  border-left-color: var(--gw-risk-high);
}
.rk__item[data-level='medium'] {
  border-left-color: var(--gw-risk-medium);
}
.rk__item[data-level='low'] {
  border-left-color: var(--gw-risk-low);
}

.rk__top {
  display: flex;
  align-items: center;
  gap: var(--gw-s2);
  margin-bottom: var(--gw-s2);
}

.rk__no {
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
}

.rk__text {
  font-size: var(--gw-fs-md);
  font-weight: 500;
  color: var(--gw-text);
  line-height: 1.45;
}

.rk__meta {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-top: var(--gw-s2);
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
  min-width: 0;
}

.rk__type {
  flex: none;
  padding: 0 5px;
  border: 1px solid var(--gw-line);
  border-radius: var(--gw-r-sm);
  line-height: 15px;
}

.rk__sep {
  flex: none;
  color: var(--gw-line-strong);
}

.rk__loc {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 5px;
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
  min-width: 0;
}

.rk__loc svg {
  flex: none;
  color: var(--gw-accent);
}
</style>
