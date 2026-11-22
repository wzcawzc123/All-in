'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {dom} = require('./browser-fixture.cjs');
const {readFunction} = require('./model-selection-fixture.cjs');

const html = fs.readFileSync(process.argv[2] || 'app/src/main/assets/dashboard.html', 'utf8');
process.env.ALLIN_TEST_TMP ||= path.resolve('build/codex-card-stream-browser');
const styles = [...html.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)].map(match => match[0]).join('\n');
const loginCode = html.slice(html.indexOf('    async function pollCodexFlow'), html.indexOf('    function clineLogin()'));
const functions = ['refreshProviderBadges', 'accountIdentityText', 'accountDisplayInfo', 'streamPingCurrentModel', 'escapeHtml']
  .map(name => readFunction(html, name)).join('\n');

for (const theme of ['light', 'dark']) {
  const page = `<!doctype html><html class="glass-ui" data-theme="${theme}"><meta charset="utf-8">${styles}
  <body class="liquid-glass"><div id="badge-count-codex"></div><div id="codex-current-accounts"></div>
  <div id="codex-status"></div><div id="codex-usage-status"></div><input id="ping-model-select" value="codex/fixture">
  <div id="ping-test-output"></div><div id="ping-model-status"></div><pre id="result">WAIT</pre><script>
  const API_BASE = '', PROVIDER_CARD_IDS = ['codex'];
  let mutedAlerts = 0, reads = 0, usageResolve, interval, clock = 0;
  const provider = {id:'codex', accounts:[{id:'a', email:'first@example.invalid'}]};
  const appConfig = {providers:[provider]};
  const find = id => document.getElementById(id);
  const check = (ok, note) => { if (!ok) throw new Error(note); };
  const esc = value => String(value).replaceAll("&", "&amp;").replaceAll("<", "&lt;");
  const errText = error => error.message;
  const alert = () => mutedAlerts++;
  const prompt = () => 'fixture';
  const showToast = () => {};
  const setInterval = fn => { interval = fn; return 1; };
  const clearInterval = () => {};
  Object.defineProperty(performance,'now',{value:() => ++clock * 50});
  async function loadConfig() { reads++; refreshProviderBadges(); }
  async function fetch(url) {
    if (url.endsWith('/usage')) return new Promise(resolve => { usageResolve = resolve; });
    return {ok:true, json:async() => ({ok:true, status:'done', account:'stale-receipt@example.invalid'})};
  }
  ${functions}${loginCode}
  window.AllInNative = {startStream(id) {
    for (const chunk of [{delta:{content:'绿色'}},{delta:{content:'可读'}},{delta:{},finish_reason:'stop'}])
      window.__allInStreamChunk(id, 'data: ' + JSON.stringify({choices:[chunk]}));
    window.__allInStreamChunk(id,'data: [DONE]');
    window.__allInStreamEnd(id,200,'');
  }};
  (async () => {
    refreshProviderBadges();
    check(find('codex-current-accounts').textContent.includes('first@example.invalid'),'initial account missing');
    const usage = checkCodexUsage();
    provider.accounts = [];
    refreshProviderBadges();
    check(find('codex-current-accounts').textContent === '尚未接入 Codex 账号','deleted account stayed visible');
    usageResolve({ok:true,json:async() => ({plan_type:'old-plan'})});
    await usage;
    check(find('codex-usage-status').textContent === '', 'deleted account usage was repainted');
    provider.accounts = [{id:'b', email:'second@example.invalid'}];
    await promptImportCodexCredentials();
    check(find('codex-current-accounts').textContent.includes('second@example.invalid'),'new account missing');
    check(!document.body.innerText.includes('first@example.invalid'),'old account returned');
    find('codex-status').innerHTML = '<input id="codex-manual-code" value="fixture">';
    await submitCodexCode('fixture');
    pollCodexFlow('fixture'); await interval();
    check(reads === 3 && !mutedAlerts && !find('codex-status').textContent,'all login paths need a real config reload');
    check(!document.body.innerText.includes('stale-receipt@example.invalid'),'receipt painted an account identity');
    streamPingCurrentModel();
    const out = find('ping-test-output');
    const colors = [out, out.querySelector('b'), out.querySelector('span'), out.lastElementChild,
      find('ping-model-status').firstElementChild].map(node => getComputedStyle(node).color);
    const expected = '${theme}' === 'light' ? 'rgb(7, 92, 57)' : 'rgb(134, 239, 172)';
    check(colors.every(color => color === expected),'incorrect computed success colors: '+colors.join(' / '));
    check(out.textContent.includes('绿色可读') && out.textContent.includes('SSE 完整'),'stream output or verdict missing');
    find('result').textContent = JSON.stringify({passed:true, theme:'${theme}', colors});
  })().catch(error => { find('result').textContent = 'FAIL '+error.message; });
  </script></body></html>`;
  const rendered = dom(page, 2500);
  const result = rendered.match(/<pre id="result"[^>]*>([^<]*)<\/pre>/)?.[1];
  assert(result?.includes('"passed":true'), result);
  console.log(result);
}
