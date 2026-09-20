<script setup lang="ts">
/**
 * AI 法务助手（AGENTS.md 第 3 条）
 *
 * 责任：轻量、即时的咨询入口。用户输入一句宣传语、一段文案，或临时上传一张图片，
 *      询问是否存在明显风险、涉及哪些规则、为何判定为高风险、如何改写。
 *
 * 边界：不能创建正式审核任务、不能对几十份材料做批次审核、不能生成批次通过率、
 *      不能改变 Risk Case 状态、不能执行终审、签名、批准或关闭。
 *      如用户希望转为正式审核，走「转为正式审核任务」入口，把输入带入接收区由用户确认。
 *
 * 视觉上刻意比另外三个模块「轻」：中性灰蓝点缀色、更少的卡片、更松的留白，
 * 让它在导航里一眼可辨为「旁路入口」而非流程环节。
 */
import { ref } from 'vue'
import ConversationThread, { type Message } from '@/components/ConversationThread.vue'
import PanelCard from '@/components/PanelCard.vue'

const messages = ref<Message[]>([
  {
    id: 'a1',
    role: 'user',
    text: '帮我看下这句宣传语有没有问题：「全新一代电池，续航可达 1000 公里，行业第一」。',
    time: '10:24',
  },
  {
    id: 'a2',
    role: 'agent',
    text:
      '这句话有两处独立风险，性质不同，建议分开处理：\n\n' +
      '「行业第一」属于绝对化用语，命中较明确的规则，通常不能使用，除非能提供权威第三方排名依据并标明来源与统计口径。\n\n' +
      '「续航可达 1000 公里」本身不必然违规，但缺少测试工况与数据来源标注——这是常见的被认定为误导消费者的情形。建议标注「CLTC 工况下」等条件并附检测报告编号。',
    time: '10:24',
    tone: 'warning',
    trace: [
      {
        stage: '意图识别',
        summary: '判断为单条文案风险咨询，非正式审核任务',
        costMs: 180,
      },
      {
        stage: '规则检索',
        summary: '命中现行规则 3 条，均为可直接引用的条款',
        details: [
          '绝对化用语相关条款（现行）',
          '宣传数据需标明来源与工况的要求',
          '企业内部广宣规范：续航类表述必须标注测试标准',
        ],
        costMs: 620,
      },
      {
        stage: '结论生成',
        summary: '给出两处风险、依据与改写建议',
        costMs: 940,
      },
    ],
  },
  {
    id: 'a3',
    role: 'user',
    text: '那改成什么比较稳妥？',
    time: '10:26',
  },
  {
    id: 'a4',
    role: 'agent',
    text:
      '推荐表达：\n\n「全新一代电池，CLTC 工况续航最高 1000 公里」\n\n' +
      '改动点：删去「行业第一」，为续航数值补上测试工况。若确实需要强调领先性，' +
      '可改为可验证的客观表述，例如「续航表现较上一代提升 XX%」，并注明对比测试条件。\n\n' +
      '保留原表达需要补充的材料：第三方检测报告（含工况说明）、以及排名类依据（如确有）。',
    time: '10:26',
    tone: 'success',
  },
])

/** 助手可用的临时输入类型；这些内容不会进入正式流程 */
const capabilities = [
  { label: '单条宣传语', hint: '直接粘贴文案' },
  { label: '临时图片', hint: '海报 / 截图，有有效期' },
  { label: '短视频片段', hint: '提取口播与字幕' },
  { label: '零散法律问题', hint: '如免责声明怎么写' },
]
</script>

