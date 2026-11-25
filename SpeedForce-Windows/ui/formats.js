// Turns yt-dlp's video information into the rows of the quality picker, and reads its progress lines.
'use strict';

function humanSize(bytes) {
  if (!(bytes > 0)) return '';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let v = bytes, i = 0;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return i <= 1 ? `${Math.round(v)} ${units[i]}` : `${v.toFixed(1)} ${units[i]}`;
}

const num = v => (typeof v === 'number' && isFinite(v) ? v : 0);

function toFormat(f) {
  const size = num(f.filesize) || num(f.filesize_approx);
  const fmt = {
    id: f.format_id || 'best', ext: f.ext || '', width: num(f.width), height: num(f.height), fps: num(f.fps),
    vcodec: f.vcodec || null, acodec: f.acodec || null, fileSize: size, tbr: num(f.tbr),
    note: f.format_note || '', protocol: f.protocol || '',
  };
  fmt.hasVideo = fmt.vcodec !== 'none' && (fmt.height > 0 || fmt.vcodec != null);
  // HLS sites often keep sound in a separate track whose codec isn't stated ("hls-audio-128000")
  fmt.audioOnly = (fmt.vcodec === 'none' && fmt.acodec !== 'none') ||
    (fmt.vcodec == null && fmt.height === 0 && fmt.acodec !== 'none' && /audio/i.test(fmt.id));
  fmt.videoOnly = fmt.acodec === 'none' && fmt.vcodec !== 'none';
  // The "p" number people know: the shorter side of the picture (portrait videos too)
  fmt.p = fmt.width > 0 && fmt.height > 0 ? Math.min(fmt.width, fmt.height) : fmt.height;
  return fmt;
}

/** Parse yt-dlp "-J" output. A page with several videos is a playlist: every entry becomes one video. */
function parseInfo(json) {
  const out = [];
  (function collect(o) {
    if (Array.isArray(o.entries) && !o.formats) { o.entries.forEach(e => e && collect(e)); return; }
    const formats = Array.isArray(o.formats) ? o.formats.map(toFormat) : (o.format_id || o.url ? [toFormat(o)] : []);
    out.push({
      title: o.title || o.id || 'video',
      webpageUrl: o.webpage_url || o.original_url || '',
      extractor: o.extractor_key || o.extractor || '',
      duration: Math.floor(num(o.duration)),
      thumbnail: o.thumbnail || '',
      formats,
    });
  })(JSON.parse(json.substring(json.indexOf('{'))));
  return out;
}

/** Higher = plays in more video players. */
function compat(f) {
  const v = (f.vcodec || '').toLowerCase();
  if (v.startsWith('avc') || v.startsWith('h264')) return 4;
  if (!v && (f.ext === 'mp4' || f.protocol.startsWith('m3u8'))) return 4;
  if (/^(hev|hvc|h265)/.test(v)) return 3;
  if (/^(vp9|vp09)/.test(v)) return 2;
  if (v.startsWith('av01')) return 1;
  return 2;
}

function qualityName(p) {
  if (p >= 4320) return `${p}p (8K)`;
  if (p >= 2160) return `${p}p (4K)`;
  if (p >= 1440) return `${p}p (2K)`;
  return `${p}p`;
}

/** Picker rows: "Best", one row per resolution (highest first), "Audio only". [maxRes] 0 = no limit. */
function buildOptions(info, maxRes) {
  const formats = info.formats.map(f =>
    // No size given: estimate it from bitrate (kbit/s) x duration
    f.fileSize <= 0 && f.tbr > 0 && info.duration > 0 ? { ...f, fileSize: Math.round(f.tbr * 1000 / 8 * info.duration) } : f);
  const audio = formats.filter(f => f.audioOnly);
  const hasSeparateAudio = audio.length > 0;
  const bestAudioSize = Math.max(0, ...audio.map(f => f.fileSize));
  const byRes = new Map();
  formats.filter(f => f.hasVideo && f.p > 0).forEach(f => byRes.set(f.p, [...(byRes.get(f.p) || []), f]));

  const rows = [{
    label: maxRes > 0 ? `Best (up to ${maxRes}p)` : 'Best quality',
    detail: 'Automatic: picks the best available',
    format: maxRes > 0 ? `bv*[height<=${maxRes}]+ba/b[height<=${maxRes}]/bv*+ba/b` : 'bv*+ba/b',
    height: maxRes,
  }];
  for (const p of [...byRes.keys()].sort((a, b) => b - a)) {
    const pick = byRes.get(p).slice().sort((a, b) =>
      compat(b) - compat(a) || b.fps - a.fps || b.tbr - a.tbr || b.fileSize - a.fileSize)[0];
    const needsAudio = pick.videoOnly && hasSeparateAudio;
    const size = pick.fileSize + (needsAudio ? bestAudioSize : 0);
    rows.push({
      label: qualityName(p) + (pick.fps > 30 ? ` ${pick.fps}fps` : ''),
      detail: [pick.ext, size > 0 ? '~' + humanSize(size) : '', pick.videoOnly && !hasSeparateAudio ? 'no sound' : '']
        .filter(Boolean).join(' · '),
      format: needsAudio ? `${pick.id}+ba[ext=m4a]/${pick.id}+ba/${pick.id}` : pick.id,
      height: p,
    });
  }
  if (!byRes.size && formats.length > 1) {
    // Formats without picture size (some direct streams): list them as they are
    formats.filter(f => !f.audioOnly).forEach((f, i) => rows.push({
      label: `Version ${i + 1}` + (f.note ? ` (${f.note})` : ''),
      detail: [f.ext, f.fileSize > 0 ? '~' + humanSize(f.fileSize) : ''].filter(Boolean).join(' · '),
      format: f.id, height: 0,
    }));
  }
  rows.push({ label: 'Audio only', detail: bestAudioSize > 0 ? 'm4a · ~' + humanSize(bestAudioSize) : 'm4a',
    format: 'ba/b', audioOnly: true, height: 0 });
  return rows;
}

/** Row to highlight first: the closest resolution at or below the limit. */
function defaultIndex(rows, maxRes) {
  if (maxRes <= 0) return 0;
  const i = rows.findIndex(r => !r.audioOnly && r.height > 0 && r.height <= maxRes && !r.label.startsWith('Best'));
  return i >= 0 ? i : 0;
}

/** Reads "[download]  42.0% of 10.00MiB at 1.20MiB/s ETA 00:05". */
function parseProgress(line) {
  if (!line.includes('[download]')) return null;
  const g = re => { const m = re.exec(line); return m ? m[1].replace(/\s/g, '') : null; };
  const pct = g(/\[download\]\s+([\d.]+)%/);
  const speed = g(/at\s+([\d.]+\s*[KMGT]?i?B\/s)/);
  if (pct == null && speed == null) return null;
  return { percent: pct == null ? null : parseFloat(pct), total: g(/of\s+~?\s*([\d.]+\s*[KMGT]?i?B)/), speed, eta: g(/ETA\s+([\d:]+)/) };
}

/** Turns long yt-dlp errors into one readable line. */
function shortError(text) {
  const lines = String(text || '').split(/\r?\n/);
  const line = lines.find(l => l.includes('ERROR')) || lines.find(l => l.trim()) || String(text);
  return line.replace(/^.*?ERROR:\s*/, '').slice(0, 300);
}

module.exports = { humanSize, parseInfo, buildOptions, defaultIndex, qualityName, parseProgress, shortError };
