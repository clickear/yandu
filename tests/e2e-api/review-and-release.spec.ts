import { test, expect } from '@playwright/test'

test('API workspace approves a reviewed translation and publishes its snapshot', async ({ page }) => {
  const projectId = '42'
  const entryId = '17'
  const releaseId = '701'
  const releaseJobId = '702'
  const now = new Date().toISOString()
  let approved = false
  let releaseRequested = false

  await page.addInitScript(() => {
    sessionStorage.setItem('oidc.user:https://issuer.example.test:yandu-e2e', JSON.stringify({
      access_token: 'browser-test-token',
      token_type: 'Bearer',
      scope: 'openid profile',
      expires_at: Math.floor(Date.now() / 1000) + 3600,
      profile: { sub: 'api-workspace-reviewer' },
    }))
  })

  await page.route('http://127.0.0.1:4174/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname.replace('/api/v1', '')
    let body: unknown

    if (request.method() === 'GET' && path === '/projects') {
      body = [{ id: projectId, name: '审核与发布项目', description: '', sourceLocale: 'zh-CN',
        targetLocales: ['en-US'], defaultNamespaceId: '7', status: 'ACTIVE' }]
    } else if (request.method() === 'GET' && path === '/me') {
      body = { id: '102' }
    } else if (request.method() === 'GET' && path === `/projects/${projectId}/members`) {
      body = [{ userId: '102', role: 'REVIEWER', createdAt: now }]
    } else if (request.method() === 'GET' && path === `/projects/${projectId}/namespaces`) {
      body = [{ id: '7', projectId, name: 'default', status: 'ACTIVE' }]
    } else if (request.method() === 'GET' && path === `/projects/${projectId}/languages/archived`) {
      body = []
    } else if (request.method() === 'GET' && path === `/projects/${projectId}/translations`) {
      body = { page: 1, pageSize: 50, total: 1, allTotal: 1, translated: 1, reviewed: approved ? 1 : 0,
        items: [{ entryId, key: 'welcome.title', sourceText: '欢迎来到言渡', translation: 'Welcome to YANDU',
          sourceRevision: 1, translationVersion: approved ? 4 : 3, translationSourceRevision: 1,
          status: approved ? 'APPROVED' : 'IN_REVIEW' }] }
    } else if (request.method() === 'GET'
      && path === `/projects/${projectId}/catalog/entries/${entryId}/translations/en-US`) {
      body = { latestReviewDecision: approved ? 'APPROVE' : null, latestReviewReason: null, latestReviewAt: null }
    } else if (request.method() === 'POST'
      && path === `/projects/${projectId}/catalog/entries/${entryId}/translations/en-US/review`) {
      const decision = request.postDataJSON() as { decision: string; expectedVersion: number }
      expect(decision).toMatchObject({ decision: 'APPROVE', expectedVersion: 3 })
      approved = true
      body = { version: 4, status: 'APPROVED' }
    } else if (request.method() === 'POST' && path === `/projects/${projectId}/releases`) {
      releaseRequested = true
      body = { id: releaseJobId, projectId, locale: 'en-US', format: 'json-flat-v1', status: 'COMPLETED',
        releaseId, error: null, createdAt: now, updatedAt: now }
    } else if (request.method() === 'GET' && path === `/projects/${projectId}/releases/jobs`) {
      body = releaseRequested ? [{ id: releaseJobId, projectId, locale: 'en-US', format: 'json-flat-v1',
        status: 'COMPLETED', releaseId, error: null, createdAt: now, updatedAt: now }] : []
    } else if (request.method() === 'GET' && path === `/projects/${projectId}/releases`) {
      body = releaseRequested ? [{ id: releaseId, projectId, locale: 'en-US', format: 'json-flat-v1',
        version: 1, rollbackOfReleaseId: null, checksumSha256: 'a'.repeat(64), entryCount: 1,
        publishedBy: '102', publishedAt: now }] : []
    } else if (request.method() === 'GET' && path === `/projects/${projectId}/releases/${releaseId}`) {
      body = { id: releaseId, projectId, locale: 'en-US', format: 'json-flat-v1', version: 1,
        rollbackOfReleaseId: null, checksumSha256: 'a'.repeat(64), entryCount: 1,
        publishedBy: '102', publishedAt: now }
    } else if (request.method() === 'GET' && path === `/projects/${projectId}/releases/${releaseId}/content`) {
      await route.fulfill({ status: 200, contentType: 'application/json', body: '{"welcome.title":"Welcome to YANDU"}' })
      return
    } else {
      await route.fulfill({ status: 404, contentType: 'application/problem+json',
        body: JSON.stringify({ title: `Unexpected API request: ${request.method()} ${path}` }) })
      return
    }

    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })

  await page.goto('/')
  await expect(page.getByRole('heading', { name: '审核与发布项目' })).toBeVisible()
  await expect(page.locator('tbody tr').first()).toContainText('审核中')
  await page.getByRole('button', { name: '编辑译文 welcome.title' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '审核通过' }).click()
  await expect(page.getByRole('status')).toContainText('审核已通过。')
  await expect(page.locator('tbody tr').first()).toContainText('已审核')

  await page.getByRole('button', { name: '发布并导出 JSON' }).click()
  await expect(page.getByRole('status')).toContainText('发布任务完成，已下载 English 的 json 快照。')
  await expect(page.getByRole('dialog')).toContainText('已完成')
  await expect(page.getByRole('dialog')).toContainText('v1 · JSON')
  await expect(page.getByRole('dialog').getByRole('button', { name: '下载文件' })).toBeVisible()
})
