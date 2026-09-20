<script setup lang="ts">
/**
 * 左侧导航：技能切换标签 + 左下角用户区（设置入口）。
 *
 * 每个技能标签带一条自己的点缀色竖条，使模块可区分但整体仍统一在墨青体系里。
 *
 * 左下角原来有两块常驻信息（AI 能力状态条与个人信息），现在合并成一个入口：
 * 它们回答的是同一个问题——"我以什么身份、用什么能力在看这个工作台"，
 * 而 AI 供应商与模型型号是低频的运维配置，不该天天占着导航底部的视觉重量。
 */
import { RouterLink } from 'vue-router'
import { MODULES, type ModuleKey } from '@/config/modules'
import UserPanel from '@/components/UserPanel.vue'

const props = defineProps<{ active: ModuleKey }>()
</script>

<template>
  <nav class="nav">
    <!-- ── 顶部品牌标识 ─────────────────────────────────────────── -->
    <div class="nav__brand">
      <div class="nav__mark" aria-hidden="true">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none">
          <path
            d="M12 2.6 4.2 6.1v5.6c0 4.5 3.2 8.6 7.8 9.7 4.6-1.1 7.8-5.2 7.8-9.7V6.1L12 2.6Z"
            stroke="currentColor"
            stroke-width="1.5"
            stroke-linejoin="round"
          />
          <path
            d="M8.6 12.1l2.5 2.5 4.4-4.9"
            stroke="currentColor"
            stroke-width="1.5"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
      </div>
      <div class="nav__brand-text">
        <div class="nav__brand-name">广宣法务审核</div>
        <div class="nav__brand-sub">Agent 工作台</div>
      </div>
    </div>

    <div class="nav__rule" />

    <!-- ── 模块切换：只保留模块名，副标题（职责说明）已移除 ──────── -->
    <div class="nav__section">
      <div class="nav__caption gw-label">技能模块</div>
      <ul class="nav__list">
        <li v-for="m in MODULES" :key="m.key">
          <RouterLink
            :to="`/${m.key}`"
            class="nav__item"
            :class="{ 'is-active': props.active === m.key }"
            :data-tone="m.tone"
            :title="m.role"
          >
            <span class="nav__accent" aria-hidden="true" />
            <span class="nav__item-name">{{ m.name }}</span>
          </RouterLink>
        </li>
      </ul>
    </div>

    <div class="nav__spacer" />

    <!-- ── 左下角：身份 + 设置入口 ───────────────────────────────── -->
    <div class="nav__rule" />
    <UserPanel />
  </nav>
</template>

<style scoped>
.nav {
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: var(--gw-s5) var(--gw-s3) var(--gw-s3);
  background: var(--gw-surface);
  border-right: 1px solid var(--gw-line);
}

/* ── 品牌 ─────────────────────────────────────────────────────────── */
.nav__brand {
  display: flex;
  align-items: center;
  gap: var(--gw-s3);
  padding: 0 var(--gw-s2) var(--gw-s4);
}

.nav__mark {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  flex: none;
  border-radius: var(--gw-r);
  background: var(--gw-ink-700);
  color: #fff;
  box-shadow: var(--gw-shadow-xs);
}

.nav__brand-name {
  font-size: var(--gw-fs-md);
  font-weight: 600;
  color: var(--gw-text);
  letter-spacing: -0.01em;
}

.nav__brand-sub {
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-tertiary);
  margin-top: 1px;
}

.nav__rule {
  height: 1px;
  background: var(--gw-line);
}

/* ── 技能列表 ─────────────────────────────────────────────────────── */
.nav__section {
  padding-top: var(--gw-s5);
}

.nav__caption {
  padding: 0 var(--gw-s2) var(--gw-s3);
}

.nav__list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.nav__item {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--gw-s3);
  padding: 10px var(--gw-s2) 10px var(--gw-s3);
  border-radius: var(--gw-r);
  color: var(--gw-text-secondary);
  transition: background var(--gw-dur-fast) var(--gw-ease),
    color var(--gw-dur-fast) var(--gw-ease);
}

.nav__item:hover {
  background: var(--gw-bg-sunken);
  color: var(--gw-text);
}

/* 模块点缀色竖条 —— 模块可区分的关键视觉线索 */
.nav__accent {
  width: 3px;
  height: 20px;
  flex: none;
  border-radius: var(--gw-r-pill);
  background: var(--gw-line-strong);
  transition: background var(--gw-dur-fast) var(--gw-ease);
}

.nav__item[data-tone='ink'] .nav__accent {
  --tone: var(--gw-ink-700);
}
.nav__item[data-tone='brass'] .nav__accent {
  --tone: var(--gw-brass-500);
}
.nav__item[data-tone='wine'] .nav__accent {
  --tone: var(--gw-wine-500);
}

.nav__item.is-active {
  background: var(--gw-bg-sunken);
  color: var(--gw-text);
}

.nav__item.is-active .nav__accent {
  background: var(--tone);
}

.nav__item-name {
  font-size: var(--gw-fs-md);
  font-weight: 600;
  letter-spacing: 0.01em;
  line-height: 1.3;
  min-width: 0;
}

.nav__spacer {
  flex: 1;
  min-height: var(--gw-s6);
}

/* ── 窄屏：只留点缀色，避免挤压 ─────────────────────────────────────
   左下角用户区自己的窄屏适配在 UserPanel 内（它有头像可退化成图标）。 */
@media (max-width: 1180px) {
  .nav {
    padding-left: var(--gw-s2);
    padding-right: var(--gw-s2);
  }
  .nav__brand-text,
  .nav__item-name,
  .nav__caption {
    display: none;
  }
  .nav__item {
    justify-content: center;
    padding: 10px 0;
  }
}
</style>
