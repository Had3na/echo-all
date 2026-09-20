import {localAction} from './lan-client.mjs';
const $=id=>document.getElementById(id);
let localPC=false;
export async function initLocal(cloud,runSync){
 if(!window.EchoNotes && ['127.0.0.1','localhost'].includes(location.hostname)){
   try{const r=await fetch('/api/local/info');if(r.ok){const data=await r.json();localPC=data.local===true;}}
   catch{}
 }
 $('lan-host').hidden=!localPC;$('lan-phone').hidden=localPC;
 if(localPC&&!cloud.config().token&&(!localStorage.getItem('echo-owner')||localStorage.getItem('echo-owner').startsWith('lan:')))await cloud.connectLocal();
 $('local-sync').onclick=()=>{$('lan-status').textContent=cloud.config().mode?.startsWith('lan')?'Appareil associé · '+(cloud.config().name||'Echo-All'):'';$('lan-dialog').showModal();};
 $('close-lan').onclick=()=>$('lan-dialog').close();
 $('show-code').onclick=async()=>{try{$('show-code').disabled=true;await cloud.connectLocal();await runSync();const data=await localAction('pair');$('pair-code').value=data.code;$('pair-address').textContent='Sur '+data.name+' · '+data.address+' · valable 5 minutes, une seule fois';const qr=qrcode(0,'M');qr.addData('echoall://notes?code='+encodeURIComponent(data.code));qr.make();$('pair-qr').src=qr.createDataURL(5,8);$('pair-qr').hidden=false;$('lan-status').textContent='Sur le téléphone : Notes → Même Wi-Fi → saisir le code, ou scanner ce QR avec l’appareil photo.';}catch(e){$('lan-status').textContent=e.message;}finally{$('show-code').disabled=false;}};
 $('copy-code').onclick=async()=>{try{await navigator.clipboard.writeText($('pair-code').value);$('lan-status').textContent='Code copié';}catch{$('pair-code').select();}};
 $('pair-form').onsubmit=async e=>{e.preventDefault();$('pair-submit').disabled=true;try{await cloud.pairLocal($('pair-input').value);$('pair-input').value='';$('lan-status').textContent='Associé. Synchronisation en cours…';await runSync();$('lan-status').textContent='Association réussie. Les prochaines synchronisations se feront automatiquement sur ce réseau.';}catch(error){$('lan-status').textContent=error.message;}finally{$('pair-submit').disabled=false;}};
 $('lan-sync-now').onclick=()=>runSync();
 $('revoke-devices').onclick=async()=>{if(!confirm('Dissocier les téléphones ? Leurs notes locales resteront conservées.'))return;try{await localAction('revoke');$('lan-status').textContent='Téléphones dissociés. Un nouveau code sera nécessaire.';}catch(e){$('lan-status').textContent=e.message;}};
 window.echoPairFromLink=code=>{$('pair-input').value=code;$('lan-dialog').showModal();};
 if(window.echoPendingPairCode)window.echoPairFromLink(window.echoPendingPairCode);
}
const rgb=s=>s.match(/[a-f\d]{2}/gi).map(x=>parseInt(x,16));
function mix(a,b,t){const aa=rgb(a),bb=rgb(b);return '#'+aa.map((v,i)=>Math.round(v+(bb[i]-v)*t).toString(16).padStart(2,'0')).join('');}
function luminance(s){return rgb(s).map(v=>{v/=255;return v<=.04045?v/12.92:((v+.055)/1.055)**2.4;}).reduce((sum,v,i)=>sum+v*[.2126,.7152,.0722][i],0);}
export function theme(){
 let native;try{native=window.EchoNotes?.getTheme?JSON.parse(window.EchoNotes.getTheme()):null;}catch{}
 let saved;try{saved=JSON.parse(localStorage.getItem('echo-notes-theme')||'{}');}catch{saved={};}
 const selected=/^#[a-f\d]{6}$/i.test(saved.color||'')?saved.color:'#E879F9',dark=saved.mode==='light'?false:saved.mode==='system'?!matchMedia('(prefers-color-scheme:light)').matches:true;
 const accent=dark?(luminance(selected)<.18?mix(selected,'#ffffff',.48):selected):(luminance(selected)>.3?mix(selected,'#000000',.45):selected);
 const values=native||{dark,selected,accent,bg:mix(dark?'#101114':'#FAFAFC',selected,dark?.035:.025),panel:mix(dark?'#212227':'#E8E8EF',selected,.08),fg:dark?'#F2F2F5':'#17171D',muted:dark?'#B6B6C0':'#50505A',line:dark?'#303039':'#E0E0E7',onAccent:luminance(accent)>.4?'#101114':'#FFFFFF'};
 const root=document.documentElement;for(const key of ['bg','panel','fg','muted','line','accent','onAccent'])root.style.setProperty('--'+key,values[key]);root.style.setProperty('--hover',mix(values.panel,values.accent,.1));root.style.setProperty('--active',mix(values.panel,values.accent,.16));root.style.setProperty('--glow',values.accent+'66');root.style.colorScheme=values.dark?'dark':'light';root.dataset.reduced=values.reduceMotion?'true':'false';
 $('theme-native').hidden=!native;$('theme-controls').hidden=!!native;$('theme-mode').value=saved.mode||'dark';$('theme-color').value=selected;
 $('appearance').onclick=()=>$('theme-dialog').showModal();$('close-theme').onclick=()=>$('theme-dialog').close();
 for(const id of ['theme-mode','theme-color'])$(id).onchange=()=>{localStorage.setItem('echo-notes-theme',JSON.stringify({mode:$('theme-mode').value,color:$('theme-color').value}));theme();};
 for(const b of document.querySelectorAll('[data-color]'))b.onclick=()=>{$('theme-color').value=b.dataset.color;$('theme-color').onchange();};
}
