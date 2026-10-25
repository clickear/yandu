import { test, expect } from '@playwright/test'
import { readFile } from 'node:fs/promises'

const importFile = async (
  page: import('@playwright/test').Page,
  content: string,
  locale = 'en-US',
) => {
  await page.getByRole('button', { name: '导入 JSON', exact: true }).click()
  await page.getByLabel('导入语言').selectOption(locale)
  await page.getByLabel('选择 JSON 文件').setInputFiles({
    name: 'messages.json',
    mimeType: 'application/json',
    buffer: Buffer.from(content),
  })
  await page.getByRole('button', { name: '确认导入' }).click()
}

test('sample workspace loads, filters by language/status and searches translations', async ({
  page,
}) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '翻译工作台' })).toBeVisible()
  await expect(page.locator('tbody tr')).toHaveCount(12)
  await page.getByLabel('按状态筛选').selectOption('untranslated')
  await expect(page.locator('tbody tr')).toHaveCount(3)
  await page.getByLabel('搜索文案或键名').fill('common.search')
  await expect(page.locator('tbody tr')).toHaveCount(1)
  await page.getByRole('button', { name: '日 日本語' }).click()
  await expect(page.locator('tbody tr')).toHaveCount(12)
  expect(errors).toEqual([])
})

test('create a project, add language and entry, review a translation and persist it', async ({
  page,
}) => {
  await page.goto('/')
  await page.getByRole('button', { name: '新建项目', exact: true }).click()
  await page.getByLabel('项目名称', { exact: true }).fill('移动应用')
  await page.getByLabel('项目描述').fill('App localization')
  await page.getByRole('dialog').getByRole('button', { name: '创建项目', exact: true }).click()
  await page.getByRole('button', { name: '添加文案', exact: true }).first().click()
  await page.getByLabel('键名', { exact: true }).fill('app.welcome')
  await page.getByLabel('源文案').fill('欢迎来到言渡')
  await page.getByRole('dialog').getByRole('button', { name: '添加文案' }).click()
  await page.getByRole('button', { name: '编辑 app.welcome', exact: true }).click()
  await page.getByRole('textbox', { name: '译文 · English', exact: true }).fill('Welcome to YANDU')
  await page.getByLabel('标记为已审核').check()
  await page.getByRole('button', { name: '保存译文' }).click()
  await expect(page.locator('tbody tr').first()).toContainText('已审核')
  await page.getByRole('button', { name: '添加语言', exact: true }).click()
  await page.getByRole('dialog').getByRole('combobox').selectOption('fr-FR')
  await page.getByRole('dialog').getByRole('button', { name: '添加语言', exact: true }).click()
  await expect(page.locator('tbody tr').first()).toContainText('未翻译')
  await page.reload()
  await page
    .getByRole('navigation', { name: '翻译项目' })
    .getByRole('button', { name: '移动应用' })
    .click()
  await expect(page.locator('tbody tr').first()).toContainText('Welcome to YANDU')
  await expect(page.locator('tbody tr').first()).toContainText('已审核')
  await expect(page.getByRole('button', { name: 'FR Français' })).toBeVisible()
})

test('editing a reviewed translation resets the review and warns before discarding', async ({
  page,
}) => {
  await page.goto('/')
  await page.getByRole('button', { name: '编辑 home.hero.title', exact: true }).click()
  await expect(page.getByLabel('标记为已审核')).toBeChecked()
  await page.getByRole('textbox', { name: '译文 · English', exact: true }).fill('A new translation')
  await expect(page.getByLabel('标记为已审核')).not.toBeChecked()
  page.once('dialog', (dialog) => dialog.dismiss())
  await page.getByRole('button', { name: '关闭弹窗' }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.getByRole('button', { name: '保存译文' }).click()
  await expect(page.locator('tbody tr').first()).toContainText('待审核')
})

test('imports, updates, and exports real JSON files', async ({ page }) => {
  await page.goto('/')
  await importFile(page, '{"home.hero.title":"Updated headline","new.key":"Fresh text"}')
  await expect(page.locator('tbody tr')).toHaveCount(13)
  await expect(page.locator('tbody tr').first()).toContainText('Updated headline')
  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出 JSON', exact: true }).click()
  const download = await downloadPromise
  const data = JSON.parse(await readFile((await download.path())!, 'utf8'))
  expect(data['home.hero.title']).toBe('Updated headline')
  expect(data['new.key']).toBe('Fresh text')
  expect(data['common.search']).toBe('')
  await importFile(page, '{"home.hero.title":"新的源文案"}', 'zh-CN')
  await expect(page.locator('tbody tr').first()).toContainText('新的源文案')
  await expect(page.locator('tbody tr').first()).toContainText('待审核')
})

test('invalid import preserves existing data and duplicate entry shows an error', async ({
  page,
}) => {
  await page.goto('/')
  await importFile(page, '{"home.hero.title":"must not apply","bad":7}')
  await expect(page.getByRole('dialog').getByRole('alert')).toContainText('字符串')
  await page.getByRole('button', { name: '关闭弹窗' }).click()
  await expect(page.locator('tbody tr')).toHaveCount(12)
  await expect(page.locator('tbody tr').first()).toContainText('Every word, a world closer.')
  await page.getByRole('button', { name: '添加文案', exact: true }).click()
  await page.getByLabel('键名', { exact: true }).fill('home.hero.title')
  await page.getByLabel('源文案').fill('重复')
  await page.getByRole('dialog').getByRole('button', { name: '添加文案', exact: true }).click()
  await expect(page.getByRole('dialog').getByRole('alert')).toContainText('已存在')
})

test('corrupted persisted data is preserved until explicit reset', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('yandu.workspace.v1', 'broken data'))
  await page.goto('/')
  await expect(page.getByRole('alert')).toContainText('原数据不会被覆盖')
  expect(await page.evaluate(() => localStorage.getItem('yandu.workspace.v1'))).toBe('broken data')
  page.once('dialog', (dialog) => dialog.accept())
  await page.getByRole('button', { name: '重置本地存储' }).click()
  await expect(page.getByRole('alert')).toHaveCount(0)
  expect(
    JSON.parse((await page.evaluate(() => localStorage.getItem('yandu.workspace.v1')))!).version,
  ).toBe(1)
})

test('mobile layout contains overflow within the translation table', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '翻译工作台' })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  )
  await page.getByRole('button', { name: '编辑 home.hero.title', exact: true }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await expect(page.getByRole('textbox', { name: '译文 · English', exact: true })).toBeVisible()
})
