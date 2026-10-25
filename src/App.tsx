import { useEffect, useRef, useState } from 'react'
import type { FormEvent, ReactNode } from 'react'
import {
  ArrowDownToLine,
  ArrowUpFromLine,
  ArrowUpRight,
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
} from './model'
import type { Entry, Project, Workspace } from './model'

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
}: {
  entry: Entry
  project: Project
  locale: string
  onSave: (value: string, reviewed: boolean) => void
  onClose: () => void
}) {
  const initial = entry.translations[locale]
  const [value, setValue] = useState(initial?.value ?? '')
  const [reviewed, setReviewed] = useState(initial?.status === 'reviewed')
  const dirty = value !== (initial?.value ?? '') || reviewed !== (initial?.status === 'reviewed')
  const close = () => {
    if (!dirty || window.confirm('译文尚未保存，确定放弃修改？')) onClose()
  }
  return (
    <Modal title="编辑译文" onClose={close} wide>
      <form
        onSubmit={(e) => {
          e.preventDefault()
          onSave(value, reviewed)
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
              }}
              placeholder="让表达跨越语言…"
            />
          </label>
        </div>
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
        <div className="modal-actions">
          <button type="button" className="button secondary" onClick={close}>
            取消
          </button>
          <button className="button primary" type="submit">
            <Check size={16} />
            保存译文
          </button>
        </div>
      </form>
    </Modal>
  )
}

