import { test, expect } from '@playwright/test'

test('API workspace tracks a machine translation result and opens its entry', async ({ page }) => {
  let jobCreated = false
  const projectId = '42'
  const jobId = '900'
  const now = new Date().toISOString()

  await page.addInitScript(() => {
    sessionStorage.setItem('oidc.user:https://issuer.example.test:yandu-e2e', JSON.stringify({
      access_token: 'browser-test-token',
      token_type: 'Bearer',
      scope: 'openid profile',
      expires_at: Math.floor(Date.now() / 1000) + 3600,
      profile: { sub: 'api-workspace-user' },
    }))
  })

  await page.route('http://127.0.0.1:4174/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname.replace('/api/v1', '')
    let body: unknown

    if (request.method() === 'GET' && path === '/projects') {
      body = [{ id: projectId, name: 'API 演示项目', description: '', sourceLocale: 'zh-CN',
        targetLocales: ['en-US'], defaultNamespaceId: '7', status: 'ACTIVE' }]
    } else if (request.method() === 'GET' && path === '/me') {
      body = { id: '101' }
    } else if (request.method() === 'GET' && path === `/projects/${projectId}/members`) {
      body = [{ userId: '101', role: 'OWNER', createdAt: now }]
    } else if (request.method() === 'GET' && path === `/projects/${projectId}/namespaces`) {
      body = [{ id: '7', projectId, name: 'default', status: 'ACTIVE' }]
    } else if (request.method() === 'GET' && path === `/projects/${projectId}/languages/archived`) {
      body = []
    } else if (request.method() === 'GET' && path === `/projects/${projectId}/translations`) {
      body = {
        page: 1, pageSize: 50, total: 1, allTotal: 1,
        translated: jobCreated ? 1 : 0, reviewed: 0,
        items: [{ entryId: '17', key: 'welcome.title', sourceText: '欢迎来到言渡',
          translation: jobCreated ? 'Welcome to YANDU' : null, sourceRevision: 1,
          translationVersion: jobCreated ? 1 : null, translationSourceRevision: jobCreated ? 1 : null,
          status: jobCreated ? 'DRAFT' : null }],
      }
    } else if (request.method() === 'GET' && path === `/projects/${projectId}/machine-translation-jobs`) {
      body = jobCreated ? [{ id: jobId, projectId, locale: 'en-US', status: 'COMPLETED',
        totalCount: 1, completedCount: 1, skippedCount: 0, failedCount: 0,
        createdAt: now, updatedAt: now, error: null }] : []
    } else if (request.method() === 'GET' && path === `/projects/${projectId}/machine-translation-jobs/${jobId}/items`) {
      body = { page: 1, pageSize: 50, total: 1, items: [{ entryId: '17', namespaceId: '7',
        key: 'welcome.title', status: 'COMPLETED', attempts: 1, error: null }] }
    } else if (request.method() === 'POST' && path === `/projects/${projectId}/machine-translation-jobs`) {
      jobCreated = true
      body = { id: jobId, projectId, locale: 'en-US', status: 'QUEUED', totalCount: 1,
        completedCount: 0, skippedCount: 0, failedCount: 0, createdAt: now, updatedAt: now }
      await route.fulfill({ status: 202, contentType: 'application/json', body: JSON.stringify(body) })
      return
    } else {
      await route.fulfill({ status: 404, contentType: 'application/problem+json',
        body: JSON.stringify({ title: `Unexpected API request: ${request.method()} ${path}` }) })
      return
    }

    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })

  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'API 演示项目' })).toBeVisible()
  await expect(page.getByRole('button', { name: '机器翻译' })).toBeVisible()
  await page.getByRole('button', { name: '机器翻译' }).click()
  await page.getByRole('button', { name: '翻译未译文案' }).click()
  await expect(page.getByRole('status')).toContainText('机器翻译任务已提交，共 1 条。')
  await expect(page.getByRole('region', { name: '机器翻译词条结果' })
    .getByText('已回填草稿')).toBeVisible()
  await page.getByRole('button', { name: '定位词条' }).click()
  await expect(page.locator('tbody tr').first()).toContainText('Welcome to YANDU')
})
