'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {dom}=require('./browser-fixture.cjs');
process.env.ALLIN_TEST_TMP ||= path.resolve(__dirname,'../build');
const source=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/dashboard.html'),'utf8');
const native=fs.readFileSync(path.join(__dirname,'../app/src/main/java/com/gaeul/allin/MainActivity.java'),'utf8');
const head=source.match(/<script>\s*\/\* 主题[\s\S]*?<\/script>/)[0];
const start=source.indexOf("    const THEME_KEY = 'allin_theme';");
const end=source.indexOf('    // ==================== 每日用量热力图',start);
assert(start>0&&end>start);
const theme=source.slice(start,end);
assert(native.includes('PanelThemePage.readAndSeed(getAssets().open("dashboard.html"), isSystemDark())'));
assert(native.includes('if (view != webView || bridgeDestroyed || !bridgeAllowed() || isFinishing() || isDestroyed()) return;'));
assert(native.includes('window.__allinInitialSystemDark=null;'));
let checks=0;
for(const mode of ['auto','light','dark'])for(const systemDark of [true,false]){
 const expected=mode==='dark'||mode==='auto'&&systemDark;
 const setup=`<script>
 let mode='${mode}',live=false,bars=[],mqListeners=[];
 Object.defineProperty(window,'localStorage',{value:{getItem:()=>mode,setItem:(key,value)=>{mode=value;}}});
 window.matchMedia=()=>({matches:false,addEventListener:(type,fn)=>mqListeners.push(fn),addListener:fn=>mqListeners.push(fn)});
 window.AllInNative={isSystemDark:()=>live,applySystemBars:value=>bars.push(value)};
 window.__allinInitialSystemDark=${systemDark};
 </script>`;
 const exercise=`<script>
 ${theme}
 const check=(ok,label)=>{if(!ok)throw Error(label);};
 try {
  check(document.documentElement.getAttribute('data-theme')==='${expected?'dark':'light'}','first document theme before readiness');
  check(document.documentElement.getAttribute('data-theme-mode')==='${mode}','explicit mode preserved');
  check(bars[0]===${expected},'first system bar request');
  live=${systemDark};window.__allinInitialSystemDark=null;window.__onSystemThemeChanged();
  check(document.documentElement.getAttribute('data-theme')==='${expected?'dark':'light'}','ready reconciliation');
  if(mode==='auto'){
   live=!live;window.__onSystemThemeChanged();
   check(document.documentElement.getAttribute('data-theme')===(live?'dark':'light'),'live system theme update');
   check(document.getElementById('theme-hint').innerText.includes(live?'深色':'浅色'),'hint agrees with actual theme');
   setThemeMode('light');live=true;window.__onSystemThemeChanged();
   check(document.documentElement.getAttribute('data-theme')==='light','manual light cannot be overridden');
   setThemeMode('dark');live=false;window.__onSystemThemeChanged();
   check(document.documentElement.getAttribute('data-theme')==='dark','manual dark cannot be overridden');
   setThemeMode('auto');
   check(document.documentElement.getAttribute('data-theme')==='light','return to auto uses live bridge');
  }else{
   live=!live;window.__onSystemThemeChanged();
   check(document.documentElement.getAttribute('data-theme')==='${expected?'dark':'light'}','system update leaves manual mode alone');
  }
  document.getElementById('result').textContent='PASS';
 }catch(e){document.getElementById('result').textContent='FAIL '+e.message;}
 </script>`;
 const page='<!doctype html><html><head><meta charset="UTF-8">'+setup+head+'</head><body><div id="theme-seg"><button class="seg-btn" data-mode="auto"></button><button class="seg-btn" data-mode="dark"></button><button class="seg-btn" data-mode="light"></button></div><p id="theme-hint"></p><pre id="result">WAIT</pre>'+exercise+'</body></html>';
 const out=dom(page,500);assert.equal(out.match(/id="result"[^>]*>([^<]+)/)?.[1],'PASS',mode+' nativeDark='+systemDark);checks++;
}
// Desktop/browser-only fallback remains available without any native bootstrap.
for(const dark of [true,false]){
 const setup=`<script>Object.defineProperty(window,'localStorage',{value:{getItem:()=>null}});window.matchMedia=()=>({matches:${dark}});</script>`;
 const page='<!doctype html><html><head>'+setup+head+'</head><body><pre id="result">'+(dark?'dark':'light')+'</pre><script>document.getElementById("result").textContent=document.documentElement.getAttribute("data-theme");</script></body></html>';
 assert.equal(dom(page,100).match(/id="result"[^>]*>([^<]+)/)?.[1],dark?'dark':'light');checks++;
}
console.log('Startup theme: '+checks+' browser scenarios passed; early false bridge, manual modes, live reconciliation and desktop fallback.');
