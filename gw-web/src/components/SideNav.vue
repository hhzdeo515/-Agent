<script setup lang="ts">
/**
 * 左侧导航：三个技能切换标签 + 左下角个人信息。
 *
 * 每个技能标签带一条自己的点缀色竖条，使模块可区分但整体仍统一在墨青体系里。
 */
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { MODULES, type ModuleKey } from '@/config/modules'

const props = defineProps<{ active: ModuleKey }>()

// 演示用身份。接入登录后应来自 /api/me，且权限点绝不从客户端声明（04 文档 §1.8）
const user = {
  name: '林砚',
  role: '法务审核',
  dept: '法律合规部',
}

const initials = computed(() => user.name.slice(0, 1))
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

    <!-- ── 三个技能模块切换 ──────────────────────────────────────── -->
    <div class="nav__section">
      <div class="nav__caption gw-label">技能模块</div>
      <ul class="nav__list">
        <li v-for="m in MODULES" :key="m.key">
          <RouterLink
            :to="`/${m.key}`"
            class="nav__item"
            :class="{ 'is-active': props.active === m.key }"
            :data-tone="m.tone"
            :title="m.desc"
          >
            <span class="nav__accent" aria-hidden="true" />
            <span class="nav__item-body">
              <span class="nav__item-code">{{ m.code }}</span>
              <span class="nav__item-name">{{ m.name }}</span>
            </span>
          </RouterLink>
        </li>
      </ul>
    </div>

    <div class="nav__spacer" />

    <!-- ── 左下角：个人信息 ──────────────────────────────────────── -->
    <div class="nav__rule" />
    <div class="nav__user">
      <div class="nav__avatar" aria-hidden="true">{{ initials }}</div>
      <div class="nav__user-text">
        <div class="nav__user-name gw-truncate">{{ user.name }}</div>
        <div class="nav__user-meta gw-truncate">{{ user.role }} · {{ user.dept }}</div>
      </div>
      <button class="nav__user-more" type="button" title="账户与设置" aria-label="账户与设置">
        <svg viewBox="0 0 16 16" width="14" height="14" fill="currentColor" aria-hidden="true">
          <circle cx="8" cy="3.2" r="1.3" />
          <circle cx="8" cy="8" r="1.3" />
          <circle cx="8" cy="12.8" r="1.3" />
        </svg>
      </button>
    </div>
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
  padding: 9px var(--gw-s2) 9px var(--gw-s3);
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

.nav__item-body {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.nav__item-code {
  font-size: var(--gw-fs-md);
  font-weight: 600;
  letter-spacing: 0.01em;
  line-height: 1.25;
}

.nav__item-name {
  font-size: var(--gw-fs-sm);
  color: var(--gw-text-tertiary);
  line-height: 1.35;
}

.nav__item.is-active .nav__item-name {
  color: var(--gw-text-secondary);
}

.nav__spacer {
  flex: 1;
  min-height: var(--gw-s6);
}

/* ── 左下角个人信息 ───────────────────────────────────────────────── */
.nav__user {
  display: flex;
  align-items: center;
  gap: var(--gw-s3);
  padding: var(--gw-s4) var(--gw-s2) var(--gw-s2);
}

.nav__avatar {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  flex: none;
  border-radius: var(--gw-r);
  background: var(--gw-ink-100);
  color: var(--gw-ink-700);
  font-size: var(--gw-fs-base);
  font-weight: 600;
}

.nav__user-text {
  min-width: 0;
  flex: 1;
}

.nav__user-name {
  font-size: var(--gw-fs-base);
  font-weight: 500;
  color: var(--gw-text);
  line-height: 1.3;
}

.nav__user-meta {
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
  line-height: 1.4;
}

.nav__user-more {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  flex: none;
  border: 0;
  border-radius: var(--gw-r-sm);
  background: transparent;
  color: var(--gw-text-tertiary);
  transition: background var(--gw-dur-fast) var(--gw-ease), color var(--gw-dur-fast) var(--gw-ease);
}

.nav__user-more:hover {
  background: var(--gw-bg-sunken);
  color: var(--gw-text-secondary);
}

/* 窄屏：只留点缀色与首字母，避免挤压 */
@media (max-width: 1180px) {
  .nav {
    padding-left: var(--gw-s2);
    padding-right: var(--gw-s2);
  }
  .nav__brand-text,
  .nav__item-body,
  .nav__user-text,
  .nav__caption {
    display: none;
  }
  .nav__item {
    justify-content: center;
    padding: 10px 0;
  }
  .nav__user {
    justify-content: center;
  }
  .nav__user-more {
    display: none;
  }
}
</style>