export default function App() {
  const [initial] = useState(readInitial)
  const [workspace, setWorkspace] = useState<Workspace>(initial.workspace)
  const [storageWarning, setStorageWarning] = useState(initial.warning)
  const [storageBlocked, setStorageBlocked] = useState(Boolean(initial.warning))
  const [projectId, setProjectId] = useState(workspace.projects[0]?.id ?? '')
  const [locale, setLocale] = useState(workspace.projects[0]?.targetLanguages[0] ?? 'en-US')
  const [query, setQuery] = useState('')
  const [filter, setFilter] = useState('all')
  const [modal, setModal] = useState<'project' | 'entry' | 'language' | 'import' | null>(null)
  const [editingId, setEditingId] = useState<string | null>(null)
  const [feedback, setFeedback] = useState<{ text: string; error: boolean } | null>(null)
  const [formError, setFormError] = useState('')
  const [importText, setImportText] = useState('')
  const [importLocale, setImportLocale] = useState(locale)
  const [importFile, setImportFile] = useState('')
  const project = workspace.projects.find((p) => p.id === projectId)
  const editing = project?.entries.find((e) => e.id === editingId)
  const stats = project
    ? progress(project, locale)
    : { total: 0, translated: 0, reviewed: 0, percentage: 0 }

  useEffect(() => {
    if (storageBlocked) return
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
    setImportLocale(locale)
    setModal(next)
  }
  const chooseProject = (p: Project) => {
    setProjectId(p.id)
    setLocale(p.targetLanguages[0])
    setQuery('')
    setFilter('all')
    setEditingId(null)
  }
  const submit = (event: FormEvent<HTMLFormElement>, action: (data: FormData) => void) => {
    event.preventDefault()
    try {
      action(new FormData(event.currentTarget))
      setModal(null)
      setFormError('')
    } catch (error) {
      setFormError(error instanceof Error ? error.message : '操作失败，请重试。')
    }
  }
  const download = () => {
    if (!project) return
    const url = URL.createObjectURL(
      new Blob([exportMessages(project, locale)], { type: 'application/json' }),
    )
    const link = document.createElement('a')
    link.href = url
    link.download = `yandu-${project.id}-${locale}.json`
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
    notify(`已导出 ${languageName(locale)} 文案；未翻译条目保留为空字符串。`)
  }
  const visible =
    project?.entries.filter((entry) => {
      const search = query.trim().toLowerCase()
      const matches = [entry.key, entry.source, entry.translations[locale]?.value ?? ''].some(
        (value) => value.toLowerCase().includes(search),
      )
      return matches && (filter === 'all' || statusOf(entry, locale) === filter)
    }) ?? []
  const availableLanguages = LANGUAGES.filter(
    (l) => l.code !== project?.sourceLanguage && !project?.targetLanguages.includes(l.code),
  )

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
            <span>个人 · 本地原型</span>
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
            {storageBlocked ? '仅内存 · 请导出备份' : '文案保存在此浏览器'}
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
          <span className="prototype-pill">本地原型</span>
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
                <span className="source-language">
                  <Globe2 size={14} />
                  源语言：{languageName(project.sourceLanguage)}
                </span>
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
                            setFilter('all')
                            setQuery('')
                          }}
                          aria-pressed={locale === code}
                        >
                          <span className="language-avatar">{languageShort(code)}</span>
                          <span>{languageName(code)}</span>
                          <small>{p.percentage}%</small>
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
                      onChange={(e) => setQuery(e.target.value)}
                    />
                  </label>
                  <select
                    aria-label="按状态筛选"
                    value={filter}
                    onChange={(e) => setFilter(e.target.value)}
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
                    <button className="button plain" onClick={download}>
                      <ArrowDownToLine size={16} />
                      导出 JSON
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
                            <td className="row-index">{String(index + 1).padStart(2, '0')}</td>
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
                            <td>
                              <button
                                className="edit-button"
                                aria-label={`编辑 ${entry.key}`}
                                onClick={() => setEditingId(entry.id)}
                              >
                                编辑
                                <ChevronRight size={13} />
                              </button>
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
                    <h3>{project.entries.length ? '没有找到匹配的文案' : '从第一条文案开始'}</h3>
                    <p>
                      {project.entries.length
                        ? '换个关键词，或调整状态筛选。'
                        : '添加一条源文案，或导入已有的 JSON 语言文件。'}
                    </p>
                    {!project.entries.length && (
                      <button className="button secondary" onClick={() => openModal('entry')}>
                        添加文案
                      </button>
                    )}
                  </div>
                )}
                <div className="table-footer">
                  <span>
                    显示 {visible.length} 条，共 {project.entries.length} 条文案
                  </span>
                  <span>
                    <span className="dot-green" />
                    {storageBlocked ? '仅保存在当前页面，请导出备份' : '自动保存至此浏览器'}
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
              submit(e, (data) => {
                const p = createProject(
                  String(data.get('name')),
                  String(data.get('description')),
                  String(data.get('source')),
                )
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
              <input name="description" maxLength={200} placeholder="这个项目想向世界表达什么？" />
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
              submit(e, (data) => {
                updateProject(
                  addEntry(project, String(data.get('key')), String(data.get('source'))),
                )
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
              submit(e, (data) => {
                const next = String(data.get('locale'))
                updateProject(addLanguage(project, next))
                setLocale(next)
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
      {modal === 'import' && project && (
        <Modal title="导入 JSON 文案" onClose={() => setModal(null)}>
          <form
            onSubmit={(e) =>
              submit(e, () => {
                const result = importMessages(project, importLocale, importText)
                updateProject(result.project)
                notify(`已导入 ${result.count} 条${languageName(importLocale)}文案。`)
              })
            }
          >
            <label>
              导入语言
              <select value={importLocale} onChange={(e) => setImportLocale(e.target.value)}>
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
            {formError && (
              <p className="form-error" role="alert">
                {formError}
              </p>
            )}
            <div className="modal-actions">
              <button type="button" className="button secondary" onClick={() => setModal(null)}>
                取消
              </button>
              <button className="button primary" type="submit" disabled={!importText}>
                确认导入
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
          onClose={() => setEditingId(null)}
          onSave={(value, reviewed) => {
            try {
              updateProject(saveTranslation(project, editing.id, locale, value, reviewed))
              setEditingId(null)
              notify('译文已保存。')
            } catch (error) {
              notify(error instanceof Error ? error.message : '保存失败。', true)
            }
          }}
        />
      )}
    </div>
  )
}
