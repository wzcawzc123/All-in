'use strict';
const assert = require('node:assert/strict');
const cp = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

function findChrome() {
  const candidates = [
    process.env.ALLIN_CHROME,
    process.env.CHROME_BIN,
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    'chromium', 'chromium-browser', 'google-chrome', 'google-chrome-stable'
  ].filter(Boolean);
  for (const command of candidates) {
    if (fs.existsSync(command) && fs.statSync(command).isFile()) return command;
    for (const dir of (process.env.PATH || '').split(path.delimiter)) {
      for (const suffix of process.platform === 'win32' ? ['', '.exe', '.cmd'] : ['']) {
        const file = path.join(dir, command + suffix);
        if (fs.existsSync(file) && fs.statSync(file).isFile()) return file;
      }
    }
  }
  throw new Error('No headless Chromium browser found. Set ALLIN_CHROME or CHROME_BIN.');
}

function runDom(html, budgetMs, screenshotPath) {
  const base = process.env.ALLIN_TEST_TMP || path.resolve('build');
  fs.mkdirSync(base, { recursive: true });
  const dir = fs.mkdtempSync(path.join(base, 'allin-pv-test-'));
  try {
    const file = path.join(dir, 'test.html');
    fs.writeFileSync(file, html);
    const args = [
      '--headless', '--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage',
      '--no-first-run', '--no-default-browser-check',
      '--user-data-dir=' + path.join(dir, 'profile'),
      '--window-size=390,960',
      '--virtual-time-budget=' + budgetMs,
      '--dump-dom'
    ];
    if (screenshotPath) {
      fs.mkdirSync(path.dirname(screenshotPath), { recursive: true });
      args.push('--screenshot=' + screenshotPath);
    }
    args.push(pathToFileURL(file).href);
    const r = cp.spawnSync(findChrome(), args, {
      encoding: 'utf8',
      timeout: 60000,
      maxBuffer: 16 * 1024 * 1024
    });
    if (r.error || r.status !== 0) {
      throw new Error('Browser run failed: ' + (r.error ? r.error.message : r.stderr));
    }
    return r.stdout;
  } finally {
    fs.rmSync(dir, { recursive: true, force: true, maxRetries: 10, retryDelay: 50 });
  }
}

const CARD_IDS = [
  'workbuddy', 'raccoon', 'workbuddy-ai', 'loomy', 'catpaw',
  'github-copilot', 'minimax-code', 'deepseek_web', 'zcode',
  'qoder_cn', 'cline', 'codex', 'antigravity', 'trae-cn', 'doubao', 'doubao-web'
];

const dashboardHtml = fs.readFileSync(
  path.join(__dirname, '../app/src/main/assets/dashboard.html'),
  'utf8'
);

