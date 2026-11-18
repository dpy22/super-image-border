import { mkdir, readFile, writeFile, copyFile } from 'node:fs/promises';
import { build } from 'esbuild';

await mkdir('dist', { recursive: true });
const html = await readFile('index.html', 'utf8');
await build({
  entryPoints: ['src/native.js'],
  outfile: 'dist/native.js',
  bundle: true,
  format: 'iife',
  target: 'chrome90',
  minify: true
});
await copyFile('src/watermark.css', 'dist/watermark.css');
await copyFile('src/watermark.js', 'dist/watermark.js');
await writeFile('dist/index.html', html
  .replace('src/watermark.css', 'watermark.css')
  .replace('src/watermark.js', 'watermark.js')
  .replace('</body>', '<script src="native.js"></script>\n</body>'));
console.log('网页已打包到 dist/，可离线运行。');
