import { UserManager, WebStorageStateStore } from 'oidc-client-ts'
import type { Entry, Project } from './model'

const env = import.meta.env
export const backendEnabled = Boolean(env.VITE_YANDU_API_URL)
const apiUrl = String(env.VITE_YANDU_API_URL ?? '').replace(/\/$/, '')
const authority = String(env.VITE_OIDC_AUTHORITY ?? '')
const clientId = String(env.VITE_OIDC_CLIENT_ID ?? '')
const callbackUrl = String(env.VITE_OIDC_REDIRECT_URI ?? `${window.location.origin}/auth/callback`)

export const backendConfigurationError = backendEnabled && (!authority || !clientId)
  ? '已配置服务端地址，但缺少 OIDC 登录地址或客户端 ID。请配置 VITE_OIDC_AUTHORITY 和 VITE_OIDC_CLIENT_ID。'
  : ''

const userManager = backendEnabled && !backendConfigurationError
  ? new UserManager({
      authority,
      client_id: clientId,
      redirect_uri: callbackUrl,
      post_logout_redirect_uri: window.location.origin,
      response_type: 'code',
      scope: String(env.VITE_OIDC_SCOPE ?? 'openid profile'),
      userStore: new WebStorageStateStore({ store: window.sessionStorage }),
    })
  : undefined

export async function initializeBackendSession() {
  if (!userManager) throw new Error(backendConfigurationError || '服务端认证尚未配置。')
  if (window.location.pathname === new URL(callbackUrl).pathname) {
    await userManager.signinRedirectCallback()
    window.history.replaceState({}, document.title, '/')
  }
  return userManager.getUser()
}

export async function signIn() {
  if (!userManager) throw new Error(backendConfigurationError || '服务端认证尚未配置。')
  await userManager.signinRedirect()
}

