const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {dom} = require('./browser-fixture.cjs');
const root = path.resolve(__dirname, '..');
const html = fs.readFileSync(path.join(root, 'app/src/main/assets/dashboard.html'), 'utf8');

function part(start, end) {
  const from = html.indexOf(start), to = html.indexOf(end, from);
  assert(from >= 0 && to > from, start);
  return html.slice(from, to);
}

const card = part('      <section class="card home-quota"', '      <div class="glass-section-heading"><h2>快捷入口');
assert(html.indexOf(card) > html.indexOf('<div class="grid-stats">'));
const script = part('    // A home refresh only reads billing;', '    const providerBillingCache =');
const helpers = part('    function billingNumber(', '    function billingFreshness(');
const css = html.match(/<style>([\s\S]*?)<\/style>/)[1];
const test = String.raw`
let checks=0;
const node=id=>document.getElementById(id);
const verify=(value,message)=>{checks++;if(!value)throw new Error(message);};
const money=(remaining,windows=[])=>({scope:'account',unit:'credits',remaining,windows});
const wb={id:'workbuddy',name:'WorkBuddy',accounts:[{id:'a',name:'主账号'},{id:'off',enabled:false}]};
const trae={id:'trae-cn',name:'Trae CN',accounts:[{id:'a',name:'工作账号'}]};
const raccoon={id:'raccoon',name:'小浣熊',accounts:[{id:'r',name:'<img src=x onerror=alert(1)>'}]};
const loomy={id:'loomy',name:'Loomy',accounts:[{id:'l',name:'钱包'}]};
const codex={id:'codex',name:'Codex',accounts:[{id:'c',name:'订阅账号'}]};
const account=(accountId,allowance)=>({accountId,allowance});
const data={workbuddy:[account('a',money(2223.41,[{remaining:2223.41,unit:'credits'}])),account('off',money(99999))],
 'trae-cn':[account('a',money(undefined,[{name:'Credits',remaining:2062.06,unit:'credits'}]))],
 raccoon:[account('r',{...money(3474),dailyGrantRemaining:30})],
 loomy:[account('l',money(0,[{name:'daily',remaining:0,unit:'credits'}]))],
 codex:[account('c',{scope:'account',unit:'percent',windows:[{name:'weekly',remainingPercent:80}]})]};
let gatewayRunning=true,statusRevision=0,providerWriteRevision=0;
let appConfig={providers:[wb,trae,raccoon,loomy,codex]};
const API_BASE='';
const requests=[];
let error='',active=0,peak=0,heldProvider='',release;
async function fetch(url,opts) {
 const provider=new URL(url,'http://fixture').searchParams.get('provider');
 requests.push({url,method:opts.method||'GET'});
 peak=Math.max(peak,++active);
 try {
   if(provider===heldProvider)await new Promise(resolve=>release=resolve);
   else await new Promise(resolve=>setTimeout(resolve,8));
   if(provider===error)throw new Error('fixture unavailable');
   return {ok:true,json:async()=>({ok:true,provider,accounts:data[provider]})};
 } finally {active--;}
}
`;
const assertions = String.raw`
(async()=>{
 await refreshHomeQuota();
 verify(node('home-quota-total').textContent==='7,759.47','expected total ignores repeating windows and disabled accounts');
 verify(node('home-quota-status').textContent.includes('4/5')&&node('home-quota-status').textContent.includes('最近更新'),'actual coverage and timestamp');
 verify(node('home-quota-list').textContent.includes('80%'),'percentage visible in details');
 verify(!node('home-quota-list').querySelector('img')&&node('home-quota-list').textContent.includes('<img src=x'),'account name rendered as text');
 verify(peak===3&&requests.length===5&&requests.every(r=>r.method==='GET'&&r.url.startsWith('/api/providers/billing?')),'three-way read-only batching');
 for(let i=0;i<5;i++)await refreshHomeQuota();
 verify(requests.length===5,'poll does not refetch unchanged accounts');
 node('home-quota-details').open=true;
 verify([...node('home-quota-list').children].every(row=>row.scrollWidth<=row.clientWidth),'account rows fit the narrow card');
 error='raccoon';
 const first=refreshHomeQuota(true); await refreshHomeQuota(true); await first;
 verify(requests.length===10&&!node('home-quota-refresh').disabled,'refresh double-click sends one batch');
 verify(node('home-quota-total').textContent==='7,759.47'&&node('home-quota-status').textContent.includes('上次结果'),'failed account keeps marked snapshot');
 verify(node('home-quota-details').open,'refresh keeps disclosure expanded');
 data.loomy=[account('l',money(1.25,[{name:'daily',remaining:2.25,unit:'credits'}]))];
 appConfig.providers=appConfig.providers.filter(p=>p.id!=='raccoon');
 await refreshHomeQuota();
 verify(node('home-quota-total').textContent==='4,288.97'&&!node('home-quota-list').textContent.includes('小浣熊'),'removed accounts vanish and independent Loomy wallets add');
 heldProvider='trae-cn';
 const busy=refreshHomeQuota(true);
 verify(node('home-quota-refresh').disabled,'refresh disabled while busy');
 gatewayRunning=false; statusRevision++;
 data['trae-cn']=[account('a',money(700000))];
 release(); await busy;
 verify(node('home-quota-total').textContent==='4,288.97'&&node('home-quota-status').textContent.includes('网关关闭'),'late response cannot repopulate stopped gateway');
 verify(node('home-quota-refresh').disabled,'closed gateway cannot refresh');
 heldProvider=''; gatewayRunning=true;
 appConfig.providers=[trae]; data['trae-cn']=[account('a',{...money(undefined),unlimited:true})];
 await refreshHomeQuota();
 verify(node('home-quota-total').textContent==='—'&&node('home-quota-list').textContent.includes('不限额'),'unlimited is not numeric zero');
 data['trae-cn']=[account('a',money(0))]; await refreshHomeQuota(true);
 verify(node('home-quota-total').textContent==='0','known zero remains numeric');
 appConfig.providers=[]; await refreshHomeQuota();
 verify(node('home-quota-details').hidden&&node('home-quota-status').textContent==='暂无启用账号','empty state hides disclosure');
 verify(homeQuotaCredits('loomy',money(undefined,[{name:'daily',remaining:4,unit:'credits'}]))===null,'partial wallet excluded');
 verify(homeQuotaCredits('zcode',{scope:'account',unit:'gift_units',remaining:200})===null,'gift units excluded');
 verify(homeQuotaCredits('trae-cn',money(undefined,[{name:'Credits',remaining:20,unit:'credits',scope:'model'}]))===null,'model windows excluded');
 node('result').textContent='PASS '+checks;
})().catch(e=>node('result').textContent='FAIL '+e.message);
`;
const harness = `<!doctype html><html lang="zh-CN" class="glass-ui" data-theme="dark"><meta charset="utf-8"><style>${css}</style>
<body class="liquid-glass"><section id="tab-overview" class="active"><div id="overview-main-view" style="width:360px;max-width:100%;margin:0 auto">${card}</div></section>
<pre id="result">WAIT</pre><script>${test}${helpers}${script}${assertions}</script></body></html>`;
process.env.ALLIN_TEST_TMP ||= path.join(root,'build');
const output = dom(harness, 3000, {windowSize:[390,900]});
const result = output.match(/<pre id="result">([^<]+)<\/pre>/)?.[1];
assert(result?.startsWith('PASS '), result || 'Missing browser result');
console.log('Home quota browser regression:', result);