<template>
  <div class="view">
    <ConversationThread :messages="messages" />

    <Teleport defer to="#wb-aside">
      <!-- 能力范围：先讲清能做什么，避免用户误以为能替代正式审核 -->
      <PanelCard title="可以帮你">
        <ul class="cap">
          <li v-for="c in capabilities" :key="c.label" class="cap__item">
            <span class="cap__dot" aria-hidden="true" />
            <div class="cap__text">
              <span class="cap__label">{{ c.label }}</span>
              <span class="cap__hint">{{ c.hint }}</span>
            </div>
          </li>
        </ul>
      </PanelCard>

      <!-- 边界说明：这是产品最容易产生误解的地方，必须显式写出来 -->
      <PanelCard title="助手不做这些">
        <ul class="limits">
          <li>不创建正式审核任务</li>
          <li>不对多份材料做批次审核</li>
          <li>不生成批次通过率，也不改变风险状态</li>
          <li>不执行终审、签名、批准或风险关闭</li>
        </ul>
        <p class="limits__tip">
          这些动作属于正式流程，需要由有权限的法务在接收区、反馈区、终审区完成。
        </p>
      </PanelCard>

      <!-- 转正式任务的入口：预填充而非直接创建 -->
      <PanelCard title="转成正式审核">
        <p class="promote__tip">
          如果这次咨询涉及要对外发布的一批材料，建议转为正式审核任务。
          我会把当前输入与结论带入接收区，<strong>由你确认后</strong>才创建任务。
        </p>
        <dl class="gw-kv" style="margin-top: var(--gw-s3)">
          <div class="gw-kv__row"><dt>将带入</dt><dd>本次输入内容与分析结论</dd></div>
          <div class="gw-kv__row"><dt>不会自动</dt><dd>创建任务、上传材料、启动初审</dd></div>
        </dl>
        <div class="gw-btn-row">
          <button class="gw-btn gw-btn--primary gw-btn--block" type="button">
            转为正式审核任务
          </button>
        </div>
      </PanelCard>

      <!-- 本次会话的临时附件 -->
      <PanelCard title="本次会话文件" hint="有有效期">
        <p class="temp__tip">
          助手内的文件仅用于本次咨询，到期自动清理，不会进入正式物料库。
        </p>
        <ul class="gw-rows">
          <li class="gw-row">
            <div class="gw-row__main">
              <span class="gw-row__title">宣传语截图.png</span>
              <span class="gw-row__sub">临时文件 · 24 小时后自动清理</span>
            </div>
            <span class="gw-status gw-status--muted">未入库</span>
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

/* ── 能力列表 ─────────────────────────────────────────────────────── */
.cap {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.cap__item {
  display: flex;
  align-items: flex-start;
  gap: var(--gw-s3);
  padding: 7px 0;
}

.cap__dot {
  width: 5px;
  height: 5px;
  flex: none;
  margin-top: 7px;
  border-radius: 50%;
  background: var(--gw-accent);
  opacity: 0.7;
}

.cap__text {
  min-width: 0;
}

.cap__label {
  display: block;
  font-size: var(--gw-fs-base);
  color: var(--gw-text);
  line-height: 1.4;
}

.cap__hint {
  display: block;
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
  margin-top: 1px;
}

/* ── 边界说明 ─────────────────────────────────────────────────────── */
.limits {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.limits li {
  position: relative;
  padding-left: var(--gw-s4);
  font-size: var(--gw-fs-base);
  color: var(--gw-text-secondary);
  line-height: var(--gw-lh);
}

/* 用「短横」而非圆点：语义上表示"禁止/排除"，与能力列表的点形成对照 */
.limits li::before {
  content: '';
  position: absolute;
  left: 0;
  top: 9px;
  width: 6px;
  height: 1.5px;
  border-radius: 1px;
  background: var(--gw-slate-400);
}

.limits__tip {
  margin-top: var(--gw-s4);
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-tertiary);
  line-height: var(--gw-lh);
  padding-top: var(--gw-s3);
  border-top: 1px solid var(--gw-line-soft);
}

/* ── 转正式任务 ───────────────────────────────────────────────────── */
.promote__tip {
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-secondary);
  line-height: var(--gw-lh);
  padding: var(--gw-s3);
  border-radius: var(--gw-r-sm);
  background: var(--gw-accent-faint);
}

.promote__tip strong {
  color: var(--gw-text);
  font-weight: 600;
}

/* ── 临时文件 ─────────────────────────────────────────────────────── */
.temp__tip {
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-tertiary);
  line-height: var(--gw-lh);
  margin-bottom: var(--gw-s3);
}
</style>
