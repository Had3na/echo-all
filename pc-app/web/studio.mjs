export const clamp=(n,min,max)=>Math.min(max,Math.max(min,n));
export const crossGains=x=>[Math.cos(clamp(x,0,1)*Math.PI/2),Math.sin(clamp(x,0,1)*Math.PI/2)];
export const tempoRate=(bpm,target)=>clamp(target/bpm,.5,2);
const fmt=t=>Math.floor(Math.max(0,t)/60)+':'+String(Math.floor(Math.max(0,t)%60)).padStart(2,'0');
export class Studio {
 constructor(notify){this.notify=notify;this.decks=[];this.recording=false;this.initUI();this.tick=this.tick.bind(this);requestAnimationFrame(this.tick);}
 async activate(){
  if(!this.context){
   this.context=new AudioContext();
   this.master=this.context.createGain();this.master.gain.value=Number(document.getElementById('master-volume').value);
   this.limiter=this.context.createDynamicsCompressor();this.limiter.threshold.value=-2;this.limiter.knee.value=0;this.limiter.ratio.value=20;this.limiter.attack.value=.003;this.limiter.release.value=.15;
   this.analyser=this.context.createAnalyser();this.analyser.fftSize=256;
   this.capture=this.context.createMediaStreamDestination();
   this.monitorBus=this.context.createMediaStreamDestination();this.monitorAudio=new Audio();this.monitorAudio.srcObject=this.monitorBus.stream;
   this.master.connect(this.limiter);this.limiter.connect(this.analyser);this.analyser.connect(this.context.destination);this.limiter.connect(this.capture);
   this.meterData=new Float32Array(this.analyser.fftSize);
   for(const deck of this.decks){
    deck.volume=this.context.createGain();deck.fader=this.context.createGain();deck.monitor=this.context.createGain();deck.monitor.gain.value=0;
    deck.filters=[['lowshelf',250],['peaking',1000],['highshelf',4000]].map(([type,freq])=>{const f=this.context.createBiquadFilter();f.type=type;f.frequency.value=freq;f.Q.value=.7;return f;});
    deck.filters[0].connect(deck.filters[1]);deck.filters[1].connect(deck.filters[2]);deck.filters[2].connect(deck.volume);deck.filters[2].connect(deck.monitor);deck.monitor.connect(this.monitorBus);deck.volume.connect(deck.fader);deck.fader.connect(this.master);
    deck.filters.forEach((f,i)=>f.gain.value=Number(deck.root.querySelector('[data-eq="'+i+'"]').value));
    deck.volume.gain.value=Number(deck.root.querySelector('[data-volume]').value);
   }
   this.cross();
  }
  if(this.context.state!=='running')await this.context.resume();
 }
 initUI(){
  for(const [index,letter] of ['a','b'].entries()){
   const root=document.getElementById('deck-'+letter);root.classList.add(letter);
   root.innerHTML='<div class="deck-head"><span class="deck-letter">'+letter.toUpperCase()+'</span><span class="deck-tag">PLATINE '+letter.toUpperCase()+'</span><button data-eject aria-label="Décharger la platine '+letter.toUpperCase()+'">⏏</button></div><h2 class="deck-name">Ton prochain morceau</h2><p class="deck-artist">Choisis un titre dans la bibliothèque</p><canvas class="deck-wave" width="900" height="180" tabindex="0" role="slider" aria-label="Position de la platine '+letter.toUpperCase()+'"></canvas><div class="deck-time"><span data-position>0:00</span><span data-total>—</span></div><div class="deck-controls"><button data-cue>CUE</button><button data-play class="deck-play">▶ LECTURE</button><button data-sync>SYNC TEMPO</button></div><button data-monitor class="monitor-button" aria-pressed="false">◖ Préécoute casque</button><div class="deck-tempo"><label>BPM <input data-bpm type="number" min="40" max="240" value="120" aria-label="BPM platine '+letter.toUpperCase()+'"></label><button data-tap>TAP BPM</button><span class="subtle" data-rate-label>0.0 %</span></div><input data-rate type="range" min=".5" max="2" step=".001" value="1" aria-label="Vitesse de la platine '+letter.toUpperCase()+'"><div class="deck-eq"><label><span>BASSES</span><input data-eq="0" type="range" min="-24" max="12" step=".5" value="0"><output>0 dB</output></label><label><span>MÉDIUMS</span><input data-eq="1" type="range" min="-24" max="12" step=".5" value="0"><output>0 dB</output></label><label><span>AIGUS</span><input data-eq="2" type="range" min="-24" max="12" step=".5" value="0"><output>0 dB</output></label></div><label>GAIN<input data-volume type="range" min="0" max="1.5" step=".01" value="1"></label><p class="cue-label">HOT CUES · MAJ + CLIC POUR MÉMORISER</p><div class="hotcues"><button data-hot="0">1</button><button data-hot="1">2</button><button data-hot="2">3</button><button data-hot="3">4</button></div><p class="cue-label">BOUCLE · NOMBRE DE TEMPS</p><div class="loop-buttons"><button data-loop="1">1</button><button data-loop="2">2</button><button data-loop="4">4</button><button data-loop="8">8</button><button data-loop="0">OFF</button></div>';
   const d={index,root,buffer:null,item:null,source:null,offset:0,started:0,rate:1,bpm:120,cues:[null,null,null,null],loop:null,playing:false,taps:[],generation:0};
   this.decks.push(d);
   root.querySelector('[data-play]').onclick=()=>this.safe(async()=>{await this.activate();if(!d.buffer)throw Error('Charge un morceau sur cette platine.');d.playing?this.pause(d):this.start(d);});
   root.querySelector('[data-eject]').onclick=()=>{d.generation++;this.pause(d);d.buffer=null;d.item=null;d.offset=0;d.loop=null;d.peaks=null;d.root.querySelector('.deck-name').textContent='Ton prochain morceau';d.root.querySelector('.deck-artist').textContent='Choisis un titre dans la bibliothèque';d.root.querySelector('[data-total]').textContent='—';this.render(d);};
   root.querySelector('[data-monitor]').onclick=()=>this.safe(async()=>{await this.activate();if(!d.buffer)throw Error('Charge un morceau avant la préécoute.');const enabled=d.monitor.gain.value<.5;d.monitor.gain.setTargetAtTime(enabled?1:0,this.context.currentTime,.02);await this.monitorAudio.play();root.querySelector('[data-monitor]').setAttribute('aria-pressed',String(enabled));});
   root.querySelector('[data-cue]').onclick=()=>{if(!d.buffer)return;this.pause(d);d.offset=d.cues[0]??0;this.render(d);};
   root.querySelector('[data-sync]').onclick=()=>{const other=this.decks[1-index];if(!other.buffer||!d.buffer)return this.notify('Charge les deux platines pour caler le tempo.');this.setRate(d,tempoRate(d.bpm,other.bpm*other.rate));this.notify('Tempo calé · ajuste la position à l’oreille.');};
   root.querySelector('[data-rate]').oninput=e=>this.setRate(d,Number(e.target.value));
   root.querySelector('[data-bpm]').onchange=e=>{d.bpm=clamp(Number(e.target.value)||120,40,240);e.target.value=d.bpm;this.persist(d);};
   root.querySelector('[data-tap]').onclick=()=>{const now=performance.now();d.taps=d.taps.filter(t=>now-t<4000);d.taps.push(now);if(d.taps.length>1){d.bpm=clamp(Math.round(60000*(d.taps.length-1)/(now-d.taps[0])),40,240);root.querySelector('[data-bpm]').value=d.bpm;this.persist(d);}};
   root.querySelector('[data-volume]').oninput=e=>{if(d.volume)d.volume.gain.setTargetAtTime(Number(e.target.value),this.context.currentTime,.02);};
   root.querySelectorAll('[data-eq]').forEach(el=>el.oninput=()=>{el.nextElementSibling.textContent=Number(el.value)+' dB';if(d.filters)d.filters[Number(el.dataset.eq)].gain.setTargetAtTime(Number(el.value),this.context.currentTime,.02);});
   root.querySelectorAll('[data-hot]').forEach(el=>el.onclick=e=>{if(!d.buffer)return;const i=Number(el.dataset.hot);if(e.shiftKey||d.cues[i]===null){d.cues[i]=this.position(d);this.persist(d);}else this.seek(d,d.cues[i]);this.render(d);});
   root.querySelectorAll('[data-loop]').forEach(el=>el.onclick=()=>{if(!d.buffer)return;const beats=Number(el.dataset.loop);if(!beats){d.loop=null;if(d.source)d.source.loop=false;}else{const start=this.position(d),end=Math.min(d.buffer.duration,start+beats*60/d.bpm);if(end-start<.05)return;d.loop={start,end,beats};if(d.source){d.source.loop=true;d.source.loopStart=start;d.source.loopEnd=end;}}this.render(d);});
   const wave=root.querySelector('canvas');
   wave.onclick=e=>{if(d.buffer){d.loop=null;this.seek(d,clamp((e.clientX-wave.getBoundingClientRect().left)/wave.clientWidth,0,1)*d.buffer.duration);}};
   wave.onkeydown=e=>{if(d.buffer&&['ArrowLeft','ArrowRight'].includes(e.key)){e.preventDefault();this.seek(d,this.position(d)+(e.key==='ArrowRight'?5:-5));}};
  }
  document.getElementById('crossfader').oninput=()=>this.cross();
  document.getElementById('cross-center').onclick=()=>{document.getElementById('crossfader').value=.5;this.cross();};
  document.getElementById('master-volume').oninput=e=>{if(this.master)this.master.gain.setTargetAtTime(Number(e.target.value),this.context.currentTime,.02);};
  document.getElementById('refresh-outputs').onclick=()=>this.safe(async()=>{await this.activate();const outputs=await navigator.mediaDevices.enumerateDevices();const select=document.getElementById('monitor-output');select.replaceChildren();for(const output of outputs.filter(d=>d.kind==='audiooutput')){const option=document.createElement('option');option.value=output.deviceId;option.textContent=output.label||'Sortie système';select.append(option);}if(!select.options.length){const option=document.createElement('option');option.value='default';option.textContent='Sortie système';select.append(option);}this.notify('Sorties disponibles actualisées. Aucun microphone n’est utilisé.');});
  document.getElementById('monitor-output').onchange=e=>this.safe(async()=>{await this.activate();if(!this.monitorAudio.setSinkId)throw Error('Le choix de sortie n’est pas disponible ici.');await this.monitorAudio.setSinkId(e.target.value);});
  document.getElementById('record-mix').onclick=()=>this.safe(()=>this.record());
 }
 safe(action){Promise.resolve().then(action).catch(e=>this.notify(e.message||'Opération audio impossible.'));}
 async load(index,item){
  const d=this.decks[index];if(!d)return;
  if(item.bytes>150*1024*1024)throw Error('Le studio accepte des fichiers de 150 Mo maximum.');
  const gen=++d.generation;this.pause(d);d.buffer=null;d.offset=0;d.loop=null;d.peaks=null;
  d.root.querySelector('.deck-name').textContent='Préparation du morceau…';
  try{
   await this.activate();
   const response=await fetch('/api/file/'+encodeURIComponent(item.id));if(!response.ok)throw Error('Fichier introuvable.');
   const buffer=await this.context.decodeAudioData(await response.arrayBuffer());if(gen!==d.generation)return;
   d.buffer=buffer;d.item=item;d.offset=0;d.rate=1;d.cues=[null,null,null,null];d.bpm=120;
   try{const saved=JSON.parse(localStorage.getItem('echo-dj:'+item.id)||'{}');d.bpm=clamp(Number(saved.bpm)||120,40,240);d.cues=Array.from({length:4},(_,i)=>Number.isFinite(saved.cues?.[i])?clamp(saved.cues[i],0,buffer.duration):null);}catch{}
   d.root.querySelector('.deck-name').textContent=item.title;
   d.root.querySelector('.deck-artist').textContent=item.artist||item.folder;
   d.root.querySelector('[data-total]').textContent=fmt(buffer.duration);
   d.root.querySelector('[data-bpm]').value=d.bpm;d.root.querySelector('[data-rate]').value=1;d.root.querySelector('[data-rate-label]').textContent='0.0 %';
   const data=buffer.getChannelData(0),step=Math.max(1,Math.floor(data.length/450));d.peaks=[];
   for(let i=0;i<450;i++){let peak=0;for(let k=i*step;k<Math.min((i+1)*step,data.length);k+=Math.max(1,Math.floor(step/150)))peak=Math.max(peak,Math.abs(data[k]));d.peaks.push(peak);}
   this.render(d);this.notify('Platine '+(index?'B':'A')+' · '+item.title);
  }catch(e){if(gen===d.generation){d.root.querySelector('.deck-name').textContent='Chargement impossible';d.root.querySelector('.deck-artist').textContent='Essaie un fichier audio compatible.';}throw e;}
 }
 position(d){
  if(!d.playing||!d.buffer)return d.offset;
  let p=d.offset+(this.context.currentTime-d.started)*d.rate;
  if(d.loop&&p>=d.loop.end)p=d.loop.start+(p-d.loop.start)%(d.loop.end-d.loop.start);
  return clamp(p,0,d.buffer.duration);
 }
 stopNode(d){if(d.source){d.source.onended=null;try{d.source.stop();}catch{}d.source.disconnect();d.source=null;}}
 start(d){
  if(!d.buffer)return;
  this.stopNode(d);if(d.offset>=d.buffer.duration)d.offset=0;
  const source=this.context.createBufferSource();source.buffer=d.buffer;source.playbackRate.value=d.rate;
  if(d.loop){source.loop=true;source.loopStart=d.loop.start;source.loopEnd=d.loop.end;}
  source.connect(d.filters[0]);d.source=source;d.started=this.context.currentTime;d.playing=true;
  source.onended=()=>{if(d.source===source){d.playing=false;d.offset=0;source.disconnect();d.source=null;this.render(d);}};
  source.start(0,d.offset);this.render(d);
 }
 pause(d){d.offset=this.position(d);d.playing=false;this.stopNode(d);this.render(d);}
 seek(d,p){const playing=d.playing;this.pause(d);d.offset=clamp(p,0,Math.max(0,d.buffer.duration-.001));if(playing)this.start(d);this.render(d);}
 setRate(d,rate){const p=this.position(d);d.rate=clamp(rate,.5,2);d.offset=p;if(d.playing){d.started=this.context.currentTime;d.source.playbackRate.setValueAtTime(d.rate,this.context.currentTime);}d.root.querySelector('[data-rate]').value=d.rate;d.root.querySelector('[data-rate-label]').textContent=((d.rate-1)*100).toFixed(1)+' %';}
 cross(){if(!this.context)return;crossGains(Number(document.getElementById('crossfader').value)).forEach((gain,i)=>this.decks[i].fader.gain.setTargetAtTime(gain,this.context.currentTime,.015));}
 persist(d){if(d.item)try{localStorage.setItem('echo-dj:'+d.item.id,JSON.stringify({bpm:d.bpm,cues:d.cues}));}catch{this.notify('Impossible de mémoriser les CUE : stockage plein.');}}
 render(d){
  d.root.classList.toggle('is-playing',d.playing);d.root.querySelector('[data-play]').textContent=d.playing?'Ⅱ PAUSE':'▶ LECTURE';
  d.root.querySelectorAll('[data-hot]').forEach(el=>el.classList.toggle('saved',d.cues[Number(el.dataset.hot)]!==null));
  d.root.querySelectorAll('[data-loop]').forEach(el=>el.classList.toggle('active',Number(el.dataset.loop)===(d.loop?.beats||0)));
 }
 async record(){
  if(this.recorder?.state==='recording'){this.recorder.stop();return;}
  await this.activate();
  const type=['audio/webm;codecs=opus','audio/webm'].find(t=>MediaRecorder.isTypeSupported(t));
  if(!type)throw Error('L’enregistrement audio n’est pas disponible.');
  const chunks=[];let bytes=0;
  const recorder=new MediaRecorder(this.capture.stream,{mimeType:type,audioBitsPerSecond:192000});this.recorder=recorder;
  recorder.ondataavailable=e=>{if(e.data.size){chunks.push(e.data);bytes+=e.data.size;if(bytes>200*1024*1024&&recorder.state==='recording'){recorder.stop();this.notify('Mix arrêté à 200 Mo pour préserver la mémoire.');}}};
  const button=document.getElementById('record-mix');
  recorder.onstop=()=>{clearTimeout(this.recordTimer);this.recording=false;button.classList.remove('recording');button.textContent='● Enregistrer le mix';const url=URL.createObjectURL(new Blob(chunks,{type}));const a=document.createElement('a');a.href=url;a.download='Echo-Mix-'+new Date().toISOString().replaceAll(':','-').slice(0,19)+'.webm';a.click();setTimeout(()=>URL.revokeObjectURL(url),60000);this.notify('Mix exporté en WebM/Opus.');};
  recorder.onerror=()=>{this.notify('L’enregistrement a été interrompu.');if(recorder.state==='recording')recorder.stop();};
  recorder.start(1000);this.recording=true;button.classList.add('recording');button.textContent='■ Arrêter et exporter';
  this.recordTimer=setTimeout(()=>{if(recorder.state==='recording')recorder.stop();},90*60*1000);
  this.notify('Enregistrement du mix · seul le son du studio est capturé.');
 }
 tick(){
  if(!document.getElementById('studio-page').hidden){
   for(const d of this.decks){
    const canvas=d.root.querySelector('canvas'),ctx=canvas.getContext('2d'),w=canvas.width,h=canvas.height,p=this.position(d),ratio=d.buffer?p/d.buffer.duration:0;
    ctx.clearRect(0,0,w,h);ctx.fillStyle='#514557';ctx.fillRect(0,h/2,w,1);
    if(d.peaks){const color=d.index?'#9cbbff':'#e879f9';for(let i=0;i<d.peaks.length;i++){const x=i*w/d.peaks.length,bar=Math.max(2,d.peaks[i]*(h-16));ctx.fillStyle=x<w*ratio?color:'#62526d';ctx.fillRect(x,(h-bar)/2,1.4,bar);}if(d.loop){ctx.fillStyle='#e879f925';ctx.fillRect(d.loop.start/d.buffer.duration*w,0,(d.loop.end-d.loop.start)/d.buffer.duration*w,h);}ctx.fillStyle='#fff';ctx.fillRect(w*ratio,0,2,h);d.cues.forEach((cue,i)=>{if(cue!==null){ctx.fillStyle=color;const x=cue/d.buffer.duration*w;ctx.fillRect(x,0,2,14);ctx.font='13px sans-serif';ctx.fillText(String(i+1),x+4,12);}});}
    d.root.querySelector('[data-position]').textContent=fmt(p);canvas.setAttribute('aria-valuenow',Math.round(p));canvas.setAttribute('aria-valuemax',Math.round(d.buffer?.duration||0));
   }
   const canvas=document.getElementById('master-meter'),ctx=canvas.getContext('2d');ctx.clearRect(0,0,80,170);let peak=0;
   if(this.analyser){this.analyser.getFloatTimeDomainData(this.meterData);for(const value of this.meterData)peak=Math.max(peak,Math.abs(value));}
   for(let i=0;i<23;i++){const on=i/23<peak;ctx.fillStyle=on?(i>19?'#ff6b8a':i>15?'#eac684':'#b4e6bb'):'#25252b';ctx.fillRect(23,163-i*7,14,4);ctx.fillRect(43,163-i*7,14,4);}
  }
  requestAnimationFrame(this.tick);
 }
}