const stubScript = `<script>
(function () {
  const PROVIDERS = ${JSON.stringify(CARD_IDS)};
  const privacyState = {
    enabled: true,
    maskApiKeys: true,
    maskEmails: true,
    maskPhones: true,
    maskIds: false,
    maskBankCards: false,
    maskLandlines: false,
    customPatterns: []
  };
  const state = {
    rev: 1,
    fetches: [],
    nativeCalls: [],
    alerts: [],
    confirms: [],
    prompts: [],
    openedUrls: [],
    failEnabledOnceFor: null,
    failPrivacyOnce: false,
    privacyPostDelays: [],
    privacyGetDelayMs: 0,
    privacyInFlightPosts: 0,
    privacyMaxConcurrentPosts: 0,
    privacyState,
    exposedState: {
      ok: true,
      enabled: true,
      items: ['antigravity/model-a'],
      groups: [{ id: 'g-default', name: '默认组' }],
      providers: [
        { id: 'antigravity', name: 'Antigravity', live: true, models: Array.from({ length: 48 }, (_, i) => 'model-' + String(i).padStart(2, '0')) },
        { id: 'codex', name: 'Codex', live: true, models: Array.from({ length: 48 }, (_, i) => 'codex-' + String(i).padStart(2, '0')) },
        { id: 'gemini', name: 'Gemini', live: true, models: Array.from({ length: 48 }, (_, i) => 'gemini-' + String(i).padStart(2, '0')) },
        { id: 'kiro', name: 'Kiro', live: true, models: Array.from({ length: 48 }, (_, i) => 'kiro-' + String(i).padStart(2, '0')) },
        { id: 'zcode', name: 'ZCode', live: true, models: Array.from({ length: 48 }, (_, i) => 'glm-test-' + String(i).padStart(2, '0')) }
      ]
    },
    config: {
      port: 8317,
      lanHost: false,
      groupsEnabled: true,
      bareFallback: true,
      groups: [],
      providers: PROVIDERS.map((id) => ({
        id,
        name: id,
        enabled: true,
        models: ['demo-model-' + id],
        modelMeta: { ['demo-model-' + id]: { name: 'Demo ' + id, billing: { kind: 'free', scope: 'model' } } },
        keys: [],
        accounts: [
          { id: id + '-acc-1', name: id + ' 主账号', email: id + '-one@example.com', addedAt: 1732500000000, enabled: true, off: false, site: id === 'zcode' ? 'zai' : undefined },
          { id: id + '-acc-2', name: id + ' 备用账号', email: id + '-two@example.com', addedAt: 1732501000000, enabled: false, off: true, site: id === 'zcode' ? 'bigmodel' : undefined }
        ]
      }))
    }
  };
  state.config.providers.push({
    id: 'openai',
    name: 'OpenAI',
    protocol: 'openai',
    keys: [],
    accounts: [],
    models: []
  });
  function token() {
    return 'tok-' + state.rev;
  }
  function cloneConfig() {
    const c = JSON.parse(JSON.stringify(state.config));
    c.providerStateTokens = {};
    c.providers.forEach((p) => { c.providerStateTokens[p.id] = token(); });
    return c;
  }
  window.__TEST_STATE = state;
  window.alert = (msg) => { state.alerts.push(String(msg)); };
  window.confirm = (msg) => { state.confirms.push(String(msg)); return true; };
  window.prompt = (msg, def) => { state.prompts.push(String(msg)); return idPromptValue(msg, def); };
  function idPromptValue(msg) {
    if (String(msg).includes('Cline API Key')) return 'sk-cline-fixture-0001';
    return 'fixture-input';
  }
  window.open = (url) => { state.openedUrls.push(String(url)); return { closed: false, close() {} }; };
  async function handleMockRequest(rawUrl, opts) {
    const url = String(rawUrl);
    let bodyObj = null;
    if (opts && typeof opts.body === 'string' && opts.body.trim()) {
      try { bodyObj = JSON.parse(opts.body); } catch (_) { bodyObj = opts.body; }
    }
    state.fetches.push({ url, method: (opts && opts.method) || 'GET', body: bodyObj });

    if (url.endsWith('/api/config')) return { status: 200, data: cloneConfig() };
    if (url.endsWith('/api/server-key')) return { status: 200, data: { ok: true, authKey: 'sk-eta-fixture' } };
    if (url.includes('/api/status')) return { status: 200, data: { ok: true, running: true, port: 8317, requests: 0 } };
    if (url.includes('/api/gateway/health')) return { status: 200, data: { ok: true, status: 'ready' } };
    if (url.includes('/api/app-update')) return { status: 200, data: { ok: true, available: false } };
    if (url.includes('/api/auth/capabilities')) {
      return { status: 200, data: { ok: true, fileTransfer: true, providers: PROVIDERS } };
    }
    if (url.includes('/api/usage')) {
      return { status: 200, data: { ok: true, summary: { totalTokens: 0, requests: 0 }, days: [], models: [], providers: [], events: [] } };
    }
    if (url.endsWith('/api/providers/endpoint-probe')) {
      return {
        status: 200,
        data: {
          ok: true,
          scope: 'official-defaults',
          scopeNote: '仅探测官方固定地址，不改变路由。',
          summary: '官方固定地址连通正常',
          results: [{ reachable: true, label: '官方端点', url: 'https://api.example.com', method: 'GET', httpStatus: 200, kind: 'ok', ms: 18 }]
        }
      };
    }
    const syncMatch = new RegExp('/api/auth/([^/]+)/sync-models').exec(url);
    if (syncMatch) {
      const pid = decodeURIComponent(syncMatch[1]);
      return { status: 200, data: { ok: true, provider: pid, count: 1, models: ['demo-model-' + pid], message: '已同步官方模型' } };
    }
    const enabledMatch = new RegExp('/api/auth/([^/]+)/enabled$').exec(url);
    if (enabledMatch) {
      const pid = decodeURIComponent(enabledMatch[1]);
      if (state.failEnabledOnceFor === pid) {
        state.failEnabledOnceFor = null;
        return { status: 409, data: { ok: false, error: { message: '状态冲突请重试' } } };
      }
      const prov = state.config.providers.find((p) => p.id === pid);
      const acc = prov && prov.accounts.find((a) => a.id === (bodyObj && bodyObj.id));
      if (!acc) return { status: 404, data: { ok: false, error: 'account not found' } };
      acc.enabled = !!(bodyObj && bodyObj.enabled);
      acc.off = !acc.enabled;
      state.rev++;
      return { status: 200, data: { ok: true, providerId: pid, id: acc.id, enabled: acc.enabled, stateToken: token() } };
    }
    const removeMatch = new RegExp('/api/auth/([^/]+)/remove$').exec(url);
    if (removeMatch) {
      const pid = decodeURIComponent(removeMatch[1]);
      const prov = state.config.providers.find((p) => p.id === pid);
      let removed = false;
      if (prov && bodyObj && bodyObj.id) {
        const beforeLen = prov.accounts.length;
        prov.accounts = bodyObj.id === 'all' ? [] : prov.accounts.filter((a) => a.id !== bodyObj.id);
        removed = prov.accounts.length !== beforeLen;
        state.rev++;
      }
      return { status: 200, data: { ok: true, providerId: pid, removed, stateToken: token() } };
    }
    const addMatch = new RegExp('/api/auth/([^/]+)/add$').exec(url);
    if (addMatch) {
      const pid = decodeURIComponent(addMatch[1]);
      const prov = state.config.providers.find((p) => p.id === pid);
      if (prov) {
        prov.accounts.push({ id: pid + '-acc-' + (prov.accounts.length + 1), name: (bodyObj && bodyObj.name) || '新账号', addedAt: Date.now(), enabled: true, off: false });
        state.rev++;
      }
      return { status: 200, data: { ok: true, providerId: pid, stateToken: token() } };
    }
    if (url.includes('/api/zcode/gift')) {
      return { status: 200, data: { ok: true, remainingUnits: 1200, buckets: [{ name: 'Start Plan', remaining: 1200, total: 2000, capabilities: ['model:glm-4.6'] }], claimable: [], claimed: [{ planId: 'daily' }] } };
    }
    if (url.includes('/api/minimax/checkin')) {
      return { status: 200, data: { ok: true, outcome: 'done', credit: 100 } };
    }
    if (url.includes('/api/trae/checkin-status')) {
      return { status: 200, data: { ok: true, outcome: 'status', status: { checked_in: false }, credits: 88 } };
    }
    if (url.includes('/api/trae/checkin')) {
      return { status: 200, data: { ok: true, outcome: 'done', credits: 108 } };
    }
    if (url.includes('/api/antigravity/probe')) {
      return { status: 200, data: { ok: true, verdict: 'ok', summary: '双端点正常', results: [{ name: 'https://daily-cloudcode-pa.sandbox.googleapis.com', role: 'chat', alive: true, status: 401, ms: 42 }] } };
    }
    if (url.includes('/api/codex/usage')) {
      return { status: 200, data: { ok: true, plan_type: 'plus', credits: { balance: '12.5' }, rate_limit: { primary_window: { used_percent: 15 } } } };
    }
    if (url.includes('/api/auth/codex/init')) {
      return { status: 200, data: { ok: true, authUrl: 'https://auth.openai.com/authorize?fixture=1', flowId: 'codex-flow-1' } };
    }
    if (url.includes('/api/auth/codex/poll')) {
      return { status: 200, data: { ok: true, status: 'done' } };
    }
    if (url.includes('/api/test-model')) {
      return { status: 200, data: { ok: true, latencyMs: 12, reply: 'pong' } };
    }
    if (url.endsWith('/api/providers')) {
      return { status: 200, data: { ok: true, providerId: (bodyObj && bodyObj.providerId) || 'openai', keyCount: 0 } };
    }
    if (url.includes('/api/exposed')) {
      if (opts && opts.method === 'POST' && bodyObj) {
        state.exposedState.enabled = !!bodyObj.enabled;
        state.exposedState.items = Array.isArray(bodyObj.items) ? bodyObj.items.slice() : [];
        return { status: 200, data: { ok: true, count: state.exposedState.items.length, enabled: state.exposedState.enabled } };
      }
      return { status: 200, data: JSON.parse(JSON.stringify(state.exposedState)) };
    }
    if (url.includes('/api/privacy')) {
      if (opts && opts.method === 'POST') {
        state.privacyInFlightPosts++;
        if (state.privacyInFlightPosts > state.privacyMaxConcurrentPosts) {
          state.privacyMaxConcurrentPosts = state.privacyInFlightPosts;
        }
        const delayMs = state.privacyPostDelays.length ? state.privacyPostDelays.shift() : 0;
        if (delayMs > 0) await new Promise((r) => setTimeout(r, delayMs));
        state.privacyInFlightPosts--;
        if (state.failPrivacyOnce) {
          state.failPrivacyOnce = false;
          return { status: 500, data: { ok: false, error: { message: '模拟保存失败' } } };
        }
        if (bodyObj && bodyObj.privacy) {
          Object.assign(privacyState, bodyObj.privacy);
        }
      } else if (state.privacyGetDelayMs > 0) {
        const dMs = state.privacyGetDelayMs;
        state.privacyGetDelayMs = 0;
        await new Promise((r) => setTimeout(r, dMs));
      }
      return { status: 200, data: { ok: true, privacy: JSON.parse(JSON.stringify(privacyState)), live: { totalMasked: 5, lastAt: Date.now(), lastEnabled: privacyState.enabled, lastMaskedCount: 2 } } };
    }
    if (url.includes('/api/test-mask')) {
      const txt = (bodyObj && bodyObj.text) || '';
      return {
        status: 200,
        data: {
          ok: true,
          applied: !!privacyState.enabled,
          hits: privacyState.enabled ? 2 : 0,
          categories: privacyState.enabled ? ['API Key', '手机号'] : [],
          masked: privacyState.enabled ? '[KEY_1] / [PHONE_1]' : txt,
          unmasked: txt
        }
      };
    }
    return { status: 200, data: { ok: true } };
  }
  window.AllInNative = {
    openAccountTransfer(action, providerId) {
      state.nativeCalls.push({ type: 'transfer', action, providerId });
      return true;
    },
    requestAsync(id, method, reqPath, body) {
      handleMockRequest(reqPath, { method, body }).then((r) => {
        if (typeof window.__allInBridgeDone === 'function') {
          window.__allInBridgeDone(id, JSON.stringify({ status: r.status, body: JSON.stringify(r.data) }));
        }
      });
    }
  };
  window.fetch = async (rawUrl, opts) => {
    const r = await handleMockRequest(rawUrl, opts);
    return {
      ok: r.status >= 200 && r.status < 300,
      status: r.status,
      statusText: r.status === 200 ? 'OK' : 'Error',
      json: async () => JSON.parse(JSON.stringify(r.data))
    };
  };
})();
</script>`;

