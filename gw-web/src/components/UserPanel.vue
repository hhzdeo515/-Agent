<script setup lang="ts">
/**
 * 左下角用户区：显示当前身份，点击打开设置面板。
 *
 * <h3>为什么要把原来的 AI 状态条与个人信息合成一个入口</h3>
 * 两者回答的是同一个问题——"我现在以什么身份、用什么能力在看这个工作台"。
 * 拆成两个常驻控件会让左栏底部挤满次要信息，而它们都不是高频操作；
 * 合成一个入口后，左栏底部只留一处，设置里的三个分区各自对自己的语义负责。
 *
 * <h3>身份为什么从接口取，而不是像原来那样写死</h3>
 * 写死的"林砚 / 法务审核"会在接入登录后立刻变成谎话，而且显示名一旦在设置里改过，
 * 左栏会与设置面板自相矛盾。这里以 /api/settings/profile 为准，
 * 读不到就如实显示读取失败，不编造一个看起来正常的身份。
 */
import { computed, onMounted, ref } from 'vue'
import SettingsDialog from '@/components/SettingsDialog.vue'
import { apiGet } from '@/api/client'

interface Profile {
  id: number
  username: string
  displayName: string
  dept: string | null
  roleCodes: string | null
}

/** 角色码到中文名的映射：库里存的是 LEGAL / BRAND 这类码，界面上要让法务看懂 */
const ROLE_LABEL: Record<string, string> = {
  LEGAL: '法务',
  BRAND: '品牌',
  DESIGN: '设计',
  BIZ: '业务',
  ADMIN: '管理员',
  AI_SERVICE: 'AI 服务',
}

const open = ref(false)
const profile = ref<Profile | null>(null)
const failed = ref(false)

onMounted(() => {
  void load()
})

async function load() {
  try {
    profile.value = await apiGet<Profile>('/api/settings/profile')
    failed.value = false
  } catch {
    failed.value = true
  }
}

const name = computed(() => profile.value?.displayName?.trim() || profile.value?.username || '未登录')
const initials = computed(() => name.value.slice(0, 1))

/** 角色 + 部门；两者都缺时不给占位文案，避免"法务 · 未知部门"这种半真半假的展示 */
const meta = computed(() => {
  if (failed.value) return '身份读取失败'
  const role = (profile.value?.roleCodes ?? '')
    .split(',')
    .map((c) => ROLE_LABEL[c.trim()] ?? c.trim())
    .filter(Boolean)
    .join(' / ')
  const dept = profile.value?.dept?.trim() ?? ''
  return [role, dept].filter(Boolean).join(' · ') || profile.value?.username || ''
})
</script>

<template>
  <div class="up">
    <button
      class="up__btn"
      type="button"
      title="设置：AI 调用 / 个人信息 / 个性化"
      aria-haspopup="dialog"
      @click="open = true"
    >
      <span class="up__avatar" aria-hidden="true">{{ initials }}</span>
      <span class="up__text">
        <span class="up__name gw-truncate">{{ name }}</span>
        <span class="up__meta gw-truncate">{{ meta }}</span>
      </span>
      <span class="up__gear" aria-hidden="true">
        <svg viewBox="0 0 16 16" width="14" height="14" fill="none">
          <circle cx="8" cy="8" r="2.2" stroke="currentColor" stroke-width="1.4" />
          <path
            d="M8 1.6v1.7M8 12.7v1.7M14.4 8h-1.7M3.3 8H1.6M12.5 3.5l-1.2 1.2M4.7 11.3l-1.2 1.2M12.5 12.5l-1.2-1.2M4.7 4.7 3.5 3.5"
            stroke="currentColor"
            stroke-width="1.4"
            stroke-linecap="round"
          />
        </svg>
      </span>
    </button>

    <SettingsDialog v-model="open" :profile="profile" @profile-saved="profile = $event" />
  </div>
</template>

<style scoped>
.up {
  padding: var(--gw-s2);
}

.up__btn {
  display: flex;
  align-items: center;
  gap: var(--gw-s3);
  width: 100%;
  padding: var(--gw-s2);
  border: 1px solid transparent;
  border-radius: var(--gw-r);
  background: transparent;
  text-align: left;
  transition: background var(--gw-dur-fast) var(--gw-ease),
    border-color var(--gw-dur-fast) var(--gw-ease);
}

.up__btn:hover {
  background: var(--gw-bg-sunken);
  border-color: var(--gw-line);
}

.up__avatar {
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

.up__text {
  display: flex;
  flex-direction: column;
  min-width: 0;
  flex: 1;
}

.up__name {
  font-size: var(--gw-fs-base);
  font-weight: 500;
  color: var(--gw-text);
  line-height: 1.3;
}

.up__meta {
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
  line-height: 1.4;
}

.up__gear {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  flex: none;
  color: var(--gw-text-tertiary);
  transition: color var(--gw-dur-fast) var(--gw-ease);
}

.up__btn:hover .up__gear {
  color: var(--gw-text-secondary);
}

/* 窄屏只留头像：文字被挤成一条竖线比隐藏更难读 */
@media (max-width: 1180px) {
  .up {
    padding: var(--gw-s2) 0;
  }
  .up__btn {
    justify-content: center;
    padding: var(--gw-s2) 0;
  }
  .up__text,
  .up__gear {
    display: none;
  }
}
</style>
