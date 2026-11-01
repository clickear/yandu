import { test, expect } from '@playwright/test'

test('API workspace tracks a machine translation result and opens its entry', async ({ page }) => {
  let jobCreated = false
  let retryRequested = false
  let failNextJobsPoll = false
  let translationValue: string | null = null
  let translationVersion = 0
  let translationStatus: string | null = null
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
        translated: translationValue ? 1 : 0, reviewed: 0,
        items: [{ entryId: '17', key: 'welcome.title', sourceText: '欢迎来到言渡',
          translation: translationValue, sourceRevision: 1,
          translationVersion: translationValue ? translationVersion : null,
          translationSourceRevision: translationValue ? 1 : null, status: translationStatus }],
      }
    } else if (request.method() === 'GET' && path === `/projects/${projectId}/machine-translation-jobs`) {
      if (failNextJobsPoll) {
        failNextJobsPoll = false
        await route.fulfill({ status: 503, contentType: 'application/problem+json',
          body: JSON.stringify({ title: '任务状态暂时无法读取。' }) })
        return
      }
      body = jobCreated ? [{ id: jobId, projectId, locale: 'en-US',
        status: retryRequested ? 'COMPLETED' : 'PARTIAL', totalCount: 1,
        completedCount: retryRequested ? 1 : 0, skippedCount: 0, failedCount: retryRequested ? 0 : 1,
        createdAt: now, updatedAt: now, error: null }] : []
    } else if (request.method() === 'GET' && path === `/projects/${projectId}/machine-translation-jobs/${jobId}/items`) {
      body = { page: 1, pageSize: 50, total: 1, items: [{ entryId: '17', namespaceId: '7',
        key: 'welcome.title', status: retryRequested ? 'COMPLETED' : 'FAILED', attempts: retryRequested ? 2 : 1,
        error: retryRequested ? null : '临时 provider 故障' }] }
    } else if (request.method() === 'GET'
      && path === `/projects/${projectId}/catalog/entries/17/translations/en-US/suggestions`) {
      body = { memory: [], terms: [] }
    } else if (request.method() === 'GET'
      && path === `/projects/${projectId}/catalog/entries/17/translations/en-US/history`) {
      body = []
    } else if (request.method() === 'PUT'
      && path === `/projects/${projectId}/catalog/entries/17/translations/en-US`) {
      const update = request.postDataJSON() as { expectedVersion: number; value: string }
      translationValue = update.value
      translationVersion = update.expectedVersion + 1
      translationStatus = 'DRAFT'
      body = { version: translationVersion }
    } else if (request.method() === 'POST'
      && path === `/projects/${projectId}/catalog/entries/17/translations/en-US/submit`) {
      translationVersion += 1
      translationStatus = 'IN_REVIEW'
      body = { version: translationVersion, status: translationStatus }
    } else if (request.method() === 'POST' && path === `/projects/${projectId}/machine-translation-jobs`) {
      jobCreated = true
      body = { id: jobId, projectId, locale: 'en-US', status: 'QUEUED', totalCount: 1,
        completedCount: 0, skippedCount: 0, failedCount: 0, createdAt: now, updatedAt: now }
      await route.fulfill({ status: 202, contentType: 'application/json', body: JSON.stringify(body) })
      return
    } else if (request.method() === 'POST' && path === `/projects/${projectId}/machine-translation-jobs/${jobId}/retry`) {
      retryRequested = true
      failNextJobsPoll = true
      translationValue = 'Welcome to YANDU'
      translationVersion = 1
      translationStatus = 'DRAFT'
      body = { id: jobId, projectId, locale: 'en-US', status: 'QUEUED', totalCount: 1,
        completedCount: 0, skippedCount: 0, failedCount: 0, createdAt: now, updatedAt: now }
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
  const machineDialog = page.getByRole('dialog')
  await expect(machineDialog).toContainText('部分失败')
  await expect(machineDialog).toContainText('临时 provider 故障')
  await machineDialog.getByRole('button', { name: '重试失败项' }).click()
  await expect(machineDialog.getByRole('alert')).toContainText('任务状态暂时无法读取。', { timeout: 5_000 })
  await expect(machineDialog.getByText('已回填草稿')).toBeVisible({ timeout: 10_000 })
  await expect(machineDialog.getByRole('alert')).toHaveCount(0)
  await page.getByRole('button', { name: '定位词条' }).click()
  await expect(page.locator('tbody tr').first()).toContainText('Welcome to YANDU')
  await page.getByRole('button', { name: '编辑译文 welcome.title' }).click()
  const translationEditor = page.getByRole('dialog')
  const translationInput = translationEditor.getByRole('textbox', { name: '译文 · English' })
  await translationInput.fill('Welcome to YANDU!')
  await page.reload()
  await expect(page.locator('tbody tr').first()).toContainText('Welcome to YANDU')
  await page.getByRole('button', { name: '编辑译文 welcome.title' }).click()
  await expect(page.getByRole('dialog').getByRole('textbox', { name: '译文 · English' }))
    .toHaveValue('Welcome to YANDU!')
  await page.getByRole('dialog').getByRole('button', { name: '保存并送审' }).click()
  await expect(page.getByRole('status')).toContainText('译文已保存并提交审核。')
  await expect(page.locator('tbody tr').first()).toContainText('审核中')
})
