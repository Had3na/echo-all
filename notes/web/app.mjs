import {initLocal,theme} from './local-ui.mjs';
import * as db from './store.mjs';
import * as cloud from './sync.mjs';
import {validateNote} from './model.mjs';
import * as pdfjs from './vendor/pdf.mjs';
pdfjs.GlobalWorkerOptions.workerSrc=new URL('./vendor/pdf.worker.mjs',import.meta.url).href;
const $=id=>document.getElementById(id), clone=x=>structuredClone(x);
let navigationEpoch=0;
let current=null,pageIndex=0,pdf=null,pdfTask=null,renderTask=null,renderEpoch=0,tool='pen',stroke=null,pointer=null,redos=[],history=[],beforePage=null,records=[],saveChain=Promise.resolve(),saveFailed=false;
const canvas=$('ink-canvas'),ctx=canvas.getContext('2d'),background=$('pdf-canvas');
const PDF_LIMIT=25*1024*1024;
function toast(message){$('toast').textContent=message;$('toast').hidden=false;clearTimeout(toast.timer);toast.timer=setTimeout(()=>$('toast').hidden=true,6500);}
function fail(e){console.error(e);toast(e.message||'Action impossible');}
function status(message){$('sync-status').textContent=message;}
function persist(){
  if(!current)return;
  current.updatedAt=Date.now();const snapshot=clone(current);
  $('save-status').textContent='Enregistrement…';
  saveChain=saveChain.catch(()=>{}).then(async()=>{validateNote(snapshot);await db.save(snapshot);saveFailed=false;$('save-status').textContent='Enregistré sur cet appareil';}).catch(e=>{saveFailed=true;$('save-status').textContent='Échec de sauvegarde — exporte une copie avant de quitter';fail(e);});
  return saveChain;
}
async function list(){
  records=await db.all();
  const selectedFolder=$('folder-filter').value,folders=[...new Set(records.filter(r=>!r.doc.deleted).map(r=>r.doc.folder).filter(Boolean))].sort();
  $('folder-filter').replaceChildren(new Option('Toutes les matières',''),...folders.map(f=>new Option(f,f)));$('folder-filter').value=folders.includes(selectedFolder)?selectedFolder:'';
  const query=$('search').value.toLocaleLowerCase('fr');$('note-list').replaceChildren();
  const visible=records.filter(r=>!r.doc.deleted&&(!selectedFolder||r.doc.folder===selectedFolder)&&[r.doc.title,r.doc.folder,r.doc.text,...r.doc.checks.map(c=>c.text)].join(' ').toLocaleLowerCase('fr').includes(query)).sort((a,b)=>b.doc.updatedAt-a.doc.updatedAt);
  if(!visible.length){const p=document.createElement('p');p.className='muted';p.textContent='Aucune note ici. Crée un cours, une liste ou importe un PDF.';$('note-list').append(p);}
  for(const record of visible){const n=record.doc,b=document.createElement('button');b.className='note-card'+(current?.id===n.id?' active':'');const title=document.createElement('b');title.textContent=n.title||'Sans titre';const meta=document.createElement('span');meta.textContent=({ink:'Manuscrit',pdf:'PDF',text:'Texte',check:'Liste'}[n.kind])+' · '+(n.folder||'Personnel')+(record.dirty&&cloud.config().token?' · à synchroniser':'');b.append(title,meta);b.onclick=()=>open(n.id).catch(fail);$('note-list').append(b);}
}
function base(kind){return {id:crypto.randomUUID(),title:({ink:'Nouveau cours',text:'Nouvelle note',check:'Ma liste',pdf:'Mon PDF'}[kind]),kind,folder:'',text:'',checks:[],pages:[[]],paper:'lines',pdfId:null,deleted:false,createdAt:Date.now(),updatedAt:Date.now()};}
async function create(kind){$('new-dialog').close();await saveChain;if(saveFailed)return;const doc=base(kind);await db.save(doc);await open(doc.id);$('title').focus();$('title').select();}
async function open(id,onlyIfCurrent=false){
  const navigation=++navigationEpoch;
  await saveChain;if(saveFailed||navigation!==navigationEpoch||(onlyIfCurrent&&current?.id!==id))return;
  const row=await db.get(id);if(!row||row.doc.deleted||navigation!==navigationEpoch||(onlyIfCurrent&&current?.id!==id))return;
  renderEpoch++;renderTask?.cancel();const previousPdf=pdfTask;pdfTask=null;await previousPdf?.destroy();if(navigation!==navigationEpoch||(onlyIfCurrent&&current?.id!==id))return;pdf=null;current=clone(row.doc);pageIndex=0;redos=[];history=[];
  document.body.classList.add('editing');$('welcome').hidden=true;$('editor').hidden=false;
  $('title').value=current.title;$('folder').value=current.folder;$('text').value=current.text;$('text').hidden=current.kind!=='text';$('checklist').hidden=current.kind!=='check';$('drawing').hidden=!['ink','pdf'].includes(current.kind);$('paper').hidden=current.kind==='pdf';$('add-page').hidden=current.kind==='pdf';$('paper').value=current.paper||'lines';$('zoom').value='1';$('save-status').textContent='Enregistré sur cet appareil';
  if(current.kind==='check')renderChecks();
  if(current.kind==='pdf'){
    const capturedId=current.id,file=await db.get(current.pdfId,'files');if(!file)throw Error('PDF indisponible sur cet appareil. Synchronise pour le récupérer.');
    pdfTask=loadPdf(await file.blob.arrayBuffer());const loaded=await pdfTask.promise;if(current?.id!==capturedId||navigation!==navigationEpoch)return;pdf=loaded;
  }
  if(['ink','pdf'].includes(current.kind))await renderPage();
  await list();
}
function loadPdf(data){return pdfjs.getDocument({data:new Uint8Array(data),isEvalSupported:false,cMapUrl:new URL('./vendor/cmaps/',import.meta.url).href,cMapPacked:true,standardFontDataUrl:new URL('./vendor/standard_fonts/',import.meta.url).href,wasmUrl:new URL('./vendor/wasm/',import.meta.url).href});}
function renderChecks(){
  $('checks').replaceChildren();for(const item of current.checks){const row=document.createElement('div');row.className='check-row'+(item.done?' done':'');const box=document.createElement('input');box.type='checkbox';box.checked=item.done;box.setAttribute('aria-label',item.text);box.onchange=()=>{item.done=box.checked;persist();renderChecks();};const text=document.createElement('span');text.textContent=item.text;const del=document.createElement('button');del.textContent='×';del.setAttribute('aria-label','Retirer '+item.text);del.onclick=()=>{current.checks=current.checks.filter(c=>c.id!==item.id);persist();renderChecks();};row.append(box,text,del);$('checks').append(row);}
}
function paper(ctx,w,h,style){ctx.fillStyle='#fffef9';ctx.fillRect(0,0,w,h);if(style==='plain')return;ctx.strokeStyle='#dce5eb';ctx.lineWidth=w/1200;const step=w/26;ctx.beginPath();for(let y=step;y<h;y+=step){ctx.moveTo(0,y);ctx.lineTo(w,y);}if(style==='grid')for(let x=step;x<w;x+=step){ctx.moveTo(x,0);ctx.lineTo(x,h);}ctx.stroke();}
function ink(target,strokes,w,h){
  for(const s of strokes){const p=s.points;if(!p.length)continue;target.save();target.strokeStyle=s.color;target.fillStyle=s.color;target.lineCap='round';target.lineJoin='round';target.globalAlpha=s.tool==='highlighter'?.28:1;
    if(s.tool==='highlighter'){target.lineWidth=s.width*w/1000;target.beginPath();target.moveTo(p[0][0]*w,p[0][1]*h);for(const q of p)target.lineTo(q[0]*w,q[1]*h);if(p.length===1)target.lineTo(p[0][0]*w+.1,p[0][1]*h);target.stroke();}
    else if(p.length===1){target.beginPath();target.arc(p[0][0]*w,p[0][1]*h,s.width*w/2000*(.35+p[0][2]),0,Math.PI*2);target.fill();}
    else for(let i=1;i<p.length;i++){target.lineWidth=s.width*w/1000*(.35+(p[i-1][2]+p[i][2])/2);target.beginPath();target.moveTo(p[i-1][0]*w,p[i-1][1]*h);target.lineTo(p[i][0]*w,p[i][1]*h);target.stroke();}target.restore();
  }
}
function draw(){ctx.clearRect(0,0,canvas.width,canvas.height);ink(ctx,[...(current?.pages[pageIndex]||[]),...(stroke?[stroke]:[])],canvas.width,canvas.height);$('undo').disabled=!history[pageIndex]?.length;$('redo').disabled=!redos[pageIndex]?.length;}
async function renderPage(){
  if(!current||!['ink','pdf'].includes(current.kind))return;
  if(stroke)finish();const epoch=++renderEpoch;renderTask?.cancel();
  const page=pdf?await pdf.getPage(pageIndex+1):null;if(epoch!==renderEpoch)return;
  const ratio=page?(()=>{const v=page.getViewport({scale:1});return v.height/v.width;})():1.4142;
  const width=Math.min(900,Math.max(230,$('viewport').clientWidth-36))*Number($('zoom').value),height=width*ratio,dpr=Math.min(devicePixelRatio||1,2,2300/width);
  $('sheet').style.width=width+'px';$('sheet').style.height=height+'px';
  for(const c of [canvas,background]){c.width=Math.round(width*dpr);c.height=Math.round(height*dpr);}
  if(page){const v=page.getViewport({scale:background.width/page.getViewport({scale:1}).width});renderTask=page.render({canvasContext:background.getContext('2d'),viewport:v});try{await renderTask.promise;}catch(e){if(e.name!=='RenderingCancelledException')throw e;}}
  else paper(background.getContext('2d'),background.width,background.height,current.paper);
  if(epoch!==renderEpoch)return;draw();const count=pdf?.numPages||current.pages.length;$('page-number').textContent=(pageIndex+1)+' / '+count;$('previous').disabled=pageIndex===0;$('next').disabled=pageIndex>=count-1;
}
const clamp=n=>Math.max(0,Math.min(1,n));
function point(e){const r=canvas.getBoundingClientRect();return [clamp((e.clientX-r.left)/r.width),clamp((e.clientY-r.top)/r.height),clamp(e.pointerType==='pen'?(e.pressure||.15):.5)];}
let erased=false,pan=null;
canvas.onpointerover=e=>{canvas.style.touchAction=tool==='hand'?'pan-x pan-y':(e.pointerType==='pen'||$('touch').checked?'none':'pan-x pan-y');};
canvas.onpointerdown=e=>{
  if(pointer!==null||!current)return;
  if(tool==='hand'){if(e.pointerType!=='touch'){pan={x:e.clientX,y:e.clientY,left:$('viewport').scrollLeft,top:$('viewport').scrollTop};pointer=e.pointerId;canvas.setPointerCapture(pointer);}return;}
  if(e.pointerType==='touch'&&!$('touch').checked)return;
  if(e.button!==0&&!(e.pointerType==='pen'&&e.button===5))return;
  e.preventDefault();pointer=e.pointerId;canvas.setPointerCapture(pointer);erased=tool==='eraser'||e.button===5;beforePage=current.pages[pageIndex].slice();
  if(erased)erase(point(e));else stroke={tool,color:$('color').value,width:Number($('width').value)*(tool==='highlighter'?5:1),points:[point(e)]};draw();
};
function erase(p){const strokes=current.pages[pageIndex];const aspect=canvas.height/canvas.width;function distance(a,b){const dx=b[0]-a[0],dy=(b[1]-a[1])*aspect,px=p[0]-a[0],py=(p[1]-a[1])*aspect,t=Math.max(0,Math.min(1,(px*dx+py*dy)/(dx*dx+dy*dy||1)));return Math.hypot(px-t*dx,py-t*dy);}
  for(let i=strokes.length-1;i>=0;i--){const s=strokes[i],radius=.014+s.width/2000;if(s.points.some((a,k)=>distance(a,s.points[Math.max(0,k-1)])<radius)){strokes.splice(i,1);}}
}
canvas.onpointermove=e=>{if(e.pointerId!==pointer)return;if(pan){$('viewport').scrollLeft=pan.left+pan.x-e.clientX;$('viewport').scrollTop=pan.top+pan.y-e.clientY;return;}e.preventDefault();for(const event of (e.getCoalescedEvents?.().length?e.getCoalescedEvents():[e])){if(erased)erase(point(event));else if(stroke&&stroke.points.length<100000)stroke.points.push(point(event));}draw();};
function finish(){if(pointer===null)return;if(stroke){current.pages[pageIndex].push(stroke);stroke=null;}if(!pan){(history[pageIndex]??=[]).push(beforePage||[]);if(history[pageIndex].length>40)history[pageIndex].shift();redos[pageIndex]=[];persist();}beforePage=null;pan=null;pointer=null;draw();}
canvas.onpointerup=finish;canvas.onpointercancel=finish;canvas.onlostpointercapture=finish;
for(const b of document.querySelectorAll('[data-tool]'))b.onclick=()=>{finish();tool=b.dataset.tool;for(const t of document.querySelectorAll('[data-tool]'))t.classList.toggle('selected',t===b);canvas.style.touchAction=$('touch').checked&&tool!=='hand'?'none':'pan-x pan-y';};
$('touch').onchange=()=>{canvas.style.touchAction=$('touch').checked&&tool!=='hand'?'none':'pan-x pan-y';};
$('undo').onclick=()=>{finish();const previous=history[pageIndex]?.pop();if(previous){(redos[pageIndex]??=[]).push(current.pages[pageIndex].slice());current.pages[pageIndex]=previous;persist();draw();}};
$('redo').onclick=()=>{finish();const next=redos[pageIndex]?.pop();if(next){(history[pageIndex]??=[]).push(current.pages[pageIndex].slice());current.pages[pageIndex]=next;persist();draw();}};
$('previous').onclick=()=>{finish();pageIndex=Math.max(0,pageIndex-1);renderPage().catch(fail);};$('next').onclick=()=>{finish();pageIndex=Math.min((pdf?.numPages||current.pages.length)-1,pageIndex+1);renderPage().catch(fail);};
$('add-page').onclick=()=>{if(current.pages.length>=500){toast('500 pages maximum par cahier');return;}finish();current.pages.push([]);pageIndex=current.pages.length-1;persist();renderPage().catch(fail);};
$('paper').onchange=()=>{current.paper=$('paper').value;persist();renderPage().catch(fail);};$('zoom').onchange=()=>renderPage().catch(fail);
let resizeTimer;new ResizeObserver(()=>{clearTimeout(resizeTimer);resizeTimer=setTimeout(()=>renderPage().catch(fail),160);}).observe($('viewport'));
$('title').oninput=()=>{current.title=$('title').value;persist();};$('folder').oninput=()=>{current.folder=$('folder').value;persist();};$('text').oninput=()=>{current.text=$('text').value;persist();};
$('check-form').onsubmit=e=>{e.preventDefault();const text=$('check-input').value.trim();if(!text)return;if(current.checks.length>=2000){toast('2000 éléments maximum');return;}current.checks.push({id:crypto.randomUUID(),text,done:false});$('check-input').value='';persist();renderChecks();};
$('new-note').onclick=()=>$('new-dialog').showModal();for(const b of document.querySelectorAll('[data-create]'))b.onclick=()=>create(b.dataset.create).catch(fail);
$('back').onclick=async()=>{finish();await saveChain;if(saveFailed)return;document.body.classList.remove('editing');await list();};
$('search').oninput=()=>list().catch(fail);$('folder-filter').onchange=()=>list().catch(fail);
$('delete').onclick=async()=>{if(!confirm('Supprimer cette note sur les appareils synchronisés ? Exporte-la d’abord si tu souhaites garder une copie.'))return;current.deleted=true;await persist();if(saveFailed){current.deleted=false;return;}current=null;renderEpoch++;$('editor').hidden=true;$('welcome').hidden=false;document.body.classList.remove('editing');await list();runSync();};
$('import-pdf').onclick=()=>$('pdf-file').click();$('pdf-file').onchange=async()=>{const file=$('pdf-file').files[0];$('pdf-file').value='';if(!file)return;try{if(file.size>PDF_LIMIT)throw Error('PDF limité à 25 Mo');const task=loadPdf(await file.arrayBuffer());let count;try{const document=await task.promise;count=document.numPages;if(count>500)throw Error('PDF limité à 500 pages');}finally{await task.destroy();}const id=await db.digest(file);await db.put({id,blob:file},'files');const note=base('pdf');note.pdfId=id;note.title=file.name.replace(/\.pdf$/i,'').slice(0,200);note.pages=Array.from({length:count},()=>[]);await db.save(note);await open(note.id);toast('PDF importé et disponible hors connexion');}catch(e){fail(e);}};
async function download(blob,name){
  if(window.EchoNotes?.saveFile){if(blob.size>80*1024*1024)throw Error('Export trop volumineux sur Android');const reader=new FileReader();const data=await new Promise((ok,no)=>{reader.onload=()=>ok(reader.result.split(',')[1]);reader.onerror=no;reader.readAsDataURL(blob);});window.EchoNotes.saveFile(name,blob.type,data);return;}
  const url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),60000);
}
async function exportPdf(){
  finish();await saveChain;if(!current)return;const doc=clone(current),out=await PDFLib.PDFDocument.create(),c=document.createElement('canvas');let exportTask,source;
  if(doc.kind==='pdf'){const file=await db.get(doc.pdfId,'files');exportTask=loadPdf(await file.blob.arrayBuffer());source=await exportTask.promise;}
  try{
    let textPages=[];
    if(['text','check'].includes(doc.kind)){c.width=1240;c.height=1754;const cx=c.getContext('2d');cx.font='25px sans-serif';const lines=[];const content=doc.title+'\n\n'+(doc.kind==='check'?doc.checks.map(i=>(i.done?'☑ ':'☐ ')+i.text).join('\n'):doc.text);for(const paragraph of content.split('\n')){let line='';for(const char of paragraph){if(cx.measureText(line+char).width>1080){lines.push(line);line='';}line+=char;}lines.push(line);}for(let i=0;i<lines.length;i+=38)textPages.push(lines.slice(i,i+38));}
    const count=textPages.length||doc.pages.length;
    for(let i=0;i<count;i++){
      $('save-status').textContent='Export PDF · page '+(i+1)+' / '+count;
      let ratio=1.4142,pg;
      if(source){pg=await source.getPage(i+1);const v=pg.getViewport({scale:1});ratio=v.height/v.width;}
      c.width=1240;c.height=Math.round(1240*ratio);const cx=c.getContext('2d');
      if(pg)await pg.render({canvasContext:cx,viewport:pg.getViewport({scale:1240/pg.getViewport({scale:1}).width})}).promise;
      else paper(cx,c.width,c.height,textPages.length?'plain':doc.paper);
      if(textPages.length){cx.fillStyle='#23334a';cx.font='25px sans-serif';textPages[i].forEach((line,j)=>cx.fillText(line,80,95+j*40));}else ink(cx,doc.pages[i],c.width,c.height);
      const blob=await new Promise(ok=>c.toBlob(ok,'image/png'));const image=await out.embedPng(await blob.arrayBuffer());const p=out.addPage([595,595*ratio]);p.drawImage(image,{x:0,y:0,width:595,height:595*ratio});
    }
    await download(new Blob([await out.save()],{type:'application/pdf'}),(doc.title||'Note').replace(/[\\/:*?"<>|]/g,'_')+'.pdf');
  }finally{await exportTask?.destroy();$('save-status').textContent=saveFailed?'Sauvegarde locale en échec':'Enregistré sur cet appareil';}
}
$('export').onclick=async()=>{$('export').disabled=true;try{await exportPdf();}catch(e){fail(e);}finally{$('export').disabled=false;}};
$('backup').onclick=async()=>{try{await saveChain;const notes=(await db.all()).map(r=>r.doc),files=[];let size=0;for(const f of await db.all('files')){size+=f.blob.size;if(size>55*1024*1024)throw Error('Sauvegarde trop volumineuse : exporte les cours individuellement en PDF');const data=await new Promise((ok,no)=>{const r=new FileReader();r.onload=()=>ok(r.result.split(',')[1]);r.onerror=no;r.readAsDataURL(f.blob);});files.push({id:f.id,data});}await download(new Blob([JSON.stringify({format:'echo-notes',version:1,notes,files})],{type:'application/json'}),'Echo-Notes-'+new Date().toISOString().slice(0,10)+'.json');}catch(e){fail(e);}};
$('restore').onclick=()=>$('backup-file').click();$('backup-file').onchange=async()=>{const file=$('backup-file').files[0];$('backup-file').value='';if(!file)return;try{if(file.size>80*1024*1024)throw Error('Sauvegarde limitée à 80 Mo');const pack=JSON.parse(await file.text());if(pack.format!=='echo-notes'||pack.version!==1||!Array.isArray(pack.notes)||!Array.isArray(pack.files))throw Error('Sauvegarde incompatible');const files=[];for(const n of pack.notes)validateNote(n);for(const f of pack.files){const bytes=Uint8Array.from(atob(f.data),c=>c.charCodeAt(0));const blob=new Blob([bytes],{type:'application/pdf'});if(blob.size>PDF_LIMIT||await db.digest(blob)!==f.id)throw Error('PDF de sauvegarde endommagé');files.push({id:f.id,blob});}for(const n of pack.notes)if(n.pdfId&&!files.some(f=>f.id===n.pdfId)&&!await db.get(n.pdfId,'files'))throw Error('PDF absent de la sauvegarde');for(const f of files)await db.put(f,'files');for(const n of pack.notes.filter(n=>!n.deleted)){n.id=crypto.randomUUID();n.title=(n.title+' · restauré').slice(0,200);await db.save(n);}await list();toast('Notes restaurées en copies ; aucune note existante remplacée');}catch(e){fail(e);}};
async function runSync(){await saveChain;if(saveFailed)return;await cloud.sync(status,async event=>{if(event.type==='conflict'){if(current?.id===event.id){current=clone(event.copy.doc);$('title').value=current.title;}toast('Deux versions ont été conservées. Compare la copie en conflit avec la note d’origine.');}else if(current?.id===event.id){if(event.doc.deleted){current=null;$('editor').hidden=true;$('welcome').hidden=false;document.body.classList.remove('editing');}else await open(event.id,true);}},async id=>{if(current?.id!==id)return ()=>{};$('editor').inert=true;finish();await saveChain;if(saveFailed){$('editor').inert=false;throw Error('Sauvegarde locale en échec');}return ()=>{$('editor').inert=false;};});await list();}
$('account').onclick=()=>{const c=cloud.config();$('server').value=c.base||'';$('email').value=c.email||'';$('password').value='';$('account-message').textContent=c.email?'Connecté : '+c.email:'';$('account-dialog').showModal();};
$('close-account').onclick=()=>$('account-dialog').close();$('login-form').onsubmit=async e=>{e.preventDefault();$('connect').disabled=true;try{await cloud.login($('server').value,$('email').value,$('password').value);$('password').value='';$('account-message').textContent='Compte connecté';await runSync();}catch(e){$('account-message').textContent=e.message;}finally{$('connect').disabled=false;}};
$('sync-now').onclick=()=>runSync().catch(fail);$('logout').onclick=async()=>{try{await cloud.logout();$('account-message').textContent='Déconnecté. Notes conservées sur cet appareil.';status('Sur cet appareil · déconnecté');}catch(e){fail(e);}};
window.addEventListener('online',()=>runSync().catch(fail));document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='visible')runSync().catch(fail);else finish();});
window.addEventListener('beforeunload',e=>{if(saveFailed||$('save-status').textContent==='Enregistrement…'){e.preventDefault();e.returnValue='';}});
setInterval(()=>{if(document.visibilityState==='visible')runSync().catch(fail);},10000);
theme();matchMedia('(prefers-color-scheme:dark)').addEventListener('change',theme);
try{await initLocal(cloud,runSync);await list();await runSync();navigator.storage?.persist?.();if('serviceWorker'in navigator&&location.hostname!=='notes.echo-all.local')await navigator.serviceWorker.register('./sw.js');}catch(e){fail(e);}

window.echoNotesFlush=async()=>{finish();await saveChain;return !saveFailed;};
