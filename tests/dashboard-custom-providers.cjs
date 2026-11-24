'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const {dom}=require('./browser-fixture.cjs');
process.env.ALLIN_TEST_TMP ||= path.resolve(__dirname,'../build');
const source=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/dashboard.html'),'utf8');
const start=source.indexOf('// Header templates adapted from the pinned Magpie');
const end=source.indexOf('    function providerCounts(p)',start);
assert(start>0&&end>start);const code=source.slice(start,end);
const ctx=vm.createContext({});vm.runInContext(code+';globalThis.api={customApplyPreset,customHeadersOf,CUSTOM_HEADER_PRESETS};',ctx);
let rows=[['user-agent','mine'],['x-title',''],['','']];ctx.api.customApplyPreset(rows,ctx.api.CUSTOM_HEADER_PRESETS.find(x=>x.name==='Kilo Code'));
assert.equal(rows.find(x=>x[0]==='user-agent')[1],'mine');assert.equal(rows.find(x=>x[0]==='x-title')[1],'Kilo Code');assert.equal(rows.filter(x=>x[0].toLowerCase()==='user-agent').length,1);
assert.throws(()=>ctx.api.customHeadersOf([['Host','x'],['host','y']]),/重复/);
assert.throws(()=>ctx.api.customHeadersOf([['X-Test','x\r\ny']]),/单行/);
const ref='allin-secret-ref:'+ 'a'.repeat(64);
for(const width of [360,390]){
 const setup=`let appConfig={providers:[{type:'custom-api',id:'custom-fixture',name:'Fixture <not markup>',baseUrl:'https://example.invalid/v1',protocol:'openai',authMode:'auto',enabled:true,models:['org/model'],keys:['${ref}'],headers:{'User-Agent':'${ref}'}}],providerStateTokens:{'custom-fixture':'old-token'}};
 const providerWrites=new Set();let providerWriteRevision=0,sent=null;
 const API_BASE='';const providerToken=id=>appConfig.providerStateTokens[id];const withProviderToken=(body,token)=>(token===undefined?body:{...body,expectedStateToken:token});
 const showToast=()=>{};const showProviderView=()=>{};const loadConfig=async()=>{};const errText=(e,f)=>typeof e==='string'?e:f;
 window.fetch=async(url,opt)=>{sent=JSON.parse(opt.body);return {ok:true,json:async()=>({ok:true,action:sent.action,providerId:sent.provider.id,stateToken:'new-token'})};};`;
 const exercise=`(async()=>{const check=(v,m)=>{if(!v)throw Error(m);};try{
  check(innerWidth===${width},'actual iframe viewport width');
  openCustomEditor('custom-fixture');const form=document.querySelector('form');
  check(form.textContent.includes('Fixture <not markup>')===false,'name is input not HTML');
  check(!form.querySelector('not'),'markup name interpreted');
  check([...form.querySelectorAll('input')].every(x=>!x.value.startsWith('allin-secret-ref:')),'opaque secret reference shown');
  check(form.querySelector('[aria-modal="true"]')===null && form.getAttribute('aria-modal')==='true','dialog semantics');
  const header=form.querySelector('.custom-api-header-row');check(header.querySelectorAll('input')[1].value==='','secret value empty');
  // Renaming a saved header and clearing its value must never emit undefined or carry stale ref.
  header.querySelectorAll('input')[0].value='X-Renamed';header.querySelectorAll('input')[0].dispatchEvent(new Event('input'));
  header.querySelectorAll('input')[1].value='temp';header.querySelectorAll('input')[1].dispatchEvent(new Event('input'));
  header.querySelectorAll('input')[1].value='';header.querySelectorAll('input')[1].dispatchEvent(new Event('input'));
  header.querySelectorAll('input')[0].value='User-Agent';header.querySelectorAll('input')[0].dispatchEvent(new Event('input'));
  header.querySelectorAll('input')[1].value='';header.querySelectorAll('input')[1].dispatchEvent(new Event('input'));
  const preset=form.querySelectorAll('select')[2];preset.value='3';preset.dispatchEvent(new Event('change'));
  check(form.querySelectorAll('.custom-api-header-row').length===1,'preset duplicated case-insensitive header');
  check(document.documentElement.scrollWidth<=innerWidth,'document overflows mobile viewport');
  check(form.scrollWidth<=form.clientWidth,'form overflows mobile viewport');
  form.querySelector('input').value='Updated';form.dispatchEvent(new Event('submit',{cancelable:true}));
  for(let i=0;i<12;i++)await Promise.resolve();
  check(sent.expectedStateToken==='old-token','stale fence lost');
  check(sent.provider.keys[0]==='${ref}','saved key replaced with blank');
  check(sent.provider.headers['User-Agent']==='${ref}','preset overwrote saved secret');
  if(customEditor?.closePromise){customEditorMotion.finish(customEditor);await customEditor.closePromise;}
  check(!document.querySelector('.custom-api-overlay'),'successful save failed to close');
  parent.postMessage('PASS '+innerWidth,'*');
 }catch(e){parent.postMessage('FAIL '+e.message,'*');}})();`;
 const styles=source.match(/<style>([\s\S]*?)<\/style>/)[1];
 const inner='<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>'+styles+'</style></head><body><script>'+setup+code+exercise+'</script></body></html>';
 const outer='<!doctype html><html><body><pre id="result">WAIT</pre><iframe style="border:0;width:'+width+'px;height:900px" srcdoc="'+inner.replace(/&/g,'&amp;').replace(/"/g,'&quot;')+'"></iframe><script>addEventListener("message",e=>document.getElementById("result").textContent=e.data);</script></body></html>';
 const out=dom(outer,1500,{windowSize:[700,1000]});assert.equal(out.match(/id="result"[^>]*>([^<]+)/)?.[1],'PASS '+width);
}
console.log('Custom API panel PASS: preset merge, duplicate/newline rejection, 360/390 real iframe viewports, secret preservation and fenced save. Desktop Chromium, not installed Android WebView.');