export async function signOut() {
  if (!userManager) return
  await userManager.signoutRedirect()
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const user = await userManager?.getUser()
  if (!user?.access_token || user.expired) throw new Error('登录已过期，请重新登录。')
  const response = await fetch(`${apiUrl}/api/v1${path}`, {
    ...init,
    headers: {
      Accept: 'application/json',
      Authorization: `Bearer ${user.access_token}`,
      ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
      ...init?.headers,
    },
  })
  if (!response.ok) {
    const problem = await response.json().catch(() => null) as {
      detail?: string; title?: string; issues?: { message?: string }[]
      expectedVersion?: number; actualVersion?: number
      expectedRevision?: number; actualRevision?: number
    } | null
    const qualityMessages = [...new Set((problem?.issues ?? [])
      .map((issue) => issue.message?.trim()).filter((message): message is string => Boolean(message)))]
    let message = problem?.detail ?? problem?.title ?? `服务端请求失败（${response.status}）。`
    if (qualityMessages.length) {
      message = `质量检查未通过：${qualityMessages.join('；')}`
    } else if (response.status === 409 && problem
      && Number.isInteger(problem?.expectedVersion) && Number.isInteger(problem?.actualVersion)) {
      message = `译文已被其他操作更新（本次使用版本 ${problem.expectedVersion}，当前版本 ${problem.actualVersion}）。`
        + '当前编辑草稿仍保留在本次浏览器会话中；请重新读取最新译文、合并修改后再保存。'
    } else if (response.status === 409 && problem
      && Number.isInteger(problem?.expectedRevision) && Number.isInteger(problem?.actualRevision)) {
      message = `源文已被其他操作更新（本次使用版本 ${problem.expectedRevision}，当前版本 ${problem.actualRevision}）。`
        + '请读取最新源文后确认修改内容，再重新保存。'
    }
    throw new Error(message)
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

type ApiProject = {
  id: string
  name: string
  description?: string
  sourceLocale: string
  targetLocales: string[]
  defaultNamespaceId: string
  status?: 'ACTIVE' | 'ARCHIVED'
}

export function mapProject(project: ApiProject): Project {
  return {
    id: project.id,
    name: project.name,
    description: project.description ?? '',
    sourceLanguage: project.sourceLocale,
    targetLanguages: project.targetLocales,
    entries: [],
    defaultNamespaceId: project.defaultNamespaceId,
    status: project.status ?? 'ACTIVE',
  }
}

export function listProjects() {
  return request<ApiProject[]>('/projects')
}

export async function getCurrentActorId() {
  const actor = await request<{ id: string }>('/me')
  return actor.id
}

export async function getProjectRole(projectId: string, actorId: string) {
  const members = await request<{ userId: string; role: string }[]>(`/projects/${projectId}/members`)
  return members.find((member) => member.userId === actorId)?.role ?? null
}

export function listRemoteMembers(projectId: string) {
  return request<{ userId: string; role: string; createdAt: string }[]>(`/projects/${projectId}/members`)
}

export function addRemoteMember(projectId: string, userId: string, role: string) {
  return request<void>(`/projects/${projectId}/members`, {
    method: 'POST', body: JSON.stringify({ userId, role }),
  })
}

export function createRemoteProject(name: string, sourceLocale: string, description = '') {
  return request<ApiProject>('/projects', {
    method: 'POST',
    body: JSON.stringify({ name, sourceLocale, description }),
  })
}

export function archiveRemoteProject(projectId: string) {
  return request<void>(`/projects/${projectId}/archive`, { method: 'POST' })
}

export function restoreRemoteProject(projectId: string) {
  return request<void>(`/projects/${projectId}/restore`, { method: 'POST' })
}

export function transferRemoteOwnership(projectId: string, newOwnerId: string) {
  return request<void>(`/projects/${projectId}/ownership-transfer`, {
    method: 'POST', body: JSON.stringify({ newOwnerId }),
  })
}

export function changeRemoteMemberRole(projectId: string, userId: string, role: string) {
  return request<void>(`/projects/${projectId}/members/${userId}`, {
    method: 'PUT', body: JSON.stringify({ role }),
  })
}

export function removeRemoteMember(projectId: string, userId: string) {
  return request<void>(`/projects/${projectId}/members/${userId}`, { method: 'DELETE' })
}

export function leaveRemoteProject(projectId: string) {
  return request<void>(`/projects/${projectId}/leave`, { method: 'POST' })
}

export function addRemoteLocale(projectId: string, locale: string) {
  return request<ApiProject>(`/projects/${projectId}/languages`, {
    method: 'POST',
    body: JSON.stringify({ locale }),
  })
}

export type RemoteNamespace = { id: string; projectId: string; name: string; status: 'ACTIVE' | 'ARCHIVED' }
export function listRemoteNamespaces(projectId: string, includeArchived = false) {
  return request<RemoteNamespace[]>(`/projects/${projectId}/namespaces?includeArchived=${includeArchived}`)
}
export function createRemoteNamespace(projectId: string, name: string) {
  return request<RemoteNamespace>(`/projects/${projectId}/namespaces`, {
    method: 'POST', body: JSON.stringify({ name }),
  })
}
export function renameRemoteNamespace(projectId: string, namespaceId: string, name: string) {
  return request<RemoteNamespace>(`/projects/${projectId}/namespaces/${namespaceId}`, {
    method: 'PUT', body: JSON.stringify({ name }),
  })
}
export function archiveRemoteNamespace(projectId: string, namespaceId: string) {
  return request<void>(`/projects/${projectId}/namespaces/${namespaceId}`, { method: 'DELETE' })
}
export function restoreRemoteNamespace(projectId: string, namespaceId: string) {
  return request<RemoteNamespace>(`/projects/${projectId}/namespaces/${namespaceId}/restore`, { method: 'POST' })
}

export function archiveRemoteLocale(projectId: string, locale: string) {
  return request<string[]>(`/projects/${projectId}/languages/${encodeURIComponent(locale)}`, {
    method: 'DELETE',
  })
}

export type MachineTranslationJob = {
  id: string
  projectId: string
  locale: string
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'PARTIAL' | 'FAILED'
  totalCount: number
  completedCount: number
  skippedCount: number
  failedCount: number
  createdAt: string
  updatedAt: string
  error?: string | null
}

export type MachineTranslationJobItems = {
  page: number
  pageSize: number
  total: number
  items: { entryId: string; namespaceId: string; key: string; status: string; attempts: number; error?: string | null }[]
}

export function createMachineTranslationJob(projectId: string, locale: string, idempotencyKey: string) {
  return request<MachineTranslationJob>(`/projects/${projectId}/machine-translation-jobs`, {
    method: 'POST', headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({ locale }),
  })
}

export function listMachineTranslationJobs(projectId: string) {
  return request<MachineTranslationJob[]>(`/projects/${projectId}/machine-translation-jobs`)
}

export function getMachineTranslationJobItems(projectId: string, jobId: string, page = 1, pageSize = 50) {
  return request<MachineTranslationJobItems>(
    `/projects/${projectId}/machine-translation-jobs/${jobId}/items?page=${page}&pageSize=${pageSize}`,
  )
}

export function retryMachineTranslationJob(projectId: string, jobId: string) {
  return request<MachineTranslationJob>(
    `/projects/${projectId}/machine-translation-jobs/${jobId}/retry`, { method: 'POST' },
  )
}

export function addRemoteEntry(project: Project, key: string, sourceText: string, namespaceId = project.defaultNamespaceId) {
  return request(`/projects/${project.id}/catalog/entries`, {
    method: 'POST',
    body: JSON.stringify({ namespaceId, key, sourceText }),
  })
}

export function updateRemoteSource(projectId: string, entryId: string, expectedSourceRevision: number,
                                   sourceText: string) {
  return request(`/projects/${projectId}/catalog/entries/${entryId}/source`, {
    method: 'PUT', body: JSON.stringify({ expectedSourceRevision, sourceText }),
  })
}

export async function reorganizeRemoteEntry(projectId: string, entryId: string,
                                            namespaceId: string, key: string) {
  const current = await request<{ version: number }>(`/projects/${projectId}/catalog/entries/${entryId}`)
  return request(`/projects/${projectId}/catalog/entries/${entryId}/organization`, {
    method: 'PUT', body: JSON.stringify({ expectedVersion: current.version, namespaceId, key }),
  })
}

export function readRemoteSourceHistory(projectId: string, entryId: string) {
  return request<{ id: string; sourceRevision: number; sourceText: string; changedBy: string; createdAt: string }[]>(
    `/projects/${projectId}/catalog/entries/${entryId}/history`,
  )
}

export function readRemoteTranslationHistory(projectId: string, entryId: string, locale: string) {
  return request<{ id: string; version: number; value: string; sourceRevision: number;
    editedBy: string; createdAt: string }[]>(
    `/projects/${projectId}/catalog/entries/${entryId}/translations/${encodeURIComponent(locale)}/history`,
  )
}

export function archiveRemoteEntry(projectId: string, entryId: string) {
  return request<void>(`/projects/${projectId}/catalog/entries/${entryId}`, { method: 'DELETE' })
}

export function restoreRemoteEntry(projectId: string, entryId: string) {
  return request<void>(`/projects/${projectId}/catalog/entries/${entryId}/restore`, { method: 'POST' })
}

export async function listRemoteArchivedEntries(project: Project, namespaceId: string,
                                                keyword: string, page: number) {
  const params = new URLSearchParams({ namespaceId, page: String(page), pageSize: '50' })
  if (keyword) params.set('keyword', keyword)
  const result = await request<{ page: number; pageSize: number; total: number; items: {
    id: string; key: string; sourceText: string; sourceRevision: number
  }[] }>(`/projects/${project.id}/catalog/archived-entries?${params}`)
  return { ...result, entries: result.items.map((item) => ({
    id: item.id, key: item.key, source: item.sourceText, sourceRevision: item.sourceRevision,
    workflowStatuses: { archived: 'ARCHIVED' }, translations: {},
  } satisfies Entry)) }
}

type ApiEntry = {
  entryId: string
  key: string
  sourceText: string
  translation?: string | null
  sourceRevision: number
  translationVersion: number | null
  translationSourceRevision: number | null
  status: string | null
}

export async function loadTranslationPage(project: Project, locale: string, keyword: string,
                                         status: string, page: number, namespaceId = project.defaultNamespaceId) {
  const params = new URLSearchParams({
    namespaceId: namespaceId ?? project.defaultNamespaceId ?? project.id,
    locale,
    page: String(page),
    pageSize: '50',
    status,
  })
  if (keyword) params.set('keyword', keyword)
  const result = await request<{ page: number; pageSize: number; total: number; allTotal: number; translated: number;
    reviewed: number; items: ApiEntry[] }>(
    `/projects/${project.id}/translations?${params}`,
  )
  const entries: Entry[] = result.items.map((item) => ({
    id: item.entryId,
    key: item.key,
    source: item.sourceText,
    sourceRevision: item.sourceRevision,
    versions: { [locale]: item.translationVersion },
    workflowStatuses: { [locale]: item.status },
    translations: item.translation === null || item.translation === undefined ? {} : {
      [locale]: { value: item.translation, status: item.status === 'APPROVED' ? 'reviewed' : 'draft' },
    },
  }))
  return { ...result, entries }
}

export function saveRemoteTranslation(projectId: string, entry: Entry, locale: string, value: string) {
  return request<{ version: number }>(`/projects/${projectId}/catalog/entries/${entry.id}/translations/${encodeURIComponent(locale)}`, {
    method: 'PUT',
    body: JSON.stringify({ expectedVersion: entry.versions?.[locale] ?? 0,
      basedOnSourceRevision: entry.sourceRevision ?? 1, value }),
  })
}

export function submitRemoteTranslation(projectId: string, entryId: string, locale: string, version: number) {
  return request(`/projects/${projectId}/catalog/entries/${entryId}/translations/${encodeURIComponent(locale)}/submit`, {
    method: 'POST', body: JSON.stringify({ expectedVersion: version }),
  })
}

export function getRemoteTranslation(projectId: string, entryId: string, locale: string) {
  return request<{ latestReviewDecision: 'APPROVE' | 'REQUEST_CHANGES' | null;
    latestReviewReason: string | null; latestReviewAt: string | null }>(
    `/projects/${projectId}/catalog/entries/${entryId}/translations/${encodeURIComponent(locale)}`,
  )
}

export function reviewRemoteTranslation(projectId: string, entryId: string, locale: string,
                                        version: number, decision: 'APPROVE' | 'REQUEST_CHANGES', reason = '') {
  return request(`/projects/${projectId}/catalog/entries/${entryId}/translations/${encodeURIComponent(locale)}/review`, {
    method: 'POST', body: JSON.stringify({ expectedVersion: version, decision, reason }),
  })
}

export function approveRemoteTranslation(projectId: string, entryId: string, locale: string, version: number) {
  return reviewRemoteTranslation(projectId, entryId, locale, version, 'APPROVE')
}

export function previewRemoteImport(projectId: string, project: Project, locale: string, json: string,
                                    namespaceId = project.defaultNamespaceId) {
  return request<{ id: string; previewHash: string;
    preview: { conflicts: number; added: number; updated: number; unchanged: number;
      items: { key: string; action: string; currentValue: string | null; incomingValue: string; message: string | null }[] } }>(
    `/projects/${projectId}/imports/previews`, {
      method: 'POST',
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ namespaceId, locale, json }),
    },
  )
}

