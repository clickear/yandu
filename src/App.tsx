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
  backendConfigurationError,
  backendEnabled,
  createRemoteProject,
  downloadRemoteRelease,
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
} from './backend'

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
}: {
  entry: Entry
  project: Project
  locale: string
  onSave: (value: string, reviewed: boolean) => void | Promise<void>
  onClose: () => void
  backendMode?: boolean
  canReview?: boolean
  onApprove?: () => void | Promise<void>
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
  useEffect(() => {
    if (!backendMode) return
    let active = true
    void loadMemorySuggestions(project.id, entry.id, locale)
      .then((matches) => { if (active) setSuggestions(matches) })
      .catch(() => { if (active) setSuggestions({ memory: [], terms: [] }) })
    return () => { active = false }
  }, [backendMode, project.id, entry.id, locale])
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
            <button type="button" className="button secondary" disabled={dirty} onClick={() => void onApprove?.()}>
              审核通过
            </button>
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
  const [query, setQuery] = useState('')
  const [filter, setFilter] = useState('all')
  const [modal, setModal] = useState<'project' | 'entry' | 'language' | 'import' | 'members' | 'glossary' | null>(null)
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
  const [glossaryTerms, setGlossaryTerms] = useState<Awaited<ReturnType<typeof listRemoteGlossary>>['items']>([])
  const [glossaryLoading, setGlossaryLoading] = useState(false)
  const project = workspace.projects.find((p) => p.id === projectId)
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
    if (!backendEnabled || authStatus !== 'ready' || !project) return
    let active = true
    setRemoteLoading(true)
    void loadTranslationPage(project, locale, query.trim(), filter, remotePage).then((result) => {
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
  }, [authStatus, projectId, locale, query, filter, remotePage, remoteRefresh])

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
    setImportLocale(locale)
    setModal(next)
  }
  const chooseProject = (p: Project) => {
    setProjectId(p.id)
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
      void downloadRemoteRelease(project.id, locale, format).then(({ content }) => {
        const link = document.createElement('a')
        link.href = URL.createObjectURL(new Blob([content], { type: extension === 'properties' ? 'text/plain' : 'application/json' }))
        link.download = `yandu-${project.id}-${locale}.${extension}`
        link.click()
        setTimeout(() => URL.revokeObjectURL(link.href), 1000)
        notify(`已发布并下载 ${languageName(locale)} 的 ${extension} 快照。`)
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
  const canManageCatalog = !backendEnabled || ['OWNER', 'ADMIN', 'TRANSLATOR'].includes(projectRole ?? '')
  const canManageMembers = ['OWNER', 'ADMIN'].includes(projectRole ?? '')
  const canManageGlossary = ['OWNER', 'ADMIN'].includes(projectRole ?? '')

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
                      <button className="button secondary" onClick={() => openModal('glossary')}>
                        术语库
                      </button>
                      <button className="button secondary" onClick={() => openModal('members')}>
                        项目成员
                      </button>
                    </>
                  )}
                </div>
              </section>
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
                    <option value="draft">待审核</option>
                    <option value="reviewed">已审核</option>
                  </select>
                  <div className="toolbar-actions">
                    <button className="button plain" onClick={() => openModal('import')}>
                      <ArrowUpFromLine size={16} />
                      导入 JSON
                    </button>
                    <button className="button plain" onClick={() => download('json-flat-v1')}>
                      <ArrowDownToLine size={16} />
                      导出 JSON
                    </button>
                    <button className="button plain" onClick={() => download('java-properties-v1')}>
                      <ArrowDownToLine size={16} />
                      导出 Properties
                    </button>
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
                        const status = statusOf(entry, locale)
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
                                {status === 'reviewed'
                                  ? '已审核'
                                  : status === 'draft'
                                    ? '待审核'
                                    : '未翻译'}
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
                  ? mapProject(await createRemoteProject(String(data.get('name')), String(data.get('source'))))
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
            {!backendEnabled && (
              <label>
                项目描述
                <input name="description" maxLength={200} placeholder="这个项目想向世界表达什么？" />
              </label>
            )}
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
                  await addRemoteEntry(project, key, source)
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
                  <option value="ADMIN">管理员</option>
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
      {modal === 'import' && project && (
        <Modal title="导入 JSON 文案" onClose={() => setModal(null)} wide={backendEnabled}>
          <form
            onSubmit={(e) => {
              e.preventDefault()
              void (async () => {
                try {
                  if (backendEnabled) {
                    if (!importPreview) {
                      const preview = await previewRemoteImport(project.id, project, importLocale, importText)
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
