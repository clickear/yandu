import { useEffect, useRef, useState } from 'react'
import type { FormEvent, ReactNode } from 'react'
import {
  ArrowDownToLine,
  ArrowUpFromLine,
  ArrowUpRight,
  Archive,
  Check,
  CheckCheck,
  ChevronDown,
  ChevronRight,
  CircleHelp,
  FileText,
  FolderKanban,
  Globe2,
  Languages,
  Plus,
  Search,
  Sparkles,
  X,
} from 'lucide-react'
import {
  addEntry,
  addLanguage,
  createProject,
  exportMessages,
  exportProperties,
  importMessages,
  LANGUAGES,
  languageName,
  languageShort,
  MAX_IMPORT_BYTES,
  parseWorkspace,
  progress,
  saveTranslation,
  seedWorkspace,
  statusOf,
  STORAGE_KEY,
  updateSource,
} from './model'
import type { Entry, Project, Workspace } from './model'
import {
  addRemoteEntry,
  addRemoteGlossaryTerm,
  addRemoteLocale,
  addRemoteMember,
  applyRemoteImport,
  archiveRemoteEntry,
  archiveRemoteGlossaryTerm,
  approveRemoteTranslation,
  reviewRemoteTranslation,
  getRemoteTranslation,
  backendConfigurationError,
  backendEnabled,
  createRemoteProject,
  requestRemoteRelease,
  getRemoteReleaseJob,
  retryRemoteReleaseJob,
  downloadRemoteReleaseJobResult,
  downloadRemoteReleaseSnapshot,
  listRemoteReleases,
  rollbackRemoteRelease,
  getCurrentActorId,
  getProjectRole,
  initializeBackendSession,
  loadMemorySuggestions,
  listProjects,
  listRemoteGlossary,
  listRemoteMembers,
  loadTranslationPage,
  mapProject,
  previewRemoteImport,
  readRemoteSourceHistory,
  saveRemoteTranslation,
  signIn,
  signOut,
  submitRemoteTranslation,
  updateRemoteSource,
  waitForRemoteImport,
  previewProjectMigration,
  applyProjectMigration,
  getProjectMigration,
  archiveRemoteProject,
  restoreRemoteProject,
  transferRemoteOwnership,
  changeRemoteMemberRole,
  removeRemoteMember,
  leaveRemoteProject,
  archiveRemoteLocale,
  archiveRemoteNamespace,
  createRemoteNamespace,
  listRemoteNamespaces,
  restoreRemoteNamespace,
  renameRemoteNamespace,
  reorganizeRemoteEntry,
  createMachineTranslationJob,
  listMachineTranslationJobs,
  getMachineTranslationJobItems,
  retryMachineTranslationJob,
} from './backend'
import type { MachineTranslationJob, MachineTranslationJobItems, ProjectMigration, RemoteNamespace, RemoteRelease, RemoteReleaseJob } from './backend'

function readInitial() {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    return { workspace: saved ? parseWorkspace(saved) : seedWorkspace(), warning: '' }
  } catch {
    return {
      workspace: seedWorkspace(),
      warning:
        '无法读取本地数据，已显示示例。原数据不会被覆盖；可导出当前文案，或确认重置本地存储。',
    }
  }
}

const workflowStatusLabels: Record<string, string> = {
  reviewed: '已审核', 'in-review': '审核中', 'changes-requested': '已退回',
  'needs-review': '需复核', draft: '草稿', untranslated: '未翻译',
}

function readLocalProjectsForMigration(): Project[] {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    return saved ? parseWorkspace(saved).projects : []
  } catch { return [] }
}

function Modal({
  title,
  children,
  onClose,
  wide = false,
}: {
  title: string
  children: ReactNode
  onClose: () => void
  wide?: boolean
}) {
  const ref = useRef<HTMLDialogElement>(null)
  useEffect(() => {
    const dialog = ref.current!
    dialog.showModal()
    return () => dialog.close()
  }, [])
  return (
    <dialog
      ref={ref}
      className={`modal ${wide ? 'modal-wide' : ''}`}
      aria-label={title}
      onCancel={(e) => {
        e.preventDefault()
        onClose()
      }}
    >
      <div className="modal-heading">
        <h2>{title}</h2>
        <button type="button" className="icon-button" aria-label="关闭弹窗" onClick={onClose}>
          <X size={20} />
        </button>
      </div>
      {children}
    </dialog>
  )
}

function TranslationEditor({
  entry,
  project,
  locale,
  onSave,
  onClose,
  backendMode = false,
  canReview = false,
  onApprove,
  onRequestChanges,
}: {
  entry: Entry
  project: Project
  locale: string
  onSave: (value: string, reviewed: boolean) => void | Promise<void>
  onClose: () => void
  backendMode?: boolean
  canReview?: boolean
  onApprove?: () => void | Promise<void>
  onRequestChanges?: (reason: string) => void | Promise<void>
}) {
  const initial = entry.translations[locale]
  const draftKey = `yandu.translation-draft.v1:${project.id}:${entry.id}:${locale}`
  const [value, setValue] = useState(() => {
    if (backendMode) {
      try {
        const savedDraft = sessionStorage.getItem(draftKey)
        if (savedDraft !== null) return savedDraft
      } catch {
        // Keep the editor usable if browser session storage is unavailable.
      }
    }
    return initial?.value ?? ''
  })
  const [reviewed, setReviewed] = useState(initial?.status === 'reviewed')
  const [suggestions, setSuggestions] = useState<Awaited<ReturnType<typeof loadMemorySuggestions>>>(
    { memory: [], terms: [] },
  )
  const [reviewFeedback, setReviewFeedback] = useState<{ reason: string | null; at: string | null } | null>(null)
  const [reviewFeedbackError, setReviewFeedbackError] = useState(false)
  useEffect(() => {
    if (!backendMode) return
    let active = true
    void loadMemorySuggestions(project.id, entry.id, locale)
      .then((matches) => { if (active) setSuggestions(matches) })
      .catch(() => { if (active) setSuggestions({ memory: [], terms: [] }) })
    return () => { active = false }
  }, [backendMode, project.id, entry.id, locale])
  useEffect(() => {
    if (!backendMode || entry.workflowStatuses?.[locale] !== 'CHANGES_REQUESTED') return
    let active = true
    void getRemoteTranslation(project.id, entry.id, locale)
      .then((translation) => {
        if (active) setReviewFeedback({ reason: translation.latestReviewReason, at: translation.latestReviewAt })
      })
      .catch(() => { if (active) setReviewFeedbackError(true) })
    return () => { active = false }
  }, [backendMode, entry.workflowStatuses, project.id, entry.id, locale])
  const dirty = value !== (initial?.value ?? '') || reviewed !== (initial?.status === 'reviewed')
  const save = (submitForReview: boolean) => {
    void Promise.resolve(onSave(value, submitForReview)).then(() => {
      if (!backendMode) return
      try {
        sessionStorage.removeItem(draftKey)
      } catch {
        // A stale local recovery copy is harmless; the server remains authoritative.
      }
    }).catch(() => {
      // The parent reports the request error and leaves this editor open for recovery.
    })
  }
  const close = () => {
    if (!dirty) return onClose()
    if (!window.confirm('译文尚未保存，确定放弃修改？')) return
    if (backendMode) {
      try { sessionStorage.removeItem(draftKey) } catch { /* Ignore unavailable session storage. */ }
    }
    onClose()
  }
  return (
    <Modal title="编辑译文" onClose={close} wide>
      <form
        onSubmit={(e) => {
          e.preventDefault()
          if (!backendMode) save(reviewed)
        }}
      >
        <div className="editor-key">
          <code>{entry.key}</code>
        </div>
        {backendMode && entry.workflowStatuses?.[locale] === 'CHANGES_REQUESTED' && (
          <section className="review-feedback" aria-label="审核退回意见">
            <h3>审核意见 · 请修改后重新送审</h3>
            {reviewFeedbackError ? <p role="alert">无法读取审核意见，请稍后重试。</p>
              : reviewFeedback?.reason ? <p>{reviewFeedback.reason}</p>
                : <p>正在读取审核意见…</p>}
            {reviewFeedback?.at && <time>{new Date(reviewFeedback.at).toLocaleString()}</time>}
          </section>
        )}
        <div className="editor-grid">
          <div>
            <span className="field-caption">源文案 · {languageName(project.sourceLanguage)}</span>
            <div className="source-copy">
              {entry.source || '尚未添加源文案，可通过源语言 JSON 导入补充。'}
            </div>
          </div>
          <label>
            <span className="field-caption">译文 · {languageName(locale)}</span>
            <textarea
              autoFocus
              rows={7}
              value={value}
              onChange={(e) => {
                setValue(e.target.value)
                setReviewed(false)
                if (backendMode) {
                  try {
                    sessionStorage.setItem(draftKey, e.target.value)
                  } catch {
                    // Keep the text in React state if storage is full or unavailable.
                  }
                }
              }}
              placeholder="让表达跨越语言…"
            />
          </label>
        </div>
        {backendMode && (suggestions.memory.length > 0 || suggestions.terms.length > 0) && (
          <section className="memory-suggestions" aria-label="翻译记忆建议">
            {suggestions.memory.length > 0 && <h3>相同源文的已审核译文</h3>}
            {suggestions.memory.map((suggestion) => (
              <div className="memory-suggestion" key={suggestion.entryId}>
                <div><code>{suggestion.key}</code><p>{suggestion.translation}</p></div>
                <button type="button" className="button plain" onClick={() => {
                  setValue(suggestion.translation)
                  setReviewed(false)
                  try { sessionStorage.setItem(draftKey, suggestion.translation) } catch { /* Keep the editor state in memory. */ }
                }}>采用</button>
              </div>
            ))}
            {suggestions.terms.length > 0 && <>
              <h3>项目术语</h3>
              {suggestions.terms.map((term) => (
                <div className="memory-suggestion" key={term.id}>
                  <div><code>{term.sourceTerm}</code><p>{term.targetTerm}</p>
                    {term.note && <small>{term.note}</small>}</div>
                </div>
              ))}
            </>}
          </section>
        )}
        {backendMode ? (
          <p className="form-hint">可单独保存或送审；审核由其他有权限的成员完成。保存失败时，草稿会留在当前浏览器会话中供恢复。</p>
        ) : (
          <>
            <label className="checkbox-label">
              <input
                type="checkbox"
                checked={reviewed}
                disabled={!value.trim()}
                onChange={(e) => setReviewed(e.target.checked)}
              />
              标记为已审核
            </label>
            <p className="form-hint">修改译文后会自动回到待审核状态。</p>
          </>
        )}
        <div className="modal-actions">
          <button type="button" className="button secondary" onClick={close}>
            取消
          </button>
          {backendMode && canReview && entry.workflowStatuses?.[locale] === 'IN_REVIEW' && (
            <>
              <button type="button" className="button secondary" disabled={dirty} onClick={() => {
                const reason = window.prompt('请说明退回修改的原因')?.trim()
                if (reason) void onRequestChanges?.(reason)
              }}>退回修改</button>
              <button type="button" className="button secondary" disabled={dirty} onClick={() => void onApprove?.()}>
                审核通过
              </button>
            </>
          )}
          {backendMode ? (
            <>
              <button className="button secondary" type="button" onClick={() => save(false)}>
                保存草稿
              </button>
              <button className="button primary" type="button"
                disabled={!value.trim() || (entry.workflowStatuses?.[locale] === 'APPROVED' && !dirty)}
                onClick={() => save(true)}>
                <Check size={16} />保存并送审
              </button>
            </>
          ) : (
            <button className="button primary" type="submit">
              <Check size={16} />保存译文
            </button>
          )}
        </div>
      </form>
    </Modal>
  )
}