export function applyRemoteImport(projectId: string, jobId: string, previewHash: string) {
  return request<{ status: string }>(`/projects/${projectId}/imports/${jobId}/apply`, {
    method: 'POST', body: JSON.stringify({ previewHash }),
  })
}

export type RemoteImportJob = {
  id: string; locale: string; status: string; previewHash: string
  added: number; updated: number; unchanged: number; conflicts: number
  error: string | null; createdAt: string; updatedAt: string
}

export function listRemoteImportJobs(projectId: string) {
  return request<RemoteImportJob[]>(`/projects/${projectId}/imports`)
}

export function retryRemoteImport(projectId: string, jobId: string, previewHash: string) {
  return request<unknown>(`/projects/${projectId}/imports/${jobId}/retry`, {
    method: 'POST', body: JSON.stringify({ previewHash }),
  })
}

export type ProjectMigration = {
  id: string; status: string; targetName: string; previewHash: string
  preview: { targetName: string; entryCount: number; nameCollision: boolean }
  projectId: string | null; completedEntries: number; entryIdMap: Record<string, string>; error: string | null
}

export function previewProjectMigration(packageJson: string, targetName: string) {
  return request<ProjectMigration>('/project-migrations/previews', {
    method: 'POST', body: JSON.stringify({ packageJson, targetName }),
  })
}

