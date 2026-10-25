import { readFile, writeFile, mkdir, copyFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import sharp from 'sharp'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const at = (file) => path.join(root, file)
for (const dir of ['brand/svg', 'brand/png', 'brand/icons', 'brand/social', 'public/brand'])
  await mkdir(at(dir), { recursive: true })
const markSource = await readFile(at('brand/source/mark.svg'), 'utf8')
const wordmarkSource = await readFile(at('brand/source/wordmark.svg'), 'utf8')
const inner = (svg) => svg.slice(svg.indexOf('>') + 1, svg.lastIndexOf('</svg>'))
const mark = inner(markSource)
const wordmark = inner(wordmarkSource)
const svg = (width, height, body, label = '言渡 YANDU') =>
  `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" aria-label="${label}">${body}</svg>\n`
const symbol = (color) =>
  color ? mark.replaceAll('#7C3AED', color).replaceAll('#FF7849', color) : mark
const logo = (color = '#24233A', mono = false) =>
  svg(
    460,
    112,
    `<g transform="translate(0 -3) scale(.46)">${symbol(mono ? color : undefined)}</g><g transform="translate(128 7)">${wordmark.replaceAll('#24233A', color)}</g>`,
  )
const stack = svg(
  512,
  512,
  `<g transform="translate(128 35)">${mark}</g><g transform="translate(70 336) scale(1.2)">${wordmark}</g>`,
)
const appIcon = (maskable = false) =>
  svg(
    512,
    512,
    `<rect width="512" height="512" rx="${maskable ? 0 : 104}" fill="#F7F1FF"/><g transform="translate(${maskable ? 96 : 58} ${maskable ? 96 : 58}) scale(${maskable ? 1.25 : 1.55})">${mark}</g>`,
  )
const variants = {
  'logo-mark': svg(256, 256, mark),
  'logo-mark-mono': svg(256, 256, symbol('#24233A')),
  'logo-mark-white': svg(256, 256, symbol('#FFFFFF')),
  'logo-horizontal': logo(),
  'logo-horizontal-white': logo('#FFFFFF'),
  'logo-horizontal-mono': logo('#24233A', true),
  'logo-horizontal-reversed': logo('#FFFFFF', true),
  'logo-stacked': stack,
  'app-icon': appIcon(),
  'app-icon-maskable': appIcon(true),
}
for (const [name, content] of Object.entries(variants))
  await writeFile(at(`brand/svg/${name}.svg`), content)
for (const size of [16, 24, 32, 48, 64, 128, 256, 512, 1024]) {
  await sharp(Buffer.from(variants['logo-mark']))
    .resize(size, size)
    .png()
    .toFile(at(`brand/png/logo-mark-${size}.png`))
}
for (const width of [320, 640, 1280, 2560]) {
  await sharp(Buffer.from(variants['logo-horizontal']))
    .resize({ width })
    .png()
    .toFile(at(`brand/png/logo-horizontal-${width}.png`))
}
for (const size of [512, 1024, 2048])
  await sharp(Buffer.from(stack))
    .resize(size, size)
    .png()
    .toFile(at(`brand/png/logo-stacked-${size}.png`))
for (const [name, size, maskable] of [
  ['apple-touch-icon', 180, false],
  ['android-chrome-192', 192, false],
  ['android-chrome-512', 512, false],
  ['maskable-icon-512', 512, true],
]) {
  await sharp(Buffer.from(appIcon(maskable)))
    .resize(size, size)
    .flatten({ background: '#F7F1FF' })
    .png()
    .toFile(at(`brand/icons/${name}.png`))
}
// ICO with PNG-compressed frames; supported by modern browsers and Windows.
const frames = await Promise.all(
  [16, 32, 48].map((size) =>
    sharp(Buffer.from(variants['logo-mark'])).resize(size, size).png().toBuffer(),
  ),
)
const header = Buffer.alloc(6 + 16 * frames.length)
header.writeUInt16LE(1, 2)
header.writeUInt16LE(frames.length, 4)
let offset = header.length
for (let i = 0; i < frames.length; i++) {
  const size = [16, 32, 48][i],
    pos = 6 + 16 * i
  header[pos] = size
  header[pos + 1] = size
  header.writeUInt16LE(1, pos + 4)
  header.writeUInt16LE(32, pos + 6)
  header.writeUInt32LE(frames[i].length, pos + 8)
  header.writeUInt32LE(offset, pos + 12)
  offset += frames[i].length
}
await writeFile(at('brand/icons/favicon.ico'), Buffer.concat([header, ...frames]))
const social = svg(
  1200,
  630,
  `<defs><linearGradient id="bg" x2="1" y2="1"><stop stop-color="#FBF7FF"/><stop offset="1" stop-color="#FFF2E9"/></linearGradient></defs><rect width="1200" height="630" fill="url(#bg)"/><circle cx="1050" cy="70" r="220" fill="#EBDDFF" opacity=".45"/><circle cx="1050" cy="70" r="160" fill="none" stroke="#D6BFF6" stroke-width="1"/><g transform="translate(112 180) scale(2.1)">${inner(variants['logo-horizontal'])}</g><path d="M120 450H1080" stroke="#DECDF0"/><g transform="translate(0 20)" fill="#9E84B4"><circle cx="120" cy="470" r="4"/><circle cx="138" cy="470" r="4" fill="#FF7849"/><path d="M1020 466h60v2h-60z"/></g>`,
)
await writeFile(at('brand/social/og-image.svg'), social)
await sharp(Buffer.from(social)).png().toFile(at('brand/social/og-image-1200x630.png'))
const cover = svg(
  1280,
  640,
  `<rect width="1280" height="640" fill="#24233A"/><circle cx="1100" cy="80" r="350" fill="#362848"/><g transform="translate(140 240) scale(2.1)">${inner(variants['logo-horizontal-white'])}</g><path d="M140 490h1000" stroke="#53415F"/>`,
)
await writeFile(at('brand/social/github-cover.svg'), cover)
await sharp(Buffer.from(cover)).png().toFile(at('brand/social/github-cover-1280x640.png'))
for (const name of ['logo-mark', 'logo-horizontal'])
  await copyFile(at(`brand/svg/${name}.svg`), at(`public/brand/${name}.svg`))
await copyFile(at('brand/svg/logo-mark.svg'), at('public/favicon.svg'))
await copyFile(at('brand/icons/favicon.ico'), at('public/favicon.ico'))
await copyFile(at('brand/icons/apple-touch-icon.png'), at('public/apple-touch-icon.png'))
for (const name of ['android-chrome-192', 'android-chrome-512', 'maskable-icon-512'])
  await copyFile(at(`brand/icons/${name}.png`), at(`public/${name}.png`))
await copyFile(at('brand/social/og-image-1200x630.png'), at('public/og-image.png'))
await writeFile(
  at('public/site.webmanifest'),
  JSON.stringify(
    {
      name: '言渡 YANDU',
      short_name: '言渡',
      lang: 'zh-CN',
      start_url: '/',
      display: 'standalone',
      theme_color: '#7C3AED',
      background_color: '#F8F9FC',
      icons: [
        { src: '/android-chrome-192.png', sizes: '192x192', type: 'image/png', purpose: 'any' },
        { src: '/android-chrome-512.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
        { src: '/maskable-icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
      ],
    },
    null,
    2,
  ) + '\n',
)
console.log('Generated SVG, transparent PNG, ICO, app icons, social images and public assets.')
