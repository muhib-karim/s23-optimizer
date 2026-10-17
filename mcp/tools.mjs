import * as power from './parsers.mjs';
import { randomUUID } from 'node:crypto';

const packagePattern = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+$/;
const schema = properties => ({ type: 'object', properties, required: Object.keys(properties), additionalProperties: false });
const pkg = { type: 'string', pattern: packagePattern.source, maxLength: 255 };
const confirm = { type: 'boolean', const: true, description: 'Set true only after the owner explicitly approves this exact action in the MCP client.' };
const read = (name, description) => ({ name, description, inputSchema: schema({}), annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false } });
const write = (name, description, extra = {}) => ({ name, description, inputSchema: schema({ pkg, ...extra, confirm }), annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: false } });
export const toolDefinitions = [
  read('battery_overview', 'Battery level, OS health flag, temperature, screen-on/off drain since charge. Unknown fields are null.'),
  read('top_drainers', 'Estimated mAh per UID since charge, mapped to installed packages. Shared UIDs have one estimate.'),
  read('wakelocks', 'Top partial wakelocks with UID, name, held milliseconds and counts since charge.'),
  read('alarms_jobs', 'Wakeup alarm and job run counts per UID since charge; missing counts remain unknown.'),
  read('standby_buckets', 'Standby buckets for user-installed packages.'),
  read('background_restrictions', 'RUN_ANY_IN_BACKGROUND appops for up to 30 user-installed apps ranked by estimated drain.'),
  read('procstats', 'Sampled process PSS over 3 hours; largest background-state average is separate from total PSS. Not energy.'),
  write('set_standby_bucket', 'Set rare or restricted standby after explicit confirmation. May delay notifications/jobs. Saved prior value and manual undo command are journaled.', { bucket: { type: 'string', enum: ['rare', 'restricted'] } }),
  write('restrict_background', 'Restrict background work after explicit confirmation. May delay notifications/sync. Prior value and manual undo command are journaled.'),
  write('force_stop', 'Force-stop one installed app after explicit confirmation. NON-REVERTIBLE. Interrupts work and notifications until reopened. Never retry an uncertain result automatically.'),
];

function validate(name, args) {
  const tool = toolDefinitions.find(tool => tool.name === name);
  if (!tool) throw Error('Unknown tool. Only listed power tools are allowed.');
  if (!args || typeof args !== 'object' || Array.isArray(args)) throw Error('Arguments must be an object.');
  const keys = Object.keys(tool.inputSchema.properties);
  if (Object.keys(args).length !== keys.length || !keys.every(key => Object.hasOwn(args, key))) throw Error('Unexpected or missing arguments.');
  if (!tool.annotations.readOnlyHint) {
    if (args.confirm !== true) throw Error('Owner confirmation required: confirm must be true.');
    if (typeof args.pkg !== 'string' || args.pkg.length > 255 || !packagePattern.test(args.pkg)) throw Error('Invalid package name.');
    if (name === 'set_standby_bucket' && !['rare', 'restricted'].includes(args.bucket)) throw Error('Bucket must be rare or restricted.');
  }
  return tool;
}

