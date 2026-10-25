export const LANGUAGES = [
  { code: 'zh-CN', name: '简体中文', short: '中' },
  { code: 'en-US', name: 'English', short: 'EN' },
  { code: 'ja-JP', name: '日本語', short: '日' },
  { code: 'fr-FR', name: 'Français', short: 'FR' },
  { code: 'de-DE', name: 'Deutsch', short: 'DE' },
  { code: 'es-ES', name: 'Español', short: 'ES' },
  { code: 'zh-TW', name: '繁體中文', short: '繁' },
] as const
export type TranslationStatus = 'draft' | 'reviewed'
export interface Translation {
  value: string
  status: TranslationStatus
}
export interface Entry {
  id: string
  key: string
  source: string
  translations: Record<string, Translation>
  sourceRevision?: number
  versions?: Record<string, number | null>
  workflowStatuses?: Record<string, string | null>
}
export interface Project {
  id: string
  name: string
  description: string
  sourceLanguage: string
  targetLanguages: string[]
  entries: Entry[]
  defaultNamespaceId?: string
}
export interface Workspace {
  version: 1
  projects: Project[]
}
export const STORAGE_KEY = 'yandu.workspace.v1'
export const MAX_IMPORT_BYTES = 1024 * 1024
export const languageName = (code: string) => LANGUAGES.find((l) => l.code === code)?.name ?? code
export const languageShort = (code: string) => LANGUAGES.find((l) => l.code === code)?.short ?? code
const localWorkerId = crypto.getRandomValues(new Uint16Array(1))[0] & 0x3ff
const localEpoch = Date.UTC(2024, 0, 1)
let lastLocalTimestamp = 0
let localSequence = 0
export const uid = () => {
  let timestamp = Math.max(Date.now() - localEpoch, lastLocalTimestamp)
  localSequence = timestamp === lastLocalTimestamp ? localSequence + 1 : 0
  if (localSequence > 0xfff) {
    timestamp = lastLocalTimestamp + 1
    localSequence = 0
  }
  lastLocalTimestamp = timestamp
  return ((BigInt(timestamp) << 22n) | (BigInt(localWorkerId) << 12n) | BigInt(localSequence)).toString()
}
const validLocale = (locale: unknown): locale is string =>
  typeof locale === 'string' && LANGUAGES.some((l) => l.code === locale)
const isObject = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)

export function validateKey(key: string): string {
  const cleaned = key.trim()
  if (!cleaned || cleaned.length > 200) throw new Error('键名需为 1–200 个字符。')
  if (cleaned.split('.').some((part) => ['__proto__', 'prototype', 'constructor'].includes(part)))
    throw new Error('键名包含不支持的保留字段。')
  return cleaned
}

export function createProject(name: string, description = '', sourceLanguage = 'zh-CN'): Project {
  if (!name.trim() || name.trim().length > 80) throw new Error('项目名称需为 1–80 个字符。')
  if (!validLocale(sourceLanguage)) throw new Error('请选择支持的源语言。')
  return {
    id: uid(),
    name: name.trim(),
    description: description.trim(),
    sourceLanguage,
    targetLanguages: [sourceLanguage === 'en-US' ? 'zh-CN' : 'en-US'],
    entries: [],
  }
}

export function addLanguage(project: Project, locale: string): Project {
  if (
    !validLocale(locale) ||
    locale === project.sourceLanguage ||
    project.targetLanguages.includes(locale)
  )
    throw new Error('请选择尚未添加的目标语言。')
  return { ...project, targetLanguages: [...project.targetLanguages, locale] }
}

export function addEntry(project: Project, key: string, source: string): Project {
  key = validateKey(key)
  if (!source.trim()) throw new Error('请填写源文案。')
  if (project.entries.some((entry) => entry.key === key))
    throw new Error('键名已存在，请使用不同的键名。')
  return { ...project, entries: [{ id: uid(), key, source, translations: {} }, ...project.entries] }
}

export function updateSource(project: Project, entryId: string, source: string): Project {
  if (!source.trim()) throw new Error('请填写源文案。')
  if (source.length > 16000) throw new Error('源文案不能超过 16,000 个字符。')
  const entry = project.entries.find((item) => item.id === entryId)
  if (!entry) throw new Error('翻译条目不存在。')
  if (entry.source === source) return project
  return {
    ...project,
    entries: project.entries.map((item) => item.id !== entryId ? item : {
      ...item,
      source,
      sourceRevision: (item.sourceRevision ?? 1) + 1,
      translations: Object.fromEntries(Object.entries(item.translations).map(([locale, translation]) => [
        locale, { ...translation, status: 'draft' as const },
      ])),
    }),
  }
}

