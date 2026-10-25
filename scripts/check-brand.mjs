import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import sharp from 'sharp'

for (const size of [16, 24, 32, 48, 64, 128, 256, 512, 1024]) {
  const metadata = await sharp(`brand/png/logo-mark-${size}.png`).metadata()
  assert.equal(metadata.width, size)
  assert.equal(metadata.height, size)
  assert.equal(metadata.hasAlpha, true)
  const { data } = await sharp(`brand/png/logo-mark-${size}.png`)
    .ensureAlpha()
    .raw()
    .toBuffer({ resolveWithObject: true })
  assert.equal(data[3], 0, 'Logo background must be transparent')
}
for (const width of [320, 640, 1280, 2560]) {
  const metadata = await sharp(`brand/png/logo-horizontal-${width}.png`).metadata()
  assert.equal(metadata.width, width)
  assert.equal(metadata.hasAlpha, true)
}
for (const size of [512, 1024, 2048]) {
  const metadata = await sharp(`brand/png/logo-stacked-${size}.png`).metadata()
  assert.equal(metadata.width, size)
  assert.equal(metadata.height, size)
}
for (const [name, size] of [
  ['apple-touch-icon', 180],
  ['android-chrome-192', 192],
  ['android-chrome-512', 512],
  ['maskable-icon-512', 512],
]) {
  const metadata = await sharp(`brand/icons/${name}.png`).metadata()
  assert.equal(metadata.width, size)
  assert.equal(metadata.height, size)
  assert.equal(metadata.hasAlpha, false, 'App icons need an opaque background')
}
for (const [name, width, height] of [
  ['og-image-1200x630', 1200, 630],
  ['github-cover-1280x640', 1280, 640],
]) {
  const metadata = await sharp(`brand/social/${name}.png`).metadata()
  assert.equal(metadata.width, width)
  assert.equal(metadata.height, height)
}
for (const name of [
  'logo-mark',
  'logo-horizontal',
  'logo-stacked',
  'logo-horizontal-white',
  'logo-horizontal-mono',
  'logo-horizontal-reversed',
]) {
  const content = await readFile(`brand/svg/${name}.svg`, 'utf8')
  assert.ok(content.includes('<path'))
  assert.ok(
    !/<(?:text|image|foreignObject)\b|https?:\/\/(?!www.w3.org)/i.test(content),
    'SVG must use local vector paths without font or raster dependencies',
  )
}
for (const [original, deployed] of [
  ['brand/svg/logo-horizontal.svg', 'public/brand/logo-horizontal.svg'],
  ['brand/svg/logo-mark.svg', 'public/favicon.svg'],
  ['brand/icons/favicon.ico', 'public/favicon.ico'],
]) {
  assert.deepEqual(
    await readFile(original),
    await readFile(deployed),
    'Public copies must match brand masters',
  )
}
const ico = await readFile('brand/icons/favicon.ico')
assert.equal(ico.readUInt16LE(2), 1)
assert.equal(ico.readUInt16LE(4), 3)
for (let i = 0; i < 3; i++) {
  const pos = 6 + i * 16,
    size = [16, 32, 48][i]
  assert.equal(ico[pos], size)
  assert.equal(ico[pos + 1], size)
  const length = ico.readUInt32LE(pos + 8),
    offset = ico.readUInt32LE(pos + 12)
  const metadata = await sharp(ico.subarray(offset, offset + length)).metadata()
  assert.equal(metadata.width, size)
}
console.log(
  'Brand checks passed: vector sources, PNG sizes/transparency, app icons, social formats, ICO frames and public copies.',
)