export function applyProjectMigration(migrationId: string, previewHash: string) {
  return request<ProjectMigration>(`/project-migrations/${migrationId}/apply`, {
    method: 'POST', body: JSON.stringify({ previewHash }),
  })
}

export function getProjectMigration(migrationId: string) {
  return request<ProjectMigration>(`/project-migrations/${migrationId}`)
}

export type RemoteRelease = {
  id: string; projectId: string; locale: string; format: 'json-flat-v1' | 'java-properties-v1'
  version: number; rollbackOfReleaseId: string | null; checksumSha256: string; entryCount: number
  publishedBy: string; publishedAt: string
}

export type RemoteReleaseJob = {
  id: string; projectId: string; locale: string; format: 'json-flat-v1' | 'java-properties-v1'
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  releaseId: string | null; error: string | null; createdAt: string; updatedAt: string
}

export function requestRemoteRelease(projectId: string, locale: string,
                                     format: 'json-flat-v1' | 'java-properties-v1', idempotencyKey: string) {
  return request<RemoteReleaseJob>(`/projects/${projectId}/releases`, {
    method: 'POST', headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({ locale, format }),
  })
}

export function getRemoteReleaseJob(projectId: string, jobId: string) {
  return request<RemoteReleaseJob>(`/projects/${projectId}/releases/jobs/${jobId}`)
}

