import { describe, expect, it } from 'vitest'
import {
  addEntry,
  addLanguage,
  createProject,
  exportMessages,
  exportProperties,
  importMessages,
  parseWorkspace,
  progress,
  saveTranslation,
  seedWorkspace,
  statusOf,
} from './model'

const project = () => seedWorkspace().projects[0]

describe('project and translation workflow', () => {
  it('creates an empty project and selects a distinct target language', () => {
    const p = createProject('  Store  ', 'Shop', 'en-US')
    expect(p.name).toBe('Store')
    expect(p.entries).toEqual([])
    expect(p.targetLanguages).toEqual(['zh-CN'])
    expect(() => createProject(' ')).toThrow()
  })
  it('adds only supported, distinct target languages', () => {
    expect(addLanguage(project(), 'fr-FR').targetLanguages).toContain('fr-FR')
    for (const locale of ['en-US', 'zh-CN', '__proto__'])
      expect(() => addLanguage(project(), locale)).toThrow()
  })
  it('adds unique keys and rejects empty source copy and dangerous key paths', () => {
    const p = addEntry(project(), ' settings.title ', '设置')
    expect(p.entries[0].key).toBe('settings.title')
    expect(() => addEntry(p, 'settings.title', '其他')).toThrow(/已存在/)
    expect(() => addEntry(p, 'new', ' ')).toThrow(/源文案/)
    expect(() => addEntry(p, '__proto__.polluted', 'bad')).toThrow(/保留/)
  })
  it('saves and reviews translations, rejecting empty reviews and unknown targets', () => {
    const p = project(),
      e = p.entries[0]
    const updated = saveTranslation(p, e.id, 'en-US', 'A new world', true)
    expect(statusOf(updated.entries[0], 'en-US')).toBe('reviewed')
    expect(p.entries[0].translations['en-US'].value).not.toBe('A new world')
    expect(() => saveTranslation(p, e.id, 'en-US', ' ', true)).toThrow(/空译文/)
    expect(() => saveTranslation(p, e.id, 'fr-FR', 'Bonjour', false)).toThrow()
    expect(() => saveTranslation(p, 'missing', 'en-US', 'hello', false)).toThrow()
  })
  it('computes per-language progress and handles a zero-entry project', () => {
    expect(progress(project(), 'en-US')).toEqual({
      total: 12,
      translated: 9,
      reviewed: 6,
      percentage: 75,
    })
    expect(progress(createProject('Empty'), 'en-US').percentage).toBe(0)
    expect(statusOf(project().entries[8], 'en-US')).toBe('untranslated')
  })
})

describe('JSON import/export', () => {
  it('merges keys without deleting other keys, languages, or mutating the original', () => {
    const p = project(),
      original = JSON.stringify(p)
    const result = importMessages(p, 'en-US', '{"home.hero.title":"New title","new.key":"Fresh"}')
    expect(result.count).toBe(2)
    expect(result.project.entries).toHaveLength(13)
    const changed = result.project.entries.find((e) => e.key === 'home.hero.title')!
    expect(changed.translations['en-US']).toEqual({ value: 'New title', status: 'draft' })
    expect(changed.translations['ja-JP']).toEqual(p.entries[0].translations['ja-JP'])
    expect(result.project.entries.find((e) => e.key === 'new.key')!.source).toBe('')
    expect(JSON.stringify(p)).toBe(original)
  })
  it('keeps reviewed status when a reimport has unchanged values', () => {
    const p = project(),
      value = p.entries[0].translations['en-US'].value
    const result = importMessages(p, 'en-US', JSON.stringify({ 'home.hero.title': value }))
    expect(result.project.entries[0].translations['en-US'].status).toBe('reviewed')
  })
  it('invalidates all reviews when source changes, preserving translated text', () => {
    const p = project()
    const updated = importMessages(p, 'zh-CN', '{"home.hero.title":"新的源文案"}').project
    expect(updated.entries[0].source).toBe('新的源文案')
    expect(updated.entries[0].translations['en-US'].status).toBe('draft')
    expect(updated.entries[0].translations['ja-JP'].status).toBe('draft')
    expect(updated.entries[0].translations['ja-JP'].value).toBe(
      p.entries[0].translations['ja-JP'].value,
    )
    expect(p.entries[0].source).not.toBe('新的源文案')
  })
  it.each([
    '{',
    '[]',
    'null',
    '{}',
    '{"a": 1}',
    '{"a":{"b":"nested"}}',
    '{"__proto__":"bad"}',
    '{"constructor.bad":"bad"}',
    '{" key":"bad"}',
  ])('rejects invalid input atomically: %s', (text) => {
    const p = project(),
      original = JSON.stringify(p)
    expect(() => importMessages(p, 'en-US', text)).toThrow()
    expect(JSON.stringify(p)).toBe(original)
  })
  it('rejects oversized files and unknown import languages', () => {
    expect(() => importMessages(project(), 'en-US', 'a'.repeat(1024 * 1024 + 1))).toThrow(/1 MB/)
    expect(() => importMessages(project(), 'fr-FR', '{"a":"b"}')).toThrow()
  })
  it('exports a flat map including empty untranslated strings, with Unicode roundtrip', () => {
    const p = project(),
      json = exportMessages(p, 'en-US'),
      parsed = JSON.parse(json)
    expect(parsed['common.search']).toBe('')
    const updated = importMessages(p, 'en-US', json).project
    expect(exportMessages(updated, 'en-US')).toBe(json)
    expect(JSON.parse(exportMessages(p, 'zh-CN'))['common.save']).toBe('保存更改')
    expect(() => exportMessages(p, 'fr-FR')).toThrow()
  })
  it('exports Java Properties as sorted ASCII with escaped Unicode', () => {
    const p = project()
    const output = exportProperties(p, 'zh-CN')
    expect(output).toContain('\\u')
    expect(output.endsWith('\n')).toBe(true)
    expect([...output].every((char) => char.charCodeAt(0) <= 0x7f)).toBe(true)
    expect(() => exportProperties(p, 'fr-FR')).toThrow()
  })
})

describe('versioned storage', () => {
  it('restores the entire workspace, including review state', () => {
    const workspace = seedWorkspace()
    expect(parseWorkspace(JSON.stringify(workspace))).toEqual(workspace)
  })
  it.each(['{}', '{"version":2,"projects":[]}', '{"version":1,"projects":[{}]}'])(
    'rejects corrupted or unsupported storage: %s',
    (text) => {
      expect(() => parseWorkspace(text)).toThrow()
    },
  )
  it('rejects corrupt translation states and duplicate keys', () => {
    const workspace = seedWorkspace()
    workspace.projects[0].entries[1].key = workspace.projects[0].entries[0].key
    expect(() => parseWorkspace(JSON.stringify(workspace))).toThrow()
    const broken = seedWorkspace()
    broken.projects[0].entries[0].translations['en-US'] = { value: '', status: 'reviewed' }
    expect(() => parseWorkspace(JSON.stringify(broken))).toThrow()
  })
})
