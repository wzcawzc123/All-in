'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const browser = require('./browser-fixture.cjs');

process.env.ALLIN_TEST_TMP ||= path.resolve('build');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/dashboard.html'), 'utf8');

const css = [...html.slice(0, html.indexOf('<body')).matchAll(/<style[^>]*>[\s\S]*?<\/style>/g)]
  .map(match => match[0]).join('');

const tabMarkup = html
  .slice(html.indexOf('<section id="tab-routing"'), html.indexOf('<section id="tab-insight"'))
  .replace('class="tab-pane"', 'class="tab-pane active"');

const sheetMarkup = html.slice(
  html.indexOf('<div id="route-picker-backdrop"'),
  html.indexOf('<div id="app-update-modal"')
);

const routingStateSlice = html.slice(
  html.indexOf('    /** 路由组开关状态'),
  html.indexOf('    let providerConfigRequest = 0')
);

const routeUiAndGroupsSlice = html.slice(
  html.indexOf('    // ===== 路由页交互辅助'),
  html.indexOf('    async function saveProviders()')
);

const page = `<!doctype html>
<html class="glass-ui" data-theme="light">
<head>
<meta name="viewport" content="width=device-width,initial-scale=1">
${css}
<style>
  html, body { margin: 0; padding: 0; }
  #test-shell { width: 390px; max-width: 390px; box-sizing: border-box; padding: 14px; }
</style>
</head>
<body class="liquid-glass">
<main id="test-shell">${tabMarkup}</main>
${sheetMarkup}
<pre id="result">WAIT</pre>
<script>
const API_BASE = '';
const toasts = [];
let appConfig = {
  providers: [
    {
      id: 'openai', name: 'OpenAI', enabled: true,
      selectedModels: ['gpt-4o', 'gpt-4.1-mini', 'o4-mini'],
      models: ['gpt-4o', 'gpt-4.1-mini', 'o4-mini']
    },
    {
      id: 'deepseek', name: 'DeepSeek', enabled: true,
      selectedModels: ['deepseek-chat', 'deepseek-reasoner'],
      models: ['deepseek-chat', 'deepseek-reasoner']
    }
  ],
  groups: [
    {
      id: 'main-chat', name: '主力对话', strategy: 'order',
      targets: ['openai/gpt-4o'], pick: ''
    }
  ]
};

function showToast(msg) { toasts.push(String(msg)); }
function errText(e) { return (e && e.message) || String(e || 'error'); }
function copyText() {}
function visibleProviders(list) { return Array.isArray(list) ? list : []; }
function providerHasCredential(p) { return !!(p && p.enabled); }
function pingModelsOf(p) { return (p && p.models) || []; }
window.confirm = () => true;
async function loadConfig() { return appConfig; }

let serverGroups = JSON.parse(JSON.stringify(appConfig.groups));
const postQueue = [];
let postCount = 0;
let nextGetGroupsMode = 'normal';

window.fetch = function(url, init) {
  const p = String(url);
  const method = (init && init.method) || 'GET';
  const body = init && init.body ? JSON.parse(init.body) : null;
  if (p === '/api/routing') {
    return Promise.resolve({ ok: true, json: async () => ({ ok: true, enabled: true, bareModelFallback: false }) });
  }
  if (p === '/api/groups' && method === 'GET') {
    if (nextGetGroupsMode === 'offline') return Promise.reject(new TypeError('offline'));
    return Promise.resolve({
      ok: true,
      json: async () => ({ ok: true, groups: JSON.parse(JSON.stringify(serverGroups)) })
    });
  }
  if (p === '/api/config' && method === 'GET') {
    if (nextGetGroupsMode === 'offline') return Promise.reject(new TypeError('offline'));
    return Promise.resolve({
      ok: true,
      json: async () => ({ ok: true, groups: JSON.parse(JSON.stringify(serverGroups)), providers: appConfig.providers })
    });
  }
  if (p === '/api/groups' && method === 'POST') {
    postCount++;
    return new Promise((resolve, reject) => {
      postQueue.push({ body: JSON.parse(JSON.stringify(body)), resolve, reject });
    });
  }
  return Promise.resolve({ ok: false, status: 404, json: async () => ({ ok: false }) });
};

${routingStateSlice}
${routeUiAndGroupsSlice}

const flush = () => new Promise(r => setTimeout(r, 0));

function sheetRowFor(ref) {
  return Array.from(document.querySelectorAll('#route-sheet-list .route-sheet-item'))
    .find(el => el.textContent.includes(ref));
}

(async function run() {
  const assertTrue = (c, m) => { if (!c) throw new Error(m); };
  try {
    renderGroups(true);
    bindGroupEvents();
    await loadRoutingState();
    document.querySelector('[data-action="open-picker"]').click();
    await flush();

    // Case 1: Server explicitly rejects add -> NO '已加入' toast, NO fake checked state
    toasts.length = 0;
    sheetRowFor('openai/o4-mini').click();
    await flush();
    assertTrue(postQueue.length === 1, 'Case 1: expected 1 pending POST');
    assertTrue(sheetRowFor('openai/o4-mini').textContent.includes('添加中'), 'Case 1: expected pending pill before server reply');
    assertTrue(!toasts.some(t => t.includes('已加入')), 'Case 1: must NOT toast success before server confirmation');
    postQueue.shift().resolve({
      ok: false, status: 400,
      json: async () => ({ ok: false, error: { message: '目标模型已禁用' } })
    });
    await flush(); await flush();
    assertTrue(toasts.some(t => t.includes('保存失败：目标模型已禁用')), 'Case 1: missing rejection failure toast');
    assertTrue(!toasts.some(t => t.includes('已加入')), 'Case 1: leaked false success toast on rejection');
    assertTrue(sheetRowFor('openai/o4-mini').textContent.includes('+ 添加'), 'Case 1: rejected model must roll back in sheet');
    assertTrue(!document.querySelector('.route-card .route-member-list').textContent.includes('o4-mini'), 'Case 1: rejected model must roll back in member list');
    assertTrue(!groupDraft[0].targets.includes('openai/o4-mini'), 'Case 1: groupDraft must not keep rejected model');
    assertTrue(serverGroups[0].targets.length === 1, 'Case 1: server state remained intact');

    // Case 2: Rapid consecutive clicks on two models -> serialized, second inherits first, neither lost
    toasts.length = 0;
    const beforePost2 = postCount;
    const pA = sheetRowFor('openai/gpt-4.1-mini').click();
    const pB = sheetRowFor('deepseek/deepseek-chat').click();
    await flush();
    assertTrue(postCount === beforePost2 + 1 && postQueue.length === 1, 'Case 2: second POST must wait in queue until first completes');
    assertTrue(sheetRowFor('openai/gpt-4.1-mini').textContent.includes('添加中'), 'Case 2: first row should show pending');
    assertTrue(sheetRowFor('deepseek/deepseek-chat').textContent.includes('添加中'), 'Case 2: second row should show pending');
    const firstReq = postQueue.shift();
    assertTrue(JSON.stringify(firstReq.body.groups[0].targets) === JSON.stringify(['openai/gpt-4o', 'openai/gpt-4.1-mini']), 'Case 2: wrong first POST payload');
    serverGroups = JSON.parse(JSON.stringify(firstReq.body.groups));
    firstReq.resolve({ ok: true, status: 200, json: async () => ({ ok: true, groups: JSON.parse(JSON.stringify(serverGroups)) }) });
    await flush(); await flush();
    assertTrue(toasts.some(t => t === '已加入 openai/gpt-4.1-mini'), 'Case 2: first toast should appear after confirm 1');
    assertTrue(postCount === beforePost2 + 2 && postQueue.length === 1, 'Case 2: second queued POST should now fire');
    const secondReq = postQueue.shift();
    assertTrue(
      JSON.stringify(secondReq.body.groups[0].targets) === JSON.stringify(['openai/gpt-4o', 'openai/gpt-4.1-mini', 'deepseek/deepseek-chat']),
      'Case 2: second POST lost first selection: ' + JSON.stringify(secondReq.body.groups[0].targets)
    );
    serverGroups = JSON.parse(JSON.stringify(secondReq.body.groups));
    secondReq.resolve({ ok: true, status: 200, json: async () => ({ ok: true, groups: JSON.parse(JSON.stringify(serverGroups)) }) });
    await flush(); await flush();
    assertTrue(toasts.some(t => t === '已加入 deepseek/deepseek-chat'), 'Case 2: second toast should appear after confirm 2');
    assertTrue(sheetRowFor('openai/gpt-4.1-mini').textContent.includes('✓ 已添加'), 'Case 2: model A confirmed');
    assertTrue(sheetRowFor('deepseek/deepseek-chat').textContent.includes('✓ 已添加'), 'Case 2: model B confirmed');
    assertTrue(document.querySelector('.route-meta-count').textContent === '3 个目标', 'Case 2: card must show all 3 targets');

    // Case 3: Ambiguous network error where server actually committed vs didn't commit -> readback reconciles
    toasts.length = 0;
    nextGetGroupsMode = 'normal';
    sheetRowFor('openai/o4-mini').click();
    await flush();
    const amb1 = postQueue.shift();
    // Simulate server committed before client saw connection reset
    serverGroups = JSON.parse(JSON.stringify(amb1.body.groups));
    amb1.reject(new TypeError('network reset'));
    await flush(); await flush(); await flush();
    assertTrue(toasts.some(t => t.includes('已生效')), 'Case 3a: expected readback confirmed toast, got ' + JSON.stringify(toasts));
    assertTrue(sheetRowFor('openai/o4-mini').textContent.includes('✓ 已添加'), 'Case 3a: UI must reflect readback saved item');

    // Case 3b: Network error where server did NOT save -> readback reconciles back
    toasts.length = 0;
    sheetRowFor('deepseek/deepseek-reasoner').click();
    await flush();
    const amb2 = postQueue.shift();
    // Server did NOT save deepseek-reasoner
    amb2.reject(new TypeError('timeout'));
    await flush(); await flush(); await flush();
    assertTrue(toasts.some(t => t.includes('未保存，已回退')), 'Case 3b: expected readback rollback toast, got ' + JSON.stringify(toasts));
    assertTrue(sheetRowFor('deepseek/deepseek-reasoner').textContent.includes('+ 添加'), 'Case 3b: UI must roll back per readback');

    // Case 4: Full offline (POST & readback both fail) -> roll back draft without fake checkmark, retain pending item for recovery
    toasts.length = 0;
    nextGetGroupsMode = 'offline';
    sheetRowFor('deepseek/deepseek-reasoner').click();
    await flush();
    postQueue.shift().reject(new TypeError('offline'));
    await flush(); await flush(); await flush();
    assertTrue(toasts.some(t => t.includes('网络断开')), 'Case 4: expected offline toast');
    assertTrue(!sheetRowFor('deepseek/deepseek-reasoner').textContent.includes('✓ 已添加'), 'Case 4: offline change must NOT show confirmed check');
    assertTrue(sheetRowFor('deepseek/deepseek-reasoner').textContent.includes('添加中'), 'Case 4: retained Write #1 must remain pending sync');
    assertTrue(routeSyncUncertain === true && routePendingMutations.length === 1, 'Case 4: Write #1 must stay queued');
    assertTrue(document.querySelector('.route-meta-count').textContent === '4 个目标', 'Case 4: count must remain 4');

    // Case 5: Move-up then click delete on B before POST 1 returns -> must delete B, never A
    nextGetGroupsMode = 'normal';
    routeSyncUncertain = false;
    routePendingMutations.length = 0;
    routePendingSheetKeys.clear();
    appConfig.groups = [{
      id: 'main-chat', name: '主力对话', strategy: 'order',
      targets: ['openai/gpt-4o', 'openai/gpt-4.1-mini', 'deepseek/deepseek-chat'], pick: ''
    }];
    serverGroups = JSON.parse(JSON.stringify(appConfig.groups));
    groupDraft = routeCloneGroups(appConfig.groups);
    groupsSig = '';
    renderGroups(true, true);
    await flush();
    const rowBtnsBeforeMove = () => Array.from(document.querySelectorAll('.route-card .route-member-pill'));
    assertTrue(rowBtnsBeforeMove().length === 3, 'Case 5: expected 3 initial rows');
    const bUpBtn = rowBtnsBeforeMove()[1].querySelector('[data-action="up"]');
    const bDelBtn = rowBtnsBeforeMove()[1].querySelector('[data-action="del"]');
    assertTrue(bUpBtn.getAttribute('data-ref') === 'openai/gpt-4.1-mini' && bDelBtn.getAttribute('data-ref') === 'openai/gpt-4.1-mini',
      'Case 5: expected stable data-ref on move/del buttons');
    bUpBtn.click();
    // Click delete on B's screen node BEFORE POST 1 returns
    bDelBtn.click();
    await flush();
    assertTrue(postQueue.length === 1, 'Case 5: expected 1 active POST while del queues');
    const moveReq = postQueue.shift();
    assertTrue(JSON.stringify(moveReq.body.groups[0].targets) === JSON.stringify(['openai/gpt-4.1-mini', 'openai/gpt-4o', 'deepseek/deepseek-chat']),
      'Case 5: POST 1 must reorder [B,A,C], got ' + JSON.stringify(moveReq.body.groups[0].targets));
    serverGroups = JSON.parse(JSON.stringify(moveReq.body.groups));
    moveReq.resolve({ ok: true, status: 200, json: async () => ({ ok: true, groups: JSON.parse(JSON.stringify(serverGroups)) }) });
    await flush(); await flush();
    assertTrue(postQueue.length === 1, 'Case 5: expected queued POST 2 after POST 1');
    const delReq = postQueue.shift();
    assertTrue(JSON.stringify(delReq.body.groups[0].targets) === JSON.stringify(['openai/gpt-4o', 'deepseek/deepseek-chat']),
      'Case 5: POST 2 must delete B leaving [A,C], got ' + JSON.stringify(delReq.body.groups[0].targets));
    serverGroups = JSON.parse(JSON.stringify(delReq.body.groups));
    delReq.resolve({ ok: true, status: 200, json: async () => ({ ok: true, groups: JSON.parse(JSON.stringify(serverGroups)) }) });
    await flush(); await flush();
    assertTrue(JSON.stringify(appConfig.groups[0].targets) === JSON.stringify(['openai/gpt-4o', 'deepseek/deepseek-chat']),
      'Case 5: final confirmed config must retain A and C');

    // Case 6: Uncertain write #1 (server saved, POST drops, both readbacks fail) pauses queued write #2,
    // then next mutation after connectivity recovers reads back write #1 and merges cleanly without overwrite.
    toasts.length = 0;
    nextGetGroupsMode = 'offline';
    const postCountBeforeUncertain = postCount;
    sheetRowFor('deepseek/deepseek-reasoner').click();
    sheetRowFor('openai/o4-mini').click();
    await flush();
    const uncReq = postQueue.shift();
    // Server actually persisted write #1 ([A, C, deepseek-reasoner]), but client loses response & readbacks fail
    serverGroups = JSON.parse(JSON.stringify(uncReq.body.groups));
    uncReq.reject(new TypeError('socket hangup'));
    await flush(); await flush(); await flush();
    assertTrue(postCount === postCountBeforeUncertain + 1 && postQueue.length === 0,
      'Case 6: queued write #2 must pause without posting stale snapshot when server state is uncertain');
    assertTrue(routeSyncUncertain === true, 'Case 6: routeSyncUncertain flag should remain true');
    assertTrue(JSON.stringify(serverGroups[0].targets) === JSON.stringify(['openai/gpt-4o', 'deepseek/deepseek-chat', 'deepseek/deepseek-reasoner']),
      'Case 6: server must still retain write #1 intact');
    // Connectivity recovers -> next mutation reconciles [A, C, deepseek-reasoner] first and merges o4-mini
    nextGetGroupsMode = 'normal';
    toasts.length = 0;
    sheetRowFor('openai/o4-mini').click();
    await flush(); await flush();
    assertTrue(postQueue.length === 1, 'Case 6: recovered write should post merged config');
    const mergedReq = postQueue.shift();
    assertTrue(JSON.stringify(mergedReq.body.groups[0].targets) === JSON.stringify(['openai/gpt-4o', 'deepseek/deepseek-chat', 'deepseek/deepseek-reasoner', 'openai/o4-mini']),
      'Case 6: merged POST must preserve write #1 + add write #2, got ' + JSON.stringify(mergedReq.body.groups[0].targets));
    serverGroups = JSON.parse(JSON.stringify(mergedReq.body.groups));
    mergedReq.resolve({ ok: true, status: 200, json: async () => ({ ok: true, groups: JSON.parse(JSON.stringify(serverGroups)) }) });
    await flush(); await flush();
    assertTrue(routeSyncUncertain === false, 'Case 6: routeSyncUncertain clears after confirmed sync');

    // Case 7: HTTP 503 with {"ok":false} when server actually persisted write #1 + both readbacks fail:
    // write #2 pauses without second POST and stays queued; after network recovers + retry/online,
    // final config contains BOTH write #1 and write #2 without overwrite.
    toasts.length = 0;
    appConfig.groups = [{
      id: 'main-chat', name: '主力对话', strategy: 'order',
      targets: ['openai/gpt-4o', 'deepseek/deepseek-chat'], pick: ''
    }];
    serverGroups = JSON.parse(JSON.stringify(appConfig.groups));
    groupDraft = routeCloneGroups(appConfig.groups);
    groupsSig = '';
    renderGroups(true, true);
    renderRoutePickerSheet();
    await flush();

    nextGetGroupsMode = 'offline';
    const postCountCase7 = postCount;
    // Queue Write #1 (+deepseek-reasoner) and Write #2 (+openai/o4-mini)
    sheetRowFor('deepseek/deepseek-reasoner').click();
    sheetRowFor('openai/o4-mini').click();
    await flush();
    assertTrue(postQueue.length === 1 && postCount === postCountCase7 + 1, 'Case 7: expected only POST 1 in flight');
    const c7Req1 = postQueue.shift();
    // Server persists Write #1, but responds with HTTP 503 + {"ok":false} AND both readbacks fail!
    serverGroups = JSON.parse(JSON.stringify(c7Req1.body.groups));
    c7Req1.resolve({
      ok: false, status: 503,
      json: async () => ({ ok: false, error: { message: 'backend temporary unavailable' } })
    });
    await flush(); await flush(); await flush();
    assertTrue(postCount === postCountCase7 + 1 && postQueue.length === 0,
      'Case 7: must NOT send POST 2 while 503 + offline readback leaves state unconfirmed, postCount=' + postCount);
    assertTrue(routeSyncUncertain === true && routePendingMutations.length === 2,
      'Case 7: expected uncertain state with 2 retained pending mutations, got uncertain=' + routeSyncUncertain + ' pending=' + routePendingMutations.length);
    assertTrue(JSON.stringify(serverGroups[0].targets) === JSON.stringify(['openai/gpt-4o', 'deepseek/deepseek-chat', 'deepseek/deepseek-reasoner']),
      'Case 7: server must retain Write #1');
    const retryBtn = document.querySelector('#groups-container [data-action="retry-route-sync"]');
    assertTrue(!!retryBtn, 'Case 7: expected retry banner button in #groups-container');

    // Recover network and trigger retry button (no manual re-click on o4-mini needed)
    nextGetGroupsMode = 'normal';
    retryBtn.click();
    await flush(); await flush(); await flush();
    assertTrue(postCount === postCountCase7 + 2 && postQueue.length === 1,
      'Case 7: expected merged retry POST after connectivity recovers');
    const c7MergedReq = postQueue.shift();
    assertTrue(JSON.stringify(c7MergedReq.body.groups[0].targets) === JSON.stringify([
      'openai/gpt-4o', 'deepseek/deepseek-chat', 'deepseek/deepseek-reasoner', 'openai/o4-mini'
    ]), 'Case 7: merged retry POST lost Write #1 or #2: ' + JSON.stringify(c7MergedReq.body.groups[0].targets));
    serverGroups = JSON.parse(JSON.stringify(c7MergedReq.body.groups));
    c7MergedReq.resolve({ ok: true, status: 200, json: async () => ({ ok: true, groups: JSON.parse(JSON.stringify(serverGroups)) }) });
    await flush(); await flush();
    assertTrue(routeSyncUncertain === false && routePendingMutations.length === 0, 'Case 7: clean settled state');
    assertTrue(!document.querySelector('#groups-container [data-action="retry-route-sync"]'), 'Case 7: retry banner cleared after settle');
    assertTrue(JSON.stringify(appConfig.groups[0].targets) === JSON.stringify([
      'openai/gpt-4o', 'deepseek/deepseek-chat', 'deepseek/deepseek-reasoner', 'openai/o4-mini'
    ]), 'Case 7: final appConfig preserved both changes');

    document.getElementById('result').textContent = 'PASS';
  } catch (err) {
    document.getElementById('result').textContent = 'FAIL ' + (err && err.stack ? err.stack : err.message);
  }
})();
</script>
</body>
</html>`;