export default function App() {
  const [initial] = useState(() => backendEnabled
    ? { workspace: { version: 1 as const, projects: [] }, warning: '' }
    : readInitial())
  const [workspace, setWorkspace] = useState<Workspace>(initial.workspace)
  const [storageWarning, setStorageWarning] = useState(initial.warning)
  const [storageBlocked, setStorageBlocked] = useState(Boolean(initial.warning))
  const [projectId, setProjectId] = useState(workspace.projects[0]?.id ?? '')
  const [locale, setLocale] = useState(workspace.projects[0]?.targetLanguages[0] ?? 'en-US')
  const [namespaceId, setNamespaceId] = useState(workspace.projects[0]?.defaultNamespaceId ?? '')
  const [namespaces, setNamespaces] = useState<RemoteNamespace[]>([])
  const [query, setQuery] = useState('')
  const [filter, setFilter] = useState('all')
  const [modal, setModal] = useState<'project' | 'entry' | 'language' | 'import' | 'members' | 'glossary' | 'migration' | 'machine-translation' | 'releases' | null>(null)
  const [editingId, setEditingId] = useState<string | null>(null)
  const [sourceEditingId, setSourceEditingId] = useState<string | null>(null)
  const [sourceText, setSourceText] = useState('')
  const [sourceHistory, setSourceHistory] = useState<Awaited<ReturnType<typeof readRemoteSourceHistory>>>([])
  const [sourceHistoryLoading, setSourceHistoryLoading] = useState(false)
  const [feedback, setFeedback] = useState<{ text: string; error: boolean } | null>(null)
  const [formError, setFormError] = useState('')
  const [importText, setImportText] = useState('')
  const [importLocale, setImportLocale] = useState(locale)
  const [importFile, setImportFile] = useState('')
  const [importPreview, setImportPreview] = useState<Awaited<ReturnType<typeof previewRemoteImport>> | null>(null)
  const [authStatus, setAuthStatus] = useState<'loading' | 'signed-out' | 'ready' | 'error'>(
    backendEnabled ? 'loading' : 'ready',
  )
  const [backendError, setBackendError] = useState(backendConfigurationError)
  const [remotePage, setRemotePage] = useState(1)
  const [remoteTotal, setRemoteTotal] = useState(0)
  const [remoteAllTotal, setRemoteAllTotal] = useState(0)
  const [remoteTranslated, setRemoteTranslated] = useState(0)
  const [remoteReviewed, setRemoteReviewed] = useState(0)
  const [remoteLoading, setRemoteLoading] = useState(false)
  const [remoteRefresh, setRemoteRefresh] = useState(0)
  const [actorId, setActorId] = useState('')
  const [projectRole, setProjectRole] = useState<string | null>(null)
  const [projectMembers, setProjectMembers] = useState<Awaited<ReturnType<typeof listRemoteMembers>>>([])
  const [membersLoading, setMembersLoading] = useState(false)
  const [machineTranslationJobs, setMachineTranslationJobs] = useState<MachineTranslationJob[]>([])
  const [machineTranslationJobId, setMachineTranslationJobId] = useState('')
  const [machineTranslationItems, setMachineTranslationItems] = useState<MachineTranslationJobItems | null>(null)
  const [machineTranslationItemsPage, setMachineTranslationItemsPage] = useState(1)
  const [machineTranslationRequestKey, setMachineTranslationRequestKey] = useState('')
  const [machineTranslationCreating, setMachineTranslationCreating] = useState(false)
  const [releaseHistory, setReleaseHistory] = useState<RemoteRelease[]>([])
  const [releaseHistoryLoading, setReleaseHistoryLoading] = useState(false)
  const [releaseJob, setReleaseJob] = useState<RemoteReleaseJob | null>(null)
  const [releaseJobError, setReleaseJobError] = useState('')
  const releaseRequestKeys = useRef(new Map<string, string>())
  const downloadedReleaseJobs = useRef(new Set<string>())
  const machineJobStatusById = useRef(new Map<string, string>())
  const [glossaryTerms, setGlossaryTerms] = useState<Awaited<ReturnType<typeof listRemoteGlossary>>['items']>([])
  const [glossaryLoading, setGlossaryLoading] = useState(false)
  const [localProjects] = useState(readLocalProjectsForMigration)
  const [localProjectId, setLocalProjectId] = useState('')
  const [migrationName, setMigrationName] = useState('')
  const [migrationPreview, setMigrationPreview] = useState<ProjectMigration | null>(null)
  const [migrationBusy, setMigrationBusy] = useState(false)
  const project = workspace.projects.find((p) => p.id === projectId)
  const activeNamespaceId = namespaceId || project?.defaultNamespaceId || ''
  const editing = project?.entries.find((e) => e.id === editingId)
  const sourceEditing = project?.entries.find((e) => e.id === sourceEditingId)
  const localStats = project
    ? progress(project, locale)
    : { total: 0, translated: 0, reviewed: 0, percentage: 0 }
  const stats = backendEnabled
    ? { total: remoteAllTotal, translated: remoteTranslated, reviewed: remoteReviewed,
        percentage: remoteAllTotal ? Math.round(remoteTranslated * 100 / remoteAllTotal) : 0 }
    : localStats

  useEffect(() => {
    if (!backendEnabled || backendConfigurationError) return
    let active = true
    void initializeBackendSession().then(async (user) => {
      if (!active) return
      if (!user || user.expired) {
        setAuthStatus('signed-out')
        return
      }
      const [projectData, currentActorId] = await Promise.all([listProjects(), getCurrentActorId()])
      const projects = projectData.map(mapProject)
      if (!active) return
      setWorkspace({ version: 1, projects })
      setActorId(currentActorId)
      setProjectId((current) => current || projects[0]?.id || '')
      setLocale((current) => current || projects[0]?.targetLanguages[0] || 'en-US')
      setAuthStatus('ready')
    }).catch((error: unknown) => {
      if (!active) return
      setBackendError(error instanceof Error ? error.message : '无法初始化服务端会话。')
      setAuthStatus('error')
    })
    return () => { active = false }
  }, [])

  useEffect(() => {
    if (!backendEnabled || authStatus !== 'ready' || !projectId || !actorId) return
    let active = true
    void getProjectRole(projectId, actorId).then((role) => {
      if (active) setProjectRole(role)
    }).catch(() => { if (active) setProjectRole(null) })
    return () => { active = false }
  }, [authStatus, projectId, actorId])

  useEffect(() => {
    if (!backendEnabled || authStatus !== 'ready' || !projectId) return
    let active = true
    void listRemoteNamespaces(projectId, true).then((items) => {
      if (!active) return
      setNamespaces(items)
      setNamespaceId((current) => items.some((item) => item.id === current && item.status === 'ACTIVE')
        ? current : items.find((item) => item.status === 'ACTIVE')?.id ?? '')
    }).catch((error: unknown) => { if (active) notify(error instanceof Error ? error.message : '无法读取命名空间。', true) })
    return () => { active = false }
  }, [authStatus, projectId, remoteRefresh])

  useEffect(() => {
    if (!backendEnabled || authStatus !== 'ready' || modal !== 'members' || !projectId) return
    let active = true
    setMembersLoading(true)
    void listRemoteMembers(projectId).then((members) => {
      if (active) setProjectMembers(members)
    }).catch((error: unknown) => {
      if (active) notify(error instanceof Error ? error.message : '无法读取项目成员。', true)
    }).finally(() => { if (active) setMembersLoading(false) })
    return () => { active = false }
  }, [authStatus, modal, projectId])

  useEffect(() => {
    if (!backendEnabled || authStatus !== 'ready' || modal !== 'releases' || !projectId) return
    let active = true
    setReleaseHistoryLoading(true)
    void listRemoteReleases(projectId, locale).then((items) => {
      if (active) setReleaseHistory(items)
    }).catch((error: unknown) => {
      if (active) notify(error instanceof Error ? error.message : '无法读取发布历史。', true)
    }).finally(() => { if (active) setReleaseHistoryLoading(false) })
    return () => { active = false }
  }, [authStatus, modal, projectId, locale])

  useEffect(() => {
    if (!backendEnabled || authStatus !== 'ready' || !projectId || !releaseJob
      || releaseJob.projectId !== projectId || releaseJob.status === 'FAILED') return
    if (releaseJob.status === 'COMPLETED') {
      if (downloadedReleaseJobs.current.has(releaseJob.id)) return
      downloadedReleaseJobs.current.add(releaseJob.id)
      releaseRequestKeys.current.delete(`${releaseJob.projectId}:${releaseJob.locale}:${releaseJob.format}`)
      void downloadRemoteReleaseJobResult(projectId, releaseJob).then(({ release, content, extension }) => {
        const link = document.createElement('a')
        link.href = URL.createObjectURL(new Blob([content], {
          type: extension === 'properties' ? 'text/plain' : 'application/json',
        }))
        link.download = `yandu-${release.projectId}-${release.locale}-v${release.version}.${extension}`
        link.click()
        setTimeout(() => URL.revokeObjectURL(link.href), 1000)
        setReleaseJobError('')
        setReleaseHistory((history) => [release, ...history.filter((item) => item.id !== release.id)])
        releaseRequestKeys.current.delete(`${release.projectId}:${release.locale}:${release.format}`)
        notify(`发布任务完成，已下载 ${languageName(release.locale)} 的 ${extension} 快照。`)
      }).catch((error: unknown) => {
        setReleaseJobError(error instanceof Error ? error.message : '发布文件下载失败。')
      })
      return
    }
    let active = true
    const timer = window.setTimeout(() => {
      void getRemoteReleaseJob(projectId, releaseJob.id).then((job) => {
        if (active) { setReleaseJob(job); setReleaseJobError('') }
      }).catch((error: unknown) => {
        if (active) {
          setReleaseJobError(error instanceof Error ? error.message : '发布任务状态查询失败。')
          setReleaseJob((job) => job ? { ...job } : job)
        }
      })
    }, 1500)
    return () => { active = false; window.clearTimeout(timer) }
  }, [authStatus, projectId, releaseJob])

  useEffect(() => {
    if (!backendEnabled || authStatus !== 'ready' || modal !== 'machine-translation' || !projectId) return
    let active = true
    const refresh = async () => {
      const jobs = await listMachineTranslationJobs(projectId)
      if (!active) return
      setMachineTranslationJobs(jobs)
      for (const job of jobs) {
        const previousStatus = machineJobStatusById.current.get(job.id)
        if (['COMPLETED', 'PARTIAL', 'FAILED'].includes(job.status) && previousStatus !== job.status)
          setRemoteRefresh((value) => value + 1)
        machineJobStatusById.current.set(job.id, job.status)
      }
      if (machineTranslationJobId) {
        const page = await getMachineTranslationJobItems(projectId, machineTranslationJobId,
          machineTranslationItemsPage, 50)
        if (active) setMachineTranslationItems(page)
      } else {
        setMachineTranslationItems(null)
      }
    }
    void refresh().catch((error: unknown) => notify(error instanceof Error ? error.message : '机器翻译任务读取失败。', true))
    const timer = window.setInterval(() => { void refresh().catch(() => {}) }, 3000)
    return () => { active = false; window.clearInterval(timer) }
  }, [authStatus, modal, projectId, machineTranslationJobId, machineTranslationItemsPage])

  useEffect(() => {
    if (!backendEnabled || authStatus !== 'ready' || !project) return
    if (project.status === 'ARCHIVED') {
      setRemoteLoading(false)
      setRemoteTotal(0); setRemoteAllTotal(0); setRemoteTranslated(0); setRemoteReviewed(0)
      return
    }
    let active = true
    setRemoteLoading(true)
    void loadTranslationPage(project, locale, query.trim(), filter, remotePage, activeNamespaceId).then((result) => {
      if (!active) return
      setWorkspace((current) => ({
        ...current,
        projects: current.projects.map((item) => item.id === project.id
          ? { ...item, entries: result.entries }
          : item),
      }))
      setRemoteTotal(result.total)
      setRemoteAllTotal(result.allTotal)
      setRemoteTranslated(result.translated)
      setRemoteReviewed(result.reviewed)
    }).catch((error: unknown) => {
      if (active) notify(error instanceof Error ? error.message : '无法加载文案。', true)
    }).finally(() => { if (active) setRemoteLoading(false) })
    return () => { active = false }
  }, [authStatus, projectId, project?.status, locale, activeNamespaceId, query, filter, remotePage, remoteRefresh])

  useEffect(() => {
    if (!backendEnabled || authStatus !== 'ready' || !projectId || !sourceEditingId) {
      setSourceHistory([])
      return
    }
    let active = true
    setSourceHistoryLoading(true)
    void readRemoteSourceHistory(projectId, sourceEditingId).then((history) => {
      if (active) setSourceHistory(history)
    }).catch((error: unknown) => {
      if (active) notify(error instanceof Error ? error.message : '无法读取源文案历史。', true)
    }).finally(() => { if (active) setSourceHistoryLoading(false) })
    return () => { active = false }
  }, [authStatus, projectId, sourceEditingId, remoteRefresh])

  useEffect(() => {
    if (backendEnabled || storageBlocked) return
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(workspace))
    } catch {
      setStorageBlocked(true)
      setStorageWarning('浏览器本地存储不可用或已满。当前修改仅保留在内存中，请及时导出 JSON。')
    }
  }, [workspace, storageBlocked])

  const notify = (text: string, error = false) => setFeedback({ text, error })
  const updateProject = (next: Project) =>
    setWorkspace((w) => ({ ...w, projects: w.projects.map((p) => (p.id === next.id ? next : p)) }))
  const openModal = (next: typeof modal) => {
    setFormError('')
    setImportText('')
    setImportFile('')
    setImportPreview(null)
    setMigrationPreview(null)
    if (next === 'machine-translation') {
      setMachineTranslationJobId('')
      setMachineTranslationItemsPage(1)
      setMachineTranslationRequestKey('')
    }
    if (next === 'migration') {
      const first = localProjects[0]
      setLocalProjectId(first?.id ?? '')
      setMigrationName(first?.name ?? '')
    }
    setImportLocale(locale)
    setModal(next)
  }
  const localMigrationProject = localProjects.find((item) => item.id === localProjectId)
  const chooseProject = (p: Project) => {
    setProjectId(p.id)
    setNamespaceId(p.defaultNamespaceId ?? '')
    setLocale(p.targetLanguages[0])
    setQuery('')
    setFilter('all')
    setEditingId(null)
    setRemotePage(1)
  }
  const submit = (event: FormEvent<HTMLFormElement>, action: (data: FormData) => void | Promise<void>) => {
    event.preventDefault()
    const formData = new FormData(event.currentTarget)
    void Promise.resolve().then(() => action(formData))
      .then(() => { setModal(null); setFormError('') })
      .catch((error: unknown) => setFormError(error instanceof Error ? error.message : '操作失败，请重试。'))
  }
  const saveSource = async () => {
    if (!project || !sourceEditing) return
    try {
      if (backendEnabled) {
        await updateRemoteSource(project.id, sourceEditing.id, sourceEditing.sourceRevision ?? 1, sourceText)
        setRemoteRefresh((value) => value + 1)
      } else updateProject(updateSource(project, sourceEditing.id, sourceText))
      setSourceEditingId(null)
      notify('源文案已保存，相关译文需要重新审核。')
    } catch (error) {
      notify(error instanceof Error ? error.message : '源文案保存失败。', true)
    }
  }
  const archiveEntry = async (entry: Entry) => {
    if (!project || !window.confirm(`确定归档「${entry.key}」？归档条目不会出现在工作台和发布文件中。`)) return
    try {
      await archiveRemoteEntry(project.id, entry.id)
      setRemoteRefresh((value) => value + 1)
      notify('词条已归档。')
    } catch (error) {
      notify(error instanceof Error ? error.message : '词条归档失败。', true)
    }
  }
  const download = (format: 'json-flat-v1' | 'java-properties-v1' = 'json-flat-v1') => {
    if (!project) return
    const extension = format === 'java-properties-v1' ? 'properties' : 'json'
    if (backendEnabled) {
      const requestKey = `${project.id}:${locale}:${format}`
      const idempotencyKey = releaseRequestKeys.current.get(requestKey) ?? crypto.randomUUID()
      releaseRequestKeys.current.set(requestKey, idempotencyKey)
      void requestRemoteRelease(project.id, locale, format, idempotencyKey).then((job) => {
        setReleaseJob(job)
        setReleaseJobError('')
        setModal('releases')
        notify(job.status === 'COMPLETED' ? '发布已完成，正在准备文件。' : '发布任务已提交，可在发布窗口查看进度。')
      }).catch((error: unknown) => notify(error instanceof Error ? error.message : '发布失败。', true))
      return
    }
    const content = format === 'java-properties-v1'
      ? exportProperties(project, locale) : exportMessages(project, locale)
    const url = URL.createObjectURL(new Blob([content], {
      type: extension === 'properties' ? 'text/plain' : 'application/json',
    }))
    const link = document.createElement('a')
    link.href = url
    link.download = `yandu-${project.id}-${locale}.${extension}`
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
    notify(`已导出 ${languageName(locale)} 的 ${extension} 文案；未翻译条目保留为空字符串。`)
  }
  const visible =
    project?.entries.filter((entry) => {
      const search = query.trim().toLowerCase()
      const matches = [entry.key, entry.source, entry.translations[locale]?.value ?? ''].some(
        (value) => value.toLowerCase().includes(search),
      )
      return matches && (filter === 'all' || statusOf(entry, locale) === filter)
    }) ?? []
  const entryCount = backendEnabled ? remoteAllTotal : project?.entries.length ?? 0
  const availableLanguages = LANGUAGES.filter(
    (l) => l.code !== project?.sourceLanguage && !project?.targetLanguages.includes(l.code),
  )
  const projectActive = !backendEnabled || project?.status !== 'ARCHIVED'
  const canManageCatalog = projectActive && (!backendEnabled || ['OWNER', 'ADMIN'].includes(projectRole ?? ''))
  const canManageMembers = projectActive && ['OWNER', 'ADMIN'].includes(projectRole ?? '')
  const canManageProject = ['OWNER', 'ADMIN'].includes(projectRole ?? '')
  const canManageGlossary = projectActive && ['OWNER', 'ADMIN'].includes(projectRole ?? '')
  const canManageTranslation = projectActive && ['OWNER', 'ADMIN', 'TRANSLATOR'].includes(projectRole ?? '')

  useEffect(() => {
    if (!backendEnabled || modal !== 'glossary' || !project) return
    let active = true
    setGlossaryLoading(true)
    void listRemoteGlossary(project.id, project.sourceLanguage, locale)
      .then((page) => { if (active) setGlossaryTerms(page.items) })
      .catch((error: unknown) => {
        if (active) setFeedback({ text: error instanceof Error ? error.message : '术语读取失败。', error: true })
      })
      .finally(() => { if (active) setGlossaryLoading(false) })
    return () => { active = false }
  }, [modal, project?.id, project?.sourceLanguage, locale])

  useEffect(() => {
    if (modal !== 'migration' || !migrationPreview
      || !['QUEUED', 'RUNNING'].includes(migrationPreview.status)) return
    let active = true
    const timer = window.setInterval(() => {
      void getProjectMigration(migrationPreview.id).then(async (state) => {
        if (!active) return
        setMigrationPreview(state)
        if (state.status === 'COMPLETED') {
          const projects = (await listProjects()).map(mapProject)
          if (!active) return
          setWorkspace({ version: 1, projects })
          if (state.projectId) setProjectId(state.projectId)
          notify('本地项目迁移完成，项目现已加入团队工作空间。')
        }
      }).catch((error: unknown) => {
        if (active) setFormError(error instanceof Error ? error.message : '迁移状态读取失败。')
      })
    }, 2000)
    return () => { active = false; window.clearInterval(timer) }
  }, [modal, migrationPreview?.id, migrationPreview?.status])

  if (backendEnabled && authStatus !== 'ready') {
    const message = backendConfigurationError || backendError
    return (
      <div className="app-shell">
        <main className="empty-state" aria-live="polite">
          <img src="/brand/logo-horizontal.svg" alt="言渡 YANDU" width="210" />
          <h1>{authStatus === 'loading' ? '正在连接工作空间…' : '连接团队工作空间'}</h1>
          <p>{message || (authStatus === 'signed-out' ? '登录后读取服务端项目和译文。' : '')}</p>
          {authStatus === 'signed-out' && !message && (
            <button className="button primary" onClick={() => void signIn().catch((error: unknown) => {
              setBackendError(error instanceof Error ? error.message : '登录跳转失败。')
              setAuthStatus('error')
            })}>使用团队账号登录</button>
          )}
        </main>
      </div>
    )
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <a className="brand-lockup" href="/" aria-label="言渡 YANDU 首页">
          <img src="/brand/logo-horizontal.svg" alt="言渡 YANDU" />
        </a>
        <div className="workspace-label">
          <span className="workspace-avatar">言</span>
          <div>
            <strong>我的工作空间</strong>
            <span>{backendEnabled ? '团队协作 · 服务端' : '个人 · 本地原型'}</span>
          </div>
          <ChevronDown size={15} aria-hidden="true" />
        </div>
        <div className="nav-section-title">工作空间</div>
        <div className="nav-active">
          <FolderKanban size={18} />
          翻译项目<span>{workspace.projects.length}</span>
        </div>
        <div className="project-list-heading">
          <span>我的项目</span>
          <button
            className="icon-button"
            aria-label="创建项目"
            onClick={() => openModal('project')}
          >
            <Plus size={16} />
          </button>
        </div>
        <nav aria-label="翻译项目">
          {workspace.projects.map((p) => (
            <button
              key={p.id}
              className={`project-nav ${p.id === projectId ? 'selected' : ''}`}
              onClick={() => chooseProject(p)}
            >
              <span className="project-dot" />
              <span>{p.name}</span>
              {p.id === projectId && <ChevronRight size={14} />}
            </button>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <div className="small-brand">
            <Sparkles size={18} />
            <strong>让好表达，走得更远。</strong>
            <p>
              从一个词，到整个世界。
              <br />
              言渡，陪你跨越语言。
            </p>
          </div>
          <a
            className="help-link"
            href="https://github.com/clickear/yandu"
            target="_blank"
            rel="noreferrer"
          >
            <CircleHelp size={16} />
            项目文档
            <ArrowUpRight size={13} />
          </a>
          <div className="local-indicator">
            <span className={storageBlocked ? 'dot-warning' : 'dot-green'} />
            {backendEnabled ? '文案保存至团队服务端'
              : storageBlocked ? '仅内存 · 请导出备份' : '文案保存在此浏览器'}
          </div>
        </div>
      </aside>

      <div className="main-shell">
        <header className="topbar">
          <div className="breadcrumb">
            工作空间
            <ChevronRight size={14} />
            <span>翻译项目</span>
            {project && (
              <>
                <ChevronRight size={14} />
                <strong>{project.name}</strong>
              </>
            )}
          </div>
          <span className="prototype-pill">{backendEnabled ? '团队工作空间' : '本地原型'}</span>
          {backendEnabled && localProjects.length > 0 && (
            <button className="button plain" onClick={() => openModal('migration')}>迁移本地项目</button>
          )}
          {backendEnabled && <button className="button plain" onClick={() => void signOut()}>退出登录</button>}
        </header>
        <main>
          {storageWarning && (
            <div className="warning-banner" role="alert">
              <span>{storageWarning}</span>
              <button
                onClick={() => {
                  if (
                    window.confirm('确定重置？将删除此浏览器中原有的言渡数据，并保存当前工作空间。')
                  ) {
                    try {
                      localStorage.setItem(STORAGE_KEY, JSON.stringify(workspace))
                      setStorageBlocked(false)
                      setStorageWarning('')
                    } catch {
                      notify('本地存储仍不可用，请使用 JSON 导出。', true)
                    }
                  }
                }}
              >
                重置本地存储
              </button>
            </div>
          )}
          <div className="page-intro">
            <div>
              <div className="eyebrow">
                <span />
                YOUR WORDS, WORLDWIDE
              </div>
              <h1>
                让每一种语言，<span>准确抵达。</span>
              </h1>
              <p>在同一处整理文案、打磨翻译，让每个表达都恰到好处。</p>
            </div>
            <button className="button secondary" onClick={() => openModal('project')}>
              <Plus size={17} />
              新建项目
            </button>
          </div>
          {feedback && (
            <div
              className={`feedback ${feedback.error ? 'feedback-error' : ''}`}
              role={feedback.error ? 'alert' : 'status'}
            >
              <span>{feedback.text}</span>
              <button
                className="icon-button"
                aria-label="关闭提示"
                onClick={() => setFeedback(null)}
              >
                <X size={15} />
              </button>
            </div>
          )}
          {project ? (
            <>
              <section className="project-header">
                <div className="project-title">
                  <span className="project-icon">
                    <FolderKanban size={25} />
                  </span>
                  <div>
                    <h2>
                      {project.name}
                      {project.id === 'demo-website' && (
                        <span className="sample-badge">示例项目</span>
                      )}
                    </h2>
                    <p>{project.description || '每一条文案，都是与世界的连接。'}</p>
                  </div>
                </div>
                <div className="project-actions">
                  <span className="source-language">
                    <Globe2 size={14} />
                    源语言：{languageName(project.sourceLanguage)}
                  </span>
                  {backendEnabled && (
                    <>
                      {canManageTranslation && (
                        <button className="button secondary" onClick={() => openModal('machine-translation')}>
                          <Sparkles size={15} />机器翻译
                        </button>
                      )}
                      {canManageProject && project.status === 'ARCHIVED' && (
                        <button className="button secondary" onClick={async () => {
                          try {
                            await restoreRemoteProject(project.id)
                            const projects = (await listProjects()).map(mapProject)
                            setWorkspace({ version: 1, projects })
                            notify('项目已恢复。')
                          } catch (error) { notify(error instanceof Error ? error.message : '项目恢复失败。', true) }
                        }}>恢复项目</button>
                      )}
                      {canManageProject && project.status !== 'ARCHIVED' && (
                        <button className="button secondary" onClick={async () => {
                          if (!window.confirm(`确定归档「${project.name}」？历史发布和审计记录仍可读取。`)) return
                          try {
                            await archiveRemoteProject(project.id)
                            const projects = (await listProjects()).map(mapProject)
                            setWorkspace({ version: 1, projects })
                            notify('项目已归档，历史发布与审计记录仍可查询。')
                          } catch (error) { notify(error instanceof Error ? error.message : '项目归档失败。', true) }
                        }}>归档项目</button>
                      )}
                      <button className="button secondary" onClick={() => openModal('glossary')}>
                        术语库
                      </button>
                      <button className="button secondary" onClick={() => openModal('members')}>
                        项目成员
                      </button>
                      {canManageProject && projectActive && project.targetLanguages.length > 1 && (
                        <button className="button secondary" onClick={async () => {
                          if (!window.confirm(`归档当前目标语言 ${locale}？旧发布仍可下载，译文和审计会保留。`)) return
                          try {
                            const targetLanguages = await archiveRemoteLocale(project.id, locale)
                            updateProject({ ...project, targetLanguages })
                            if (!targetLanguages.includes(locale)) setLocale(targetLanguages[0])
                            setRemotePage(1)
                            notify('目标语言已归档。')
                          } catch (error) { notify(error instanceof Error ? error.message : '目标语言归档失败。', true) }
                        }}>归档此语言</button>
                      )}
                    </>
                  )}
                </div>
              </section>
              {backendEnabled && project.status === 'ARCHIVED' && (
                <div className="warning-banner" role="status">
                  项目已归档。词条、译文和发布不能修改；具有项目成员身份的用户仍可查询历史发布与审计记录。
                </div>
              )}
              <section className="stats-grid" aria-label="项目概况">
                <div className="stat-card">
                  <div>
                    <span>文案总数</span>
                    <strong>
                      {stats.total}
                      <small>条</small>
                    </strong>
                  </div>
                  <span className="stat-icon violet">
                    <FileText size={21} />
                  </span>
                </div>
                <div className="stat-card">
                  <div>
                    <span>当前语言已翻译</span>
                    <strong>
                      {stats.translated}
                      <small>/ {stats.total}</small>
                    </strong>
                  </div>
                  <span className="stat-icon orange">
                    <Languages size={22} />
                  </span>
                </div>
                <div className="stat-card">
                  <div>
                    <span>当前语言已审核</span>
                    <strong>
                      {stats.reviewed}
                      <small>条</small>
                    </strong>
                  </div>
                  <span className="stat-icon green">
                    <CheckCheck size={23} />
                  </span>
                </div>
                <div className="stat-card progress-card">
                  <div>
                    <span>当前语言完成度</span>
                    <strong>
                      {stats.percentage}
                      <small>%</small>
                    </strong>
                  </div>
                  <div className="progress-track">
                    <span style={{ width: `${stats.percentage}%` }} />
                  </div>
                </div>
              </section>
              <section className="translation-panel" aria-label="翻译工作台">
                <div className="panel-title">
                  <div>
                    <h2>翻译工作台</h2>
                    <span>把意思说清楚，也把语气留下来。</span>
                  </div>
                  <button className="button primary" onClick={() => openModal('entry')}>
                    <Plus size={16} />
                    添加文案
                  </button>
                </div>
                <div className="language-strip">
                  <div className="language-tabs" role="group" aria-label="目标语言">
                    {project.targetLanguages.map((code) => {
                      const p = progress(project, code)
                      return (
                        <button
                          key={code}
                          className={`language-tab ${locale === code ? 'active' : ''}`}
                          onClick={() => {
                            setLocale(code)
                            setRemotePage(1)
                            setFilter('all')
                            setQuery('')
                          }}
                          aria-pressed={locale === code}
                        >
                          <span className="language-avatar">{languageShort(code)}</span>
                          <span>{languageName(code)}</span>
                          <small>{backendEnabled
                            ? code === locale ? `${stats.percentage}%` : '—'
                            : `${p.percentage}%`}</small>
                        </button>
                      )
                    })}
                  </div>
                  <button
                    className="add-language"
                    onClick={() => openModal('language')}
                    disabled={!availableLanguages.length}
                  >
                    <Plus size={15} />
                    添加语言
                  </button>
                </div>
                <div className="table-toolbar">
                  {backendEnabled && (
                    <div className="namespace-controls">
                      <label>命名空间
                        <select aria-label="选择命名空间" value={namespaceId}
                          onChange={(event) => { setNamespaceId(event.target.value); setRemotePage(1) }}>
                          {namespaces.filter((item) => item.status === 'ACTIVE').map((item) => (
                            <option key={item.id} value={item.id}>{item.name}</option>
                          ))}
                        </select>
                      </label>
                      {canManageProject && projectActive && <>
                        <button className="button plain" disabled={!namespaceId} onClick={async () => {
                          const current = namespaces.find((item) => item.id === namespaceId)
                          const name = window.prompt('重命名命名空间', current?.name)?.trim()
                          if (!name || !current) return
                          try {
                            const updated = await renameRemoteNamespace(project.id, namespaceId, name)
                            setNamespaces((items) => items.map((item) => item.id === updated.id ? updated : item))
                            notify('命名空间已重命名。')
                          } catch (error) { notify(error instanceof Error ? error.message : '重命名失败。', true) }
                        }}>重命名</button>
                        <button className="button plain" onClick={async () => {
                          const name = window.prompt('新命名空间名称')?.trim()
                          if (!name) return
                          try {
                            const created = await createRemoteNamespace(project.id, name)
                            setNamespaces((current) => [...current.filter((item) => item.id !== created.id), created])
                            setNamespaceId(created.id); setRemotePage(1); setRemoteRefresh((value) => value + 1)
                            notify(`已创建命名空间「${created.name}」。`)
                          } catch (error) { notify(error instanceof Error ? error.message : '创建命名空间失败。', true) }
                        }}><Plus size={14} />新建命名空间</button>
                        <button className="button plain" disabled={!namespaceId}
                          onClick={async () => {
                            if (!window.confirm('只能归档空命名空间。确认归档当前命名空间？')) return
                            try {
                              await archiveRemoteNamespace(project.id, namespaceId)
                              const items = await listRemoteNamespaces(project.id, true)
                              setNamespaces(items)
                              setNamespaceId(items.find((item) => item.status === 'ACTIVE')?.id ?? '')
                              notify('命名空间已归档。')
                            } catch (error) { notify(error instanceof Error ? error.message : '归档失败；请确认命名空间为空。', true) }
                          }}><Archive size={14} />归档</button>
                        {namespaces.filter((item) => item.status === 'ARCHIVED').map((item) => (
                          <button className="button plain" key={`restore-${item.id}`} onClick={async () => {
                            try {
                              await restoreRemoteNamespace(project.id, item.id)
                              const items = await listRemoteNamespaces(project.id, true)
                              setNamespaces(items); setNamespaceId(item.id); setRemotePage(1)
                              notify(`已恢复命名空间「${item.name}」。`)
                            } catch (error) { notify(error instanceof Error ? error.message : '恢复命名空间失败。', true) }
                          }}>恢复「{item.name}」</button>
                        ))}
                      </>}
                    </div>
                  )}
                  <label className="search-box">
                    <Search size={17} />
                    <input
                      aria-label="搜索文案或键名"
                      placeholder="搜索文案、译文或键名…"
                      value={query}
                      onChange={(e) => { setQuery(e.target.value); setRemotePage(1) }}
                    />
                  </label>
                  <select
                    aria-label="按状态筛选"
                    value={filter}
                    onChange={(e) => { setFilter(e.target.value); setRemotePage(1) }}
                  >
                    <option value="all">全部状态</option>
                    <option value="untranslated">未翻译</option>
                    <option value="draft">草稿</option>
                    <option value="reviewed">已审核</option>
                    {backendEnabled && <>
                      <option value="in-review">审核中</option>
                      <option value="changes-requested">已退回</option>
                      <option value="needs-review">需复核</option>
                    </>}
                  </select>
                  <div className="toolbar-actions">
                    <button className="button plain" onClick={() => openModal('import')}>
                      <ArrowUpFromLine size={16} />
                      导入 JSON
                    </button>
                    <button className="button plain" onClick={() => download('json-flat-v1')}>
                      <ArrowDownToLine size={16} />
                      {backendEnabled ? '发布并导出 JSON' : '导出 JSON'}
                    </button>
                    <button className="button plain" onClick={() => download('java-properties-v1')}>
                      <ArrowDownToLine size={16} />
                      {backendEnabled ? '发布并导出 Properties' : '导出 Properties'}
                    </button>
                    {backendEnabled && (
                      <button className="button plain" onClick={() => openModal('releases')}>
                        <FileText size={16} />发布历史
                      </button>
                    )}
                  </div>
                </div>
                <div className="table-scroll">
                  <table>
                    <thead>
                      <tr>
                        <th className="index-col">#</th>
                        <th>
                          键名 / 源文案 <span>{project.sourceLanguage}</span>
                        </th>
                        <th>
                          译文 <span>{locale}</span>
                        </th>
                        <th>状态</th>
                        <th>
                          <span className="sr-only">操作</span>
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {visible.map((entry, index) => {
                        const workflowStatus = backendEnabled ? entry.workflowStatuses?.[locale] : null
                        const status = !backendEnabled ? statusOf(entry, locale)
                          : workflowStatus === 'APPROVED' ? 'reviewed'
                            : workflowStatus === 'IN_REVIEW' ? 'in-review'
                              : workflowStatus === 'CHANGES_REQUESTED' ? 'changes-requested'
                                : workflowStatus === 'NEEDS_REVIEW' ? 'needs-review'
                                  : workflowStatus === 'DRAFT' ? 'draft' : 'untranslated'
                        return (
                          <tr key={entry.id}>
                            <td className="row-index">{String((remotePage - 1) * 50 + index + 1).padStart(2, '0')}</td>
                            <td>
                              <code className="entry-key">{entry.key}</code>
                              <p className="entry-source">{entry.source || '源文案待补充'}</p>
                            </td>
                            <td className="translation-cell">
                              {entry.translations[locale]?.value || (
                                <span className="translation-placeholder">等待一句恰好的表达…</span>
                              )}
                            </td>
                            <td>
                              <span className={`status-badge status-${status}`}>
                                <span />
                                {workflowStatusLabels[status]}
                              </span>
                            </td>
                            <td className="row-actions">
                              <button
                                className="edit-button"
                                aria-label={`编辑译文 ${entry.key}`}
                                onClick={() => setEditingId(entry.id)}
                              >
                                译文
                                <ChevronRight size={13} />
                              </button>
                              <button className="button plain" disabled={!canManageCatalog}
                                onClick={() => { setSourceEditingId(entry.id); setSourceText(entry.source) }}>
                                源文
                              </button>
                              {backendEnabled && canManageCatalog && (
                                <>
                                  <button className="button plain" aria-label={`改键 ${entry.key}`} onClick={async () => {
                                    const key = window.prompt('词条键（项目内唯一）', entry.key)?.trim()
                                    if (!key || key === entry.key) return
                                    try {
                                      await reorganizeRemoteEntry(project.id, entry.id, activeNamespaceId, key)
                                      setRemoteRefresh((value) => value + 1)
                                      notify('词条键已更新，译文与审核状态已保留。')
                                    } catch (error) { notify(error instanceof Error ? error.message : '改键失败。', true) }
                                  }}>改键</button>
                                  <select aria-label={`移动 ${entry.key} 到命名空间`} value={activeNamespaceId}
                                    onChange={async (event) => {
                                      const targetNamespaceId = event.target.value
                                      if (targetNamespaceId === activeNamespaceId) return
                                      try {
                                        await reorganizeRemoteEntry(project.id, entry.id, targetNamespaceId, entry.key)
                                        setRemoteRefresh((value) => value + 1)
                                        notify('词条已移动，译文与审核状态已保留。')
                                      } catch (error) { notify(error instanceof Error ? error.message : '移动词条失败。', true) }
                                    }}>
                                    {namespaces.filter((item) => item.status === 'ACTIVE').map((item) => (
                                      <option key={item.id} value={item.id}>{item.name}</option>
                                    ))}
                                  </select>
                                </>
                              )}
                              {backendEnabled && canManageCatalog && (
                                <button className="icon-button" aria-label={`归档 ${entry.key}`}
                                  onClick={() => void archiveEntry(entry)}>
                                  <Archive size={15} />
                                </button>
                              )}
                            </td>
                          </tr>
                        )
                      })}
                    </tbody>
                  </table>
                </div>
                {!visible.length && (
                  <div className="empty-state">
                    <Languages size={34} />
                    <h3>{remoteLoading ? '正在加载文案…' : entryCount ? '没有找到匹配的文案' : '从第一条文案开始'}</h3>
                    <p>
                      {entryCount
                        ? '换个关键词，或调整状态筛选。'
                        : '添加一条源文案，或导入已有的 JSON 语言文件。'}
                    </p>
                    {!entryCount && (
                      <button className="button secondary" onClick={() => openModal('entry')}>
                        添加文案
                      </button>
                    )}
                  </div>
                )}
                <div className="table-footer">
                  <span>
                    {backendEnabled
                      ? `显示 ${visible.length} 条，匹配 ${remoteTotal} 条，共 ${entryCount} 条文案`
                      : `显示 ${visible.length} 条，共 ${entryCount} 条文案`}
                  </span>
                  {backendEnabled && remoteTotal > 50 && (
                    <span className="table-pagination">
                      <button className="button plain" disabled={remotePage <= 1 || remoteLoading}
                        onClick={() => setRemotePage((p) => p - 1)}>上一页</button>
                      <span>{remotePage} / {Math.ceil(remoteTotal / 50)}</span>
                      <button className="button plain" disabled={remotePage >= Math.ceil(remoteTotal / 50) || remoteLoading}
                        onClick={() => setRemotePage((p) => p + 1)}>下一页</button>
                    </span>
                  )}
                  <span>
                    <span className="dot-green" />
                    {backendEnabled ? '由服务端保存'
                      : storageBlocked ? '仅保存在当前页面，请导出备份' : '自动保存至此浏览器'}
                  </span>
                </div>
              </section>
              <div className="page-footer">
                <span>言语有界，表达无界。</span>
                <span>
                  YANDU <span className="footer-spark">✳</span> MADE FOR UNDERSTANDING
                </span>
              </div>
            </>
          ) : (
            <div className="empty-state">
              <FolderKanban size={36} />
              <h2>为你的表达，新建一个家。</h2>
              <p>创建项目后，即可添加语言和翻译文案。</p>
              <button className="button primary" onClick={() => openModal('project')}>
                新建项目
              </button>
            </div>
          )}
        </main>
      </div>

      {modal === 'project' && (
        <Modal title="新建项目" onClose={() => setModal(null)}>
          <form
            onSubmit={(e) =>
              submit(e, async (data) => {
                const p = backendEnabled
                  ? mapProject(await createRemoteProject(String(data.get('name')), String(data.get('source')), String(data.get('description') ?? '')))
                  : createProject(String(data.get('name')), String(data.get('description')), String(data.get('source')))
                setWorkspace((w) => ({ ...w, projects: [...w.projects, p] }))
                chooseProject(p)
                notify('项目已创建，添加第一条文案吧。')
              })
            }
          >
            <label>
              项目名称
              <input name="name" required maxLength={80} placeholder="例如：移动应用" autoFocus />
            </label>
            <label>
              项目描述
              <input name="description" maxLength={2000} placeholder="这个项目想向世界表达什么？" />
            </label>
            <label>
              源语言
              <select name="source" defaultValue="zh-CN">
                {LANGUAGES.map((l) => (
                  <option key={l.code} value={l.code}>
                    {l.name} · {l.code}
                  </option>
                ))}
              </select>
            </label>
            <p className="form-hint">
              默认添加英语目标语言；源语言为英语时添加简体中文。创建后可继续添加其他语言。
            </p>
            {formError && (
              <p className="form-error" role="alert">
                {formError}
              </p>
            )}
            <div className="modal-actions">
              <button type="button" className="button secondary" onClick={() => setModal(null)}>
                取消
              </button>
              <button className="button primary" type="submit">
                创建项目
              </button>
            </div>
          </form>
        </Modal>
      )}
      {modal === 'entry' && project && (
        <Modal title="添加文案" onClose={() => setModal(null)}>
          <form
            onSubmit={(e) =>
              submit(e, async (data) => {
                if (backendEnabled) {
                  const key = String(data.get('key')).trim()
                  const source = String(data.get('source'))
                  if (!key || !source.trim()) throw new Error('请填写键名和源文案。')
                  await addRemoteEntry(project, key, source, activeNamespaceId)
                  setRemoteRefresh((value) => value + 1)
                } else {
                  updateProject(addEntry(project, String(data.get('key')), String(data.get('source'))))
                }
                notify('文案已添加。')
              })
            }
          >
            <label>
              键名
              <input
                name="key"
                required
                maxLength={200}
                placeholder="例如：home.hero.title"
                autoFocus
              />
            </label>
            <label>
              源文案 · {languageName(project.sourceLanguage)}
              <textarea name="source" required rows={4} placeholder="写下你想表达的内容…" />
            </label>
            <p className="form-hint">键名在项目内唯一，建议使用页面与用途命名。</p>
            {formError && (
              <p className="form-error" role="alert">
                {formError}
              </p>
            )}
            <div className="modal-actions">
              <button type="button" className="button secondary" onClick={() => setModal(null)}>
                取消
              </button>
              <button className="button primary" type="submit">
                添加文案
              </button>
            </div>
          </form>
        </Modal>
      )}
      {modal === 'language' && project && (
        <Modal title="添加目标语言" onClose={() => setModal(null)}>
          <form
            onSubmit={(e) =>
              submit(e, async (data) => {
                const next = String(data.get('locale'))
                if (backendEnabled) {
                  const updated = mapProject(await addRemoteLocale(project.id, next))
                  updateProject({ ...updated, entries: project.entries })
                } else updateProject(addLanguage(project, next))
                setLocale(next)
                setRemotePage(1)
                setFilter('all')
                setQuery('')
                notify('目标语言已添加。')
              })
            }
          >
            <label>
              目标语言
              <select name="locale">
                {availableLanguages.map((l) => (
                  <option key={l.code} value={l.code}>
                    {l.name} · {l.code}
                  </option>
                ))}
              </select>
            </label>
            <p className="form-hint">已有文案将保留，新增语言从未翻译状态开始。</p>
            {formError && (
              <p className="form-error" role="alert">
                {formError}
              </p>
            )}
            <div className="modal-actions">
              <button type="button" className="button secondary" onClick={() => setModal(null)}>
                取消
              </button>
              <button className="button primary" type="submit">
                添加语言
              </button>
            </div>
          </form>
        </Modal>
      )}
      {modal === 'members' && project && backendEnabled && (
        <Modal title="项目成员" onClose={() => setModal(null)} wide>
          <div className="members-list">
            <p>你的账号 ID：<code>{actorId}</code>
              <button className="button plain" type="button" onClick={() => {
                if (!navigator.clipboard) return notify('无法访问剪贴板，请手动复制账号 ID。', true)
                void navigator.clipboard.writeText(actorId).then(() => notify('账号 ID 已复制。'))
                  .catch(() => notify('无法访问剪贴板，请手动复制账号 ID。', true))
              }}>复制</button>
            </p>
            <p className="form-hint">被添加的成员需先使用团队账号登录一次，再把账号 ID 提供给项目管理员。</p>
            {membersLoading ? <p>正在读取成员…</p> : (
              <ul>
                {projectMembers.map((member) => (
                  <li key={member.userId}>
                    <code>{member.userId}</code>
                    <span>{member.role}</span>
                    <time>{new Date(member.createdAt).toLocaleDateString()}</time>
                    {canManageMembers && member.role !== 'OWNER' && member.userId !== actorId
                      && (projectRole === 'OWNER' || member.role !== 'ADMIN') && (
                      <>
                        <select aria-label={`设置 ${member.userId} 的项目角色`} value={member.role}
                          onChange={async (event) => {
                            try {
                              await changeRemoteMemberRole(project.id, member.userId, event.target.value)
                              setProjectMembers(await listRemoteMembers(project.id))
                              notify('成员角色已更新。')
                            } catch (error) { notify(error instanceof Error ? error.message : '角色更新失败。', true) }
                          }}>
                          {projectRole === 'OWNER' && <option value="ADMIN">ADMIN</option>}
                          <option value="TRANSLATOR">TRANSLATOR</option>
                          <option value="REVIEWER">REVIEWER</option>
                          <option value="VIEWER">VIEWER</option>
                        </select>
                        <button type="button" className="button plain" onClick={async () => {
                          if (!window.confirm(`从项目中移除成员 ${member.userId}？`)) return
                          try {
                            await removeRemoteMember(project.id, member.userId)
                            setProjectMembers(await listRemoteMembers(project.id))
                            notify('成员已移除。')
                          } catch (error) { notify(error instanceof Error ? error.message : '成员移除失败。', true) }
                        }}>移除</button>
                      </>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </div>
          {canManageMembers ? (
            <form onSubmit={(event) => submit(event, async (data) => {
              const userId = String(data.get('userId')).trim()
              const role = String(data.get('role'))
              if (!/^\d+$/.test(userId) || BigInt(userId) <= 0n || BigInt(userId) > 9223372036854775807n) {
                throw new Error('请输入有效的数字账号 ID。')
              }
              await addRemoteMember(project.id, userId, role)
              setProjectMembers(await listRemoteMembers(project.id))
              notify('项目成员已添加。')
            })}>
              <label>成员账号 ID
                <input name="userId" required inputMode="numeric" pattern="[0-9]+" maxLength={19}
                  placeholder="输入成员首次登录后显示的数字 ID" />
              </label>
              <label>项目角色
                <select name="role" defaultValue="TRANSLATOR">
                  {projectRole === 'OWNER' && <option value="ADMIN">管理员</option>}
                  <option value="TRANSLATOR">译员</option>
                  <option value="REVIEWER">审校员</option>
                  <option value="VIEWER">只读成员</option>
                </select>
              </label>
              {formError && <p className="form-error" role="alert">{formError}</p>}
              <div className="modal-actions">
                <button type="button" className="button secondary" onClick={() => setModal(null)}>关闭</button>
                <button className="button primary" type="submit">添加成员</button>
              </div>
            </form>
          ) : (
            <div className="modal-actions">
              <p className="form-hint">只有项目所有者或管理员可以添加成员。</p>
              <button type="button" className="button secondary" onClick={() => setModal(null)}>关闭</button>
            </div>
          )}
          {projectRole === 'OWNER' && (
            <form onSubmit={(event) => submit(event, async (data) => {
              const newOwnerId = String(data.get('newOwnerId'))
              await transferRemoteOwnership(project.id, newOwnerId)
              setProjectMembers(await listRemoteMembers(project.id))
              setProjectRole('ADMIN')
              notify('项目所有权已转移给新 Owner。')
            })}>
              <label>转让项目所有权
                <select name="newOwnerId" required defaultValue="">
                  <option value="" disabled>选择现有 Admin</option>
                  {projectMembers.filter((member) => member.role === 'ADMIN').map((member) =>
                    <option key={member.userId} value={member.userId}>{member.userId}</option>)}
                </select>
              </label>
              <button className="button secondary" type="submit"
                disabled={!projectMembers.some((member) => member.role === 'ADMIN')}>转让所有权</button>
            </form>
          )}
          {projectRole && projectRole !== 'OWNER' && projectActive && (
            <button type="button" className="button plain" onClick={async () => {
              if (!window.confirm('离开项目后将失去访问权限，确定继续吗？')) return
              try {
                await leaveRemoteProject(project.id)
                const projects = (await listProjects()).map(mapProject)
                setWorkspace({ version: 1, projects })
                setProjectId(projects[0]?.id ?? '')
                setModal(null)
                notify('已离开项目。')
              } catch (error) { notify(error instanceof Error ? error.message : '离开项目失败。', true) }
            }}>离开项目</button>
          )}
        </Modal>
      )}
      {modal === 'machine-translation' && project && backendEnabled && (
        <Modal title="机器翻译任务" onClose={() => setModal(null)} wide>
          <p className="modal-copy">
            对当前目标语言的未译文案生成机器翻译草稿。任务结果会重新检查源文和译文版本；人工已填写的内容不会被覆盖。
          </p>
          <div className="inline-actions">
            <span>目标语言：{languageName(locale)}（{locale}）</span>
            <button className="button primary" type="button" disabled={machineTranslationCreating}
              onClick={async () => {
                const idempotencyKey = machineTranslationRequestKey || crypto.randomUUID()
                setMachineTranslationRequestKey(idempotencyKey)
                setMachineTranslationCreating(true)
                try {
                  const job = await createMachineTranslationJob(project.id, locale, idempotencyKey)
                  setMachineTranslationRequestKey('')
                  setMachineTranslationJobId(job.id)
                  setMachineTranslationItemsPage(1)
                  notify(`机器翻译任务已提交，共 ${job.totalCount} 条。`)
                } catch (error) {
                  notify(error instanceof Error ? error.message : '机器翻译任务提交失败。', true)
                } finally { setMachineTranslationCreating(false) }
              }}>
              <Sparkles size={16} />{machineTranslationCreating ? '正在提交…' : '翻译未译文案'}
            </button>
          </div>
          <h3>最近任务</h3>
          {machineTranslationJobs.length === 0 ? (
            <p className="empty-state">还没有机器翻译任务。请先在服务端配置翻译 provider。</p>
          ) : (
            <ul className="job-list">
              {machineTranslationJobs.map((job) => (
                <li key={job.id} className={machineTranslationJobId === job.id ? 'selected' : ''}>
                  <div className="job-summary">
                    <strong>{languageName(job.locale)} · {job.status === 'QUEUED' ? '排队中' : job.status === 'RUNNING' ? '处理中' : job.status === 'COMPLETED' ? '已完成' : job.status === 'PARTIAL' ? '部分失败' : '失败'}</strong>
                    <span>成功 {job.completedCount} · 跳过 {job.skippedCount} · 失败 {job.failedCount} / {job.totalCount}</span>
                    {job.error && <small role="alert">{job.error}</small>}
                  </div>
                  <div className="job-actions">
                    <button type="button" className="button plain" onClick={() => {
                      setMachineTranslationJobId(job.id)
                      setMachineTranslationItemsPage(1)
                    }}>查看词条</button>
                    {['PARTIAL', 'FAILED'].includes(job.status) && job.failedCount > 0 && (
                      <button type="button" className="button secondary" onClick={async () => {
                        try {
                          await retryMachineTranslationJob(project.id, job.id)
                          notify('失败词条已重新排队。')
                        } catch (error) { notify(error instanceof Error ? error.message : '重试失败。', true) }
                      }}>重试失败项</button>
                    )}
                  </div>
                </li>
              ))}
            </ul>
          )}
          {machineTranslationJobId && machineTranslationItems && (
            <section className="job-items" aria-label="机器翻译词条结果">
              <h3>任务词条状态（{machineTranslationItems.total} 条）</h3>
              <ul>
                {machineTranslationItems.items.map((item) => (
                  <li key={item.entryId}>
                    <code>{item.key}</code>
                    <span>{item.status === 'QUEUED' ? '排队中' : item.status === 'RUNNING' ? '处理中' : item.status === 'COMPLETED' ? '已回填草稿' : item.status === 'SKIPPED' ? '已跳过' : '失败'}</span>
                    {item.error && <small>{item.error}</small>}
                  </li>
                ))}
              </ul>
              <div className="inline-actions">
                <button className="button plain" type="button" disabled={machineTranslationItemsPage <= 1}
                  onClick={() => setMachineTranslationItemsPage((page) => Math.max(1, page - 1))}>上一页</button>
                <span>第 {machineTranslationItemsPage} / {Math.max(1, Math.ceil(machineTranslationItems.total / machineTranslationItems.pageSize))} 页</span>
                <button className="button plain" type="button"
                  disabled={machineTranslationItemsPage * machineTranslationItems.pageSize >= machineTranslationItems.total}
                  onClick={() => setMachineTranslationItemsPage((page) => page + 1)}>下一页</button>
              </div>
            </section>
          )}
        </Modal>
      )}
      {modal === 'releases' && project && backendEnabled && (
        <Modal title={`发布历史 · ${languageName(locale)}`} onClose={() => setModal(null)} wide>
          <p className="modal-copy">每个发布版本都是不可变快照。可下载历史文件，或将旧版本内容作为新版本回滚。</p>
          {releaseJob && releaseJob.projectId === project.id && releaseJob.locale === locale && (
            <section className="release-job" aria-label="发布任务状态">
              <div>
                <strong>{releaseJob.status === 'QUEUED' ? '发布任务排队中'
                  : releaseJob.status === 'RUNNING' ? '正在检查并构建发布文件'
                    : releaseJob.status === 'COMPLETED' ? '发布完成'
                      : '发布失败'}</strong>
                <span>{releaseJob.format === 'java-properties-v1' ? 'Java Properties' : 'JSON'} · 最近更新 {new Date(releaseJob.updatedAt).toLocaleString()}</span>
                {(releaseJob.error || releaseJobError) && <p role="alert">{releaseJob.error || releaseJobError}</p>}
              </div>
              <div className="inline-actions">
                {releaseJob.status === 'FAILED' && projectActive && canManageProject && (
                  <button className="button secondary" type="button" onClick={async () => {
                    try {
                      setReleaseJob(await retryRemoteReleaseJob(project.id, releaseJob.id))
                      setReleaseJobError('')
                      notify('发布任务已重新排队。')
                    } catch (error) { notify(error instanceof Error ? error.message : '发布重试失败。', true) }
                  }}>重试</button>
                )}
                {releaseJob.status === 'COMPLETED' && (
                  <button className="button secondary" type="button" onClick={async () => {
                    try {
                      const { release, content, extension } = await downloadRemoteReleaseJobResult(project.id, releaseJob)
                      const link = document.createElement('a')
                      link.href = URL.createObjectURL(new Blob([content], {
                        type: extension === 'properties' ? 'text/plain' : 'application/json',
                      }))
                      link.download = `yandu-${release.projectId}-${release.locale}-v${release.version}.${extension}`
                      link.click(); setTimeout(() => URL.revokeObjectURL(link.href), 1000)
                    } catch (error) { notify(error instanceof Error ? error.message : '发布文件下载失败。', true) }
                  }}><ArrowDownToLine size={14} />下载发布文件</button>
                )}
              </div>
            </section>
          )}
          {releaseHistoryLoading ? <p>正在读取发布历史…</p> : releaseHistory.length === 0 ? (
            <div className="empty-state"><FileText size={30} /><p>当前语言还没有发布版本。</p></div>
          ) : (
            <ul className="release-list">
              {releaseHistory.map((release) => (
                <li key={release.id}>
                  <div className="release-details">
                    <strong>v{release.version} · {release.format === 'java-properties-v1' ? 'Java Properties' : 'JSON'}</strong>
                    <span>{release.entryCount} 条 · {new Date(release.publishedAt).toLocaleString()} · 发布者 {release.publishedBy}</span>
                    <code title={release.checksumSha256}>SHA-256 {release.checksumSha256}</code>
                    {release.rollbackOfReleaseId && <small>由版本 ID {release.rollbackOfReleaseId} 回滚创建</small>}
                  </div>
                  <div className="release-actions">
                    <button className="button plain" onClick={async () => {
                      try {
                        const { content, extension } = await downloadRemoteReleaseSnapshot(project.id, release)
                        const link = document.createElement('a')
                        link.href = URL.createObjectURL(new Blob([content], {
                          type: extension === 'properties' ? 'text/plain' : 'application/json',
                        }))
                        link.download = `yandu-${project.id}-${release.locale}-v${release.version}.${extension}`
                        link.click(); setTimeout(() => URL.revokeObjectURL(link.href), 1000)
                      } catch (error) { notify(error instanceof Error ? error.message : '历史版本下载失败。', true) }
                    }}><ArrowDownToLine size={14} />下载</button>
                    {canManageProject && projectActive && <button className="button plain" onClick={async () => {
                      if (!window.confirm(`将 v${release.version} 的内容回滚为新的发布版本？`)) return
                      try {
                        await rollbackRemoteRelease(project.id, release.id)
                        setReleaseHistory(await listRemoteReleases(project.id, locale))
                        notify(`已从 v${release.version} 创建新的回滚版本。`)
                      } catch (error) { notify(error instanceof Error ? error.message : '回滚失败。', true) }
                    }}>回滚为新版本</button>}
                  </div>
                </li>
              ))}
            </ul>
          )}
          <div className="modal-actions"><button className="button secondary" onClick={() => setModal(null)}>关闭</button></div>
        </Modal>
      )}
      {modal === 'glossary' && project && backendEnabled && (
        <Modal title={`术语库 · ${locale}`} onClose={() => setModal(null)} wide>
          {glossaryLoading ? <p>正在读取项目术语…</p> : glossaryTerms.length ? (
            <ul className="glossary-list">
              {glossaryTerms.map((term) => (
                <li key={term.id}>
                  <div><strong>{term.sourceTerm}</strong><span>{term.targetTerm}</span>
                    {term.note && <small>{term.note}</small>}</div>
                  {canManageGlossary && <button type="button" className="button plain"
                    onClick={() => {
                      if (!window.confirm(`归档术语「${term.sourceTerm}」？`)) return
                      void archiveRemoteGlossaryTerm(project.id, term.id).then(() => {
                        setGlossaryTerms((current) => current.filter((item) => item.id !== term.id))
                        notify('术语已归档。')
                      }).catch((error: unknown) => notify(error instanceof Error ? error.message : '归档失败。', true))
                    }}>归档</button>}
                </li>
              ))}
            </ul>
          ) : <p className="empty-glossary">该语言还没有术语。</p>}
          {canManageGlossary && (
            <form className="glossary-form" onSubmit={(event) => {
              event.preventDefault()
              const form = event.currentTarget
              const data = new FormData(form)
              void addRemoteGlossaryTerm(project.id, project.sourceLanguage, locale,
                String(data.get('sourceTerm')).trim(), String(data.get('targetTerm')).trim(),
                String(data.get('note')).trim())
                .then((term) => {
                  setGlossaryTerms((current) => [...current, term].sort((a, b) => a.sourceTerm.localeCompare(b.sourceTerm)))
                  form.reset()
                  setFormError('')
                  notify('术语已添加。')
                }).catch((error: unknown) => setFormError(error instanceof Error ? error.message : '术语添加失败。'))
            }}>
              <h3>添加术语</h3>
              <div className="glossary-fields">
                <label>源术语 · {project.sourceLanguage}<input name="sourceTerm" required maxLength={200} /></label>
                <label>目标术语 · {locale}<input name="targetTerm" required maxLength={500} /></label>
              </div>
              <label>使用说明<input name="note" maxLength={1000} /></label>
              {formError && <p className="form-error" role="alert">{formError}</p>}
              <button className="button primary" type="submit">添加术语</button>
            </form>
          )}
          <div className="modal-actions">
            <button type="button" className="button secondary" onClick={() => setModal(null)}>关闭</button>
          </div>
        </Modal>
      )}
      {modal === 'migration' && (
        <Modal title="迁移本地项目到团队工作空间" onClose={() => setModal(null)}>
          <div className="form-stack">
            <label>
              本地项目
              <select value={localProjectId} disabled={Boolean(migrationPreview && !migrationPreview.preview.nameCollision)} onChange={(event) => {
                const selected = localProjects.find((item) => item.id === event.target.value)
                setLocalProjectId(event.target.value)
                setMigrationName(selected?.name ?? '')
                setMigrationPreview(null)
              }}>
                {localProjects.map((item) => <option key={item.id} value={item.id}>{item.name} · {item.entries.length} 条</option>)}
              </select>
            </label>
            <label>团队项目名称<input value={migrationName} maxLength={120}
              disabled={Boolean(migrationPreview && !migrationPreview.preview.nameCollision)}
              onChange={(event) => { setMigrationName(event.target.value); if (migrationPreview?.preview.nameCollision) setMigrationPreview(null) }} /></label>
            <p className="form-hint">迁移会复制源文案和译文，译文状态统一转为草稿。团队项目使用新的服务端 ID，本地数据会保留。</p>
            {migrationPreview && (
              <div className="import-preview" aria-live="polite">
                <p>状态：{migrationPreview.status} · 已迁移 {migrationPreview.completedEntries} / {migrationPreview.preview.entryCount} 条</p>
                {migrationPreview.preview.nameCollision && <p className="form-error">该名称已存在，请返回修改名称后重新预览。</p>}
                {migrationPreview.error && <p className="form-error">{migrationPreview.error}</p>}
                {migrationPreview.projectId && <p>团队项目 ID：<code>{migrationPreview.projectId}</code></p>}
              </div>
            )}
            {formError && <p className="form-error" role="alert">{formError}</p>}
            <div className="modal-actions">
              {!migrationPreview ? (
                <button className="button primary" disabled={!localMigrationProject || migrationBusy || !migrationName.trim()}
                  onClick={async () => {
                    if (!localMigrationProject) return
                    setMigrationBusy(true); setFormError('')
                    try {
                      const packageJson = JSON.stringify({
                        format: 'yandu-project-migration', schemaVersion: 1,
                        sourceProjectId: localMigrationProject.id,
                        project: { name: localMigrationProject.name, description: localMigrationProject.description,
                          sourceLanguage: localMigrationProject.sourceLanguage, targetLanguages: localMigrationProject.targetLanguages },
                        entries: localMigrationProject.entries.map((entry) => ({ id: entry.id, key: entry.key,
                          source: entry.source, translations: entry.translations })),
                      })
                      setMigrationPreview(await previewProjectMigration(packageJson, migrationName.trim()))
                    } catch (error) { setFormError(error instanceof Error ? error.message : '迁移预览失败。') }
                    finally { setMigrationBusy(false) }
                  }}>{migrationBusy ? '正在预览…' : '预览迁移'}</button>
              ) : migrationPreview.status === 'PREVIEW_READY' || migrationPreview.status === 'FAILED' ? (
                <button className="button primary" disabled={migrationBusy || migrationPreview.preview.nameCollision}
                  onClick={async () => {
                    setMigrationBusy(true); setFormError('')
                    try { setMigrationPreview(await applyProjectMigration(migrationPreview.id, migrationPreview.previewHash)) }
                    catch (error) { setFormError(error instanceof Error ? error.message : '提交迁移失败。') }
                    finally { setMigrationBusy(false) }
                  }}>{migrationPreview.status === 'FAILED' ? '使用相同数据重试' : '确认并开始迁移'}</button>
              ) : null}
              <button type="button" className="button secondary" onClick={() => setModal(null)}>关闭</button>
            </div>
          </div>
        </Modal>
      )}
      {modal === 'import' && project && (
        <Modal title="导入 JSON 文案" onClose={() => setModal(null)} wide={backendEnabled}>
          <form
            onSubmit={(e) => {
              e.preventDefault()
              void (async () => {
                try {
                  if (backendEnabled) {
                    if (!importPreview) {
                      const preview = await previewRemoteImport(project.id, project, importLocale, importText, activeNamespaceId)
                      setImportPreview(preview)
                      setFormError('')
                      notify('差异预览已生成，请检查后确认导入。')
                      return
                    }
                    if (importPreview.preview.conflicts) {
                      throw new Error('预览中包含冲突项，请先修正 JSON 内容或源词条。')
                    }
                    await applyRemoteImport(project.id, importPreview.id, importPreview.previewHash)
                    setRemoteRefresh((value) => value + 1)
                    notify(`已提交 ${importPreview.preview.added + importPreview.preview.updated} 条导入任务，后台正在处理。`)
                    setModal(null)
                    void waitForRemoteImport(project.id, importPreview.id).then((job) => {
                      setRemoteRefresh((value) => value + 1)
                      if (job.status === 'COMPLETED') notify('JSON 导入任务已完成。')
                      else if (job.status === 'FAILED') notify(job.error || 'JSON 导入失败。', true)
                      else notify('导入仍在后台处理，可稍后刷新查看。')
                    }).catch((error: unknown) => {
                      notify(error instanceof Error ? error.message : '无法查询导入任务状态。', true)
                    })
                  } else {
                    const result = importMessages(project, importLocale, importText)
                    updateProject(result.project)
                    notify(`已导入 ${result.count} 条${languageName(importLocale)}文案。`)
                    setModal(null)
                  }
                } catch (error) {
                  setFormError(error instanceof Error ? error.message : '导入失败，请重试。')
                }
              })()
            }}
          >
            <label>
              导入语言
              <select value={importLocale} onChange={(e) => { setImportLocale(e.target.value); setImportPreview(null) }}>
                <option value={project.sourceLanguage}>
                  {languageName(project.sourceLanguage)} · 源语言
                </option>
                {project.targetLanguages.map((code) => (
                  <option key={code} value={code}>
                    {languageName(code)} · {code}
                  </option>
                ))}
              </select>
            </label>
            <label className="file-picker">
              <ArrowUpFromLine size={24} />
              <strong>{importFile || '选择 JSON 文件'}</strong>
              <span>扁平字符串 JSON · 最大 1 MB</span>
              <input
                aria-label="选择 JSON 文件"
                type="file"
                accept=".json,application/json"
                required
                onChange={async (e) => {
                  const file = e.target.files?.[0]
                  setImportText('')
                  setImportFile('')
                  setImportPreview(null)
                  setFormError('')
                  if (!file) return
                  if (file.size > MAX_IMPORT_BYTES) {
                    setFormError('JSON 文件不能超过 1 MB。')
                    return
                  }
                  try {
                    const text = await file.text()
                    setImportText(text)
                    setImportFile(file.name)
                  } catch {
                    setFormError('文件读取失败，请重试。')
                  }
                }}
              />
            </label>
            <p className="form-hint">
              格式示例：<code>{'{"home.title": "Welcome"}'}</code>
              <br />
              同名键会更新所选语言，其他键与语言保留。目标语言中新键的源文案留空；更改源文案会将已有译文转为待审核。请先导出备份。
            </p>
            {backendEnabled && importPreview && (
              <div className="import-preview" aria-live="polite">
                <strong>导入差异预览</strong>
                <span>新增 {importPreview.preview.added} 条 · 更新 {importPreview.preview.updated} 条 · 未变化 {importPreview.preview.unchanged} 条 · 冲突 {importPreview.preview.conflicts} 条</span>
                <ul>
                  {importPreview.preview.items.slice(0, 30).map((item) => (
                    <li key={item.key}>
                      <code>{item.key}</code> · {item.action}
                      {item.action === 'UPDATE' && <span>：{item.currentValue ?? '（空）'} → {item.incomingValue}</span>}
                      {item.message && <span>：{item.message}</span>}
                    </li>
                  ))}
                </ul>
                {importPreview.preview.items.length > 30 && <small>仅展示前 30 条，共 {importPreview.preview.items.length} 条。</small>}
                <small>预览基于当前项目版本；项目数据变化后，服务端会拒绝过期预览。</small>
              </div>
            )}
            {formError && (
              <p className="form-error" role="alert">
                {formError}
              </p>
            )}
            <div className="modal-actions">
              <button type="button" className="button secondary" onClick={() => setModal(null)}>
                取消
              </button>
              <button className="button primary" type="submit" disabled={!importText || Boolean(backendEnabled && importPreview?.preview.conflicts)}>
                {backendEnabled ? importPreview ? '确认导入并处理' : '预览导入差异' : '确认导入'}
              </button>
            </div>
          </form>
        </Modal>
      )}
      {editing && project && (
        <TranslationEditor
          key={`${editing.id}-${locale}`}
          entry={editing}
          project={project}
          locale={locale}
          backendMode={backendEnabled}
          canReview={['OWNER', 'ADMIN', 'REVIEWER'].includes(projectRole ?? '')}
          onClose={() => setEditingId(null)}
          onSave={async (value, reviewed) => {
            try {
              if (backendEnabled) {
                const saved = await saveRemoteTranslation(project.id, editing, locale, value)
                if (reviewed) await submitRemoteTranslation(project.id, editing.id, locale, saved.version)
                setRemoteRefresh((count) => count + 1)
              } else updateProject(saveTranslation(project, editing.id, locale, value, reviewed))
              setEditingId(null)
              notify(reviewed && backendEnabled ? '译文已保存并提交审核。' : '译文已保存。')
            } catch (error) {
              notify(error instanceof Error ? error.message : '保存失败。', true)
              throw error
            }
          }}
          onApprove={async () => {
            try {
              await approveRemoteTranslation(project.id, editing.id, locale, editing.versions?.[locale] ?? 0)
              setRemoteRefresh((count) => count + 1)
              setEditingId(null)
              notify('审核已通过。')
            } catch (error) {
              notify(error instanceof Error ? error.message : '审核失败。', true)
            }
          }}
          onRequestChanges={async (reason) => {
            try {
              await reviewRemoteTranslation(project.id, editing.id, locale,
                editing.versions?.[locale] ?? 0, 'REQUEST_CHANGES', reason)
              setRemoteRefresh((count) => count + 1)
              setEditingId(null)
              notify('译文已退回修改，并记录了审核原因。')
            } catch (error) {
              notify(error instanceof Error ? error.message : '退回译文失败。', true)
            }
          }}
        />
      )}
      {sourceEditing && project && (
        <Modal title="编辑源文案" onClose={() => setSourceEditingId(null)} wide>
          <div className="editor-key"><code>{sourceEditing.key}</code></div>
          <label>
            源文案 · {languageName(project.sourceLanguage)}
            <textarea autoFocus rows={5} maxLength={16000} value={sourceText}
              onChange={(event) => setSourceText(event.target.value)} />
          </label>
          <p className="form-hint">
            当前源版本：{sourceEditing.sourceRevision ?? 1}。保存后，已有译文将转为待复核。
          </p>
          {backendEnabled && (
            <section className="source-history" aria-label="源文案历史">
              <h3>历史版本</h3>
              {sourceHistoryLoading ? <p>正在读取历史…</p> : sourceHistory.length ? (
                <ol>
                  {sourceHistory.map((revision) => (
                    <li key={revision.id}>
                      <div><strong>版本 {revision.sourceRevision}</strong><time>{new Date(revision.createdAt).toLocaleString()}</time></div>
                      <p>{revision.sourceText}</p>
                    </li>
                  ))}
                </ol>
              ) : <p>暂无历史版本。</p>}
            </section>
          )}
          <div className="modal-actions">
            <button type="button" className="button secondary" onClick={() => setSourceEditingId(null)}>取消</button>
            <button type="button" className="button primary" onClick={() => void saveSource()}>保存源文案</button>
          </div>
        </Modal>
      )}
    </div>
  )
}
