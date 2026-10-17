import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import * as power from './parsers.mjs';
import { createTools, toolDefinitions } from './tools.mjs';

const fixture = async name => readFile(new URL(`../app/src/test/resources/fixtures/power/${name}.txt`, import.meta.url), 'utf8');
const packages = { 10123: ['org.example.sync', 'org.example.shared'], 10124: ['org.example.music'] };

test('overview preserves actual units and unknown health capacity', async () => {
  const value = power.batteryOverview(await fixture('battery'), await fixture('batterystats-charged'));
  assert.equal(value.levelPct, 73);
  assert.equal(value.health, 'good');
  assert.equal(value.temperatureC, 32.4);
  assert.equal(value.screenOnDrainMah, 100);
  assert.equal(value.screenOffDrainMah, 210);
  assert.equal(value.onBatteryMs, 11525120);
  assert.equal(value.startClock, '2026-10-17-08-00-00');
});

test('drainers map multiuser and shared UIDs only once', async () => {
  const rows = power.topDrainers(await fixture('batterystats-charged'), packages);
  assert.deepEqual(rows.map(row => row.uid), [10123, 10124, 1000, 1010125]);
  assert.equal(rows[0].estimatedMah, 123.45);
  assert.deepEqual(rows[0].packages, packages[10123]);
  assert.deepEqual(rows[3].packages, []);
  assert.equal(power.topDrainers(await fixture('batterystats-oneui'), packages)[0].estimatedMah, 42);
});

test('partial locks exclude full and kernel locks and reject overflow', async () => {
  const rows = power.wakelocks(await fixture('batterystats-charged'));
  assert.equal(rows.length, 4);
  assert.deepEqual(rows[0], { uid: 10123, name: '*job*/org.example.sync/.Upload', totalTimeMs: 3723004, count: 12 });
  const samsung = power.wakelocks(await fixture('batterystats-oneui'));
  assert.equal(samsung.length, 1);
  assert.equal(samsung[0].totalTimeMs, 123000);
});

test('alarms and jobs aggregate independently and preserve missing counts', async () => {
  const rows = power.alarmsJobs(await fixture('alarms-jobs'));
  assert.deepEqual(rows, [{ uid: 10123, alarmCount: 14, jobCount: 8 }, { uid: 10124, alarmCount: 0, jobCount: null }]);
});

test('standby and appops variants keep unreadable values unknown', async () => {
  assert.deepEqual(power.standbyBuckets(await fixture('standby')), { 'org.example.sync': 40, 'org.example.music': 20, 'org.example.reader': 45 });
  assert.equal(power.standbyBucket(await fixture('standby-single')), 45);
  assert.equal(power.standbyBucket('org.example.sync: bucket=40'), 40);
  assert.equal(power.standbyBucket('org.example.sync: 20'), 20);
  assert.equal(power.standbyBucket('Permission Denial: bucket=40'), null);
  assert.equal(power.standbyBucket('garbage bucket=40'), null);
  assert.equal(power.backgroundRestriction(await fixture('appops')), 'ignore');
  assert.equal(power.backgroundRestriction(await fixture('appops-default')), 'default');
  assert.equal(power.backgroundRestriction('Uid mode: RUN_ANY_IN_BACKGROUND: deny'), 'deny');
});

test('procstats exposes average PSS and background observations separately', async () => {
  const rows = power.procstats(await fixture('procstats'));
  assert.equal(rows.length, 2);
  assert.equal(rows[0].process, 'org.example.sync');
  assert.equal(rows[0].averagePssKb, 184320);
  assert.equal(rows[0].backgroundPssKb, 143360);
});

test('overflow PSS remains unknown instead of becoming zero', () => {
  const rows = power.procstats('  * org.example.sync / u0a123 / v42:\n    TOTAL: 80% (100MB-1e999MB-240MB/70MB-100MB-160MB)\n');
  assert.deepEqual(rows, []);
});

test('missing denied malformed and overflow data never fabricate measurements', async () => {
  for (const text of ['', await fixture('denied'), 'garbage', 'u99999999999999999a999:\nWake lock x: -1s partial (4 times)']) {
    assert.equal(power.batteryOverview(text, text).levelPct, null);
    assert.deepEqual(power.topDrainers(text, packages), []);
    assert.deepEqual(power.wakelocks(text), []);
    assert.deepEqual(power.alarmsJobs(text), []);
    assert.deepEqual(power.procstats(text), []);
    assert.deepEqual(power.standbyBuckets(text), {});
    assert.equal(power.backgroundRestriction(text), null);
  }
});

function fakeDevice() {
  const calls = [], printed = [];
  const tools = createTools({
    run: async args => {
      calls.push(args);
      if (args.includes('list')) return 'package:org.example.sync uid:10123\npackage:org.example.music uid:10124\n';
      if (args.includes('get-standby-bucket')) return '20';
      if (args.includes('appops') && args.includes('get')) return 'RUN_ANY_IN_BACKGROUND: allow';
      return '';
    },
    print: line => printed.push(line),
  });
  return { tools, calls, printed };
}

