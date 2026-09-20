/**
 * 极简 Markdown 渲染器（只覆盖 AI 初审报告用到的语法）。
 *
 * 为什么不用成熟库：报告的结构是后端固定的（标题 / 表格 / 引用 / 代码块 / 列表），
 * 引一个通用解析器只为渲染这一种文档，代价与收益不成比例；
 * 而且通用库允许内联 HTML，报告里一旦混入原文内容就是 XSS 面。
 *
 * 安全前提：<b>先转义再解析</b> —— 所有 `<` `>` `&` 在任何替换之前就被实体化，
 * 因此物料原文里的标签不可能变成可执行节点。
 */

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/** 行内语法：粗体、行内代码 */
function inline(s: string): string {
  return s
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
}

function splitRow(line: string): string[] {
  return line
    .replace(/^\s*\|/, '')
    .replace(/\|\s*$/, '')
    .split('|')
    .map((c) => c.trim())
}

export function renderMarkdown(md: string): string {
  const lines = escapeHtml(md).split(/\r?\n/)
  const out: string[] = []
  let i = 0

  while (i < lines.length) {
    const line = lines[i]

    // 代码块：整段原样输出（话术占了报告里的大部分代码块）
    if (line.trimStart().startsWith('```')) {
      const buf: string[] = []
      i++
      while (i < lines.length && !lines[i].trimStart().startsWith('```')) {
        buf.push(lines[i])
        i++
      }
      i++ // 跳过结束围栏
      out.push(`<pre class="md__code">${buf.join('\n')}</pre>`)
      continue
    }

    // 标题
    const h = /^(#{1,4})\s+(.*)$/.exec(line)
    if (h) {
      const level = h[1].length
      out.push(`<h${level} class="md__h md__h--${level}">${inline(h[2])}</h${level}>`)
      i++
      continue
    }

    // 表格：以 | 开头且下一行是分隔行
    if (line.trimStart().startsWith('|') && /^\s*\|[\s:|-]+\|\s*$/.test(lines[i + 1] ?? '')) {
      const head = splitRow(line)
      i += 2
      const rows: string[][] = []
      while (i < lines.length && lines[i].trimStart().startsWith('|')) {
        rows.push(splitRow(lines[i]))
        i++
      }
      out.push(
        '<table class="md__table"><thead><tr>' +
          head.map((c) => `<th>${inline(c)}</th>`).join('') +
          '</tr></thead><tbody>' +
          rows
            .map(
              (r) =>
                '<tr>' +
                head.map((_, idx) => `<td>${inline(r[idx] ?? '')}</td>`).join('') +
                '</tr>',
            )
            .join('') +
          '</tbody></table>',
      )
      continue
    }

    // 引用
    if (line.startsWith('&gt;')) {
      const buf: string[] = []
      while (i < lines.length && lines[i].startsWith('&gt;')) {
        buf.push(lines[i].replace(/^&gt;\s?/, ''))
        i++
      }
      out.push(`<blockquote class="md__quote">${inline(buf.join('<br>'))}</blockquote>`)
      continue
    }

    // 列表
    if (/^\s*[-*]\s+/.test(line)) {
      const buf: string[] = []
      while (i < lines.length && /^\s*[-*]\s+/.test(lines[i])) {
        buf.push(`<li>${inline(lines[i].replace(/^\s*[-*]\s+/, ''))}</li>`)
        i++
      }
      out.push(`<ul class="md__ul">${buf.join('')}</ul>`)
      continue
    }

    if (line.trim() === '') {
      i++
      continue
    }

    // 普通段落
    const buf: string[] = []
    while (
      i < lines.length &&
      lines[i].trim() !== '' &&
      !/^(#{1,4})\s/.test(lines[i]) &&
      !lines[i].trimStart().startsWith('|') &&
      !lines[i].startsWith('&gt;') &&
      !/^\s*[-*]\s+/.test(lines[i]) &&
      !lines[i].trimStart().startsWith('```')
    ) {
      buf.push(lines[i])
      i++
    }
    out.push(`<p class="md__p">${inline(buf.join('<br>'))}</p>`)
  }

  return out.join('\n')
}
