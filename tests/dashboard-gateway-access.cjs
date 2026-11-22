const fs=require('fs'),vm=require('vm'),assert=require('assert');
const html=fs.readFileSync('app/src/main/assets/dashboard.html','utf8');
for(const m of html.matchAll(/<script>([\s\S]*?)<\/script>/g))new vm.Script(m[1]);
const code=html.slice(html.indexOf('    // Gateway access inventory:'),html.indexOf('    // End gateway access inventory.'));
function element(){return {value:'',textContent:'',disabled:false,children:[],attributes:{},animations:[],
 appendChild(n){this.children.push(n)},replaceChildren(){this.children=[]},setAttribute(k,v){this.attributes[k]=v},getAttribute(k){return this.attributes[k]},
 animate(frames,options){const animation={frames,options,cancel(){this.cancelled=true}};this.animations.push(animation);return animation},
 classList:{contains(){return true}}};}
function page(){
 const nodes={},calls=[],copied=[],queue=[];let reduced=false;
 const context={document:{hidden:false,getElementById:id=>nodes[id]||(nodes[id]=element()),createElement:()=>element(),addEventListener(){}},API_BASE:'',statusKnown:true,gatewayRunning:true,AbortController,setTimeout,clearTimeout,Date,Map,Set,matchMedia:()=>({matches:reduced}),loadStats:async()=>{},loadAuthKey:async()=>{},copyText:t=>copied.push(t),fetch:async(url,opts)=>{calls.push({url,opts});const next=queue.shift();if(next instanceof Error)throw next;if(typeof next==='function')return next();return {ok:true,json:async()=>next}}};
 vm.createContext(context);vm.runInContext(code,context);
 return {c:context,nodes,calls,copied,queue,setReduced:value=>{reduced=value},run:x=>vm.runInContext(x,context)};
}
const model=(id,provider='pool')=>({id,name:id.split('/').slice(1).join('/'),provider,providerName:provider,kind:provider==='group'?'group':'model'});
(async()=>{
 const p=page();p.queue.push({ok:true,models:[model('pool/model/id'),model('pool/model/id'),model('group/main','group')],observedAt:123});
 await p.c.refreshGatewayAccess(false);assert.equal(p.run('gatewayAccessRows.length'),2);assert.equal(p.calls[0].url,'/api/gateway-access');assert.equal(p.calls[0].opts.cache,'no-store');
 p.c.copyGatewayModelIds();assert.equal(p.copied[0],'pool/model/id\ngroup/main');
 p.nodes['access-model-search'].value='model/id';p.c.renderGatewayModels();p.c.copyGatewayModelIds();assert.equal(p.copied.at(-1),'pool/model/id');
 p.nodes['access-model-search'].value='';
 // Automatic reads are throttled to 30 seconds; explicit refresh bypasses this.
 const beforeThrottle=p.calls.length;
 await p.c.refreshGatewayAccess(false);
 assert.equal(p.calls.length,beforeThrottle,'immediate automatic read is suppressed');
 p.run('gatewayAccessLast=Date.now()-29900');await p.c.refreshGatewayAccess(false);
 assert.equal(p.calls.length,beforeThrottle,'no automatic read before 30 seconds');
 assert.equal(p.run('GATEWAY_ACCESS_POLL_MS'),30000);
 const delayStart=html.indexOf('      function dashboardPollDelay()');
 const delayEnd=html.indexOf('      function pollDashboard()',delayStart);
 let hidden=false,access=true;
 const delayContext=vm.createContext({document:{get hidden(){return hidden}},gatewayAccessVisible:()=>access,GATEWAY_ACCESS_POLL_MS:30000});
 vm.runInContext(html.slice(delayStart,delayEnd),delayContext);
 assert.equal(delayContext.dashboardPollDelay(),30000);
 access=false;assert.equal(delayContext.dashboardPollDelay(),3000);
 hidden=true;assert.equal(delayContext.dashboardPollDelay(),30000);
 assert(html.includes('if (document.hidden) return;'));
 assert(html.includes("if (tabId === 'gateway') { gatewayAccessLast = 0; refreshGatewayAccess(false); }"));
 // New upstream catalogs must finish before obtaining the authoritative inventory.
 p.queue.push({providers:[{id:'workbuddy',accounts:[{token:'redacted-reference'}]},{id:'trae-cn',accounts:[{token:'redacted-reference'}]},{id:'openai',keys:[]},{id:'raccoon',enabled:false,keys:['redacted-reference']}]},{ok:true},{ok:true},{ok:true,models:[model('workbuddy/current','workbuddy')],observedAt:234});
 await p.c.refreshGatewayAccess(true);
 assert.deepEqual(p.calls.slice(1).map(x=>x.url),['/api/config','/api/auth/workbuddy/sync-models','/api/auth/trae-cn/sync-models','/api/gateway-access']);
 assert.equal(p.calls.find(x=>x.url.includes('trae-cn')).opts.method,'POST');
 p.c.copyGatewayModelIds();assert.equal(p.copied.at(-1),'workbuddy/current');assert.equal(p.run('gatewayAccessRows.length'),1,'removed model is gone');
 // A leftover or static key alone must not trigger a subscription directory request.
 const codex=page();
 const codexProvider={id:'codex',keys:['old-reference'],accounts:[{authMode:'apikey',token:'key-reference'},{authMode:'chatgpt',token:'disabled-reference',enabled:false}]};
 codex.queue.push({providers:[codexProvider]},{ok:true,models:[]});
 await codex.c.refreshGatewayAccess(true);
 assert.deepEqual(codex.calls.map(x=>x.url),['/api/config','/api/gateway-access']);
 assert.equal(codex.run('gatewayAccessSyncFailures'),0);
 codexProvider.accounts.push({authMode:'chatgpt',access:'active-reference'});
 codex.queue.push({providers:[codexProvider]},{ok:true},{ok:true,models:[model('codex/live','codex')]});
 await codex.c.refreshGatewayAccess(true);
 assert.deepEqual(codex.calls.slice(2).map(x=>x.url),['/api/config','/api/auth/codex/sync-models','/api/gateway-access']);
 assert.equal(codex.run('gatewayAccessRows[0].id'),'codex/live');
 // The server publishes selected custom models under their exact aliases, including bare names.
 const mixed=page();
 const custom={id:'relay-chat',name:'relay-chat',provider:'custom-api-demo',providerName:'Custom Relay',kind:'model'};
 const mixedRows=[model('codex/live','codex'),custom,model('group/main','group')];
 mixed.queue.push({ok:true,models:mixedRows});
 await mixed.c.refreshGatewayAccess(false);
 assert.equal(mixed.nodes['access-model-count'].textContent,'2','a bare custom alias must not invalidate the whole catalog');
 assert.equal(mixed.nodes['access-provider-count'].textContent,'2');
 assert.equal(mixed.nodes['access-group-count'].textContent,'1');
 mixed.c.copyGatewayModelIds();assert.equal(mixed.copied.at(-1),'codex/live\nrelay-chat\ngroup/main');
 mixed.queue.push({providers:[]},{ok:true,models:mixedRows});
 await mixed.c.refreshGatewayAccess(true);
 assert.equal(mixed.run('gatewayAccessReady'),true,'manual refresh also accepts the mixed catalog');
 mixed.queue.push({ok:true,models:[...mixedRows,{...custom,id:'  '}]});
 mixed.run('gatewayAccessLast=0');await mixed.c.refreshGatewayAccess(false);
 assert.equal(mixed.nodes['access-copy-all'].disabled,true,'an invalid blank model ID still invalidates the inventory');
 // Errors clear stale IDs and disable copy instead of presenting old inventory as current.
 p.queue.push(new Error('offline'));p.run('gatewayAccessLast=0');await p.c.refreshGatewayAccess(false);
 assert.equal(p.run('gatewayAccessRows.length'),0);assert.equal(p.nodes['access-copy-all'].disabled,true);let count=p.copied.length;p.c.copyGatewayModelIds();assert.equal(p.copied.length,count);
 // While a request is held, duplicate refreshes cannot race or make duplicate requests.
 let release;p.queue.push(()=>new Promise(r=>release=r));p.run('gatewayAccessLast=0');let request=p.c.refreshGatewayAccess(false);await Promise.resolve();count=p.calls.length;await p.c.refreshGatewayAccess(false);assert.equal(p.calls.length,count);release({ok:true,json:async()=>({ok:true,models:[],observedAt:345})});await request;
 assert.equal(p.nodes['access-refresh'].disabled,false);assert.equal(p.run('gatewayAccessReady'),true);
 // Hold network responses so transient loading flashes cannot escape detection.
 const quiet=page();let firstRelease;
 quiet.queue.push(()=>new Promise(resolve=>firstRelease=resolve));
 let first=quiet.c.refreshGatewayAccess(false);await Promise.resolve();
 assert.equal(quiet.nodes['access-refresh-label'].textContent,'读取中…','initial load still shows progress');
 assert.equal(quiet.nodes['access-refresh'].disabled,true);
 const inventory={ok:true,models:[model('pool/live')],observedAt:500};
 firstRelease({ok:true,json:async()=>inventory});await first;
 const refresh=quiet.nodes['access-refresh'],label=quiet.nodes['access-refresh-label'],status=quiet.nodes['access-refresh-status'];
 const mutations=[];
 for(const [node,key] of [[label,'textContent'],[refresh,'disabled'],[status,'textContent']]) {
  let value=node[key];Object.defineProperty(node,key,{get:()=>value,set:next=>{mutations.push([key,next]);value=next},configurable:true});
 }
 const listBefore=quiet.nodes['access-model-list'].children.slice();
 for(let i=0;i<3;i++) {
  let finish;quiet.queue.push(()=>new Promise(resolve=>finish=resolve));quiet.run('gatewayAccessLast=0');
  const pending=quiet.c.refreshGatewayAccess(false);await Promise.resolve();
  assert.equal(label.textContent,'刷新');assert.equal(refresh.disabled,false);
  assert.equal(mutations.length,0,'silent in-flight polling does not mutate loading UI');
  finish({ok:true,json:async()=>({...inventory,observedAt:501+i})});await pending;
  assert(!mutations.some(([key,value])=>key==='disabled'||String(value).includes('读取中')||String(value).includes('核对当前')),'no transient loading states');
  assert.deepEqual(quiet.nodes['access-model-list'].children,listBefore,'unchanged models preserve copy buttons');
  mutations.length=0;
 }
 // A user click while a silent read is running is queued once, not dropped.
 let finishQuiet;quiet.queue.push(()=>new Promise(resolve=>finishQuiet=resolve));quiet.run('gatewayAccessLast=0');
 const pendingQuiet=quiet.c.refreshGatewayAccess(false);await Promise.resolve();
 await quiet.c.refreshGatewayAccess(true);await quiet.c.refreshGatewayAccess(true);
 assert.equal(quiet.run('gatewayAccessRefreshPending'),true);
 quiet.queue.push({providers:[]},{ok:true,models:[],observedAt:600});
 finishQuiet({ok:true,json:async()=>inventory});await pendingQuiet;
 for(let i=0;i<12;i++)await Promise.resolve();
 assert.equal(quiet.calls.filter(x=>x.url==='/api/config').length,1,'coalesced manual refresh runs after polling');
 assert(mutations.some(([key,value])=>value==='刷新中…'),'manual refresh still displays progress');
 assert.equal(quiet.run('gatewayAccessRows.length'),0,'model removals still update during refresh');
 assert.equal(quiet.nodes['access-copy-all'].disabled,true);
 assert.equal(quiet.nodes['access-refresh-light'].animations.length,1,'queued request sweeps only once');
 const motion=page();
 const modelA=model('pool/alpha'), modelB=model('pool/beta');
 motion.queue.push({ok:true,models:[modelA]});await motion.c.refreshGatewayAccess(false);
 let releaseRefresh;
 motion.queue.push({providers:[]},()=>new Promise(resolve=>{releaseRefresh=resolve}));
 const manual=motion.c.refreshGatewayAccess(true);
 while(!releaseRefresh) await Promise.resolve();
 const counts=['model','provider','group'].map(kind=>motion.nodes['access-'+kind+'-count']);
 assert.deepEqual(counts.map(el=>el.textContent),['1','1','0'],'last confirmed counts stay visible while waiting');
 assert.equal(motion.nodes['access-copy-all'].disabled,true,'old IDs cannot be copied while waiting');
 assert.equal(motion.nodes['access-refresh'].attributes['aria-busy'],'true');
 assert.equal(motion.nodes['access-refresh-light'].animations[0].options.duration,820);
 releaseRefresh({ok:true,json:async()=>({ok:true,models:[modelA,modelB]})});await manual;
 assert.deepEqual(counts.map(el=>el.textContent),['2','1','0']);
 assert.deepEqual(counts.map(el=>el.animations.length),[1,0,0],'only changed counts animate');
 assert.equal(motion.nodes['access-refresh-light'].animations[0].cancelled,undefined,'completion does not cut off a fast sweep');
 assert.equal(motion.nodes['access-refresh'].attributes['aria-busy'],'false');
 motion.queue.push({providers:[]},{ok:true,models:[modelB,modelA]});await motion.c.refreshGatewayAccess(true);
 assert.match(motion.nodes['access-refresh-status'].textContent,/已核对 · 数据无变化/);
 assert.deepEqual(counts.map(el=>el.animations.length),[1,0,0]);
 motion.queue.push({providers:[{id:'workbuddy',keys:['test-only']}]},new Error('offline'),{ok:true,models:[modelB,modelA],unconfirmedCatalogs:true});
 await motion.c.refreshGatewayAccess(true);
 assert.match(motion.nodes['access-refresh-status'].textContent,/1 项上游目录同步失败/);
 assert.equal(motion.nodes['access-refresh-status'].attributes['data-tone'],'partial');
 motion.setReduced(true);
 motion.queue.push({providers:[]},{ok:true,models:[]});await motion.c.refreshGatewayAccess(true);
 assert.deepEqual(counts.map(el=>el.animations.length),[1,0,0],'reduced motion applies changes without animating');
 assert.equal(motion.nodes['access-refresh-light'].animations.length,3);
 motion.queue.push({providers:[]},new Error('offline'));await motion.c.refreshGatewayAccess(true);
 assert.deepEqual(counts.map(el=>el.textContent),['—','—','—']);
 assert.equal(motion.nodes['access-refresh-status'].attributes['data-tone'],'error');
 // Supplier strings are inserted as text, never HTML.
 p.queue.push({ok:true,models:[model('pool/<img src=x onerror=alert(1)>')],observedAt:456});p.run('gatewayAccessLast=0');await p.c.refreshGatewayAccess(false);
 assert.equal(p.run('gatewayAccessRows[0].id'),'pool/<img src=x onerror=alert(1)>');assert(!code.includes('innerHTML'));
 for(const id of ['cred-base-url','cred-lan-url']){assert(html.includes('<textarea id="'+id+'"'));assert.equal(html.split('id="'+id+'"').length,2)}
 assert(html.includes('class="ping-model-actions"'));assert(html.includes('class="credential-edit-row"'));assert(html.includes('/ 6 * var(--nav-idx'));assert(html.includes('.connection-field .address-value {'));assert(html.includes('.gateway-hero {'));
 const section=html.indexOf('<section id="tab-gateway"');assert(section>=0);assert(html.indexOf('id="card-credentials"')>section);assert(!code.includes('/v1/models?all'));assert(!code.includes("fetch(API_BASE + '/default_config"));
 console.log('gateway access: authoritative refresh, per-pool sync, no stale copy, exact IDs, filter, empty/error/overlap and safe DOM PASS');
})().catch(e=>{console.error(e);process.exitCode=1});
