/**
 * Regenerates PNG PWA icons from public/icons/icon.svg.
 * Requires a one-off: npm install -D sharp
 */
import { mkdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import sharp from 'sharp'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const iconsDir = join(root, 'public', 'icons')
mkdirSync(iconsDir, { recursive: true })

const svgPath = join(iconsDir, 'icon.svg')

for (const size of [192, 512]) {
  await sharp(svgPath).resize(size, size).png().toFile(join(iconsDir, `icon-${size}.png`))
}
await sharp(svgPath).resize(180, 180).png().toFile(join(iconsDir, 'apple-touch-icon.png'))
console.log('Wrote icon-192.png, icon-512.png, apple-touch-icon.png')
