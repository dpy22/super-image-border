(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const storageKey = 'image-tool-watermark-v1';
  const defaults = {
    text: '© 我的作品', mode: 'single', size: 3, opacity: 85, margin: 3,
    angle: 0, spacing: 18, font: 'sans-serif', color: '#ffffff',
    bold: false, shadow: true, outline: false, background: false,
    x: 1, y: 1, resolution: '2560', customEdge: 3840, format: 'image/png', quality: 95
  };
  const fields = {
    text: 'wmText', size: 'wmSize', opacity: 'wmOpacity', margin: 'wmMargin',
    angle: 'wmAngle', spacing: 'wmSpacing', font: 'wmFont', color: 'wmColor',
    bold: 'wmBold', shadow: 'wmShadow', outline: 'wmOutline', background: 'wmBackground',
    resolution: 'wmResolution', customEdge: 'wmCustomEdge', format: 'wmFormat', quality: 'wmQuality'
  };
  const bounds = { size: [1, 12], opacity: [5, 100], margin: [0, 12], angle: [-90, 90], spacing: [5, 50], x: [0, 1], y: [0, 1], customEdge: [64, 16384], quality: [80, 100] };
  let settings = { ...defaults };
  try { settings = sanitize(JSON.parse(localStorage.getItem(storageKey)) || {}); } catch { /* Storage can be unavailable in private/file contexts. */ }
  let frame = 0;
  let persistTimer = 0;
  let layout = null;
  let drag = null;
  let busy = false;
  let previewFailed = false;

  function sanitize(input) {
    const result = { ...defaults };
    if (!input || typeof input !== 'object') return result;
    for (const [key, [min, max]] of Object.entries(bounds)) {
      const value = Number(input[key]);
      if (input[key] !== undefined && Number.isFinite(value)) result[key] = clamp(value, min, max);
    }
    for (const key of ['bold', 'shadow', 'outline', 'background']) if (typeof input[key] === 'boolean') result[key] = input[key];
    if (typeof input.text === 'string') result.text = input.text.slice(0, 200);
    if (['single', 'tile'].includes(input.mode)) result.mode = input.mode;
    if (['sans-serif', 'serif', 'monospace'].includes(input.font)) result.font = input.font;
    if (typeof input.color === 'string' && /^#[\da-f]{6}$/i.test(input.color)) result.color = input.color;
    if (['original', '2560', '1920', 'custom'].includes(input.resolution)) result.resolution = input.resolution;
    if (['image/png', 'image/jpeg'].includes(input.format)) result.format = input.format;
    return result;
  }

  function getSource() {
    const type = $('wmSource').value;
    const image = type === 'merge' ? mergeCanvas : type === 'text' ? textCanvas : uploadedImg;
    if (!image) return null;
    return { image, width: image.naturalWidth || image.width, height: image.naturalHeight || image.height };
  }

  function exportSpec(source, value = settings) {
    const edge = value.resolution === 'custom' ? value.customEdge : Number(value.resolution);
    const ratio = value.resolution === 'original' ? 1 : Math.min(1, edge / Math.max(source.width, source.height));
    const width = Math.max(1, Math.round(source.width * ratio));
    const height = Math.max(1, Math.round(source.height * ratio));
    const valid = width <= MAX_CANVAS_SIDE && height <= MAX_CANVAS_SIDE && width * height <= MAX_CANVAS_PIXELS;
    return { width, height, valid, resized: width !== source.width || height !== source.height };
  }

  function syncControls() {
    for (const [key, id] of Object.entries(fields)) {
      const element = $(id);
      if (element.type === 'checkbox') element.checked = settings[key];
      else if (!(document.activeElement === element && ['text', 'customEdge'].includes(key))) element.value = settings[key];
    }
    for (const [id, value] of [
      ['wmSizeValue', settings.size + '%'], ['wmOpacityValue', settings.opacity + '%'],
      ['wmMarginValue', settings.margin + '%'], ['wmAngleValue', settings.angle + '°'],
      ['wmSpacingValue', settings.spacing + '%'], ['wmQualityValue', settings.quality + '%']
    ]) $(id).textContent = value;
    const tiled = settings.mode === 'tile';
    $('wmSingleMode').setAttribute('aria-pressed', String(!tiled));
    $('wmTileMode').setAttribute('aria-pressed', String(tiled));
    $('wmPositionField').hidden = tiled;
    $('wmMargin').closest('.wm-slider-field').hidden = tiled;
    $('wmSpacingField').hidden = !tiled;
    $('wmCustomField').hidden = settings.resolution !== 'custom';
    $('wmQualityField').hidden = settings.format !== 'image/jpeg';
    $('wmPreview').dataset.mode = settings.mode;
    let positionName = '自定义位置';
    document.querySelectorAll('[data-position]').forEach(button => {
      const [x, y] = button.dataset.position.split(',').map(Number);
      const selected = Math.abs(x - settings.x) < 0.001 && Math.abs(y - settings.y) < 0.001;
      button.setAttribute('aria-pressed', String(selected));
      if (selected) positionName = button.getAttribute('aria-label');
    });
    $('wmPositionName').textContent = positionName;
    syncMeta();
  }

  function syncMeta() {
    const source = getSource();
    const spec = source ? exportSpec(source) : null;
    const enabled = Boolean(source && settings.text.trim() && spec.valid && !busy && !previewFailed);
    $('wmSave').disabled = !enabled;
    $('wmShare').disabled = !enabled;
    $('wmSave').textContent = window.nativeImageApp ? '保存水印图到相册' : '下载水印图';
    $('wmSizeInfo').dataset.error = String(Boolean(spec && !spec.valid));
    $('wmSizeInfo').textContent = source
      ? `来源 ${source.width} × ${source.height} → 输出 ${spec.width} × ${spec.height} 像素${spec.valid ? '' : ' · 超出安全尺寸'}`
      : '选择图片后显示实际导出尺寸';
    $('wmResolutionHint').textContent = !source ? '导出不放大小图，原文件不会被覆盖。'
      : !spec.valid ? '超过 1200 万像素或单边 16384 像素，请改用高清尺寸或调小最长边。'
      : spec.resized ? '当前会缩小图片；选择「保持原图像素尺寸」可保留像素尺寸。原文件不变。'
      : '保持来源图片的像素尺寸，不放大。导出会生成新文件。';
    $('wmPreviewHint').textContent = !source ? '选择图片后，水印效果会实时显示在这里。'
      : !settings.text.trim() ? '输入水印文字后即可导出。'
      : settings.mode === 'tile' ? '全图平铺预览 · 水印只能增加使用标识，无法保证不可移除。'
      : '点按或拖动图片调整位置；也可用九宫格或方向键定位。';
  }

  function schedule({ persist = true } = {}) {
    if (persist) {
      clearTimeout(persistTimer);
      persistTimer = setTimeout(() => {
        try { localStorage.setItem(storageKey, JSON.stringify(settings)); } catch { /* Keep editing usable if storage is full or disabled. */ }
      }, 250);
    }
    $('wmStatus').textContent = '';
    $('wmStatus').dataset.error = 'false';
    syncControls();
    if (frame) cancelAnimationFrame(frame);
    if (!$('watermarkPanel').hidden) frame = requestAnimationFrame(renderPreview);
  }

  function measureMark(ctx, source, value) {
    const short = Math.min(source.width, source.height);
    const fontSize = short * value.size / 100;
    ctx.font = `${value.bold ? '700' : '400'} ${fontSize}px ${value.font}`;
    const lines = value.text.split('\n').flatMap(line => line.trim() ? wrapTextByWidth(ctx, line, source.width * 0.8) : ['']);
    const lineHeight = fontSize * 1.35;
    const metrics = ctx.measureText('Hg汉字');
    const ascent = metrics.actualBoundingBoxAscent || fontSize * 0.8;
    const descent = metrics.actualBoundingBoxDescent || fontSize * 0.2;
    const padding = fontSize * (value.background ? 0.4 : 0.12);
    const width = Math.max(fontSize, ...lines.map(line => ctx.measureText(line).width)) + padding * 2;
    const height = lines.length * lineHeight + padding * 2;
    const angle = value.angle * Math.PI / 180;
    const rotatedWidth = Math.abs(Math.cos(angle)) * width + Math.abs(Math.sin(angle)) * height;
    const rotatedHeight = Math.abs(Math.sin(angle)) * width + Math.abs(Math.cos(angle)) * height;
    const margin = short * value.margin / 100;
    const fit = Math.min(1, Math.max(1, source.width - margin * 2) / rotatedWidth, Math.max(1, source.height - margin * 2) / rotatedHeight);
    return { lines, fontSize, lineHeight, width, height, angle, fit, margin, rotatedWidth: rotatedWidth * fit, rotatedHeight: rotatedHeight * fit, baseline: (ascent - descent) / 2 };
  }

  function singleGeometry(source, mark, value) {
    const minX = mark.margin + mark.rotatedWidth / 2;
    const minY = mark.margin + mark.rotatedHeight / 2;
    const spanX = Math.max(0, source.width - mark.margin * 2 - mark.rotatedWidth);
    const spanY = Math.max(0, source.height - mark.margin * 2 - mark.rotatedHeight);
    return { minX, minY, spanX, spanY, cx: minX + spanX * value.x, cy: minY + spanY * value.y, width: mark.rotatedWidth, height: mark.rotatedHeight };
  }

  function drawMark(ctx, mark, value) {
    ctx.save();
    ctx.scale(mark.fit, mark.fit);
    ctx.globalAlpha = value.opacity / 100;
    if (value.background) {
      ctx.fillStyle = 'rgba(0,0,0,0.55)';
      ctx.fillRect(-mark.width / 2, -mark.height / 2, mark.width, mark.height);
    }
    ctx.font = `${value.bold ? '700' : '400'} ${mark.fontSize}px ${value.font}`;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'alphabetic';
    ctx.fillStyle = value.color;
    ctx.strokeStyle = 'rgba(0,0,0,0.85)';
    ctx.lineWidth = mark.fontSize * 0.085;
    ctx.lineJoin = 'round';
    mark.lines.forEach((line, i) => {
      const y = (i - (mark.lines.length - 1) / 2) * mark.lineHeight + mark.baseline;
      ctx.shadowColor = 'transparent';
      if (value.outline) ctx.strokeText(line, 0, y);
      if (value.shadow) {
        ctx.shadowColor = 'rgba(0,0,0,0.8)';
        // Shadow blur and offsets use device pixels, unlike the transformed text coordinates.
        const scale = Math.hypot(ctx.getTransform().a, ctx.getTransform().b);
        ctx.shadowBlur = mark.fontSize * 0.12 * scale;
        ctx.shadowOffsetY = mark.fontSize * 0.06 * scale;
      }
      ctx.fillText(line, 0, y);
    });
    ctx.restore();
  }

  function paint(ctx, width, height, source, value) {
    ctx.clearRect(0, 0, width, height);
    if (value.format === 'image/jpeg') {
      ctx.fillStyle = '#ffffff';
      ctx.fillRect(0, 0, width, height);
    }
    ctx.imageSmoothingEnabled = true;
    ctx.imageSmoothingQuality = 'high';
    ctx.drawImage(source.image, 0, 0, width, height);
    if (!value.text.trim()) return null;
    ctx.save();
    ctx.scale(width / source.width, height / source.height);
    // Measure in source-image coordinates so preview, export and drag share the same layout.
    const mark = measureMark(ctx, source, value);
    let geometry = null;
    if (value.mode === 'single') {
      geometry = singleGeometry(source, mark, value);
      ctx.translate(geometry.cx, geometry.cy);
      ctx.rotate(mark.angle);
      drawMark(ctx, mark, value);
    } else {
      const diagonal = Math.hypot(source.width, source.height);
      const gap = Math.min(source.width, source.height) * value.spacing / 100;
      // Bound work for extreme panoramas and tiny watermarks.
      const stepX = Math.max(mark.width * mark.fit + gap, diagonal / 32);
      const stepY = Math.max(mark.height * mark.fit + gap, diagonal / 32);
      ctx.translate(source.width / 2, source.height / 2);
      ctx.rotate(mark.angle);
      for (let row = -Math.ceil(diagonal / stepY / 2) - 1; row <= Math.ceil(diagonal / stepY / 2) + 1; row++) {
        for (let col = -Math.ceil(diagonal / stepX / 2) - 1; col <= Math.ceil(diagonal / stepX / 2) + 1; col++) {
          ctx.save();
          ctx.translate(col * stepX + (Math.abs(row % 2) ? stepX / 2 : 0), row * stepY);
          drawMark(ctx, mark, value);
          ctx.restore();
        }
      }
    }
    ctx.restore();
    return geometry;
  }

  function renderPreview() {
    frame = 0;
    const source = getSource();
    const canvas = $('wmPreview');
    previewFailed = false;
    layout = null;
    $('wmEmpty').hidden = Boolean(source);
    canvas.hidden = !source;
    if (!source) {
      canvas.width = canvas.height = 1;
      syncMeta();
      return;
    }
    try {
      const ratio = Math.min(1, 1400 / Math.max(source.width, source.height));
      canvas.width = Math.max(1, Math.round(source.width * ratio));
      canvas.height = Math.max(1, Math.round(source.height * ratio));
      layout = paint(canvas.getContext('2d'), canvas.width, canvas.height, source, settings);
    } catch {
      previewFailed = true;
      $('wmStatus').dataset.error = 'true';
      $('wmStatus').textContent = '预览失败，请尝试重新选择图片。';
    }
    syncMeta();
  }

  function sourceChanged() {
    $('wmSource').querySelector('[value="merge"]').disabled = !mergeCanvas;
    $('wmSource').querySelector('[value="text"]').disabled = !textCanvas;
    if (($('wmSource').value === 'merge' && !mergeCanvas) || ($('wmSource').value === 'text' && !textCanvas)) $('wmSource').value = 'original';
    previewFailed = false;
    schedule({ persist: false });
  }

  function switchTool(tool) {
    const water = tool === 'watermark';
    document.body.dataset.tool = tool;
    $('mergePanel').hidden = water;
    $('watermarkPanel').hidden = !water;
    $('mergeTextFields').hidden = water;
    $('sourceHeading').textContent = water ? '图片' : '内容';
    for (const [id, selected] of [['mergeTab', !water], ['watermarkTab', water]]) {
      $(id).setAttribute('aria-selected', String(selected));
      $(id).tabIndex = selected ? 0 : -1;
    }
    if (water) sourceChanged();
  }

  async function exportWatermark(share) {
    if (busy) return;
    if (exportingImage) {
      $('wmStatus').textContent = '上一张图片仍在导出，请稍后再试。';
      return;
    }
    const source = getSource();
    if (!source || !settings.text.trim()) return;
    const value = { ...settings };
    const spec = exportSpec(source, value);
    if (!spec.valid) return;
    const canvas = document.createElement('canvas');
    busy = true;
    syncMeta();
    $('wmStatus').setAttribute('aria-busy', 'true');
    try {
      canvas.width = spec.width;
      canvas.height = spec.height;
      paint(canvas.getContext('2d'), canvas.width, canvas.height, source, value);
      const success = await exportCanvas(canvas, '水印图', share, { mimeType: value.format, quality: value.quality / 100, statusId: 'wmStatus' });
      $('wmStatus').dataset.error = String(!success);
      if (success) $('wmStatus').textContent += ` · ${spec.width} × ${spec.height}`;
    } catch {
      $('wmStatus').dataset.error = 'true';
      $('wmStatus').textContent = '导出失败，请调小输出尺寸后重试。';
    } finally {
      canvas.width = canvas.height = 1;
      busy = false;
      $('wmStatus').removeAttribute('aria-busy');
      syncMeta();
    }
  }

  function updatePointer(event) {
    const source = getSource();
    if (!source || !layout) return;
    const rect = $('wmPreview').getBoundingClientRect();
    const x = (event.clientX - rect.left) / rect.width * source.width - (drag?.dx || 0);
    const y = (event.clientY - rect.top) / rect.height * source.height - (drag?.dy || 0);
    settings.x = layout.spanX ? clamp((x - layout.minX) / layout.spanX, 0, 1) : 0.5;
    settings.y = layout.spanY ? clamp((y - layout.minY) / layout.spanY, 0, 1) : 0.5;
    schedule();
  }

  $('wmPreview').addEventListener('pointerdown', event => {
    if (settings.mode !== 'single' || !layout || event.button !== 0) return;
    const source = getSource();
    const rect = $('wmPreview').getBoundingClientRect();
    const x = (event.clientX - rect.left) / rect.width * source.width;
    const y = (event.clientY - rect.top) / rect.height * source.height;
    const hit = Math.abs(x - layout.cx) < layout.width / 2 && Math.abs(y - layout.cy) < layout.height / 2;
    drag = { id: event.pointerId, dx: hit ? x - layout.cx : 0, dy: hit ? y - layout.cy : 0 };
    $('wmPreview').setPointerCapture(event.pointerId);
    updatePointer(event);
    event.preventDefault();
  });
  $('wmPreview').addEventListener('pointermove', event => { if (drag && drag.id === event.pointerId) updatePointer(event); });
  for (const type of ['pointerup', 'pointercancel', 'lostpointercapture']) $('wmPreview').addEventListener(type, () => { drag = null; });
  $('wmPreview').addEventListener('keydown', event => {
    if (settings.mode !== 'single' || !layout || !['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) return;
    const step = event.shiftKey ? 0.05 : 0.01;
    if (event.key === 'ArrowLeft') settings.x = clamp(settings.x - step, 0, 1);
    if (event.key === 'ArrowRight') settings.x = clamp(settings.x + step, 0, 1);
    if (event.key === 'ArrowUp') settings.y = clamp(settings.y - step, 0, 1);
    if (event.key === 'ArrowDown') settings.y = clamp(settings.y + step, 0, 1);
    schedule();
    event.preventDefault();
  });

  document.querySelectorAll('[data-position]').forEach(button => button.addEventListener('click', () => {
    [settings.x, settings.y] = button.dataset.position.split(',').map(Number);
    schedule();
  }));
  document.querySelectorAll('[data-preset]').forEach(button => button.addEventListener('click', () => {
    const preset = button.dataset.preset;
    const style = { size: 3, opacity: 85, margin: 3, angle: 0, spacing: 18, font: 'sans-serif', color: '#ffffff', bold: false, shadow: true, outline: false, background: false, x: 1, y: 1, mode: 'single' };
    if (preset === 'label') Object.assign(style, { background: true, shadow: false, bold: true, opacity: 100 });
    if (preset === 'tile') Object.assign(style, { mode: 'tile', angle: -30, opacity: 24, size: 3.5, shadow: false });
    Object.assign(settings, style);
    schedule();
  }));
  $('wmSingleMode').addEventListener('click', () => { settings.mode = 'single'; schedule(); });
  $('wmTileMode').addEventListener('click', () => { settings.mode = 'tile'; schedule(); });
  for (const [key, id] of Object.entries(fields)) {
    const element = $(id);
    element.addEventListener('input', () => {
      settings = sanitize({ ...settings, [key]: element.type === 'checkbox' ? element.checked : element.value });
      schedule();
    });
    if (key === 'customEdge') element.addEventListener('blur', () => { element.value = settings.customEdge; });
  }
  $('wmSource').addEventListener('change', () => { previewFailed = false; schedule({ persist: false }); });
  $('wmSave').addEventListener('click', () => exportWatermark(false));
  $('wmShare').addEventListener('click', () => exportWatermark(true));
  $('mergeTab').addEventListener('click', () => switchTool('merge'));
  $('watermarkTab').addEventListener('click', () => switchTool('watermark'));
  document.querySelector('.tool-tabs').addEventListener('keydown', event => {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    const water = event.key === 'End' || (event.key !== 'Home' && $('watermarkTab').getAttribute('aria-selected') !== 'true');
    switchTool(water ? 'watermark' : 'merge');
    $(water ? 'watermarkTab' : 'mergeTab').focus();
    event.preventDefault();
  });
  window.openWatermark = source => {
    $('wmSource').value = source;
    switchTool('watermark');
    document.querySelector('.tool-tabs').scrollIntoView({ behavior: 'smooth', block: 'start' });
  };
  window.addEventListener('image-tool-sourcechange', sourceChanged);
  window.addEventListener('image-tool-native-ready', syncMeta);
  syncControls();
})();
