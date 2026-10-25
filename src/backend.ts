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
    const detail = await response.json().catch(() => null) as { detail?: string; title?: string } | null
    throw new Error(detail?.detail ?? detail?.title ?? `服务端请求失败（${response.status}）。`)
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

type ApiProject = {
  id: string
  name: string
  sourceLocale: string
  targetLocales: string[]
  defaultNamespaceId: string
}

export function mapProject(project: ApiProject): Project {
  return {
    id: project.id,
    name: project.name,
    description: '',
    sourceLanguage: project.sourceLocale,
    targetLanguages: project.targetLocales,
    entries: [],
    defaultNamespaceId: project.defaultNamespaceId,
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

export function createRemoteProject(name: string, sourceLocale: string) {
  return request<ApiProject>('/projects', {
    method: 'POST',
    body: JSON.stringify({ name, sourceLocale }),
  })
}

export function addRemoteLocale(projectId: string, locale: string) {
  return request<ApiProject>(`/projects/${projectId}/languages`, {
    method: 'POST',
    body: JSON.stringify({ locale }),
  })
}

export function addRemoteEntry(project: Project, key: string, sourceText: string) {
  return request(`/projects/${project.id}/catalog/entries`, {
    method: 'POST',
    body: JSON.stringify({ namespaceId: project.defaultNamespaceId, key, sourceText }),
  })
}

export function updateRemoteSource(projectId: string, entryId: string, expectedSourceRevision: number,
                                   sourceText: string) {
  return request(`/projects/${projectId}/catalog/entries/${entryId}/source`, {
    method: 'PUT', body: JSON.stringify({ expectedSourceRevision, sourceText }),
  })
}

export function readRemoteSourceHistory(projectId: string, entryId: string) {
  return request<{ id: string; sourceRevision: number; sourceText: string; changedBy: string; createdAt: string }[]>(
    `/projects/${projectId}/catalog/entries/${entryId}/history`,
  )
}

export function archiveRemoteEntry(projectId: string, entryId: string) {
  return request<void>(`/projects/${projectId}/catalog/entries/${entryId}`, { method: 'DELETE' })
}

type ApiEntry = {
  entryId: string
  key: string
  sourceText: string
  translation: string | null
  sourceRevision: number
  translationVersion: number | null
  translationSourceRevision: number | null
  status: string | null
}

export async function loadTranslationPage(project: Project, locale: string, keyword: string,
                                         status: string, page: number) {
  const params = new URLSearchParams({
    namespaceId: project.defaultNamespaceId ?? project.id,
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
    translations: item.translation === null ? {} : {
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

export function approveRemoteTranslation(projectId: string, entryId: string, locale: string, version: number) {
  return request(`/projects/${projectId}/catalog/entries/${entryId}/translations/${encodeURIComponent(locale)}/review`, {
    method: 'POST', body: JSON.stringify({ expectedVersion: version, decision: 'APPROVE' }),
  })
}

export function previewRemoteImport(projectId: string, project: Project, locale: string, json: string) {
  return request<{ id: string; previewHash: string;
    preview: { conflicts: number; added: number; updated: number; unchanged: number;
      items: { key: string; action: string; currentValue: string | null; incomingValue: string; message: string | null }[] } }>(
    `/projects/${projectId}/imports/previews`, {
      method: 'POST',
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ namespaceId: project.defaultNamespaceId, locale, json }),
    },
  )
}

export function applyRemoteImport(projectId: string, jobId: string, previewHash: string) {
  return request<{ status: string }>(`/projects/${projectId}/imports/${jobId}/apply`, {
    method: 'POST', body: JSON.stringify({ previewHash }),
  })
}

export async function waitForRemoteImport(projectId: string, jobId: string) {
  for (let attempt = 0; attempt < 60; attempt += 1) {
    await new Promise((resolve) => window.setTimeout(resolve, 1000))
    const job = await request<{ status: string; error: string | null }>(`/projects/${projectId}/imports/${jobId}`)
    if (job.status === 'COMPLETED' || job.status === 'FAILED') return job
  }
  return { status: 'RUNNING', error: null }
}

export async function downloadRemoteRelease(projectId: string, locale: string, format: 'json-flat-v1' | 'java-properties-v1') {
  const release = await request<{ id: string }>(`/projects/${projectId}/releases`, {
    method: 'POST', body: JSON.stringify({ locale, format }),
  })
  const user = await userManager?.getUser()
  const response = await fetch(`${apiUrl}/api/v1/projects/${projectId}/releases/${release.id}/content`, {
    headers: { Authorization: `Bearer ${user?.access_token}` },
  })
  if (!response.ok) throw new Error(`发布文件下载失败（${response.status}）。`)
  return { content: await response.text(), extension: format === 'java-properties-v1' ? 'properties' : 'json' }
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