export function saveTranslation(
  project: Project,
  entryId: string,
  locale: string,
  value: string,
  reviewed: boolean,
): Project {
  if (!project.targetLanguages.includes(locale)) throw new Error('目标语言不存在。')
  if (!project.entries.some((e) => e.id === entryId)) throw new Error('翻译条目不存在。')
  if (reviewed && !value.trim()) throw new Error('空译文不能标记为已审核。')
  return {
    ...project,
    entries: project.entries.map((entry) =>
      entry.id !== entryId
        ? entry
        : {
            ...entry,
            translations: {
              ...entry.translations,
              [locale]: { value, status: reviewed ? 'reviewed' : 'draft' },
            },
          },
    ),
  }
}

export function statusOf(entry: Entry, locale: string): 'untranslated' | 'draft' | 'reviewed' {
  const translation = entry.translations[locale]
  return !translation?.value.trim() ? 'untranslated' : translation.status
}

export function progress(project: Project, locale: string) {
  const total = project.entries.length
  const translated = project.entries.filter((e) => statusOf(e, locale) !== 'untranslated').length
  const reviewed = project.entries.filter((e) => statusOf(e, locale) === 'reviewed').length
  return {
    total,
    translated,
    reviewed,
    percentage: total ? Math.round((translated / total) * 100) : 0,
  }
}

/** Flat, string-valued JSON only. Validation completes before any project mutation. */
export function importMessages(
  project: Project,
  locale: string,
  text: string,
): { project: Project; count: number } {
  if (new TextEncoder().encode(text).length > MAX_IMPORT_BYTES)
    throw new Error('JSON 文件不能超过 1 MB。')
  if (locale !== project.sourceLanguage && !project.targetLanguages.includes(locale))
    throw new Error('导入语言不存在。')
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch {
    throw new Error('JSON 格式无效，请检查文件。')
  }
  if (!isObject(parsed)) throw new Error('请导入扁平 JSON 对象，例如 {"home.title":"欢迎"}。')
  const messages = Object.entries(parsed).map(([rawKey, value]) => {
    const key = validateKey(rawKey)
    if (key !== rawKey) throw new Error('键名首尾不能包含空白。')
    if (typeof value !== 'string')
      throw new Error(`「${key}」的值必须是字符串；暂不支持嵌套 JSON。`)
    return [key, value] as const
  })
  if (!messages.length) throw new Error('JSON 中没有可导入的条目。')
  if (new Set([...project.entries.map((e) => e.key), ...messages.map(([key]) => key)]).size > 10000)
    throw new Error('单个项目最多支持 10,000 条文案。')
  const entries = project.entries.map((entry) => ({
    ...entry,
    translations: { ...entry.translations },
  }))
  for (const [key, value] of messages) {
    let entry = entries.find((item) => item.key === key)
    if (!entry) {
      entry = { id: uid(), key, source: '', translations: {} }
      entries.push(entry)
    }
    if (locale === project.sourceLanguage) {
      if (entry.source !== value) {
        entry.source = value
        for (const [lang, translation] of Object.entries(entry.translations))
          entry.translations[lang] = { ...translation, status: 'draft' }
      }
    } else if (entry.translations[locale]?.value !== value) {
      entry.translations[locale] = { value, status: 'draft' }
    }
  }
  return { project: { ...project, entries }, count: messages.length }
}

export function exportMessages(project: Project, locale: string): string {
  if (locale !== project.sourceLanguage && !project.targetLanguages.includes(locale))
    throw new Error('导出语言不存在。')
  return (
    JSON.stringify(
      Object.fromEntries(
        project.entries.map((entry) => [
          entry.key,
          locale === project.sourceLanguage
            ? entry.source
            : (entry.translations[locale]?.value ?? ''),
        ]),
      ),
      null,
      2,
    ) + '\n'
  )
}

