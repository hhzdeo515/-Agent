<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import WorkbenchLayout from '@/layouts/WorkbenchLayout.vue'
import type { ModuleKey } from '@/config/modules'

const route = useRoute()

// 当前模块由路由决定，并作为 data-module 属性驱动整套点缀色切换
const moduleKey = computed<ModuleKey>(() => (route.meta.module as ModuleKey) ?? 'tast')
</script>

<template>
  <WorkbenchLayout :module-key="moduleKey" />
</template>
