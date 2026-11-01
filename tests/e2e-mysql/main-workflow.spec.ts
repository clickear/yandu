import { createSign } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { test, expect } from '@playwright/test'

const apiUrl = 'http://127.0.0.1:4174/api/v1'
const authority = 'http://127.0.0.1:4180'
const clientId = 'yandu-mysql-e2e'
const storageKey = `oidc.user:${authority}:${clientId}`

function issueAccessToken(subject: string) {
  const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString('base64url')
  const now = Math.floor(Date.now() / 1000)
  const unsigned = `${encode({ alg: 'RS256', typ: 'JWT', kid: 'yandu-mysql-e2e' })}.${encode({
    iss: authority,
    sub: subject,
    aud: 'yandu-api',
    iat: now,
    exp: now + 3600,
  })}`
  const signer = createSign('RSA-SHA256')
  signer.update(unsigned)
  const privateKey = readFileSync(process.env.YANDU_E2E_PRIVATE_KEY_FILE!, 'utf8')
  return `${unsigned}.${signer.sign(privateKey, 'base64url')}`
}

function oidcUser(accessToken: string, subject: string) {
  return {
    access_token: accessToken,
    token_type: 'Bearer',
    scope: 'openid profile',
    expires_at: Math.floor(Date.now() / 1000) + 3600,
    profile: { sub: subject },
  }
}

test('MySQL-backed workspace completes machine translation, review, and release', async ({ page, request }) => {
  const ownerToken = issueAccessToken('browser-e2e-owner')
  const reviewerToken = issueAccessToken('browser-e2e-reviewer')
  await page.addInitScript(({ key, user }) => sessionStorage.setItem(key, JSON.stringify(user)), {
    key: storageKey,
    user: oidcUser(ownerToken, 'browser-e2e-owner'),
  })

  await page.goto('/')
  await page.getByRole('button', { name: '新建项目' }).click()
  const projectDialog = page.getByRole('dialog')
  await projectDialog.getByLabel('项目名称').fill('MySQL E2E 翻译流程')
  await projectDialog.getByRole('button', { name: '创建项目' }).click()
  await expect(page.getByRole('heading', { name: 'MySQL E2E 翻译流程' })).toBeVisible()

  const projectsResponse = await request.get(`${apiUrl}/projects`, { headers: { Authorization: `Bearer ${ownerToken}` } })
  expect(projectsResponse.ok()).toBeTruthy()
  const projects = await projectsResponse.json() as { id: string }[]
  const projectId = projects.find((project) => project.id)?.id
  expect(projectId).toBeTruthy()

  const reviewerIdentity = await request.get(`${apiUrl}/me`, { headers: { Authorization: `Bearer ${reviewerToken}` } })
  expect(reviewerIdentity.ok()).toBeTruthy()
  const reviewerId = (await reviewerIdentity.json() as { id: string }).id
  const addReviewer = await request.post(`${apiUrl}/projects/${projectId}/members`, {
    headers: { Authorization: `Bearer ${ownerToken}` },
    data: { userId: reviewerId, role: 'REVIEWER' },
  })
  expect(addReviewer.status()).toBe(201)

  await page.getByRole('button', { name: '添加文案' }).click()
  const entryDialog = page.getByRole('dialog')
  await entryDialog.getByLabel('键名').fill('welcome.title')
  await entryDialog.getByLabel('源文案 · 简体中文').fill('欢迎来到言渡')
  await entryDialog.getByRole('button', { name: '添加文案' }).click()
  await expect(page.locator('tbody tr').first()).toContainText('welcome.title')

  await page.getByRole('button', { name: '机器翻译' }).click()
  const machineDialog = page.getByRole('dialog')
  await machineDialog.getByRole('button', { name: '翻译未译文案' }).click()
  await expect(machineDialog.getByRole('status')).toContainText('机器翻译任务已提交，共 1 条。')
  await expect(machineDialog.getByText('已回填草稿')).toBeVisible({ timeout: 30_000 })
  await machineDialog.getByRole('button', { name: '定位词条' }).click()
  await expect(page.locator('tbody tr').first()).toContainText('MT: 欢迎来到言渡')

  await page.getByRole('button', { name: '编辑译文 welcome.title' }).click()
  const translationDialog = page.getByRole('dialog')
  const translation = translationDialog.getByRole('textbox', { name: '译文 · English' })
  await translation.fill('Welcome to YANDU')
  await translationDialog.getByRole('button', { name: '保存并送审' }).click()
  await expect(page.getByRole('status')).toContainText('译文已保存并提交审核。')
  await expect(page.locator('tbody tr').first()).toContainText('审核中')

  await page.evaluate(({ key, user }) => sessionStorage.setItem(key, JSON.stringify(user)), {
    key: storageKey,
    user: oidcUser(reviewerToken, 'browser-e2e-reviewer'),
  })
  await page.reload()
  await expect(page.locator('tbody tr').first()).toContainText('审核中')
  await page.getByRole('button', { name: '编辑译文 welcome.title' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '审核通过' }).click()
  await expect(page.getByRole('status')).toContainText('审核已通过。')
  await expect(page.locator('tbody tr').first()).toContainText('已审核')

  await page.evaluate(({ key, user }) => sessionStorage.setItem(key, JSON.stringify(user)), {
    key: storageKey,
    user: oidcUser(ownerToken, 'browser-e2e-owner'),
  })
  await page.reload()
  await expect(page.locator('tbody tr').first()).toContainText('已审核')
  await page.getByRole('button', { name: '发布并导出 JSON' }).click()
  await expect(page.getByRole('status')).toContainText('发布任务完成，已下载 English 的 json 快照。', { timeout: 30_000 })
  const releaseDialog = page.getByRole('dialog')
  await expect(releaseDialog).toContainText('已完成')
  await expect(releaseDialog).toContainText('v1 · JSON')
  await expect(releaseDialog.getByRole('button', { name: '下载文件' })).toBeVisible()

  const releasesResponse = await request.get(`${apiUrl}/projects/${projectId}/releases?locale=en-US`, {
    headers: { Authorization: `Bearer ${ownerToken}` },
  })
  expect(releasesResponse.ok()).toBeTruthy()
  const releases = await releasesResponse.json() as { id: string }[]
  expect(releases).toHaveLength(1)
  const contentResponse = await request.get(`${apiUrl}/projects/${projectId}/releases/${releases[0].id}/content`, {
    headers: { Authorization: `Bearer ${ownerToken}` },
  })
  expect(contentResponse.ok()).toBeTruthy()
  await expect(contentResponse.json()).resolves.toEqual({ 'welcome.title': 'Welcome to YANDU' })
})
