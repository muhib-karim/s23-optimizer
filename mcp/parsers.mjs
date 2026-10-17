// Original Apache-2.0 implementation; fixtures are shared with the Kotlin suite.
const UID = '(?:u\\d+[as]\\d+|\\d+)';
const NUMBER = '(?:\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)';
const readable = text => typeof text === 'string' && text.trim() !== '' && !/Permission Denial|SecurityException|^Error:/im.test(text);
const finite = value => value != null && value !== '' && Number.isFinite(Number(value)) && Number(value) >= 0 ? Number(value) : null;
const count = value => value != null && /^\d+$/.test(value) && Number.isSafeInteger(Number(value)) ? Number(value) : null;
const sum = (a, b) => Number.isSafeInteger((a ?? 0) + b) ? (a ?? 0) + b : null;
const escape = text => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

export function uid(token) {
  if (/^\d+$/.test(token)) { const value = count(token); return value !== null && value <= 2147483647 ? value : null; }
  const match = /^u(\d+)([as])(\d+)$/.exec(token);
  if (!match) return null;
  const user = count(match[1]), app = count(match[3]);
  if (user === null || app === null || user > 21474 || app > 99999) return null;
  const value = user * 100000 + app + (match[2] === 'a' ? 10000 : 0);
  return value <= 2147483647 ? value : null;
}

export function durationMs(text) {
  const normalized = text.trim().replace(/ /g, '');
  const parts = [...normalized.matchAll(/(\d+)(ms|d|h|m|s)/g)];
  if (!parts.length || parts.map(m => m[0]).join('') !== normalized) return null;
  let total = 0;
  for (const [, amount, unit] of parts) {
    const n = count(amount);
    if (n === null) return null;
    total += n * { d: 86400000, h: 3600000, m: 60000, s: 1000, ms: 1 }[unit];
    if (!Number.isSafeInteger(total)) return null;
  }
  return total;
}

