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

const glassEngineSlice = html.slice(
  html.indexOf('    function stepGlassSpring('),
  html.indexOf('    function setupDockLens()')
);

const segSliderSlice = html.slice(
  html.indexOf('    const insightSliders = new Map();'),
  html.indexOf("      ['insight-seg'", html.indexOf('    function remeasureRoutingSliders()'))
).replace(/\n\s*\[\s*$/, '');

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
  #test-shell { width: 390px; max-width: 390px; box-sizing: border-box; padding: 14px 14px 100px; }
</style>
</head>
<body class="liquid-glass">
<main id="test-shell">
${tabMarkup}
</main>
${sheetMarkup}
<pre id="result">WAIT</pre>
<script>
const API_BASE = '';
const toasts = [];
const copied = [];
const savedPayloads = [];
let routingState = { ok: true, enabled: true, bareModelFallback: false };
let appConfig = {
  providers: [
    {
      id: 'openai',
      name: 'OpenAI',
      enabled: true,
      selectedModels: ['gpt-4o', 'gpt-4.1-mini'],
      models: ['gpt-4o', 'gpt-4.1-mini', 'o4-mini']
    },
    {
      id: 'deepseek',
      name: 'DeepSeek',
      enabled: true,
      selectedModels: ['deepseek-chat'],
      models: ['deepseek-chat', 'deepseek-reasoner']
    }
  ],
  groups: [
    {
      id: 'main-chat',
      name: '主力对话',
      strategy: 'order',
      targets: ['openai/gpt-4o', 'deepseek/deepseek-chat'],
      pick: ''
    },
    {
      id: 'backup-pool',
      name: '备用池',
      strategy: 'rotate',
      targets: ['openai/gpt-4.1-mini'],
      pick: ''
    }
  ]
};

function showToast(msg) { toasts.push(String(msg)); }
function errText(e) { return (e && e.message) || String(e || 'error'); }
function copyText(text, msg) { copied.push({ text, msg }); showToast(msg); }
function visibleProviders(list) { return Array.isArray(list) ? list : []; }
function providerHasCredential(p) { return !!(p && p.enabled); }
function pingModelsOf(p) { return (p && p.models) || []; }
window.confirm = () => true;

const disclosureMotion = {
  panel(el, open) {
    if (!el) return;
    el.hidden = !open;
    el.inert = !open;
  }
};

let vNow = 1000;
let rafQueue = [];
const rafNative = window.requestAnimationFrame;
window.performance.now = () => vNow;
window.requestAnimationFrame = cb => { rafQueue.push(cb); return rafQueue.length; };
window.cancelAnimationFrame = () => {};
function stepFrames(count) {
  for (let i = 0; i < count; i++) {
    vNow += 16;
    const batch = rafQueue.splice(0, rafQueue.length);
    for (const cb of batch) { try { cb(vNow); } catch (_) {} }
  }
}
const flushAsync = () => new Promise(r => setTimeout(r, 0));
Element.prototype.setPointerCapture = function() {};
window.fetch = async function(url, init) {
  const path = String(url);
  const method = (init && init.method) || 'GET';
  const body = init && init.body ? JSON.parse(init.body) : null;
  if (path === '/api/routing' && method === 'GET') {
    return { ok: true, json: async () => ({ ...routingState }) };
  }
  if (path === '/api/routing' && method === 'POST') {
    routingState = { ...routingState, ...body };
    return { ok: true, json: async () => ({ ...routingState }) };
  }
  if (path === '/api/groups' && method === 'POST') {
    const clone = JSON.parse(JSON.stringify(body.groups || []));
    savedPayloads.push(clone);
    appConfig.groups = clone;
    return { ok: true, json: async () => ({ ok: true }) };
  }
  if (path === '/api/config') {
    return { ok: true, json: async () => ({ ok: true, config: appConfig }) };
  }
  return { ok: false, json: async () => ({ ok: false }) };
};

async function loadConfig() { return appConfig; }

${glassEngineSlice}
${segSliderSlice}
${routingStateSlice}
${routeUiAndGroupsSlice}

(async function runTests() {
  const assertTrue = (cond, msg) => { if (!cond) throw new Error(msg); };
  try {
    renderGroups(true);
    bindGroupEvents();
    await loadRoutingState();
    await flushAsync();
    stepFrames(4);

    // 1. Check both themes layout cleanly at 390px width with borderless cards & hidden compat tools
    const shell = document.getElementById('test-shell');
    for (const theme of ['light', 'dark']) {
      document.documentElement.dataset.theme = theme;
      assertTrue(shell.scrollWidth <= 390, theme + ' shell horizontal overflow: ' + shell.scrollWidth);
      const compatTools = document.querySelector('#tab-routing .route-compat-tools');
      assertTrue(getComputedStyle(compatTools).display === 'none', theme + ' compat tools must be hidden');
      const compatInner = document.querySelector('#tab-routing .route-compat-hidden');
      assertTrue(getComputedStyle(compatInner).display === 'none', theme + ' compat inner must be hidden');
      const card = document.querySelector('#groups-container .route-card');
      assertTrue(card && getComputedStyle(card).borderTopWidth === '0px', theme + ' card must be borderless');
    }
    document.documentElement.dataset.theme = 'light';

    // 2. Test all 4 strategy buttons by click + verify slider node NOT destroyed + persistence + UI panels
    const getCard0 = () => document.querySelector('.route-card[data-group-card="main-chat"]');
    const segBefore = getCard0().querySelector('.route-seg');
    assertTrue(!!segBefore._segApi, 'route-seg should mount segSlider API');

    const expectedStrats = ['rotate', 'manual', 'usage', 'order'];
    for (const strat of expectedStrats) {
      const btn = getCard0().querySelector('.route-seg-btn[data-strat="' + strat + '"]');
      assertTrue(!!btn, 'missing button for strategy ' + strat);
      btn.click();
      stepFrames(35);
      await flushAsync();
      stepFrames(15);

      assertTrue(getCard0().querySelector('.route-seg') === segBefore, 'seg DOM should be patched in-place without rebuilding');
      assertTrue(btn.getAttribute('aria-selected') === 'true', strat + ' aria-selected must become true');
      const latest = savedPayloads[savedPayloads.length - 1];
      assertTrue(latest && latest[0].strategy === strat, 'POST /api/groups strategy mismatch for ' + strat);

      if (strat === 'manual') {
        assertTrue(latest[0].pick === 'openai/gpt-4o', 'manual strategy should default pick to first target');
        const picks = getCard0().querySelectorAll('[data-action="pick"]');
        assertTrue(picks.length === 2, 'manual strategy should show pick button on every target row');
        assertTrue(picks[0].textContent.trim() === '主用' && picks[1].textContent.trim() === '设为主用', 'unexpected pick button labels');
        picks[1].click();
        await flushAsync();
        const pickSave = savedPayloads[savedPayloads.length - 1];
        assertTrue(pickSave[0].pick === 'deepseek/deepseek-chat', 'pick click failed to save second target');
      }

      if (strat === 'usage') {
        const moreBtn = getCard0().querySelector('[data-action="usage-detail"]');
        const detail = getCard0().querySelector('.route-usage-detail');
        assertTrue(!!moreBtn && !!detail && detail.hidden === true, 'usage detail should start collapsed');
        moreBtn.click();
        assertTrue(detail.hidden === false && detail.textContent.includes('可信上报用量计分'), 'usage detail should expand');
        moreBtn.click();
        assertTrue(detail.hidden === true, 'usage detail should collapse on second click');
      }
    }

    // 3. Pointer drag across segmented lens from order (0) to manual (2)
    const btns = Array.from(segBefore.querySelectorAll('.route-seg-btn'));
    const rect0 = btns[0].getBoundingClientRect();
    const rect2 = btns[2].getBoundingClientRect();
    const y = rect0.top + rect0.height / 2;
    const pill = segBefore.querySelector('.route-seg-pill');
    const centerX = el => el.getBoundingClientRect().left + el.getBoundingClientRect().width / 2;
    btns[0].dispatchEvent(new PointerEvent('pointerdown', {
      bubbles: true, pointerId: 1, pointerType: 'touch', clientX: centerX(btns[0]), clientY: y
    }));
    stepFrames(12);
    assertTrue(segBefore.hasAttribute('data-lens-lifted'), 'pointerdown should lift lens');
    btns[0].dispatchEvent(new PointerEvent('pointermove', {
      bubbles: true, pointerId: 1, pointerType: 'touch', clientX: centerX(btns[2]), clientY: y
    }));
    stepFrames(18);
    assertTrue(Math.abs(centerX(pill) - centerX(btns[2])) < 14, 'lens should follow pointer drag smoothly');
    btns[0].dispatchEvent(new PointerEvent('pointerup', {
      bubbles: true, pointerId: 1, pointerType: 'touch', clientX: centerX(btns[2]), clientY: y
    }));
    await flushAsync();
    stepFrames(60);
    assertTrue(btns[2].getAttribute('aria-selected') === 'true', 'pointer drag should activate 手动指定 (manual)');
    assertTrue(savedPayloads[savedPayloads.length - 1][0].strategy === 'manual', 'pointer drag should persist manual');
    assertTrue(!segBefore.hasAttribute('data-lens-lifted') && Math.abs(centerX(pill) - centerX(btns[2])) < 2, 'lens should settle on dragged strategy');

    // 4. Rule guide popover, bottom sheet picker add, reorder up/down, copy group id, add group
    const guideBtn = document.getElementById('route-guide-btn');
    const guidePop = document.getElementById('route-guide-popover');
    guideBtn.click();
    assertTrue(guidePop.hidden === false, 'guide popover should open');
    guideBtn.click();
    assertTrue(guidePop.hidden === true, 'guide popover should close');

    getCard0().querySelector('[data-action="open-picker"]').click();
    await flushAsync();
    const bd = document.getElementById('route-picker-backdrop');
    assertTrue(bd.hidden === false, 'picker sheet should open');
    onRouteSheetQueryInput('mini');
    const sheetItems = Array.from(document.querySelectorAll('#route-sheet-list .route-sheet-item'));
    assertTrue(sheetItems.some(row => row.textContent.includes('gpt-4.1-mini')), 'sheet query filter failed');
    const addMini = sheetItems.find(row => row.textContent.includes('gpt-4.1-mini'));
    addMini.click();
    await flushAsync();
    assertTrue(savedPayloads[savedPayloads.length - 1][0].targets.includes('openai/gpt-4.1-mini'), 'sheet click should add model and save');
    document.querySelector('.route-sheet-close').click();
    const sh = document.getElementById('route-picker-sheet');
    const sheetAnims = [...bd.getAnimations(), ...sh.getAnimations()];
    sheetAnims.forEach(a => a.finish());
    await Promise.all(sheetAnims.map(a => a.finished.catch(() => {})));
    await flushAsync();
    assertTrue(bd.hidden === true, 'sheet close button should hide backdrop');

    // Move third member up
    const downBtn = getCard0().querySelectorAll('[data-action="up"]')[2];
    downBtn.click();
    await flushAsync();
    assertTrue(savedPayloads[savedPayloads.length - 1][0].targets[1] === 'openai/gpt-4.1-mini', 'member up action failed');

    // Copy virtual model chip
    getCard0().querySelector('[data-action="copygroup"]').click();
    assertTrue(copied.some(c => c.text === 'group/main-chat'), 'copygroup should copy group/id');

    // Add new group
    addNewGroup();
    await flushAsync();
    assertTrue(!!document.querySelector('.route-card[data-group-card="my-group-1"]'), 'addNewGroup should render new card');

    // Eager (non-lazyGL) mountSegSlider check (mirrors governance segmented sliders)
    const eagerNav = document.createElement('div');
    eagerNav.className = 'route-seg';
    eagerNav.innerHTML = '<div class="route-seg-pill"></div>'
      + '<button class="route-seg-btn" aria-selected="true">A</button>'
      + '<button class="route-seg-btn" aria-selected="false">B</button>';
    shell.appendChild(eagerNav);
    const eagerApi = mountSegSlider(eagerNav, { pillClass: 'route-seg-pill', btnClass: 'route-seg-btn', activeAttr: 'aria-selected' });
    assertTrue(!!eagerApi && eagerNav.hasAttribute('data-lens-ready'), 'eager mountSegSlider must initialize without TDZ errors');
    eagerApi.destroy();
    eagerNav.remove();

    document.getElementById('result').textContent = 'PASS';
  } catch (err) {
    document.getElementById('result').textContent = 'FAIL ' + (err && err.stack ? err.stack : err.message);
  }
})();
</script>
</body>
</html>`;

const domOut = browser.dom(page, 2600);
const result = domOut.match(/id="result"[^>]*>([\s\S]*?)<\/pre>/)?.[1]?.trim() || 'NO_RESULT';
assert.equal(result, 'PASS', result);
console.log('Routing V3 strategies (order/rotate/manual/usage click + pointer drag + sheet + FLIP + popover) OK');
