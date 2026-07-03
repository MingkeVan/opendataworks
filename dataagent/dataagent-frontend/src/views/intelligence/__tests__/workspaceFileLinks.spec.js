import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { renderMarkdown } from '../chatMessage'

// SPA 侧 resolveWorkspaceFileHref 的产出形态（NL2SqlChatV2.vue）：
// 自描述 fragment，由消息容器点击代理拦截后走带标记头的 Blob 下载。
const resolveFileHref = (relPath) => `#odw-file=${encodeURIComponent(String(relPath || ''))}`

const extractHref = (html) => {
  const match = html.match(/<a href="([^"]*)"/)
  expect(match).toBeTruthy()
  return match[1]
}

describe('workspace file links attribute escaping', () => {
  it.each([
    ['output/report.xlsx'],
    ['output/带 空格 的文件.csv'],
    ['output/100%涨幅.txt'],
    ['output/a"b.txt'],
    ['output/<tag>.txt']
  ])('roundtrips %s through an attribute-safe href', (relPath) => {
    const html = renderMarkdown(`[文件](${encodeURI(relPath)})`, { resolveFileHref })
    const href = extractHref(html)
    expect(href.startsWith('#odw-file=')).toBe(true)
    // 属性上下文（双引号）里不允许出现裸双引号 / 尖括号 / &。
    expect(href).not.toMatch(/["<>&]/)
    // 点击代理侧 decode 后还原出原始 rel path。
    expect(decodeURIComponent(href.slice('#odw-file='.length))).toBe(relPath)
  })

  it("keeps single quotes harmless inside the double-quoted attribute", () => {
    // encodeURIComponent 不转义单引号；属性由双引号包裹，单引号无注入面。
    const html = renderMarkdown(`[文件](${encodeURI("output/a'b.txt")})`, { resolveFileHref })
    const href = extractHref(html)
    expect(href).not.toMatch(/["<>&]/)
    expect(decodeURIComponent(href.slice('#odw-file='.length))).toBe("output/a'b.txt")
  })

  it('never emits a raw & even though the escape pipeline double-encodes it', () => {
    // 路径里的 & 会先被 escapeHtml 转成 &amp; 再进入链接解析（既有管线行为），
    // 这里只断言注入安全性：产出的 href 不含裸 & / 引号 / 尖括号。
    const html = renderMarkdown('[文件](output/a%26b.txt)', { resolveFileHref })
    const href = extractHref(html)
    expect(href.startsWith('#odw-file=')).toBe(true)
    expect(href).not.toMatch(/["<>&]/)
  })

  it('leaves external links untouched', () => {
    const html = renderMarkdown('[站外](https://example.com/a)', { resolveFileHref })
    expect(html).toContain('href="https://example.com/a"')
  })
})

describe('html preview sandbox regression', () => {
  it('keeps the artifact preview iframe sandboxed without scripts/same-origin', () => {
    const source = readFileSync(
      resolve(process.cwd(), 'src/views/intelligence/NL2SqlChatV2.vue'),
      'utf-8'
    )
    const iframeTags = source.match(/<iframe[^>]*>/g) || []
    expect(iframeTags.length).toBeGreaterThan(0)
    for (const tag of iframeTags) {
      expect(tag).toMatch(/sandbox="[^"]*"/)
      expect(tag).not.toMatch(/allow-scripts/)
      expect(tag).not.toMatch(/allow-same-origin/)
    }
  })
})
