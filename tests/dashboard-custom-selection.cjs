'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {dom}=require('./browser-fixture.cjs');
process.env.ALLIN_TEST_TMP ||= path.resolve(__dirname,'../build');
const html=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/dashboard.html'),'utf8');
const start=html.indexOf('// Header templates adapted from the pinned Magpie'),end=html.indexOf('    function providerCounts(p)',start);assert(start>0&&end>start);
const code=html.slice(start,end),styles=html.match(/<style>([\s\S]*?)<\/style>/)[1],ref='allin-secret-ref:'+'a'.repeat(64);
for(const width of [360,390]){
 const setup=`let appConfig={providers:[{type:'custom-api',id:'custom-7263352e-123b-4a86-a2da-6550b2dc',name:'Fixture',baseUrl:'https://example.invalid/v1',protocol:'openai',authMode:'auto',enabled:true,models:['GPT-6.2-Sol'],catalogModels:['GPT-6.2-Sol','GPT-6.2-Astra'],keys:['${ref}'],headers:{}}],providerStateTokens:{'custom-7263352e-123b-4a86-a2da-6550b2dc':'old-token'}};
 const providerWrites=new Set();let providerWriteRevision=0,writes=[],mode='normal',release=null;
 const API_BASE='';const providerToken=id=>appConfig.providerStateTokens[id];const withProviderToken=(b,t)=>t===undefined?b:{...b,expectedStateToken:t};
 const showToast=()=>{};const showProviderView=()=>{};const loadConfig=async()=>{};const errText=(e,f)=>typeof e==='string'?e:f;
 window.fetch=async(url,opt)=>{const body=JSON.parse(opt.body);if(url.endsWith('/catalog')){if(mode==='delay')await new Promise(r=>release=r);return {ok:mode!=='failure',json:async()=>mode==='failure'?{ok:false,error:'catalog failed'}:{ok:true,providerId:body.provider.id,saved:false,models:mode==='large'?Array.from({length:1205},(_,i)=>'model-'+i):['GPT-6.2-Sol','GPT-6.2-Astra','GPT-6.1-Terra']}};}
 writes.push(body);return {ok:true,json:async()=>({ok:true,action:body.action,providerId:body.provider.id,stateToken:'new-token'})};};`;
 const exercise=`(async()=>{const check=(v,m)=>{if(!v)throw Error(m);};const tick=async()=>{for(let i=0;i<20;i++)await Promise.resolve();};try{
  check(innerWidth===${width},'exact iframe viewport');openCustomEditor('custom-7263352e-123b-4a86-a2da-6550b2dc');let form=document.querySelector('form');
  check(!form.textContent.includes('调用 ID'),'internal ID field remains');check([...form.querySelectorAll('input')].every(x=>!x.value.startsWith('custom-')),'random identity visible');
  form.querySelector('[data-custom-fetch-models]').click();await tick();check(writes.length===0,'preview wrote config');
  let inputs=[...form.querySelectorAll('[data-custom-model-chips] input')];check(inputs.length===3,'fetched candidates missing');check(inputs.filter(x=>x.checked).length===1,'new models automatically checked');
  check(form.querySelector('[data-custom-model-chips]').textContent.startsWith('GPT-6.2-Sol'),'model names not leading labels');check(!form.querySelector('[data-custom-model-chips]').textContent.includes('custom-'),'random ID in choices');
  inputs[1].click();check(inputs[1].checked,'individual selection failed');
  [...form.querySelectorAll('button')].find(x=>x.textContent==='全选').click();check([...form.querySelectorAll('[data-custom-model-chips] input')].every(x=>x.checked),'all selection failed');
  [...form.querySelectorAll('button')].find(x=>x.textContent==='全不选').click();check([...form.querySelectorAll('[data-custom-model-chips] input')].every(x=>!x.checked),'none selection failed');
  form.querySelector('[data-model-id="GPT-6.2-Sol"]').click();
  mode='failure';form.querySelector('[data-custom-fetch-models]').click();await tick();check(form.querySelector('[data-model-id="GPT-6.2-Sol"]').checked&&form.querySelector('[data-custom-model-chips]').children.length===3,'failed fetch erased selection');
  mode='delay';form.querySelector('[data-custom-fetch-models]').click();await tick();const base=form.querySelector('input[type=url]');base.value='https://changed.invalid/v1';mode='normal';release();await tick();check(form.querySelector('[data-custom-model-count]').textContent.includes('已经改变'),'stale draft result accepted');base.value='https://example.invalid/v1';
  check(form.scrollWidth<=form.clientWidth&&document.documentElement.scrollWidth<=innerWidth,'narrow viewport overflow');
  form.dispatchEvent(new Event('submit',{cancelable:true}));await tick();check(writes.length===1,'save count');check(writes[0].provider.models.length===1&&writes[0].provider.models[0]==='GPT-6.2-Sol','unchecked models saved into pool');check(writes[0].provider.catalogModels.length===3,'candidate catalog lost');check(writes[0].provider.keys[0]==='${ref}'&&writes[0].expectedStateToken==='old-token','stored key or conflict fence lost');
  if(customEditor?.closePromise){customEditorMotion.finish(customEditor);await customEditor.closePromise;}await tick();
  openCustomEditor();form=document.querySelector('form');form.querySelector('input').value='New';form.querySelector('input[type=url]').value='https://example.invalid/v1';form.querySelectorAll('select')[1].value='none';form.querySelector('[data-custom-fetch-models]').click();await tick();check([...form.querySelectorAll('[data-custom-model-chips] input')].every(x=>!x.checked),'new supplier fetch selected everything');
  mode='large';form.querySelector('[data-custom-fetch-models]').click();await tick();check(form.querySelectorAll('[data-custom-model-chips] input').length<=100,'thousands of DOM nodes rendered');[...form.querySelectorAll('button')].find(x=>x.textContent==='全选').click();check(form.querySelector('[data-custom-model-count]').textContent.includes('1205 / 1205'),'bulk selection skipped paginated models');
  form.dispatchEvent(new Event('submit',{cancelable:true}));await tick();check(writes[1].provider.models.length===1205,'all-pages selection not saved');
  parent.postMessage('PASS '+innerWidth,'*');
 }catch(e){parent.postMessage('FAIL '+e.message,'*');}})();`;
 const inner='<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>'+styles+'</style></head><body><script>'+setup+code+exercise+'</script></body></html>';
 const page='<!doctype html><html><body><pre id="result">WAIT</pre><iframe style="border:0;width:'+width+'px;height:900px" srcdoc="'+inner.replace(/&/g,'&amp;').replace(/"/g,'&quot;')+'"></iframe><script>addEventListener("message",e=>document.getElementById("result").textContent=e.data);</script></body></html>';
 assert.equal(dom(page,2200,{windowSize:[700,1000]}).match(/id="result"[^>]*>([^<]+)/)?.[1],'PASS '+width);
}
console.log('Custom selection panel PASS: hidden ID, model-first labels, read-only preview, initial none, checked-only save, failed/stale fetch preservation, bulk/pagination, 360/390 iframe widths.');
