import {Studio} from './studio.mjs';
const $=id=>document.getElementById(id);
const time=t=>Number.isFinite(t)?Math.floor(t/60)+':'+String(Math.floor(t%60)).padStart(2,'0'):'0:00';
const size=n=>n>1073741824?(n/1073741824).toFixed(1)+' Go':Math.round(n/1048576)+' Mo';
let state;try{state=JSON.parse(localStorage.getItem('echo-desktop-v1')||'{}');}catch{state={};}
state={favorites:[],playlists:[],progress:{},theme:'dark',accent:'#e879f9',reduced:false,...state};
let library=[],page='home',favoriteOnly=false,selectedPlaylist=null,current=null,queue=[],shuffle=false,repeat=0,toastTimer;
export function notify(message){$('toast').textContent=message;$('toast').hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('toast').hidden=true,5000);}
const save=()=>{try{localStorage.setItem('echo-desktop-v1',JSON.stringify(state));}catch{notify('Stockage local plein : tes derniers réglages n’ont pas été enregistrés.');}};
const studio=new Studio(notify);
const audio=$('audio');audio.volume=.8;
const safe=action=>Promise.resolve().then(action).catch(e=>notify(e.message||'Opération impossible.'));
function theme(){
 const light=state.theme==='light'||state.theme==='system'&&matchMedia('(prefers-color-scheme:light)').matches;
 const vars=light?{bg:'#f7f4f9',sidebar:'#fff',panel:'#fff',panel2:'#f0ebf3',line:'#e5deea',fg:'#25202c',muted:'#74697d','on-accent':'#201025'}:{bg:'#131216',sidebar:'#101014',panel:'#201e25',panel2:'#292630',line:'#302d38',fg:'#f2f2f5',muted:'#a29aa9','on-accent':'#19111c'};
 for(const [key,value]of Object.entries(vars))document.documentElement.style.setProperty('--'+key,value);
 document.documentElement.style.setProperty('--accent',/^#[a-f0-9]{6}$/i.test(state.accent)?state.accent:'#e879f9');
 document.documentElement.style.colorScheme=light?'light':'dark';document.documentElement.dataset.reduced=String(state.reduced);
 $('theme').value=state.theme;$('accent').value=state.accent;$('reduce-motion').checked=state.reduced;
}
theme();matchMedia('(prefers-color-scheme:light)').addEventListener('change',theme);
function openModal(title,content){$('modal-title').textContent=title;$('modal-content').replaceChildren(content);$('modal').showModal();}
$('close-modal').onclick=()=>$('modal').close();$('modal').addEventListener('close',()=>{$('modal-content').querySelectorAll('video,audio').forEach(el=>{el.pause();el.removeAttribute('src');el.load();});});
function ask(title,label,done){
 const form=document.createElement('form'),input=document.createElement('input'),button=document.createElement('button');
 input.type='text';input.maxLength=100;input.placeholder=label;input.setAttribute('aria-label',label);input.required=true;
 button.className='button primary';button.textContent='Enregistrer';form.append(input,button);
 form.onsubmit=e=>{e.preventDefault();const value=input.value.trim();if(value){done(value);$('modal').close();}};
 openModal(title,form);input.focus();
}
function openPage(next){
 page=next;document.body.dataset.page=next;if(next==='studio')audio.pause();$('breadcrumb').textContent=({home:'Accueil',music:'Musique',video:'Vidéos',photo:'Photos',studio:'Studio DJ',playlists:'Mes playlists',settings:'Préférences'})[next]||'Accueil';
 document.querySelectorAll('.page').forEach(el=>el.hidden=true);
 $(['music','video','photo'].includes(next)?'library-page':next+'-page').hidden=false;
 document.querySelectorAll('.nav-item[data-page]').forEach(el=>el.classList.toggle('active',el.dataset.page===next));
 if(['music','video','photo'].includes(next))renderLibrary();if(next==='studio')renderStudio();if(next==='playlists')renderPlaylists();
 window.scrollTo({top:0,behavior:state.reduced?'instant':'smooth'});
}
document.querySelectorAll('[data-page]').forEach(el=>el.onclick=()=>openPage(el.dataset.page));
document.querySelector('.brand').onclick=e=>{e.preventDefault();openPage('home');};
async function addFolder(){if(!window.echoDesktop?.addFolder)return notify('Ouvre la version installée pour choisir tes dossiers.');const result=await window.echoDesktop.addFolder();if(result){notify('Dossier ajouté. La bibliothèque est actualisée.');await load();}}
for(const id of ['add-folder','studio-import','settings-folder'])$(id).onclick=()=>safe(addFolder);
async function openNotes(){if(!window.echoDesktop?.openNotes)return notify('Notes est disponible dans la version installée.');await window.echoDesktop.openNotes();}
$('open-notes').onclick=$('home-notes').onclick=()=>safe(openNotes);
$('updates').onclick=()=>safe(async()=>{if(window.echoDesktop?.checkUpdates)await window.echoDesktop.checkUpdates();});
$('theme').onchange=e=>{state.theme=e.target.value;save();theme();};
$('accent').oninput=e=>{state.accent=e.target.value;save();theme();};
$('reduce-motion').onchange=e=>{state.reduced=e.target.checked;save();theme();};
$('rescan').onclick=()=>safe(async()=>{$('rescan').disabled=true;try{const r=await fetch('/api/rescan',{method:'POST'});if(!r.ok)throw Error('Analyse impossible.');await load();notify('Bibliothèque actualisée.');}finally{$('rescan').disabled=false;}});
function empty(text){const div=document.createElement('div');div.className='empty';div.textContent=text;return div;}
async function load(){
 try{
  let items=[],total=0;
  do{const r=await fetch('/api/library?offset='+items.length);if(!r.ok)throw Error('La bibliothèque ne répond pas.');const data=await r.json();items.push(...data.items);total=data.count;if(!data.items.length)break;}while(items.length<total&&items.length<10000);
  library=items;$('library-summary').textContent=items.length+' médias'+(items.length<total?' sur '+total+' · premiers 10 000 affichés':'');
  for(const [id,kind,label]of [['music','MUSIC','titres'],['video','VIDEO','vidéos'],['photo','PHOTO','photos']])$(id+'-count').textContent=items.filter(i=>i.kind===kind).length+' '+label;
  $('recent-list').replaceChildren(...(items.length?items.slice(0,5).map(i=>row(i)): [empty('Ton univers commence ici. Ajoute un dossier pour retrouver tes morceaux, vidéos et photos.')]));
  renderLibrary();renderStudio();renderPlaylists();
  const health=await fetch('/api/health').then(r=>r.json());$('folders').replaceChildren(...health.folders.map(folder=>{const p=document.createElement('p');p.className='folder';p.textContent=folder;return p;}));
 }catch(e){notify(e.message);$('recent-list').replaceChildren(empty('Bibliothèque indisponible. Réessaie avec Actualiser.'));}
}
function filtered(kind){
 const words=$('q').value.normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase().split(/\s+/).filter(Boolean);
 return library.filter(i=>(!kind||i.kind===kind)&&(!favoriteOnly||state.favorites.includes(i.id))&&words.every(w=>(i.title+' '+i.artist+' '+i.folder).normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase().includes(w))).sort((a,b)=>$('sort').value==='title'?a.title.localeCompare(b.title):$('sort').value==='artist'?a.artist.localeCompare(b.artist):b.addedAt-a.addedAt);
}
function renderLibrary(){
 const kind=({music:'MUSIC',video:'VIDEO',photo:'PHOTO'})[page];const items=filtered(kind);
 $('library-title').textContent=({music:'Musique',video:'Vidéos',photo:'Photos'})[page]||'Bibliothèque';
 $('count').textContent=items.length+' médias'+(favoriteOnly?' favoris':'');
 $('list').replaceChildren(...(items.length?items.map(i=>row(i)): [empty('Aucun média ici. Ajoute un dossier ou change les filtres.')]));
}
function toggleFavorite(id){state.favorites=state.favorites.includes(id)?state.favorites.filter(x=>x!==id):[...state.favorites,id];save();renderLibrary();renderStudio();updatePlayer();$('recent-list').replaceChildren(...library.slice(0,5).map(i=>row(i)));}
function row(item,mode='normal'){
 const div=document.createElement('div');div.className='media-row'+(current?.id===item.id?' playing':'');
 const cover=document.createElement('div');cover.className='media-cover';
 if(item.kind==='PHOTO'){const image=document.createElement('img');image.src='/api/file/'+encodeURIComponent(item.id);image.alt='';image.loading='lazy';cover.append(image);}else cover.textContent=item.kind==='VIDEO'?'▷':'♫';
 const info=document.createElement('button');info.className='media-info';const title=document.createElement('b');title.textContent=item.title;const sub=document.createElement('small');sub.textContent=item.artist||item.folder;info.append(title,sub);
 info.onclick=()=>safe(()=>play(item));const kind=document.createElement('span');kind.className='media-kind';kind.textContent=({MUSIC:'Musique',VIDEO:'Vidéo',PHOTO:'Photo'})[item.kind];
 const bytes=document.createElement('span');bytes.className='media-size';bytes.textContent=size(item.bytes);
 const actions=document.createElement('div');actions.className='media-actions';
 if(mode==='studio'){
  for(let i=0;i<2;i++){const b=document.createElement('button');b.className='deck-load';b.textContent=i?'B ↗':'A ↗';b.setAttribute('aria-label','Charger '+item.title+' sur '+(i?'B':'A'));b.onclick=()=>safe(async()=>{audio.pause();await studio.load(i,item);});actions.append(b);}
 }else{
  const fav=document.createElement('button');fav.textContent=state.favorites.includes(item.id)?'♥':'♡';fav.setAttribute('aria-label','Favori : '+item.title);fav.setAttribute('aria-pressed',String(state.favorites.includes(item.id)));fav.onclick=()=>toggleFavorite(item.id);
  const more=document.createElement('button');more.textContent='＋';more.setAttribute('aria-label','Ajouter '+item.title+' à une playlist');more.onclick=()=>choosePlaylist(item);
  actions.append(fav,more);
  if(mode==='playlist'){const remove=document.createElement('button');remove.textContent='×';remove.setAttribute('aria-label','Retirer '+item.title+' de la playlist');remove.onclick=()=>{const list=state.playlists.find(p=>p.id===selectedPlaylist);list.items=list.items.filter(id=>id!==item.id);save();renderPlaylists();};actions.append(remove);}
 }
 div.append(cover,info,kind,bytes,actions);return div;
}
function renderStudio(){const items=filtered('MUSIC');$('studio-library').replaceChildren(...(items.length?items.map(i=>row(i,'studio')):[empty('Ajoute tes morceaux puis charge-les sur A et B pour préparer ton premier mix.') ]));}
let searchTimer;$('q').oninput=()=>{clearTimeout(searchTimer);searchTimer=setTimeout(()=>{if(page==='home')openPage('music');renderLibrary();renderStudio();},150);};
$('sort').onchange=renderLibrary;$('favorites-only').onclick=()=>{favoriteOnly=!favoriteOnly;$('favorites-only').setAttribute('aria-pressed',String(favoriteOnly));renderLibrary();};
function newPlaylist(){ask('Une nouvelle playlist','Nom de la playlist',name=>{const list={id:crypto.randomUUID(),name,items:[]};state.playlists.push(list);selectedPlaylist=list.id;save();renderPlaylists();});}
$('new-playlist').onclick=newPlaylist;
function renderPlaylists(){
 $('playlists').replaceChildren(...state.playlists.map(list=>{const button=document.createElement('button');button.className='category';const span=document.createElement('span'),name=document.createElement('b'),count=document.createElement('small');name.textContent=list.name;count.textContent=list.items.length+' médias';span.append(name,count);button.append(span);button.onclick=()=>{selectedPlaylist=list.id;renderPlaylists();};return button;}));
 if(!state.playlists.length)$('playlists').append(empty('Crée ta première playlist.'));
 const list=state.playlists.find(p=>p.id===selectedPlaylist);$('playlist-title').textContent=list?.name||'Choisis une playlist';
 const items=list?.items.map(id=>library.find(i=>i.id===id)).filter(Boolean)||[];
 $('playlist-items').replaceChildren(...items.map(i=>row(i,'playlist')));
 if(list&&!items.length)$('playlist-items').append(empty('Ajoute des morceaux depuis la bibliothèque avec le bouton ＋.'));
}
function choosePlaylist(item){
 if(!state.playlists.length){ask('Créer une playlist','Nom',name=>{state.playlists.push({id:crypto.randomUUID(),name,items:[item.id]});save();renderPlaylists();notify('Playlist créée.');});return;}
 const box=document.createElement('div');
 state.playlists.forEach(list=>{const b=document.createElement('button');b.className='category';b.textContent=list.name;b.onclick=()=>{if(!list.items.includes(item.id))list.items.push(item.id);save();renderPlaylists();$('modal').close();notify('Ajouté à '+list.name);};box.append(b);});
 openModal('Ajouter à une playlist',box);
}
async function play(item){
 if(item.kind==='PHOTO'){const image=document.createElement('img');image.src='/api/file/'+encodeURIComponent(item.id);image.alt=item.title;openModal(item.title,image);return;}
 studio.decks.forEach(d=>studio.pause(d));
 if(item.kind==='VIDEO'){
  audio.pause();const video=document.createElement('video');video.controls=true;video.src='/api/file/'+encodeURIComponent(item.id);video.onloadedmetadata=()=>{const saved=state.progress[item.id]||0;if(saved<video.duration-5)video.currentTime=saved;};
  let last=0;video.ontimeupdate=()=>{if(Math.abs(video.currentTime-last)>5){state.progress[item.id]=video.currentTime;last=video.currentTime;save();}};
  openModal(item.title,video);await video.play();return;
 }
 current=item;queue=page==='playlists'?(state.playlists.find(p=>p.id===selectedPlaylist)?.items||[]).map(id=>library.find(i=>i.id===id)).filter(i=>i?.kind==='MUSIC'):filtered('MUSIC');
 if(!queue.some(i=>i.id===item.id))queue.unshift(item);
 audio.src='/api/file/'+encodeURIComponent(item.id);state.last=item.id;save();await audio.play();updatePlayer();
}
function updatePlayer(){
 $('now-title').textContent=current?.title||'À toi de donner le ton.';$('now-artist').textContent=current?(current.artist||current.folder):'Choisis un morceau dans ta bibliothèque';
 $('play-pause').textContent=audio.paused?'▶':'Ⅱ';$('play-pause').setAttribute('aria-label',audio.paused?'Lecture':'Pause');$('now-favorite').textContent=current&&state.favorites.includes(current.id)?'♥':'♡';
}
function next(delta=1){
 if(!queue.length)return;
 const index=queue.findIndex(i=>i.id===current?.id);
 let target=shuffle?Math.floor(Math.random()*queue.length):(index+delta+queue.length)%queue.length;
 if(shuffle&&queue.length>1&&target===index)target=(target+1)%queue.length;
 const savedQueue=queue;safe(async()=>{await play(savedQueue[target]);queue=savedQueue;});
}
$('play-pause').onclick=()=>safe(async()=>{if(!current){const item=library.find(i=>i.id===state.last&&i.kind==='MUSIC')||library.find(i=>i.kind==='MUSIC');if(item)await play(item);else notify('Ajoute un dossier musical pour commencer.');}else if(audio.paused){studio.decks.forEach(d=>studio.pause(d));await audio.play();}else audio.pause();});
$('previous').onclick=()=>{if(audio.currentTime>3)audio.currentTime=0;else next(-1);};$('next').onclick=()=>next();
$('shuffle').onclick=()=>{shuffle=!shuffle;$('shuffle').setAttribute('aria-pressed',String(shuffle));};
$('repeat').onclick=()=>{repeat=(repeat+1)%3;$('repeat').setAttribute('aria-pressed',String(repeat>0));$('repeat').textContent=repeat===2?'↻¹':'↻';notify(['Répétition désactivée','Répéter la file','Répéter ce morceau'][repeat]);};
audio.onended=()=>{if(repeat===2){audio.currentTime=0;safe(()=>audio.play());}else if(repeat===1||queue.findIndex(i=>i.id===current?.id)<queue.length-1)next();else updatePlayer();};
audio.onplay=audio.onpause=updatePlayer;audio.onerror=()=>notify('Ce format ne peut pas être lu ici, ou le fichier a été déplacé.');
audio.ontimeupdate=()=>{$('elapsed').textContent=time(audio.currentTime);$('duration').textContent=time(audio.duration);$('seek').value=Number.isFinite(audio.duration)?audio.currentTime/audio.duration*100:0;};
$('seek').oninput=e=>{if(Number.isFinite(audio.duration))audio.currentTime=Number(e.target.value)/100*audio.duration;};
$('volume').oninput=e=>audio.volume=Number(e.target.value);$('now-favorite').onclick=()=>{if(current)toggleFavorite(current.id);};
$('show-queue').onclick=()=>{const box=document.createElement('div');box.append(...(queue.length?queue.map(i=>row(i)):[empty('La file d’attente est vide.')]));openModal('File d’attente',box);};
document.addEventListener('keydown',e=>{if(e.code==='Space'&&!['INPUT','TEXTAREA','SELECT','BUTTON'].includes(document.activeElement?.tagName)&&!$('modal').open&&page!=='studio'){e.preventDefault();$('play-pause').click();}});
window.addEventListener('beforeunload',e=>{if(studio.recording){e.preventDefault();e.returnValue='Un mix est en cours d’enregistrement.';}});
if('mediaSession'in navigator){navigator.mediaSession.setActionHandler('play',()=>$('play-pause').click());navigator.mediaSession.setActionHandler('pause',()=>audio.pause());navigator.mediaSession.setActionHandler('nexttrack',()=>next());navigator.mediaSession.setActionHandler('previoustrack',()=>next(-1));}
safe(async()=>{const config=await fetch('/firebase-config.json').then(r=>r.json());$('firebase-state').textContent=config.projectId?'Projet connecté : '+config.projectId:'La connexion Google attend l’activation du projet Firebase.';});
window.echoDesktop?.getVersion?.().then(version=>$('app-version').textContent='Echo-All '+version);
load();

window.echoDesktop?.getSettings?.().then(settings=>$('notes-wifi').checked=settings.notesWifi);
$('notes-wifi').onchange=e=>safe(async()=>{await window.echoDesktop?.setNotesWifi?.(e.target.checked);notify('Option enregistrée. Relance Echo-All pour l’appliquer à Notes.');});

document.querySelectorAll('a[href="/social.html"]').forEach(link=>link.onclick=e=>{if(window.echoDesktop?.openSocial){e.preventDefault();safe(()=>window.echoDesktop.openSocial());}});