test('write schema and runtime require explicit true and exact installed package', async () => {
  const { tools, calls } = fakeDevice();
  for (const args of [{ pkg: 'org.example.sync' }, { pkg: 'org.example.sync', confirm: 'true' }, { pkg: 'org.example.sync', confirm: false }, { pkg: 'org.example.sync;reboot', confirm: true }, { pkg: 'org.example.missing', confirm: true }, { pkg: 'org.example.sync', confirm: true, shell: 'reboot' }]) {
    await assert.rejects(tools.call('force_stop', args));
  }
  await assert.rejects(tools.call('shell', { command: 'reboot' }));
  await assert.rejects(tools.call('set_standby_bucket', { pkg: 'org.example.sync', bucket: 'active', confirm: true }));
  assert.equal(calls.filter(args => args.includes('force-stop')).length, 0);
  assert.equal(toolDefinitions.length, 10);
  for (const tool of toolDefinitions.filter(t => t.annotations.readOnlyHint === false)) assert.ok(tool.inputSchema.required.includes('confirm'));
});

test('confirmed writes print exact argv before execution and give manual restore commands', async () => {
  const { tools, calls, printed } = fakeDevice();
  const result = await tools.call('set_standby_bucket', { pkg: 'org.example.sync', bucket: 'rare', confirm: true });
  assert.deepEqual(calls.at(-1), ['am', 'set-standby-bucket', '--user', '0', 'org.example.sync', 'rare']);
  assert.equal(printed.at(-1), 'adb shell am set-standby-bucket --user 0 org.example.sync rare');
  assert.equal(result.undoCommand, 'adb shell am set-standby-bucket --user 0 org.example.sync 20');
  const stop = await tools.call('force_stop', { pkg: 'org.example.sync', confirm: true });
  assert.equal(stop.revertible, false);
  assert.equal(stop.undoCommand, null);
});

test('read tools reject shell arguments and propagate device read failure as unavailable', async () => {
  const { tools } = fakeDevice();
  await assert.rejects(tools.call('battery_overview', { command: 'reboot' }));
  const denied = createTools({ run: async () => { throw Error('offline'); }, print: () => {} });
  const result = await denied.call('battery_overview', {});
  assert.equal(result.available, false);
  assert.equal(result.data.levelPct, null);
});

test('write journals intent before adb and journal failure blocks mutation', async () => {
  const events = [];
  const tools = createTools({
    run: async args => { events.push(args.includes('force-stop') ? 'mutation' : 'inventory'); return args.includes('list') ? 'package:org.example.sync uid:10123' : ''; },
    print: () => events.push('print'), record: async row => events.push(row.status),
  });
  await tools.call('force_stop', { pkg: 'org.example.sync', confirm: true });
  assert.deepEqual(events, ['inventory', 'PREPARED', 'print', 'mutation', 'ACCEPTED']);
  const blocked = createTools({
    run: async args => { assert.ok(!args.includes('force-stop')); return 'package:org.example.sync uid:10123'; },
    print: () => {}, record: async () => { throw Error('disk full'); },
  });
  await assert.rejects(blocked.call('force_stop', { pkg: 'org.example.sync', confirm: true }));
});

test('unreadable prior state blocks reversible writes and uncertain force-stop is never retried', async () => {
  let writes = 0;
  const rows = [];
  const tools = createTools({
    run: async args => {
      if (args.includes('list')) return 'package:org.example.sync uid:10123';
      if (args.includes('force-stop')) { writes++; throw Error('device disconnected'); }
      return 'Permission Denial';
    }, print: () => {}, record: async row => rows.push(row),
  });
  await assert.rejects(tools.call('restrict_background', { pkg: 'org.example.sync', confirm: true }));
  await assert.rejects(tools.call('set_standby_bucket', { pkg: 'org.example.sync', bucket: 'rare', confirm: true }));
  const result = await tools.call('force_stop', { pkg: 'org.example.sync', confirm: true });
  assert.equal(result.status, 'NEEDS_REVIEW');
  assert.equal(writes, 1);
  assert.equal(rows[0].status, 'PREPARED');
});

test('UID appop mode cannot masquerade as the package prior value for undo', async () => {
  const tools = createTools({
    run: async args => {
      assert.ok(!args.includes('set'));
      return args.includes('list') ? 'package:org.example.sync uid:10123' : 'Uid mode: RUN_ANY_IN_BACKGROUND: ignore';
    }, print: () => {},
  });
  await assert.rejects(tools.call('restrict_background', { pkg: 'org.example.sync', confirm: true }), /Prior appop unavailable/);
});