const domOut = browser.dom(page, 2500);
const result = domOut.match(/id="result"[^>]*>([\s\S]*?)<\/pre>/)?.[1]?.trim() || 'NO_RESULT';
assert.equal(result, 'PASS', result);

// Extract production loadConfig for auto-recovery + server conflict + double-disconnect integration suite
const loadConfigSlice = html.slice(
  html.indexOf('    async function loadConfig() {'),
  html.indexOf('\n    // 记住上一次各供应商', html.indexOf('    async function loadConfig() {'))
);

const recoveryPage = `<!doctype html>
<html class="glass-ui" data-theme="light">
<head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
<body class="liquid-glass">
<main id="test-shell">${tabMarkup}</main>
${sheetMarkup}
<pre id="res_rec">WAIT</pre>
<script>
const API_BASE = '';
const toasts = [];
let providerConfigRequest = 0, providerWriteRevision = 0, providerWrites = new Set(), providerConfigError = false;
let lastConfigSig = '', renderDirty = false;
let appConfig = {
  providers: [
    { id: 'openai', name: 'OpenAI', enabled: true, selectedModels: ['gpt-4o', 'gpt-4.1-mini', 'o4-mini'], models: ['gpt-4o', 'gpt-4.1-mini', 'o4-mini'] },
    { id: 'deepseek', name: 'DeepSeek', enabled: true, selectedModels: ['deepseek-chat', 'deepseek-reasoner'], models: ['deepseek-chat', 'deepseek-reasoner'] }
  ],
  failover: { maxAttempts: 7 },
  groups: [
    { id: 'main-chat', name: '主力对话', strategy: 'order', targets: ['openai/gpt-4o', 'openai/gpt-4.1-mini', 'deepseek/deepseek-chat'], pick: '' },
    { id: 'g-del', name: '将被删除组', strategy: 'order', targets: ['openai/gpt-4o'], pick: '' }
  ]
};
function showToast(m) { toasts.push(String(m)); }
function errText(e) { return (e && e.message) || String(e || 'err'); }
function copyText() {}
function visibleProviders(list) { return Array.isArray(list) ? list : []; }
function providerHasCredential(p) { return !!(p && p.enabled); }
function pingModelsOf(p) { return (p && p.models) || []; }
function renderProviderStatus() {}
async function reconcileProviderReadback() {}
function refreshHomeQuota() {}
function renderProviderList() {}
function flushCommercialRender() {}
function applyProviderView() {}
function autoSyncAfterLogin() {}
function renderCommercialKeys() {}
function renderWbAccounts() {}
function renderWbModelTags() {}
function renderAllProvidersCards() {}
function renderTraeAccounts() {}
function refreshProviderBadges() {}
function applyPlaceholderGuard() {}
function renderCredentials() {}
function renderMiddleware() {}
function renderPingProviders() {}
function renderAllModelsTags() {}
window.confirm = () => true;

let serverGroups = JSON.parse(JSON.stringify(appConfig.groups));
let serverFailover = { maxAttempts: 7 };
const postQueue = [];
let postCount = 0;
let getMode = 'normal';

window.fetch = function(url, init) {
  const p = String(url);
  const method = (init && init.method) || 'GET';
  const body = init && init.body ? JSON.parse(init.body) : null;
  if (p === '/api/routing') return Promise.resolve({ ok: true, json: async () => ({ ok: true, enabled: true, bareModelFallback: false }) });
  if (p === '/api/groups' && method === 'GET') {
    if (getMode === 'offline') return Promise.reject(new TypeError('offline'));
    return Promise.resolve({ ok: true, json: async () => ({ ok: true, groups: JSON.parse(JSON.stringify(serverGroups)) }) });
  }
  if (p === '/api/config' && method === 'GET') {
    if (getMode === 'offline') return Promise.reject(new TypeError('offline'));
    return Promise.resolve({
      ok: true,
      json: async () => ({
        providers: JSON.parse(JSON.stringify(appConfig.providers)),
        failover: JSON.parse(JSON.stringify(serverFailover)),
        groups: JSON.parse(JSON.stringify(serverGroups))
      })
    });
  }
  if (p === '/api/groups' && method === 'POST') {
    postCount++;
    return new Promise((resolve, reject) => {
      postQueue.push({ body: JSON.parse(JSON.stringify(body)), resolve, reject });
    });
  }
  return Promise.resolve({ ok: false, status: 404, json: async () => ({ ok: false }) });
};

${routingStateSlice}
${loadConfigSlice}
${routeUiAndGroupsSlice}

const flush = () => new Promise(r => setTimeout(r, 0));
function sheetRowFor(ref) {
  return Array.from(document.querySelectorAll('#route-sheet-list .route-sheet-item')).find(el => el.textContent.includes(ref));
}

(async function runRecoverySuite() {
  const assertTrue = (c, m) => { if (!c) throw new Error(m); };
  renderGroups(true);
  bindGroupEvents();
  await loadRoutingState();
  document.querySelector('[data-action="open-picker"]').click();
  await flush();

  // Suite R1: First POST saved on server (adds o4-mini), returns 503 + {"ok":false}, dual readback fails.
  // Queue Write #2 (adds deepseek-reasoner). Before auto-recovery, server ALSO updates name & adds gpt-4.1-mini
  // (already present) / unrelated failover field changes, and deletes B? First test server adds + rename + failover.
  getMode = 'offline';
  sheetRowFor('openai/o4-mini').click();
  sheetRowFor('deepseek/deepseek-reasoner').click();
  await flush();
  assertTrue(postCount === 1 && postQueue.length === 1, 'R1: expected POST 1 only');
  const r1Req1 = postQueue.shift();
  serverGroups = JSON.parse(JSON.stringify(r1Req1.body.groups));
  r1Req1.resolve({ ok: false, status: 503, json: async () => ({ ok: false, error: { message: 'busy' } }) });
  await flush(); await flush(); await flush();
  assertTrue(routeSyncUncertain === true && routePendingMutations.length === 2 && postCount === 1,
    'R1: paused with 2 pending mutations, uncertain=' + routeSyncUncertain + ' pending=' + routePendingMutations.length);

  // Before recovery, another client on server renames main-chat, keeps Write #1 (o4-mini), and updates failover
  serverGroups[0].name = '服务端已改名';
  serverFailover = { maxAttempts: 9, note: 'keep-me' };
  getMode = 'normal';

  // Trigger AUTOMATIC recovery via /api/config poll (loadConfig)!
  await loadConfig();
  await flush(); await flush(); await flush();
  assertTrue(postCount === 2 && postQueue.length === 1, 'R1: loadConfig auto recovery should submit 1 merged POST, postCount=' + postCount);
  const r1AutoPost = postQueue.shift();
  assertTrue(r1AutoPost.body.groups[0].name === '服务端已改名',
    'R1: auto recovery overwrote server group name with old groupDraft! got ' + r1AutoPost.body.groups[0].name);
  assertTrue(JSON.stringify(r1AutoPost.body.groups[0].targets) === JSON.stringify([
    'openai/gpt-4o', 'openai/gpt-4.1-mini', 'deepseek/deepseek-chat', 'openai/o4-mini', 'deepseek/deepseek-reasoner'
  ]), 'R1: auto recovery POST must keep Write #1 + server state + Write #2, got ' + JSON.stringify(r1AutoPost.body.groups[0].targets));
  assertTrue(appConfig.failover && appConfig.failover.maxAttempts === 9, 'R1: unrelated config fields must be preserved');

  // Suite R2: Now simulate RECOVERY POST DISCONNECTING AGAIN (and dual readbacks failing)!
  // Pending mutation (Write #2) MUST NOT be dropped; subsequent manual retry MUST still keep it!
  getMode = 'offline';
  r1AutoPost.reject(new TypeError('recovery socket reset'));
  await flush(); await flush(); await flush();
  assertTrue(routeSyncUncertain === true && routePendingMutations.length === 2,
    'R2: failed retry MUST preserve pending mutations! pending=' + routePendingMutations.length + ' uncertain=' + routeSyncUncertain);
  const retryBannerBtn = document.querySelector('#groups-container [data-action="retry-route-sync"]');
  assertTrue(!!retryBannerBtn, 'R2: retry banner button must remain visible after retry disconnect');

  // Recover and manually trigger retry button
  getMode = 'normal';
  retryBannerBtn.click();
  await flush(); await flush(); await flush();
  assertTrue(postCount === 3 && postQueue.length === 1, 'R2: second retry POST should fire, postCount=' + postCount);
  const r2Post = postQueue.shift();
  assertTrue(JSON.stringify(r2Post.body.groups[0].targets) === JSON.stringify([
    'openai/gpt-4o', 'openai/gpt-4.1-mini', 'deepseek/deepseek-chat', 'openai/o4-mini', 'deepseek/deepseek-reasoner'
  ]), 'R2: second retry POST must preserve all targets: ' + JSON.stringify(r2Post.body.groups[0].targets));
  serverGroups = JSON.parse(JSON.stringify(r2Post.body.groups));
  r2Post.resolve({ ok: true, status: 200, json: async () => ({ ok: true, groups: JSON.parse(JSON.stringify(serverGroups)) }) });
  await flush(); await flush();
  assertTrue(routeSyncUncertain === false && routePendingMutations.length === 0, 'R2: clean dequeue after confirmed retry');

  // Suite R3: Pending operations referencing server-deleted group or model MUST NOT resurrect deleted items
  getMode = 'offline';
  sheetRowFor('deepseek/deepseek-reasoner').click(); // trigger offline uncertain remove Write #1
  await flush();
  const r3Drop = postQueue.shift();
  r3Drop.reject(new TypeError('drop'));
  await flush(); await flush(); await flush();
  assertTrue(routeSyncUncertain === true, 'R3: expected uncertain state');
  // Queue a pick on deepseek/deepseek-chat in main-chat, AND a strategy change on g-del
  await enqueueGroupMutation(draft => {
    const cur = draft.find(x => x.id === 'main-chat');
    if (!cur || cur.targets.indexOf('deepseek/deepseek-chat') < 0) return { changed: false };
    cur.pick = 'deepseek/deepseek-chat';
    return { changed: true };
  });
  await enqueueGroupMutation(draft => {
    const g2 = draft.find(x => x.id === 'g-del');
    if (!g2) return { changed: false };
    g2.name = '不应复活的组';
    return { changed: true };
  });
  assertTrue(routePendingMutations.length === 3 && postQueue.length === 0, 'R3: expected 3 pending mutations paused');

  // Server deletes BOTH 'deepseek/deepseek-chat' AND group 'g-del' before recovery!
  serverGroups = [
    { id: 'main-chat', name: '服务端已改名', strategy: 'order', targets: ['openai/gpt-4o', 'openai/gpt-4.1-mini', 'openai/o4-mini'], pick: '' }
  ];
  getMode = 'normal';
  await retryPendingRouteSync();
  await flush(); await flush();
  assertTrue(postQueue.length === 0, 'R3: stale pick/rename against server-deleted model/group must be dropped as no-op without POST');
  assertTrue(routeSyncUncertain === false && routePendingMutations.length === 0,
    'R3: obsolete pending mutations must dequeue cleanly, uncertain=' + routeSyncUncertain + ' pending=' + routePendingMutations.length);
  assertTrue(appConfig.groups.length === 1 && !appConfig.groups[0].targets.includes('deepseek/deepseek-chat'),
    'R3: server deletions must NOT be resurrected: ' + JSON.stringify(appConfig.groups));

  // Suite R4 (137 review #1): First POST returns 503 + {"ok":false}, server did NOT save, dual readbacks fail.
  // Write #1 MUST stay in routePendingMutations and commit cleanly when connectivity /api/config recovers.
  appConfig.groups = [{
    id: 'main-chat', name: '主力对话', strategy: 'order',
    targets: ['openai/gpt-4o', 'openai/gpt-4.1-mini'], pick: ''
  }];
  serverGroups = JSON.parse(JSON.stringify(appConfig.groups));
  groupDraft = routeCloneGroups(appConfig.groups);
  groupsSig = '';
  routePendingMutations.length = 0;
  routePendingSheetKeys.clear();
  routeSyncUncertain = false;
  renderGroups(true, true);
  renderRoutePickerSheet();
  await flush();
  getMode = 'offline';
  const r4StartPosts = postCount;
  sheetRowFor('deepseek/deepseek-reasoner').click();
  await flush();
  assertTrue(postCount === r4StartPosts + 1 && postQueue.length === 1, 'R4: expected initial POST');
  // Server did NOT save! Responds 503 + ok:false and readbacks fail
  postQueue.shift().resolve({ ok: false, status: 503, json: async () => ({ ok: false, error: { message: 'busy' } }) });
  await flush(); await flush(); await flush();
  assertTrue(routeSyncUncertain === true && routePendingMutations.length === 1,
    'R4: Write #1 must enter routePendingMutations when 503 + offline readback leaves outcome unknown');
  assertTrue(sheetRowFor('deepseek/deepseek-reasoner').textContent.includes('添加中'), 'R4: sheet must keep pending indicator');
  // Network recovers -> /api/config auto recovery commits Write #1
  getMode = 'normal';
  await loadConfig();
  await flush(); await flush(); await flush();
  assertTrue(postCount === r4StartPosts + 2 && postQueue.length === 1, 'R4: recovery must submit Write #1 POST');
  const r4Post = postQueue.shift();
  assertTrue(JSON.stringify(r4Post.body.groups[0].targets) === JSON.stringify([
    'openai/gpt-4o', 'openai/gpt-4.1-mini', 'deepseek/deepseek-reasoner'
  ]), 'R4: recovered Write #1 must not be dropped: ' + JSON.stringify(r4Post.body.groups[0].targets));
  serverGroups = JSON.parse(JSON.stringify(r4Post.body.groups));
  r4Post.resolve({ ok: true, status: 200, json: async () => ({ ok: true, groups: JSON.parse(JSON.stringify(serverGroups)) }) });
  await flush(); await flush();
  assertTrue(routeSyncUncertain === false && routePendingMutations.length === 0, 'R4: clean settled state');
  assertTrue(serverGroups[0].targets.includes('deepseek/deepseek-reasoner'), 'R4: server retains Write #1');

  // Suite R5 (137 review #2): Calling saveGroups() while routeSyncUncertain === true captures stale draft,
  // MUST NOT overwrite recovered server config (Write #1 + server rename); also preserves newly added local group.
  getMode = 'offline';
  const r5StartPosts = postCount;
  sheetRowFor('openai/o4-mini').click();
  await flush();
  const r5Req1 = postQueue.shift();
  // Server SAVED Write #1 (+o4-mini) AND renamed group, then returned 503 + offline readbacks
  serverGroups = JSON.parse(JSON.stringify(r5Req1.body.groups));
  serverGroups[0].name = '服务端恢复新名';
  r5Req1.resolve({ ok: false, status: 503, json: async () => ({ ok: false }) });
  await flush(); await flush(); await flush();
  assertTrue(routeSyncUncertain === true, 'R5: entered uncertain state');
  // Caller hits Save while uncertain (stale groupDraft has old name & no o4-mini)
  void saveGroups({ okMsg: '已保存' });
  await flush();
  assertTrue(postQueue.length === 0, 'R5: saveGroups must pause while uncertain');
  // Recover -> must NOT overwrite serverGroups with stale snapshot!
  getMode = 'normal';
  await retryPendingRouteSync();
  await flush(); await flush();
  assertTrue(postQueue.length === 0, 'R5: stale saveGroups while uncertain with no local diff must NOT post overwrite');
  assertTrue(appConfig.groups[0].name === '服务端恢复新名' && appConfig.groups[0].targets.includes('openai/o4-mini'),
    'R5: recovered config preserved: ' + JSON.stringify(appConfig.groups[0]));
  // Now test uncertain + addNewGroup() + saveGroups(): new local group IS merged without clobbering existing group
  getMode = 'offline';
  sheetRowFor('deepseek/deepseek-chat').click();
  await flush();
  const r5Req2 = postQueue.shift();
  serverGroups = JSON.parse(JSON.stringify(r5Req2.body.groups));
  serverGroups[0].name = '二次并发新名';
  r5Req2.reject(new TypeError('drop'));
  await flush(); await flush(); await flush();
  addNewGroup();
  groupDraft[1].targets = ['openai/gpt-4o'];
  void saveGroups({ okMsg: '已保存新增组' });
  await flush();
  getMode = 'normal';
  const r5bP = retryPendingRouteSync();
  await flush(); await flush();
  assertTrue(postQueue.length === 1, 'R5b: local new group should trigger merged POST');
  const r5bPost = postQueue.shift();
  assertTrue(r5bPost.body.groups.length === 2
    && r5bPost.body.groups[0].name === '二次并发新名'
    && r5bPost.body.groups[0].targets.includes('deepseek/deepseek-chat')
    && r5bPost.body.groups[1].id === 'my-group-1',
    'R5b: merged new group must preserve server rename + Write #1: ' + JSON.stringify(r5bPost.body.groups));
  serverGroups = JSON.parse(JSON.stringify(r5bPost.body.groups));
  r5bPost.resolve({ ok: true, status: 200, json: async () => ({ ok: true, groups: JSON.parse(JSON.stringify(serverGroups)) }) });
  await r5bP;
  await flush(); await flush();

  // Suite R6 (137 review #3): up/down mutators must NOT double-move on recovery when move already applied on server
  appConfig.groups = [{
    id: 'main-chat', name: '主力对话', strategy: 'order',
    targets: ['openai/gpt-4o', 'openai/gpt-4.1-mini', 'deepseek/deepseek-chat', 'deepseek/deepseek-reasoner'], pick: ''
  }];
  serverGroups = JSON.parse(JSON.stringify(appConfig.groups));
  groupDraft = routeCloneGroups(appConfig.groups);
  groupsSig = '';
  routePendingMutations.length = 0;
  routePendingSheetKeys.clear();
  routeSyncUncertain = false;
  renderGroups(true, true);
  await flush();
  // Case R6a: Click UP on C (index 2 -> 1 => [gpt-4o, deepseek-chat, gpt-4.1-mini, deepseek-reasoner]),
  // server SAVED it, response dropped + readbacks failed. Recovery must keep [A, C, B, D] (NOT [C, A, B, D])!
  getMode = 'offline';
  const r6StartPosts = postCount;
  const r6Rows = () => Array.from(document.querySelectorAll('.route-card .route-member-pill'));
  r6Rows()[2].querySelector('[data-action="up"]').click();
  await flush();
  assertTrue(postQueue.length === 1, 'R6a: expected POST 1 for C up');
  const r6aReq = postQueue.shift();
  assertTrue(JSON.stringify(r6aReq.body.groups[0].targets) === JSON.stringify([
    'openai/gpt-4o', 'deepseek/deepseek-chat', 'openai/gpt-4.1-mini', 'deepseek/deepseek-reasoner'
  ]), 'R6a: wrong POST 1 order');
  serverGroups = JSON.parse(JSON.stringify(r6aReq.body.groups));
  r6aReq.reject(new TypeError('drop after commit'));
  await flush(); await flush(); await flush();
  assertTrue(routeSyncUncertain === true && routePendingMutations.length === 1, 'R6a: retained uncertain up op');
  getMode = 'normal';
  await retryPendingRouteSync();
  await flush(); await flush();
  assertTrue(postQueue.length === 0 && postCount === r6StartPosts + 1,
    'R6a: already-applied up must NOT post or move a second time! postQueue=' + postQueue.length);
  assertTrue(JSON.stringify(appConfig.groups[0].targets) === JSON.stringify([
    'openai/gpt-4o', 'deepseek/deepseek-chat', 'openai/gpt-4.1-mini', 'deepseek/deepseek-reasoner'
  ]), 'R6a: C must NOT jump to index 0: ' + JSON.stringify(appConfig.groups[0].targets));

  // Case R6b: Click DOWN on B (currently index 2 -> 3 => [gpt-4o, deepseek-chat, deepseek-reasoner, gpt-4.1-mini]),
  // server did NOT save on POST 1 -> recovery applies once; double-disconnect AFTER server saves -> 2nd recovery stays once!
  getMode = 'offline';
  r6Rows()[2].querySelector('[data-action="down"]').click();
  await flush();
  const r6bReq1 = postQueue.shift();
  // Server did NOT save POST 1!
  r6bReq1.resolve({ ok: false, status: 503, json: async () => ({ ok: false }) });
  await flush(); await flush(); await flush();
  // Recover -> POST 2 sends the down move; this time server SAVES it, but POST 2 disconnects too!
  getMode = 'normal';
  const retryP = retryPendingRouteSync();
  await flush(); await flush();
  assertTrue(postQueue.length === 1, 'R6b: expected recovery POST 2');
  const r6bReq2 = postQueue.shift();
  assertTrue(JSON.stringify(r6bReq2.body.groups[0].targets) === JSON.stringify([
    'openai/gpt-4o', 'deepseek/deepseek-chat', 'deepseek/deepseek-reasoner', 'openai/gpt-4.1-mini'
  ]), 'R6b: POST 2 should move gpt-4.1-mini down once: ' + JSON.stringify(r6bReq2.body.groups[0].targets));
  getMode = 'offline';
  serverGroups = JSON.parse(JSON.stringify(r6bReq2.body.groups));
  r6bReq2.reject(new TypeError('double disconnect'));
  await retryP;
  await flush(); await flush();
  assertTrue(routeSyncUncertain === true && routePendingMutations.length === 1, 'R6b: retained after double disconnect');
  // Also test UP when server HAS [gpt-4o, gpt-4.1-mini, deepseek-chat, deepseek-reasoner] is unchanged below:
  getMode = 'normal';
  await retryPendingRouteSync();
  await flush(); await flush();
  assertTrue(postQueue.length === 0, 'R6b: double-disconnect recovery must NOT move down again');
  assertTrue(JSON.stringify(serverGroups[0].targets) === JSON.stringify([
    'openai/gpt-4o', 'deepseek/deepseek-chat', 'deepseek/deepseek-reasoner', 'openai/gpt-4.1-mini'
  ]), 'R6b: final order intact');

  document.getElementById('res_rec').textContent = 'PASS';
})().catch(e => {
  document.getElementById('res_rec').textContent = 'FAIL ' + (e && e.stack ? e.stack : e.message);
});
</script>
</body>
</html>`;