export function exportProperties(project: Project, locale: string): string {
  if (locale !== project.sourceLanguage && !project.targetLanguages.includes(locale))
    throw new Error('导出语言不存在。')
  const escape = (value: string, key: boolean) => {
    let result = ''
    let leadingValueSpace = !key
    for (let i = 0; i < value.length; i += 1) {
      const char = value[i]
      if (char === ' ' && (key || leadingValueSpace)) result += '\\ '
      else if (char === '\\') result += '\\\\'
      else if (char === '\t') result += '\\t'
      else if (char === '\n') result += '\\n'
      else if (char === '\r') result += '\\r'
      else if (char === '\f') result += '\\f'
      else if (key && '=:# !'.includes(char)) result += `\\${char}`
      else {
        const code = char.charCodeAt(0)
        result += code < 0x20 || code > 0x7e
          ? `\\u${code.toString(16).toUpperCase().padStart(4, '0')}`
          : char
      }
      if (!key && char !== ' ') leadingValueSpace = false
    }
    return result
  }
  return project.entries
    .map((entry) => [entry.key, locale === project.sourceLanguage
      ? entry.source : (entry.translations[locale]?.value ?? '')] as const)
    .sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0)
    .map(([key, value]) => `${escape(key, true)}=${escape(value, false)}\n`)
    .join('')
}

export function parseWorkspace(text: string): Workspace {
  const data: unknown = JSON.parse(text)
  if (!isObject(data) || data.version !== 1 || !Array.isArray(data.projects))
    throw new Error('本地数据版本或格式不受支持。')
  const ids = new Set<string>()
  for (const p of data.projects) {
    if (
      !isObject(p) ||
      typeof p.id !== 'string' ||
      !p.id ||
      ids.has(p.id) ||
      typeof p.name !== 'string' ||
      !p.name.trim() ||
      typeof p.description !== 'string' ||
      !validLocale(p.sourceLanguage) ||
      !Array.isArray(p.targetLanguages) ||
      !p.targetLanguages.length ||
      !p.targetLanguages.every((l) => validLocale(l) && l !== p.sourceLanguage) ||
      new Set(p.targetLanguages).size !== p.targetLanguages.length ||
      !Array.isArray(p.entries)
    )
      throw new Error('本地项目数据无效。')
    ids.add(p.id)
    const keys = new Set<string>(),
      entryIds = new Set<string>()
    for (const e of p.entries) {
      if (
        !isObject(e) ||
        typeof e.id !== 'string' ||
        !e.id ||
        entryIds.has(e.id) ||
        typeof e.key !== 'string' ||
        validateKey(e.key) !== e.key ||
        keys.has(e.key) ||
        typeof e.source !== 'string' ||
        !isObject(e.translations)
      )
        throw new Error('本地条目数据无效。')
      entryIds.add(e.id)
      keys.add(e.key)
      for (const [locale, t] of Object.entries(e.translations)) {
        if (
          !p.targetLanguages.includes(locale) ||
          !isObject(t) ||
          typeof t.value !== 'string' ||
          !['draft', 'reviewed'].includes(t.status as string) ||
          (t.status === 'reviewed' && !t.value.trim())
        )
          throw new Error('本地译文数据无效。')
      }
    }
  }
  return data as unknown as Workspace
}

export function seedWorkspace(): Workspace {
  const rows = [
    [
      'home.hero.title',
      '让每一种语言，准确抵达。',
      'Every word, a world closer.',
      '言葉を、世界へ。',
      true,
    ],
    [
      'home.hero.subtitle',
      '让团队在同一处管理所有翻译。',
      'One home for every translation.',
      'すべての翻訳を、一か所で。',
      true,
    ],
    ['home.cta.start', '开始使用', 'Get started', 'はじめる', true],
    ['home.cta.learn', '了解更多', 'Learn more', '', false],
    ['nav.projects', '我的项目', 'My projects', 'プロジェクト', true],
    ['nav.settings', '项目设置', 'Project settings', '', false],
    ['common.save', '保存更改', 'Save changes', '変更を保存', true],
    ['common.cancel', '取消', 'Cancel', 'キャンセル', true],
    ['common.search', '搜索文案或键名', '', '', false],
    ['empty.title', '从第一条文案开始', '', '', false],
    ['empty.description', '添加文案，让表达跨越语言。', '', '', false],
    [
      'footer.copyright',
      '每一个字，都值得被理解。',
      'Every word deserves to be understood.',
      '',
      false,
    ],
  ] as const
  return {
    version: 1,
    projects: [
      {
        id: 'demo-website',
        name: '产品网站',
        description: '从第一句欢迎，到世界各地。',
        sourceLanguage: 'zh-CN',
        targetLanguages: ['en-US', 'ja-JP'],
        entries: rows.map(([key, source, en, ja, reviewed], index) => ({
          id: `demo-${index}`,
          key,
          source,
          translations: {
            'en-US': { value: en, status: reviewed ? 'reviewed' : 'draft' },
            'ja-JP': { value: ja, status: reviewed && ja ? 'reviewed' : 'draft' },
          },
        })),
      },
    ],
  }
}
