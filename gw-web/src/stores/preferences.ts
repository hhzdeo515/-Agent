/**
 * 个性化偏好（只作用于本机，不入后端）。
 *
 * <h3>为什么放 localStorage 而不是做成接口</h3>
 * 密度、字号、动效是"这台设备上的阅读习惯"，不是账号属性。做成接口会有两个副作用：
 * 需要新增权限点，且同事临时借用同一台机器时会被迫接受别人的字号。
 *
 * <h3>为什么注入 CSS 变量，而不是给 html 加 class 再逐组件覆写</h3>
 * 整套设计 token 都是 CSS 自定义属性，组件只在 var() 这一层引用它们。
 * 覆盖根变量能让所有组件一起变；而加 class 意味着每个组件都要写一套
 * `.is-compact` 变体，改一处必漏一处。
 *
 * <h3>选择器为什么是 html:root 而不是 :root</h3>
 * tokens.css 里的变量声明在 `:root` 上。`html:root` 匹配同一个元素但多一个
 * 类型选择器的权重，因此不依赖"我的 <style> 一定排在打包 CSS 之后"这个前提——
 * 开发期 HMR 会重新注入样式，靠源码顺序赢是不牢靠的。
 *
 * <h3>主色调为什么不在这里</h3>
 * `--gw-accent` 系列<b>只</b>在 `[data-module]` 上声明（tokens.css），而
 * `[data-module]` 挂在工作台根元素上、位于 `:root` 与所有内容之间。
 * 根元素上的任何覆盖都会被它整层遮住：写在 :root 上的值是 html 的计算值，
 * 而工作台内部每个元素都会先命中更近的祖先 `[data-module]`。
 * 想赢只能去改 `[data-module]` 本身，那等于抹掉"模块可区分"这条编码
 * （接收区墨青 / 反馈区铜金 / 终审区暗酒红 / 助手石板灰，见 modules.ts），
 * 而左栏的技能竖条是直接写死 `--gw-ink-700` 一类的，会与页面其它区域不一致。
 * 因此这里不提供主色调开关——一个改了不生效的开关比没有开关更糟。
 */
import { computed, ref } from 'vue'

export type DensityKey = 'compact' | 'normal' | 'loose'
export type FontScaleKey = 'sm' | 'normal' | 'lg'

export interface Preferences {
  density: DensityKey
  fontScale: FontScaleKey
  reducedMotion: boolean
}

export interface PreferenceOption<T> {
  value: T
  label: string
}

export const DENSITY_OPTIONS: PreferenceOption<DensityKey>[] = [
  { value: 'compact', label: '紧凑' },
  { value: 'normal', label: '标准' },
  { value: 'loose', label: '宽松' },
]

export const FONT_SCALE_OPTIONS: PreferenceOption<FontScaleKey>[] = [
  { value: 'sm', label: '小' },
  { value: 'normal', label: '标准' },
  { value: 'lg', label: '大' },
]

export const DEFAULT_PREFERENCES: Preferences = {
  density: 'normal',
  fontScale: 'normal',
  reducedMotion: false,
}

const STORAGE_KEY = 'gw.prefs'
const STYLE_ID = 'gw-prefs'

/** 间距刻度 --gw-s3 … --gw-s9（紧凑 ×0.75 / 宽松 ×1.3），原值见 tokens.css */
const DENSITY_SPACING: Record<DensityKey, number[]> = {
  compact: [9, 12, 15, 18, 24, 30, 42],
  normal: [12, 16, 20, 24, 32, 40, 56],
  loose: [16, 21, 26, 31, 42, 52, 73],
}

/** --gw-fs-base / --gw-fs-md / --gw-fs-lg；xs 与 sm 不缩放，保证最小字号始终可读 */
const FONT_SCALE: Record<FontScaleKey, number[]> = {
  sm: [12, 13, 15],
  normal: [13, 14, 16],
  lg: [15, 16, 18],
}

function isDensity(v: unknown): v is DensityKey {
  return v === 'compact' || v === 'normal' || v === 'loose'
}

function isFontScale(v: unknown): v is FontScaleKey {
  return v === 'sm' || v === 'normal' || v === 'lg'
}

/** localStorage 里的值可能是旧版本或被手改过，逐项校验，坏值退回默认而不是整份丢弃 */
function readStored(): Preferences {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return { ...DEFAULT_PREFERENCES }
    const parsed = JSON.parse(raw) as Partial<Preferences>
    return {
      density: isDensity(parsed.density) ? parsed.density : DEFAULT_PREFERENCES.density,
      fontScale: isFontScale(parsed.fontScale) ? parsed.fontScale : DEFAULT_PREFERENCES.fontScale,
      reducedMotion: parsed.reducedMotion === true,
    }
  } catch {
    return { ...DEFAULT_PREFERENCES }
  }
}

function writeStored(p: Preferences) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(p))
  } catch {
    /* 隐私模式下不可写：本次会话内仍然生效，只是下次打开回到默认 */
  }
}

function buildCss(p: Preferences): string {
  const spacing = DENSITY_SPACING[p.density]
  const font = FONT_SCALE[p.fontScale]
  const lines = [
    ...spacing.map((v, i) => `  --gw-s${i + 3}: ${v}px;`),
    `  --gw-fs-base: ${font[0]}px;`,
    `  --gw-fs-md: ${font[1]}px;`,
    `  --gw-fs-lg: ${font[2]}px;`,
  ]
  if (p.reducedMotion) {
    // 收口到变量即可：全项目的过渡与动画都引用这两个时长
    lines.push('  --gw-dur: 0ms;', '  --gw-dur-fast: 0ms;')
  }
  return `html:root {\n${lines.join('\n')}\n}`
}

function ensureStyleTag(): HTMLStyleElement {
  const existing = document.getElementById(STYLE_ID)
  if (existing instanceof HTMLStyleElement) return existing
  const el = document.createElement('style')
  el.id = STYLE_ID
  document.head.appendChild(el)
  return el
}

function apply(p: Preferences) {
  ensureStyleTag().textContent = buildCss(p)
}

const prefs = ref<Preferences>(readStored())

/** 模块级副作用：入口组件一被引入就生效，避免首帧先按默认值绘制再跳变 */
apply(prefs.value)

function patch(next: Partial<Preferences>) {
  prefs.value = { ...prefs.value, ...next }
  writeStored(prefs.value)
  apply(prefs.value)
}

export function usePreferences() {
  const isDefault = computed(
    () =>
      prefs.value.density === DEFAULT_PREFERENCES.density &&
      prefs.value.fontScale === DEFAULT_PREFERENCES.fontScale &&
      prefs.value.reducedMotion === DEFAULT_PREFERENCES.reducedMotion,
  )

  return {
    prefs,
    isDefault,
    setDensity: (v: DensityKey) => patch({ density: v }),
    setFontScale: (v: FontScaleKey) => patch({ fontScale: v }),
    setReducedMotion: (v: boolean) => patch({ reducedMotion: v }),
    resetPreferences: () => patch({ ...DEFAULT_PREFERENCES }),
  }
}