export function listRemoteReleaseJobs(projectId: string, locale: string) {
  return request<RemoteReleaseJob[]>(`/projects/${projectId}/releases/jobs?locale=${encodeURIComponent(locale)}`)
}

export function retryRemoteReleaseJob(projectId: string, jobId: string) {
  return request<RemoteReleaseJob>(`/projects/${projectId}/releases/jobs/${jobId}/retry`, { method: 'POST' })
}

export function listRemoteReleases(projectId: string, locale: string) {
  return request<RemoteRelease[]>(`/projects/${projectId}/releases?locale=${encodeURIComponent(locale)}`)
}

export function rollbackRemoteRelease(projectId: string, releaseId: string) {
  return request<RemoteRelease>(`/projects/${projectId}/releases/${releaseId}/rollback`, { method: 'POST' })
}

async function fetchRemoteReleaseContent(projectId: string, releaseId: string, format: string) {
  const user = await userManager?.getUser()
  const response = await fetch(`${apiUrl}/api/v1/projects/${projectId}/releases/${releaseId}/content`, {
    headers: { Authorization: `Bearer ${user?.access_token}` },
  })
  if (!response.ok) throw new Error(`发布文件下载失败（${response.status}）。`)
  return { content: await response.text(), extension: format === 'java-properties-v1' ? 'properties' : 'json' }
}

export function downloadRemoteReleaseSnapshot(projectId: string, release: RemoteRelease) {
  return fetchRemoteReleaseContent(projectId, release.id, release.format)
}

export async function downloadRemoteReleaseJobResult(projectId: string, job: RemoteReleaseJob) {
  if (job.status !== 'COMPLETED' || !job.releaseId) throw new Error('发布任务尚未完成。')
  const release = await request<RemoteRelease>(`/projects/${projectId}/releases/${job.releaseId}`)
  return { release, ...(await fetchRemoteReleaseContent(projectId, release.id, release.format)) }
}

export function loadMemorySuggestions(projectId: string, entryId: string, locale: string) {
  return request<{ memory: { entryId: string; key: string; sourceText: string; translation: string;
    locale: string; translationVersion: number }[];
    terms: { id: string; sourceTerm: string; targetTerm: string; note: string | null }[] }>(
    `/projects/${projectId}/catalog/entries/${entryId}/translations/${encodeURIComponent(locale)}/suggestions`,
  )
}

type RemoteGlossaryTerm = {
  id: string; projectId: string; sourceLocale: string; targetLocale: string;
  sourceTerm: string; targetTerm: string; note: string | null; createdAt: string
}

export function listRemoteGlossary(projectId: string, sourceLocale: string, targetLocale: string) {
  const query = new URLSearchParams({ sourceLocale, targetLocale, page: '1', pageSize: '100' })
  return request<{ page: number; pageSize: number; total: number; items: RemoteGlossaryTerm[] }>(
    `/projects/${projectId}/glossary?${query.toString()}`,
  )
}

export function addRemoteGlossaryTerm(projectId: string, sourceLocale: string, targetLocale: string,
                                      sourceTerm: string, targetTerm: string, note: string) {
  return request<RemoteGlossaryTerm>(`/projects/${projectId}/glossary`, {
    method: 'POST', body: JSON.stringify({ sourceLocale, targetLocale, sourceTerm, targetTerm, note }),
  })
}

export function archiveRemoteGlossaryTerm(projectId: string, termId: string) {
  return request<void>(`/projects/${projectId}/glossary/${termId}`, { method: 'DELETE' })
}