export function batteryOverview(battery, stats) {
  const field = key => readable(battery) ? finite(new RegExp(`^\\s*${escape(key)}:\\s*([+\\-]?[\\d.]+)\\s*$`, 'm').exec(battery)?.[1]) : null;
  const drain = key => readable(stats) ? finite(new RegExp(`^\\s*${escape(key)}:\\s*(${NUMBER})(?: mAh)?\\s*$`, 'im').exec(stats)?.[1]) : null;
  const level = field('level'), scale = field('scale');
  const rawTemp = readable(battery) ? /^\s*temperature:\s*(-?\d+)\s*$/m.exec(battery)?.[1] : null;
  const temp = rawTemp == null ? null : Number(rawTemp);
  const duration = readable(stats) ? /^\s*Time on battery:\s*([^\(]+)/m.exec(stats)?.[1] : null;
  return {
    levelPct: level !== null && scale > 0 && level <= scale ? level * 100 / scale : null,
    health: ({ 2: 'good', 3: 'overheat', 4: 'dead', 5: 'over voltage', 6: 'failure', 7: 'cold' })[field('health')] ?? null,
    temperatureC: temp !== null && temp >= -500 && temp <= 1000 ? temp / 10 : null,
    screenOnDrainMah: drain('Discharge screen on'), screenOffDrainMah: drain('Discharge screen off'),
    screenOnDischargePct: drain('Amount discharged while screen on'), screenOffDischargePct: drain('Amount discharged while screen off'),
    onBatteryMs: duration ? durationMs(duration) : null,
    startClock: readable(stats) ? /^\s*Start clock time:\s*([\d-]+(?:[ T][\d:.]+)?)\s*$/m.exec(stats)?.[1] ?? null : null,
  };
}

export function topDrainers(text, packages = {}) {
  if (!readable(text)) return [];
  let inPower = false, indent = 0;
  const rows = new Map(), estimate = new RegExp(`^\\s*Uid (${UID}):\\s*(${NUMBER})(?:\\s.*)?$`, 'i');
  for (const line of text.split(/\r?\n/)) {
    const spaces = line.match(/^\s*/)[0].length;
    if (line.trim().toLowerCase() === 'estimated power use (mah):') { inPower = true; indent = spaces; }
    else if (inPower) {
      if (line.trim() && spaces <= indent) inPower = false;
      const match = inPower && estimate.exec(line);
      if (match) {
        const id = uid(match[1]), mah = finite(match[2]);
        if (id !== null && mah !== null) rows.set(id, { uid: id, packages: [...new Set(packages[id] ?? [])], estimatedMah: mah });
      }
    }
  }
  return [...rows.values()].sort((a, b) => b.estimatedMah - a.estimatedMah);
}

function uidLines(text, consume) {
  if (!readable(text)) return;
  let current = null, indent = 0;
  const header = new RegExp(`^\\s*(${UID}):\\s*$`);
  for (const line of text.split(/\r?\n/)) {
    const match = header.exec(line), spaces = line.match(/^\s*/)[0].length;
    if (match) { current = uid(match[1]); indent = spaces; }
    else {
      if (line.trim() && spaces <= indent) current = null;
      if (current !== null) consume(current, line.trim());
    }
  }
}

export function wakelocks(text) {
  const rows = [];
  uidLines(text, (id, line) => {
    const match = /^Wake lock (.+): (.+?) partial(?:\s.*)?$/.exec(line);
    if (!match) return;
    const duration = durationMs(match[2]);
    if (duration !== null) rows.push({ uid: id, name: match[1], totalTimeMs: duration, count: count(/\((\d+) times\)/.exec(line)?.[1]) });
  });
  return rows.sort((a, b) => b.totalTimeMs - a.totalTimeMs);
}

export function alarmsJobs(text) {
  const rows = new Map();
  uidLines(text, (id, line) => {
    const alarms = count(/^Wakeup alarm .+: (\d+) times\s*$/.exec(line)?.[1]);
    const jobs = line.startsWith('Job ') ? count(/\((\d+) times\)/.exec(line)?.[1]) : null;
    if (alarms === null && jobs === null) return;
    const old = rows.get(id) ?? { uid: id, alarmCount: null, jobCount: null };
    rows.set(id, { uid: id, alarmCount: alarms === null ? old.alarmCount : sum(old.alarmCount, alarms), jobCount: jobs === null ? old.jobCount : sum(old.jobCount, jobs) });
  });
  return [...rows.values()].sort((a, b) => (b.alarmCount ?? 0) + (b.jobCount ?? 0) - (a.alarmCount ?? 0) - (a.jobCount ?? 0));
}

export function standbyBucket(text) {
  if (!readable(text)) return null;
  const match = /^(?:[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+:\s*)?(?:bucket=)?([a-zA-Z_]+|\d+)$/.exec(text.trim());
  if (!match) return null;
  const value = match[1].toLowerCase();
  const name = ({ active: 10, working_set: 20, frequent: 30, rare: 40, restricted: 45, never: 50 })[value];
  if (name !== undefined) return name;
  return /^\d+$/.test(value) && [5, 10, 20, 30, 40, 45, 50].includes(Number(value)) ? Number(value) : null;
}

export function standbyBuckets(text) {
  if (!readable(text)) return {};
  const rows = {};
  for (const line of text.split(/\r?\n/)) {
    const match = /^\s*([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+):\s*(.+?)\s*$/.exec(line);
    const bucket = match ? standbyBucket(match[2]) : null;
    if (bucket !== null) rows[match[1]] = bucket;
  }
  return rows;
}

export function backgroundRestriction(text) {
  if (!readable(text)) return null;
  if (text.trim() === 'No operations.') return 'default';
  const pattern = prefix => new RegExp(`^\\s*${prefix}RUN_ANY_IN_BACKGROUND:\\s*(allow|ignore|deny|default|foreground)(?:;.*)?\\s*$`, 'm').exec(text)?.[1];
  return pattern('') ?? pattern('Uid mode:\\s*') ?? null;
}

// Writes change a package op, so a UID-only mode is not a restorable package value.
export function packageBackgroundMode(text) {
  if (!readable(text)) return null;
  if (text.trim() === 'No operations.') return 'default';
  return /^\s*RUN_ANY_IN_BACKGROUND:\s*(allow|ignore|deny|default|foreground)(?:;.*)?\s*$/m.exec(text)?.[1] ?? null;
}

export function procstats(text) {
  if (!readable(text)) return [];
  const rows = [], header = new RegExp(`^\\s*\\*\\s+([^\\s/]+)\\s*/\\s*(${UID})\\s*/.*$`);
  const pss = new RegExp(`\\(\\s*(${NUMBER})(KB|MB|GB|K|M|G)\\s*-\\s*(${NUMBER})(KB|MB|GB|K|M|G)\\s*-`, 'i');
  let current = null;
  const finish = () => { if (current && (current.averagePssKb !== null || current.backgroundPssKb !== null)) rows.push(current); current = null; };
  for (const line of text.split(/\r?\n/)) {
    const match = header.exec(line);
    if (match) {
      finish(); const id = uid(match[2]);
      if (id !== null) current = { process: match[1], uid: id, averagePssKb: null, backgroundPssKb: null };
    } else if (line.trim() && !/^\s/.test(line)) finish();
    else if (current) {
      const memory = pss.exec(line);
      const amount = memory ? finite(memory[3]) : null;
      const value = amount !== null ? amount * ({ G: 1048576, M: 1024, K: 1 })[memory[4][0].toUpperCase()] : null;
      if (value !== null && Number.isFinite(value)) {
        if (/^\s*TOTAL:/i.test(line)) current.averagePssKb = value;
        else if (/^\s*(Bg|Background|Service|Service-Restarting|Receiver|Cached)(?:[ :].*)?$/i.test(line)) current.backgroundPssKb = Math.max(current.backgroundPssKb ?? 0, value);
      }
    }
  }
  finish();
  return rows.sort((a, b) => (b.backgroundPssKb ?? b.averagePssKb ?? 0) - (a.backgroundPssKb ?? a.averagePssKb ?? 0));
}