const driverScript = `<pre id="test-result" hidden>WAIT</pre>
<script>
(async function runButtonAndToggleSuite() {
  const resultEl = document.getElementById('test-result');
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const check = (cond, msg) => { if (!cond) throw new Error(msg); };
  try {
    // 1. Wait until initial loadConfig() populates providers (or invoke explicitly)
    for (let i = 0; i < 40; i++) {
      if (typeof appConfig !== 'undefined' && appConfig && Array.isArray(appConfig.providers) && appConfig.providers.length >= 14) break;
      await sleep(25);
    }
    if ((!appConfig || !Array.isArray(appConfig.providers)) && typeof loadConfig === 'function') {
      await loadConfig();
    }
    check(appConfig && Array.isArray(appConfig.providers) && appConfig.providers.length >= 14,
      'appConfig loaded failed; fetches=' + window.__TEST_STATE.fetches.map((f) => f.url).join(',') + ' typeofLoad=' + (typeof loadConfig));

    // Switch to providers tab and show all 16 cards simultaneously for layout + click audit
    if (typeof switchTab === 'function') switchTab('providers');
    else {
      document.querySelectorAll('.tab-pane').forEach((n) => n.classList.remove('active'));
      document.getElementById('tab-providers').classList.add('active');
    }
    showProviderView('add');
    document.querySelectorAll('#all-providers-cards-container > .card[data-provider-id]').forEach((c) => { c.hidden = false; });
    renderAllProvidersCards();
    refreshProviderBadges();
    renderAllModelsTags();
    renderTraeAccounts();
    await sleep(60);
    if (typeof document.getAnimations === 'function') {
      document.getAnimations().forEach((a) => { try { a.finish(); } catch (_) {} });
    }
    await sleep(20);

    // 2. Style & borderless checks in Light mode (#f7f7f7 canvas, #ffffff cards, zero dividers)
    document.documentElement.removeAttribute('data-theme');
    await sleep(20);
    const bodyBg = getComputedStyle(document.body).backgroundColor;
    check(bodyBg === 'rgb(247, 247, 247)', 'light canvas should be #f7f7f7, got ' + bodyBg);
    check(document.documentElement.scrollWidth <= window.innerWidth + 1,
      'light mode horizontal overflow: scrollWidth=' + document.documentElement.scrollWidth + ' innerWidth=' + window.innerWidth);

    const cards = Array.from(document.querySelectorAll('#all-providers-cards-container > .card[data-provider-id]'));
    check(cards.length === 16, 'expected 16 static provider cards, got ' + cards.length);
    const containerEl = document.getElementById('all-providers-cards-container');
    containerEl.style.maxWidth = '358px';
    await sleep(10);
    for (const card of cards) {
      const cs = getComputedStyle(card);
      check(cs.backgroundColor === 'rgb(255, 255, 255)', card.dataset.providerId + ' bg should be pure white, got ' + cs.backgroundColor);
      check(cs.borderTopWidth === '0px' && cs.borderRightWidth === '0px', card.dataset.providerId + ' card should have zero border');
      check(card.scrollWidth <= card.clientWidth + 1, card.dataset.providerId + ' card overflows 358px mobile width: ' + card.scrollWidth + ' > ' + card.clientWidth);
      const dock = card.querySelector('.pv-util-dock');
      check(dock && getComputedStyle(dock).borderTopWidth === '0px', card.dataset.providerId + ' dock zero border');
      const mb = card.querySelector('.pv-models-box');
      check(mb && getComputedStyle(mb).borderTopWidth === '0px', card.dataset.providerId + ' models-box zero border-top');
      for (const ub of card.querySelectorAll('.pv-util-btn')) {
        const ucs = getComputedStyle(ub);
        check(ucs.borderLeftWidth === '0px' && ucs.borderRightWidth === '0px', card.dataset.providerId + ' util-btn has divider border');
      }
      for (const row of card.querySelectorAll('.pv-acc-row')) {
        check(getComputedStyle(row).borderTopWidth === '0px', card.dataset.providerId + ' acc-row has border');
      }
    }
    containerEl.style.maxWidth = '';

    // Also verify Dark mode tokens (#1c1c1e cards)
    document.documentElement.setAttribute('data-theme', 'dark');
    await sleep(20);
    check(getComputedStyle(cards[0]).backgroundColor === 'rgb(28, 28, 30)', 'dark card bg should be #1c1c1e');
    document.documentElement.removeAttribute('data-theme');
    await sleep(20);

    // 3. Verify EVERY button across ALL 16 cards triggers cleanly
    const state = window.__TEST_STATE;
    state.loginCalls = [];
    const origTriggerPlatformLogin = window.triggerPlatformLogin;
    window.triggerPlatformLogin = function (p) {
      state.loginCalls.push(p);
      return origTriggerPlatformLogin.apply(this, arguments);
    };
    const nativeLoginProviders = ['workbuddy', 'raccoon', 'workbuddy-ai', 'catpaw', 'github-copilot', 'minimax-code', 'deepseek_web', 'trae-cn'];
    let clickedButtonCount = 0;

    for (const card of cards) {
      const pid = card.dataset.providerId;
      const badge = document.getElementById('badge-count-' + pid);
      check(badge && badge.textContent.trim() === '账号 1/2', pid + ' badge should read 账号 1/2, got ' + (badge && badge.textContent));
      check(!badge.classList.contains('badge-warn'), pid + ' badge should not warn when 1/2 enabled');

      const pool = document.getElementById('acc-pool-' + pid);
      check(pool && !pool.hidden, pid + ' acc-pool must be visible');
      const rows = pool.querySelectorAll('.pv-acc-row');
      check(rows.length === 2, pid + ' expected 2 acc rows');
      check(!rows[0].classList.contains('is-disabled') && rows[1].classList.contains('is-disabled'), pid + ' disabled class mismatch');
      check(rows[1].textContent.includes('已禁用'), pid + ' disabled row should show 已禁用 tag');

      // Audit all dock + subcard + models-head buttons inside the card (excluding dynamic pool row controls tested separately)
      const cardButtons = Array.from(card.querySelectorAll('.pv-dock button, .pv-subcard button, .pv-models-head button'));
      check(cardButtons.length >= 4, pid + ' expected >=4 buttons, got ' + cardButtons.length);

      for (const btn of cardButtons) {
        const rect = btn.getBoundingClientRect();
        check(btn.offsetParent !== null && rect.width > 20 && rect.height >= 26, pid + ' button hidden/undersized: ' + btn.textContent);
        const beforeFetch = state.fetches.length;
        const beforeNative = state.nativeCalls.length;
        const beforeLogin = state.loginCalls.length;
        const beforePrompt = state.prompts.length;
        const beforeOpened = state.openedUrls.length;
        const text = btn.textContent.replace(new RegExp('\\s+', 'g'), ' ').trim();

        btn.click();
        await sleep(30);
        clickedButtonCount++;

        if (text === '登录账号' && nativeLoginProviders.includes(pid)) {
          check(state.loginCalls.length === beforeLogin + 1 && state.loginCalls.at(-1) === pid,
            pid + ' 登录账号 button did not invoke triggerPlatformLogin');
        } else if (text.includes('导入账号') || text.includes('导出账号')) {
          const action = text.includes('导入账号') ? 'import' : 'export';
          check(state.nativeCalls.length === beforeNative + 1 && state.nativeCalls.at(-1).providerId === pid && state.nativeCalls.at(-1).action === action,
            pid + ' ' + text + ' native transfer not called');
        } else if (text.includes('端点探测') && !text.includes('高级')) {
          const last = state.fetches.slice(beforeFetch).find((f) => f.url.endsWith('/api/providers/endpoint-probe'));
          check(last && last.body && last.body.provider === pid, pid + ' endpoint probe not called');
        } else if (text.includes('同步') && text.includes('模型')) {
          const last = state.fetches.slice(beforeFetch).find((f) => f.url.includes('/api/auth/' + pid + '/sync-models'));
          check(last, pid + ' sync-models not called');
        } else if (text.includes('短信登录')) {
          const loomyOverlay = document.querySelector('#loomy-sms-modal, [data-loomy-sms]') || [...document.querySelectorAll('div')].find((d) => d.textContent.includes('验证码登录') && d.style.display !== 'none');
          check(document.body.textContent.includes('发送验证码'), 'loomy SMS modal did not open');
          const closeBtn = [...document.querySelectorAll('button')].filter((b) => b.textContent === '关闭' || b.textContent === '取消').pop();
          if (closeBtn) closeBtn.click();
        } else if (text.includes('每日签到')) {
          const last = state.fetches.slice(beforeFetch).find((f) => f.url.includes('/api/minimax/checkin'));
          check(last, 'minimax checkin not called');
        } else if (text === '刷新') {
          const last = state.fetches.slice(beforeFetch).find((f) => f.url.includes('/api/zcode/gift?action=summary'));
          check(last, 'zcode gift refresh not called');
        } else if (text === '一键领取') {
          const last = state.fetches.slice(beforeFetch).find((f) => f.url.includes('/api/zcode/gift?action=claimall'));
          check(last, 'zcode gift claim not called');
        } else if (text.includes('添加 API Key')) {
          const last = state.fetches.slice(beforeFetch).find((f) => f.url.includes('/api/auth/cline/add'));
          check(state.prompts.length > beforePrompt && last, 'cline add API key not called');
        } else if (text.includes('OAuth 授权登录')) {
          const last = state.fetches.slice(beforeFetch).find((f) => f.url.includes('/api/auth/codex/init'));
          check(last && state.openedUrls.length > beforeOpened, 'codex oauth login not called');
        } else if (text.includes('查看用量与额度')) {
          const last = state.fetches.slice(beforeFetch).find((f) => f.url.includes('/api/codex/usage'));
          check(last && document.getElementById('codex-usage-status').textContent.includes('plus'), 'codex usage not called');
        } else if (text.includes('高级探测并切换端点')) {
          const last = state.fetches.slice(beforeFetch).find((f) => f.url.includes('/api/antigravity/probe'));
          check(last && document.getElementById('antigravity-status').textContent.includes('双端点正常'), 'antigravity probe not called');
        } else if (text.includes('查看签到状态')) {
          const last = state.fetches.slice(beforeFetch).find((f) => f.url.includes('/api/trae/checkin-status'));
          check(last && document.getElementById('trae-checkin-status').textContent.includes('今日未签到'), 'trae status not called');
        } else if (text.includes('主动签到')) {
          const last = state.fetches.slice(beforeFetch).find((f) => f.url.includes('/api/trae/checkin'));
          check(last && document.getElementById('trae-checkin-status').textContent.includes('今日已签到'), 'trae checkin not called');
        } else if (text.includes('登录 Z.ai') || text.includes('登录 BigModel')) {
          check(document.getElementById('zcode-status').textContent.includes('ZCode 登录页'), 'zcode status did not update');
        } else if (text.includes('登录 Qoder CN')) {
          check(document.getElementById('qoder-status').textContent.includes('Qoder CN 登录页'), 'qoder status did not update');
        } else if (text.includes('登录 Cline')) {
          check(document.getElementById('cline-status').textContent.includes('Cline WorkOS'), 'cline status did not update');
        } else if (text.includes('Google 账号登录')) {
          check(document.getElementById('antigravity-status').textContent.includes('Google 授权链接'), 'antigravity status did not update');
        }
      }

      // Click badge to verify manageProviderAccounts opens modal cleanly
      badge.click();
      await sleep(25);
      const accModal = document.getElementById('account-manage-modal');
      check(accModal && accModal.style.display === 'flex', pid + ' badge click should open account modal');
      closeAccountModal();
      await sleep(20);
    }
    check(clickedButtonCount === 88, 'expected 88 static card buttons across 16 cards, checked ' + clickedButtonCount);

    // 4. Strictly test per-account enable/disable glass-switch in inline card pool + modal + 409 rollback + ⋯ menu
    // 4a. Inline toggle disable on catpaw acc-1 via real user click()
    const catPool = document.getElementById('acc-pool-catpaw');
    const sw1 = catPool.querySelectorAll('input.glass-switch')[0];
    check(sw1.checked === true, 'catpaw acc-1 initially enabled');
    const beforeDisable = state.fetches.length;
    sw1.click();
    await sleep(60);
    const disableReq = state.fetches.slice(beforeDisable).find((f) => f.url === '/api/auth/catpaw/enabled');
    check(disableReq && disableReq.method === 'POST' && disableReq.body.id === 'catpaw-acc-1' && disableReq.body.enabled === false && disableReq.body.expectedStateToken,
      'inline disable payload mismatch');
    const catBadge = document.getElementById('badge-count-catpaw');
    check(catBadge.textContent === '账号 0/2' && catBadge.classList.contains('badge-warn'), 'badge should update to 账号 0/2 warn');
    check(catPool.querySelectorAll('.pv-acc-row.is-disabled').length === 2, 'both catpaw accounts should render disabled');

    // 4b. Inline toggle enable on catpaw acc-2 via real user click()
    const sw2 = catPool.querySelectorAll('input.glass-switch')[1];
    sw2.click();
    await sleep(60);
    check(catBadge.textContent === '账号 1/2' && !catBadge.classList.contains('badge-warn'), 'badge should update back to 账号 1/2');

    // 4c. Modal toggle via 批量管理 › button
    catPool.querySelector('.pv-pool-link').click();
    await sleep(40);
    const modalEl = document.getElementById('account-manage-modal');
    check(modalEl.style.display === 'flex', '批量管理 button should open modal');
    const modalSwitches = modalEl.querySelectorAll('#acc-modal-list input.glass-switch');
    check(modalSwitches.length === 2 && modalSwitches[1].checked === true, 'modal switches must reflect current pool');
    const beforeModalToggle = state.fetches.length;
    modalSwitches[1].click();
    await sleep(60);
    const modalReq = state.fetches.slice(beforeModalToggle).find((f) => f.url === '/api/auth/catpaw/enabled');
    check(modalReq && modalReq.body.id === 'catpaw-acc-2' && modalReq.body.enabled === false, 'modal disable payload mismatch');
    check(catBadge.textContent === '账号 0/2', 'modal toggle must sync card badge & pool');
    closeAccountModal();
    await sleep(25);

    // 4d. 409 conflict rollback check on workbuddy inline switch
    state.failEnabledOnceFor = 'workbuddy';
    const wbPool = document.getElementById('acc-pool-workbuddy');
    const wbSw = wbPool.querySelectorAll('input.glass-switch')[0];
    wbSw.click();
    await sleep(70);
    check(wbPool.querySelectorAll('input.glass-switch')[0].checked === true, '409 failure must rollback to enabled=true via readback');
    check(document.getElementById('badge-count-workbuddy').textContent === '账号 1/2', '409 rollback preserves 1/2 badge');

    // 4e. Inline ⋯ menu: open & click 移除账号
    resultEl.textContent = 'WAIT:4e';
    const moreBtn = catPool.querySelector('.pv-more-btn');
    moreBtn.click();
    await sleep(40);
    const openMenu = document.querySelector('.glass-menu-frame');
    check(openMenu, '⋯ menu should open .glass-menu-frame');
    const removeBtn = Array.from(openMenu.querySelectorAll('button')).find((b) => b.textContent.includes('移除账号'));
    check(removeBtn, '⋯ menu should contain 移除账号 button');
    const beforeRemove = state.fetches.length;
    removeBtn.click();
    await sleep(80);
    check(state.fetches.slice(beforeRemove).some((f) => f.url === '/api/auth/catpaw/remove'), 'remove API should be called');
    check(catPool.querySelectorAll('.pv-acc-row').length === 1, 'catpaw pool should shrink to 1 row after removal');

    // 4f. Inline ⋯ menu on remaining account: click 修改备注 opens account modal
    resultEl.textContent = 'WAIT:4f';
    const moreBtn2 = catPool.querySelector('.pv-more-btn');
    moreBtn2.click();
    await sleep(40);
    const openMenu2 = document.querySelector('.glass-menu-frame');
    check(openMenu2, '2nd ⋯ menu should open');
    const noteBtn = Array.from(openMenu2.querySelectorAll('button')).find((b) => b.textContent.includes('修改备注'));
    check(noteBtn, '⋯ menu should contain 修改备注 button');
    noteBtn.click();
    await sleep(40);
    check(document.getElementById('account-manage-modal').style.display === 'flex', '修改备注 opens account modal');
    closeAccountModal();
    await sleep(30);

    // 5. Commercial Key save button, Custom API button, Model chip ping, Back button
    resultEl.textContent = 'WAIT:5a';
    const commDetails = document.getElementById('commercial-keys-details');
    commDetails.open = true;
    await sleep(15);
    const saveKeyBtn = commDetails.querySelector('button');
    check(saveKeyBtn, 'commercial save key button exists');
    saveKeyBtn.click();
    await sleep(40);

    resultEl.textContent = 'WAIT:5b';
    const modelChip = document.querySelector('#models-tags-workbuddy .model-tag');
    check(modelChip, 'workbuddy model chip rendered');
    const beforePing = state.fetches.length;
    modelChip.click();
    await sleep(40);
    check(state.fetches.slice(beforePing).some((f) => f.url === '/api/test-model'), 'model chip ping test-model API called');

    resultEl.textContent = 'WAIT:5c';
    const customBtn = document.querySelector('#custom-api-add button');
    check(customBtn, 'custom API button exists');
    customBtn.click();
    await sleep(40);
    const customOverlay = document.querySelector('.custom-api-overlay');
    check(customOverlay, 'custom API overlay opens');
    if (typeof closeCustomEditor === 'function') closeCustomEditor();
    document.getAnimations().forEach((a) => { try { a.finish(); } catch (_) {} });
    await sleep(40);

    resultEl.textContent = 'WAIT:5d';
    showProviderView('detail', 'workbuddy');
    document.getAnimations().forEach((a) => { try { a.finish(); } catch (_) {} });
    await sleep(30);
    document.getElementById('provider-back-button').click();
    document.getAnimations().forEach((a) => { try { a.finish(); } catch (_) {} });
    await sleep(30);
    check(!document.getElementById('provider-list-view').hidden, 'back button returns to provider list view');

    // 6. Verify ETA card B1 pure-white borderless styling & all 6 action buttons
    resultEl.textContent = 'WAIT:6';
    if (typeof switchTab === 'function') switchTab('overview');
    document.getAnimations().forEach((a) => { try { a.finish(); } catch (_) {} });
    await sleep(30);
    const etaCard = document.getElementById('eta-card');
    check(etaCard, 'eta-card exists');
    const etaCardStyle = getComputedStyle(etaCard);
    check(etaCardStyle.backgroundColor === 'rgb(255, 255, 255)' && etaCardStyle.borderTopWidth === '0px',
      'eta-card must be pure white borderless, got bg=' + etaCardStyle.backgroundColor + ' bw=' + etaCardStyle.borderTopWidth);
    const etaGrouped = etaCard.querySelector('.eta-grouped');
    const etaGroupedCs = getComputedStyle(etaGrouped);
    check(etaGroupedCs.backgroundColor === 'rgba(0, 0, 0, 0)' && etaGroupedCs.borderTopWidth === '0px',
      'eta-grouped must have transparent bg and zero border, got bg=' + etaGroupedCs.backgroundColor);
    const etaRows = Array.from(etaGrouped.querySelectorAll('.eta-row'));
    check(etaRows.length === 3, 'expected 3 eta rows');
    etaRows.forEach((r, idx) => {
      check(getComputedStyle(r).borderTopWidth === '0px', 'eta row #' + idx + ' must have zero border-top divider');
    });
    etaCard.querySelectorAll('.eta-cta-sec, .eta-row-copy').forEach((b) => {
      check(getComputedStyle(b).borderTopWidth === '0px', 'eta button should have zero border');
    });
    document.getElementById('eta-base').textContent = 'http://127.0.0.1:3425/v1';
    document.getElementById('eta-key').textContent = 'sk-eta-fixture';
    document.getElementById('eta-model').textContent = 'workbuddy/glm-5.3';
    const etaCopied = [];
    let etaOpened = 0;
    window.AllInNative.copyText = (txt) => { etaCopied.push(String(txt)); return true; };
    window.AllInNative.openEta = () => { etaOpened++; return true; };
    const etaButtons = Array.from(etaCard.querySelectorAll('button'));
    check(etaButtons.length === 6, 'expected 6 buttons in eta-card, got ' + etaButtons.length);
    for (const eb of etaButtons) {
      const r = eb.getBoundingClientRect();
      check(eb.offsetParent !== null && r.width > 20 && r.height >= 26, 'eta button hidden: ' + eb.textContent);
      eb.click();
      await sleep(15);
    }
    await sleep(400);
    check(etaCopied.length === 5, 'expected all 5 eta copy actions to trigger, got ' + etaCopied.length);
    check(etaOpened >= 2, 'expected etaOpen to fire for both OneTap and Open buttons, got ' + etaOpened);

    // 7. Verify Privacy tab V3 pure-white borderless cards + V2 collapsible sandbox + all switches & buttons
    resultEl.textContent = 'WAIT:7';
    if (typeof switchTab === 'function') switchTab('privacy');
    await loadPrivacy();
    document.getAnimations().forEach((a) => { try { a.finish(); } catch (_) {} });
    await sleep(30);
    check(getComputedStyle(document.body).backgroundColor === 'rgb(247, 247, 247)', 'privacy light canvas must be #f7f7f7');
    for (const cardId of ['privacy-master-card', 'privacy-rules-card', 'privacy-custom-card', 'privacy-sandbox-card']) {
      const el = document.getElementById(cardId);
      check(el, cardId + ' exists');
      const cs = getComputedStyle(el);
      check(cs.backgroundColor === 'rgb(255, 255, 255)' && cs.borderTopWidth === '0px', cardId + ' must be pure white borderless');
    }
    document.querySelectorAll('#tab-privacy .priv-pure-row').forEach((r, idx) => {
      check(getComputedStyle(r).borderTopWidth === '0px', 'priv row #' + idx + ' zero border');
    });
    const privTab = document.getElementById('tab-privacy');
    const privSwitches = Array.from(privTab.querySelectorAll('input.glass-switch'));
    check(privSwitches.length === 7, 'expected 7 glass-switches in privacy tab, got ' + privSwitches.length);
    for (const sw of privSwitches) {
      const beforePriv = state.fetches.length;
      const prevVal = sw.checked;
      sw.click();
      await sleep(35);
      const req = state.fetches.slice(beforePriv).find((f) => f.url === '/api/privacy' && f.method === 'POST');
      check(req && req.body && req.body.privacy, 'privacy switch ' + sw.id + ' did not trigger POST /api/privacy');
      check(sw.checked === !prevVal, 'privacy switch ' + sw.id + ' state did not flip');
    }
    // Re-enable master switch and verify shield reflects enabled
    const masterSw = document.getElementById('privacy-enabled');
    if (!masterSw.checked) { masterSw.click(); await sleep(35); }
    check(!document.getElementById('privacy-hero-shield').classList.contains('is-off'), 'shield should be green when enabled');

    // Refresh button
    const refreshBtn = privTab.querySelector('.priv-pill-btn');
    check(refreshBtn, 'privacy refresh button exists');
    const beforeRef = state.fetches.length;
    refreshBtn.click();
    await sleep(30);
    check(state.fetches.slice(beforeRef).some((f) => f.url === '/api/privacy' && f.method === 'GET'), 'refresh button triggers GET /api/privacy');

    // Preset regex chips
    const chips = Array.from(privTab.querySelectorAll('.priv-preset-chip'));
    check(chips.length === 2, 'expected 2 regex preset chips');
    for (const chip of chips) {
      const beforeChip = state.fetches.length;
      chip.click();
      await sleep(30);
      check(state.fetches.slice(beforeChip).some((f) => f.url === '/api/privacy' && f.method === 'POST'), 'preset chip saves customPatterns');
    }
    const pmCustom = document.getElementById('pm-custom');
    check(pmCustom.value.includes('10') && pmCustom.value.includes('EMP-'), 'preset chips populated customPatterns');

    // Collapsible sandbox summary toggle + mask run
    const sandboxDetails = document.getElementById('privacy-sandbox-card');
    const sandboxSummary = sandboxDetails.querySelector('summary');
    check(sandboxDetails.open === true, 'sandbox open by default');
    sandboxSummary.click();
    if (typeof disclosureMotion !== 'undefined') disclosureMotion.finishAll();
    document.getAnimations().forEach((a) => { try { a.finish(); } catch (_) {} });
    await sleep(40);
    check(sandboxDetails.open === false, 'sandbox collapses on summary click');
    sandboxSummary.click();
    if (typeof disclosureMotion !== 'undefined') disclosureMotion.finishAll();
    document.getAnimations().forEach((a) => { try { a.finish(); } catch (_) {} });
    await sleep(40);
    check(sandboxDetails.open === true, 'sandbox re-opens on summary click');

    document.getElementById('test-mask-input').value = 'sk-test-123 13812345678';
    const maskBtn = sandboxDetails.querySelector('.priv-cta-btn');
    const beforeMask = state.fetches.length;
    maskBtn.click();
    await sleep(40);
    check(state.fetches.slice(beforeMask).some((f) => f.url === '/api/test-mask'), 'sandbox button called /api/test-mask');
    check(document.getElementById('test-mask-out').innerText.includes('[KEY_1]'), 'sandbox masked output rendered');
    check(document.getElementById('test-unmask-out').innerText.includes('sk-test-123'), 'sandbox unmasked output rendered');
    check(document.body.dataset.activeTab === 'privacy', 'body data-active-tab synced to privacy');

    // Verify [data-active-tab] fallback CSS rules are standalone (not combined in a :has() selector list)
    const allCssRules = Array.from(document.styleSheets).flatMap((s) => {
      try { return Array.from(s.cssRules || []); } catch (_) { return []; }
    });
    const provFallbackRule = allCssRules.find((r) => r.selectorText && r.selectorText.includes('data-active-tab="providers"') && !r.selectorText.includes(':has('));
    const privFallbackRule = allCssRules.find((r) => r.selectorText && r.selectorText.includes('data-active-tab="privacy"') && !r.selectorText.includes(':has('));
    check(provFallbackRule && privFallbackRule, 'data-active-tab fallback CSS rules must be independent from :has()');
    check(getComputedStyle(document.body).backgroundColor === 'rgb(247, 247, 247)', 'light privacy canvas should be #f7f7f7');
    document.documentElement.setAttribute('data-theme', 'dark');
    check(getComputedStyle(document.body).backgroundColor !== 'rgb(247, 247, 247)', 'dark privacy canvas must not inherit light #f7f7f7');
    document.documentElement.removeAttribute('data-theme');

    // Out-of-order privacy save protection: slow OFF (80ms) followed quickly by fast ON (5ms)
    // Both succeed; final persisted state AND UI must be ON, and POSTs must serialize (max concurrent = 1).
    check(masterSw.checked === true, 'master switch starts ON before order test');
    state.privacyMaxConcurrentPosts = 0;
    state.privacyPostDelays = [80, 5];
    masterSw.click(); // OFF (queued first, 80ms)
    masterSw.click(); // ON  (queued second, 5ms)
    await sleep(180);
    check(state.privacyMaxConcurrentPosts === 1, 'privacy saves must serialize, saw concurrent=' + state.privacyMaxConcurrentPosts);
    check(state.privacyState.enabled === true && masterSw.checked === true, 'final persisted and UI privacy enabled must stay ON after OFF->ON');

    // Stale GET /api/privacy must not overwrite a newer toggle OR an actively edited regex draft
    state.privacyGetDelayMs = 60;
    const staleLoad = loadPrivacy();
    const idsSw = document.getElementById('pm-ids');
    const targetIdsVal = !idsSw.checked;
    idsSw.click();
    await Promise.all([staleLoad, sleep(110)]);
    check(idsSw.checked === targetIdsVal && state.privacyState.maskIds === targetIdsVal, 'stale loadPrivacy overwrote newer switch toggle');

    pmCustom.focus();
    pmCustom.value = 'DRAFT-\\d{4}';
    await loadPrivacy();
    check(pmCustom.value === 'DRAFT-\\d{4}', 'loadPrivacy overwrote actively edited regex draft');
    pmCustom.blur();
    await sleep(40);

    // Failed togglePrivacy() rolls back UI switch state
    state.failPrivacyOnce = true;
    const beforeFailChecked = document.getElementById('pm-ids').checked;
    document.getElementById('pm-ids').click();
    await sleep(60);
    check(document.getElementById('pm-ids').checked === beforeFailChecked, 'failed togglePrivacy did not roll back switch');

    // Cross-scenario 1: Refresh during in-flight save must NOT leak old server state into next save
    check(masterSw.checked === true && state.privacyState.enabled === true, 'cross-1 precondition master ON');
    const bankSw = document.getElementById('pm-bank');
    const targetBank = !bankSw.checked;
    state.privacyPostDelays = [90, 5];
    masterSw.click(); // Turn OFF (in-flight 90ms)
    await sleep(15);
    refreshBtn.click(); // Refresh while POST is still in-flight (GET reads old enabled=true)
    await sleep(20);
    check(masterSw.checked === false, 'refresh during in-flight save overwrote pending master OFF');
    bankSw.click(); // Toggle one category while master OFF POST is still in-flight
    await sleep(160);
    check(
      masterSw.checked === false && state.privacyState.enabled === false &&
      bankSw.checked === targetBank && state.privacyState.maskBankCards === targetBank,
      'cross-1 final state mismatch: ui=(' + masterSw.checked + ',' + bankSw.checked + ') persisted=(' + state.privacyState.enabled + ',' + state.privacyState.maskBankCards + ')'
    );
    // Restore master ON for subsequent checks
    masterSw.click();
    await sleep(50);

    // Cross-scenario 2a: Failed save rollback WITHOUT new edit restores server customPatterns + alerts
    const serverCustomBeforeFail = pmCustom.value;
    const alertsBeforeFailA = state.alerts.length;
    state.failPrivacyOnce = true;
    pmCustom.value = serverCustomBeforeFail + '\\nFAIL-ROLLBACK-\\\\d+';
    await togglePrivacy();
    await sleep(40);
    check(pmCustom.value === serverCustomBeforeFail, 'cross-2a failed save without new edit did not roll back customPatterns');
    check(state.alerts.length > alertsBeforeFailA, 'cross-2a failed save did not show alert');

    // Cross-scenario 2b: Failed save rollback WITH new edit during rollback GET preserves new draft + alerts
    const alertsBeforeFailB = state.alerts.length;
    state.failPrivacyOnce = true;
    state.privacyGetDelayMs = 70;
    pmCustom.value = serverCustomBeforeFail + '\\nFAIL-SUBMIT-\\\\d+';
    const failSavePromise = togglePrivacy();
    await sleep(20); // POST failed, rollback GET is now in-flight (70ms)
    pmCustom.focus();
    pmCustom.value = 'KEEP-NEW-DRAFT-\\\\d{4}';
    await failSavePromise;
    await sleep(30);
    check(pmCustom.value === 'KEEP-NEW-DRAFT-\\\\d{4}', 'cross-2b failed rollback overwrote newly edited draft: ' + pmCustom.value);
    check(state.alerts.length > alertsBeforeFailB, 'cross-2b failed save did not show alert');
    pmCustom.value = serverCustomBeforeFail;
    pmCustom.blur();
    await sleep(40);

    // Focus #pm-custom and click preset chip with mousedown+click: should fire single POST without blur duplicate
    pmCustom.focus();
    const beforeChipPost = state.fetches.filter((f) => f.url === '/api/privacy' && f.method === 'POST').length;
    const evDown = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
    chips[0].dispatchEvent(evDown);
    check(evDown.defaultPrevented, 'preset chip mousedown prevented textarea blur');
    chips[0].click();
    await sleep(40);
    const afterChipPost = state.fetches.filter((f) => f.url === '/api/privacy' && f.method === 'POST').length;
    check(afterChipPost - beforeChipPost === 1, 'preset chip click fired expected 1 POST, got ' + (afterChipPost - beforeChipPost));

    // Insight -> 模型白名单 scroll preservation when selecting models in a lower provider
    if (typeof switchTab === 'function') switchTab('insight');
    document.getAnimations().forEach((a) => { try { a.finish(); } catch (_) {} });
    await sleep(60);
    check(document.body.dataset.activeTab === 'insight', 'body data-active-tab synced to insight');
    const policyTabBtn = document.getElementById('insight-tab-policy');
    if (policyTabBtn) policyTabBtn.click();
    document.getAnimations().forEach((a) => { try { a.finish(); } catch (_) {} });
    await sleep(60);
    const expTrigger = document.querySelector('#exposed-card .insight-acc-trigger');
    check(expTrigger, 'exposed accordion trigger exists');
    if (expTrigger.getAttribute('aria-expanded') !== 'true') expTrigger.click();
    if (typeof disclosureMotion !== 'undefined') disclosureMotion.finishAll();
    document.getAnimations().forEach((a) => { try { a.finish(); } catch (_) {} });
    await loadExposed();
    await sleep(40);
    const expToggles = Array.from(document.querySelectorAll('#exposed-list .exp-toggle'));
    expToggles.forEach((t) => { if (t.getAttribute('aria-expanded') !== 'true') t.click(); });
    if (typeof disclosureMotion !== 'undefined') disclosureMotion.finishAll();
    document.getAnimations().forEach((a) => { try { a.finish(); } catch (_) {} });
    await sleep(40);
    const zcodeSec = Array.from(document.querySelectorAll('#exposed-list .exp-prov')).pop();
    check(zcodeSec, 'bottom provider section rendered');
    const chipsBox = zcodeSec.querySelector('.exp-chips');
    check(chipsBox && chipsBox.scrollHeight > chipsBox.clientHeight, 'bottom provider chip list is scrollable');
    window.scrollTo({ top: document.documentElement.scrollHeight, behavior: 'instant' });
    await sleep(20);
    const savedY = window.scrollY;
    chipsBox.scrollTop = 90;
    const savedChipTop = chipsBox.scrollTop;
    check(savedY > 150 && savedChipTop > 40, 'precondition scroll too small: y=' + savedY + ' chipTop=' + savedChipTop);
    const zcodeChips = Array.from(chipsBox.querySelectorAll('.exp-chip'));
    zcodeChips[22].click();
    zcodeChips[25].click();
    zcodeChips[29].click();
    await sleep(30);
    check(Math.abs(window.scrollY - savedY) <= 2, 'window scroll jumped after exp-chip clicks: from ' + savedY + ' to ' + window.scrollY);
    check(Math.abs(chipsBox.scrollTop - savedChipTop) <= 2, 'chipsBox scrollTop jumped after exp-chip clicks: from ' + savedChipTop + ' to ' + chipsBox.scrollTop);
    check(zcodeSec.querySelector('.exp-sub').textContent.startsWith('3/48'), 'bottom provider counter updated in place');
    const clearBtn = Array.from(zcodeSec.querySelectorAll('.exp-mini')).find((b) => b.textContent === '清空');
    clearBtn.click();
    zcodeChips[22].click();
    await sleep(20);
    check(Math.abs(window.scrollY - savedY) <= 2, 'window scroll jumped after group clear+chip click');
    const beforeSaveExp = state.fetches.length;
    document.querySelector('#exposed-card button.btn-primary').click();
    await sleep(40);
    const expPost = state.fetches.slice(beforeSaveExp).find((f) => f.url.includes('/api/exposed') && f.method === 'POST');
    check(expPost && expPost.body && expPost.body.items.includes('zcode/glm-test-22'), 'saveExposed did not save selected model');

    // Verify account-manage-modal sheet & rows have solid opaque backgrounds in both light and dark themes
    manageProviderAccounts('catpaw');
    await sleep(40);
    const accSheet = document.querySelector('#account-manage-modal > .account-sheet');
    const accRow = document.querySelector('#account-manage-modal .account-manage-row');
    check(accSheet && accRow, 'account-manage-modal sheet and row rendered');
    check(getComputedStyle(accSheet).backgroundColor === 'rgb(255, 255, 255)', 'light account sheet must be solid white, got ' + getComputedStyle(accSheet).backgroundColor);
    check(getComputedStyle(accRow).backgroundColor === 'rgb(246, 246, 248)', 'light account row must have surface fill, got ' + getComputedStyle(accRow).backgroundColor);
    document.documentElement.setAttribute('data-theme', 'dark');
    check(getComputedStyle(accSheet).backgroundColor === 'rgb(28, 28, 30)', 'dark account sheet must be #1c1c1e, got ' + getComputedStyle(accSheet).backgroundColor);
    check(getComputedStyle(accRow).backgroundColor === 'rgb(40, 41, 46)', 'dark account row must be #28292e, got ' + getComputedStyle(accRow).backgroundColor);
    document.documentElement.removeAttribute('data-theme');
    closeAccountModal();
    await sleep(30);

    // Verify route-picker-backdrop resolves --route-row for items outside #tab-routing
    const routeBackdrop = document.getElementById('route-picker-backdrop');
    check(routeBackdrop, 'route-picker-backdrop exists');
    const probeRouteItem = document.createElement('div');
    probeRouteItem.className = 'route-sheet-item';
    routeBackdrop.appendChild(probeRouteItem);
    const lightRouteItemBg = getComputedStyle(probeRouteItem).backgroundColor;
    check(lightRouteItemBg && lightRouteItemBg !== 'rgba(0, 0, 0, 0)' && lightRouteItemBg !== 'transparent', 'light route-sheet-item must have opaque/glass bg, got ' + lightRouteItemBg);
    document.documentElement.setAttribute('data-theme', 'dark');
    const darkRouteItemBg = getComputedStyle(probeRouteItem).backgroundColor;
    check(darkRouteItemBg && darkRouteItemBg !== 'rgba(0, 0, 0, 0)' && darkRouteItemBg !== 'transparent' && darkRouteItemBg !== lightRouteItemBg, 'dark route-sheet-item must resolve dark --route-row, got ' + darkRouteItemBg);
    document.documentElement.removeAttribute('data-theme');
    probeRouteItem.remove();

    // Verify route disabled hint collapsible capsule (Scheme A) & Style II icons
    const rHint = document.getElementById('groups-disabled-hint');
    const rHintToggle = document.getElementById('groups-disabled-hint-toggle');
    const rHintBody = document.getElementById('groups-disabled-hint-body');
    check(rHint && rHintToggle && rHintBody, 'route disabled hint collapsible structure exists');
    rHint.style.display = 'flex';
    check(rHintBody.hidden === true && rHintToggle.getAttribute('aria-expanded') === 'false', 'route disabled hint collapsed by default');
    rHintToggle.click();
    if (typeof disclosureMotion !== 'undefined') disclosureMotion.finishAll();
    document.getAnimations().forEach((a) => { try { a.finish(); } catch (_) {} });
    check(rHintBody.hidden === false && rHintToggle.getAttribute('aria-expanded') === 'true', 'route disabled hint expands on click');
    rHint.querySelector('.route-status-ghost').click();
    if (typeof disclosureMotion !== 'undefined') disclosureMotion.finishAll();
    document.getAnimations().forEach((a) => { try { a.finish(); } catch (_) {} });
    check(rHintBody.hidden === true && rHintToggle.getAttribute('aria-expanded') === 'false', 'route disabled hint collapses on ghost button click');
    rHint.style.display = 'none';

    // Return to visible add view for optional screenshot capture
    if (typeof switchTab === 'function') switchTab('providers');
    showProviderView('add');
    document.querySelectorAll('#all-providers-cards-container > .card[data-provider-id]').forEach((c) => { c.hidden = false; });
    document.getAnimations().forEach((a) => { try { a.finish(); } catch (_) {} });
    window.scrollTo(0, 0);

    resultEl.textContent = 'PASS:' + clickedButtonCount;
  } catch (err) {
    resultEl.textContent = 'FAIL:' + (err && err.stack ? err.message : String(err));
  }
})();
</script>`;

const vm = require('node:vm');
new vm.Script(stubScript.replace(/^<script>/, '').replace(/<\/script>$/, ''));
new vm.Script(driverScript.replace(/^<pre[^>]*>[^<]*<\/pre>\s*<script>/, '').replace(/<\/script>$/, ''));
let page = dashboardHtml;
page = page.replace('<head>', () => '<head>\n' + stubScript);
page = page.replace('</body>', () => driverScript + '\n</body>');
assert(page.includes('window.__TEST_STATE = state;'), 'stubScript not injected');

const out = runDom(page, 20000, process.env.ALLIN_CAPTURE_DASHBOARD_SHOT || '');
const match = /id="test-result"[^>]*>([^<]+)<\/pre>/.exec(out);
assert(match, 'missing #test-result in output: ' + out.slice(-500));
assert(match[1].startsWith('PASS:'), match[1]);
console.log('Full dashboard provider cards buttons & per-account enable/disable PASS ->', match[1]);