const recOut = browser.dom(recoveryPage, 3000);
const recRes = recOut.match(/id="res_rec"[^>]*>([\s\S]*?)<\/pre>/)?.[1]?.trim() || 'NO_RESULT';
assert.equal(recRes, 'PASS', recRes + ' tail=' + recOut.slice(-400));

// Also verify loadConfig() stale-/api/config response guard using the exact production loadConfig code slice
const staleConfigPage = `<!doctype html><html><body><pre id="res2">WAIT</pre><script>
let API_BASE = '';
let providerConfigRequest = 0, providerWriteRevision = 0, providerWrites = new Set(), providerConfigError = false;
let groupWriteInFlight = 0, routeSyncUncertain = false, groupWriteRevision = 0;
let appConfig = { providers: [{ id: 'openai', enabled: true }], groups: [{ id: 'g1', targets: ['openai/a'] }] };
function renderProviderStatus() {}
async function reconcileProviderReadback() {}
function refreshHomeQuota() {}
function renderProviderList() {}
function renderAll() {}
function populateSelects() {}
let staleResolve;
window.fetch = () => new Promise(r => { staleResolve = r; });
${loadConfigSlice}
(async () => {
  const p = loadConfig();
  // Group mutation starts and completes AFTER loadConfig started fetch('/api/config')
  groupWriteRevision++;
  appConfig.groups = [{ id: 'g1', targets: ['openai/a', 'openai/b'] }];
  staleResolve({ ok: true, json: async () => ({ providers: [{ id: 'openai', enabled: true }], groups: [{ id: 'g1', targets: ['openai/a'] }] }) });
  await p;
  if (JSON.stringify(appConfig.groups[0].targets) !== JSON.stringify(['openai/a', 'openai/b'])) {
    throw new Error('Stale /api/config overwrote confirmed groups: ' + JSON.stringify(appConfig.groups));
  }
  document.getElementById('res2').textContent = 'PASS';
})().catch(e => { document.getElementById('res2').textContent = 'FAIL ' + e.message; });
</script></body></html>`;
const staleOut = browser.dom(staleConfigPage, 2500);
const staleRes = staleOut.match(/id="res2"[^>]*>([\s\S]*?)<\/pre>/)?.[1]?.trim() || 'NO_RESULT';
assert.equal(staleRes, 'PASS', staleRes);
console.log('Routing serialized queue, auto/manual recovery, double-disconnect retention & server-delete guard OK');