function inventory(text) {
  const rows = [];
  for (const line of text.split(/\r?\n/)) {
    const match = /^package:([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)\s+uid:(\d+)\s*$/.exec(line);
    const id = match ? power.uid(match[2]) : null;
    if (match && id !== null) rows.push({ pkg: match[1], uid: id });
  }
  return rows;
}

function bounded(data, note, available = true) {
  let truncated = false;
  if (Array.isArray(data)) {
    const original = data.length;
    data = data.slice(0, 50);
    while (Buffer.byteLength(JSON.stringify(data)) > 5000 && data.length) data = data.slice(0, -1);
    truncated = data.length < original;
  }
  return { available, data, truncated, note };
}

/** run receives fixed argv, never interpolated model text. record is durable in the stdio server. */
export function createTools({ run, print, record = async () => {}, commandPrefix = ['adb', 'shell'] }) {
  const command = args => [...commandPrefix, ...args].join(' ');
  const safeRead = async args => {
    try {
      const text = await run(args);
      return /Permission Denial|SecurityException|^Error:/im.test(text) ? { text: '', ok: false } : { text, ok: text.trim().length > 0 };
    } catch { return { text: '', ok: false }; }
  };
  const packages = async usersOnly => inventory(await run(['pm', 'list', 'packages', ...(usersOnly ? ['-3'] : []), '-U', '--user', '0']));
  const stats = () => safeRead(['dumpsys', 'batterystats', '--charged']);
  const readTool = async name => {
    if (name === 'battery_overview') {
      const battery = await safeRead(['dumpsys', 'battery']), charged = await stats();
      const data = power.batteryOverview(battery.text, charged.text);
      return bounded(data, 'Health is an OS flag, not capacity. Drain is estimated and cumulative since charge; unavailable fields are null.', Object.values(data).some(value => value !== null));
    }
    if (['top_drainers', 'wakelocks', 'alarms_jobs'].includes(name)) {
      const charged = await stats();
      let data;
      if (name === 'top_drainers') {
        let apps = []; try { apps = await packages(false); } catch { /* Keep unknown UIDs without invented names. */ }
        const mapping = {};
        for (const app of apps) (mapping[app.uid] ??= []).push(app.pkg);
        data = power.topDrainers(charged.text, mapping);
      } else data = name === 'wakelocks' ? power.wakelocks(charged.text) : power.alarmsJobs(charged.text);
      return bounded(data, 'Aggregate since charge. Missing sections are unavailable, not zero. UID estimates may be shared by packages.', charged.ok && data.length > 0);
    }
    if (name === 'procstats') {
      const output = await safeRead(['dumpsys', 'procstats', '--hours', '3']);
      const data = power.procstats(output.text);
      return bounded(data, 'PSS in KiB; backgroundPssKb is the largest sampled background-state average. Memory usage is not energy.', output.ok && data.length > 0);
    }
    let apps;
    try { apps = await packages(true); } catch { return bounded([], 'Package inventory unavailable. Check adb connection.', false); }
    if (name === 'standby_buckets') {
      const output = await safeRead(['am', 'get-standby-bucket', '--user', '0']);
      const buckets = power.standbyBuckets(output.text);
      return bounded(apps.map(app => ({ pkg: app.pkg, bucket: buckets[app.pkg] ?? null })), 'Only user-installed apps; unknown buckets are null.', output.ok && Object.keys(buckets).length > 0);
    }
    const charged = await stats();
    const ranks = new Map(power.topDrainers(charged.text).map((row, index) => [row.uid, index]));
    apps.sort((a, b) => (ranks.get(a.uid) ?? Infinity) - (ranks.get(b.uid) ?? Infinity) || a.pkg.localeCompare(b.pkg));
    const rows = [];
    for (const app of apps.slice(0, 30)) {
      const output = await safeRead(['cmd', 'appops', 'get', '--user', '0', app.pkg, 'RUN_ANY_IN_BACKGROUND']);
      rows.push({ pkg: app.pkg, mode: power.backgroundRestriction(output.text) });
    }
    const result = bounded(rows, `Sampled ${rows.length} of ${apps.length} user apps. Missing modes are unknown.`, rows.some(row => row.mode !== null));
    return { ...result, truncated: result.truncated || rows.length < apps.length };
  };

  const writeTool = async (name, args) => {
    if (!(await packages(false)).some(app => app.pkg === args.pkg)) throw Error('Package is not installed for the owner profile.');
    let argv, undo = null, before = null;
    if (name === 'set_standby_bucket') {
      before = power.standbyBucket(await run(['am', 'get-standby-bucket', '--user', '0', args.pkg]));
      if (before === null) throw Error('Prior standby bucket unavailable; no change made.');
      argv = ['am', 'set-standby-bucket', '--user', '0', args.pkg, args.bucket];
      undo = ['am', 'set-standby-bucket', '--user', '0', args.pkg, String(before)];
    } else if (name === 'restrict_background') {
      before = power.packageBackgroundMode(await run(['cmd', 'appops', 'get', '--user', '0', args.pkg, 'RUN_ANY_IN_BACKGROUND']));
      if (before === null) throw Error('Prior appop unavailable; no change made.');
      argv = ['cmd', 'appops', 'set', '--user', '0', args.pkg, 'RUN_ANY_IN_BACKGROUND', 'ignore'];
      undo = ['cmd', 'appops', 'set', '--user', '0', args.pkg, 'RUN_ANY_IN_BACKGROUND', before];
    } else argv = ['am', 'force-stop', '--user', '0', args.pkg];
    const entry = { id: randomUUID(), at: new Date().toISOString(), tool: name, pkg: args.pkg, before, command: command(argv), undoCommand: undo ? command(undo) : null, revertible: undo !== null };
    await record({ ...entry, status: 'PREPARED' });
    print(entry.command); // stderr in production, before the mutation.
    let status = 'ACCEPTED';
    try { await run(argv); } catch { status = 'NEEDS_REVIEW'; }
    await record({ ...entry, status });
    return { ...entry, status, note: status === 'NEEDS_REVIEW' ? 'Outcome uncertain. Inspect live state and the journal; do not automatically retry.' : undo ? 'Command accepted. Prior setting saved; verify live state before manual undo. Android can subsequently change this setting.' : 'Command accepted. Cannot undo force-stop; reopen the app yourself when needed.' };
  };
  return {
    async call(name, args = {}) {
      const tool = validate(name, args);
      return tool.annotations.readOnlyHint ? readTool(name) : writeTool(name, args);
    },
  };
}
