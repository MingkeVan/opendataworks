import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

// scoped 样式在 jsdom 里不生效，用源码守卫锁住"表名跟随侧栏宽度"这条规则。
// 侧栏可拖到 840px（useResizablePanes 的 MAX_SIDEBAR_WIDTH），
// 表名上任何硬编码宽度上限都会让长表名一直显示省略号。
const readRule = (source, selector) => {
  const match = source.match(new RegExp(`${selector.replace('.', '\\.')}\\s*\\{([^}]*)\\}`))
  return match ? match[1] : null
}

describe('DataStudioCatalogNode catalog width', () => {
  const source = readFileSync(
    resolve(process.cwd(), 'src/views/datastudio/components/DataStudioCatalogNode.vue'),
    'utf8'
  )

  it('does not cap the table name width', () => {
    const rule = readRule(source, '.table-name')

    expect(rule).toBeTruthy()
    expect(rule).not.toMatch(/max-width/)
    expect(rule).toMatch(/min-width:\s*0/)
    expect(rule).toMatch(/text-overflow:\s*ellipsis/)
  })

  it('lets the node fill the remaining tree row width', () => {
    const rule = readRule(source, '.catalog-node')

    expect(rule).toBeTruthy()
    expect(rule).toMatch(/flex:\s*1/)
    expect(rule).toMatch(/min-width:\s*0/)
  })
})
