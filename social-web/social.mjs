import { initializeApp } from 'https://www.gstatic.com/firebasejs/12.19.0/firebase-app.js';
import { getAuth, GoogleAuthProvider, signInWithPopup, signInWithCredential, onAuthStateChanged, signOut, setPersistence, browserLocalPersistence } from 'https://www.gstatic.com/firebasejs/12.19.0/firebase-auth.js';
import { getFirestore, doc, getDoc, setDoc, collection, addDoc, query, where, onSnapshot, updateDoc, serverTimestamp, orderBy, limit } from 'https://www.gstatic.com/firebasejs/12.19.0/firebase-firestore.js';
import { getStorage, ref, uploadBytes, getBlob, deleteObject } from 'https://www.gstatic.com/firebasejs/12.19.0/firebase-storage.js';
const $ = id => document.getElementById(id);
if(window.echoDesktop) { $('libraryLink').hidden=false; $('libraryLink').onclick=e=>{if(window.echoDesktop.returnToLibrary){e.preventDefault();window.echoDesktop.returnToLibrary();}}; }
const status = text => $('status').textContent = text;
let user, selected, stopChats, stopMessages;
const blobs = new Set();
function clearMessages() { stopMessages?.(); stopMessages = null; for (const url of blobs) URL.revokeObjectURL(url); blobs.clear(); $('messages').replaceChildren(); }
function button(text, action) { const b = document.createElement('button'); b.textContent = text; b.onclick = () => action().catch(e => status(e.message)); return b; }
try {
 const response = await fetch('firebase-config.json');
 if (!response.ok) throw new Error('Les comptes ne sont pas encore activés : configuration Firebase requise.');
 const config = await response.json();
 if (!config.projectId || !config.apiKey) throw new Error('Configuration Firebase incomplète.');
 const app = initializeApp(config), auth = getAuth(app), db = getFirestore(app), storage = getStorage(app);
 await setPersistence(auth, browserLocalPersistence);
 $('login').onclick = async () => {
   $('login').disabled = true;
   try {
     if(window.echoDesktop?.googleLogin) {
       const token = await window.echoDesktop.googleLogin();
       await signInWithCredential(auth, GoogleAuthProvider.credential(token));
     } else await signInWithPopup(auth, new GoogleAuthProvider());
   } catch(e) { status(e.message); }
   finally { $('login').disabled = false; }
 };
 $('logout').onclick = () => signOut(auth).catch(e => status(e.message));
 onAuthStateChanged(auth, async current => {
   user = current; selected = null; stopChats?.(); clearMessages();
   $('conversation').hidden = true; $('friends').replaceChildren();
   $('account').hidden = !user; $('login').hidden = !!user;
   status(user ? '' : 'Connecte-toi avec le même compte Google sur chaque appareil.');
   if (!user) return;
   $('identity').textContent = `${user.displayName || 'Mon compte'} · Mon identifiant : ${user.uid}`;
   try { await setDoc(doc(db,'users',user.uid), {name:(user.displayName || 'Echo').slice(0,80)}); }
   catch(e) { status(e.message); return; }
   if(auth.currentUser?.uid !== current.uid) return;
   stopChats = onSnapshot(query(collection(db,'chats'),where('members','array-contains',user.uid)), snapshot => {
     $('friends').replaceChildren();
     for (const chat of snapshot.docs) {
       const data = chat.data(), peer = data.members.find(id => id !== user.uid);
       const row = document.createElement('article');
       const title = document.createElement('p'); title.textContent = `${peer} · ${data.status}`; row.append(title);
       if(data.status === 'pending' && data.recipient === user.uid) row.append(button('Accepter',()=>updateDoc(chat.ref,{status:'accepted'})));
       if(data.status === 'accepted') row.append(button('Ouvrir',async()=>openChat(chat.id, peer)));
       if(data.status !== 'blocked') row.append(button('Bloquer / refuser',()=>updateDoc(chat.ref,{status:'blocked'})));
       if (selected === chat.id && data.status !== 'accepted') { selected = null; clearMessages(); $('conversation').hidden = true; }
       $('friends').append(row);
     }
   }, e=>status(e.message));
 });
 $('invite').onsubmit = async event => {
   event.preventDefault();
   try {
     const peer = $('friend').value.trim();
     if(!peer || peer.includes('/') || peer === user.uid) throw new Error('Identifiant ami invalide.');
     if(!(await getDoc(doc(db,'users',peer))).exists()) throw new Error('Compte introuvable.');
     await addDoc(collection(db,'chats'),{members:[user.uid,peer],sender:user.uid,recipient:peer,status:'pending'});
     $('friend').value = ''; status('Invitation envoyée.');
   } catch(e) { status(e.message); }
 };
 function openChat(id, peer) {
   clearMessages(); selected=id; $('conversation').hidden=false; $('chatTitle').textContent=`Conversation avec ${peer}`;
   stopMessages=onSnapshot(query(collection(db,'chats',id,'messages'),orderBy('createdAt','desc'),limit(100)),snapshot=>{
     for(const url of blobs) URL.revokeObjectURL(url); blobs.clear();
     $('messages').replaceChildren();
     for(const message of [...snapshot.docs].reverse()) {
       const data=message.data(), row=document.createElement('article'), text=document.createElement('p');
       text.textContent=`${data.sender === user.uid ? 'Moi' : peer} : ${data.text}`; row.append(text);
       if(data.file) row.append(button(`Télécharger ${data.name}`,async()=>{
         const blob=await getBlob(ref(storage,data.file),25*1024*1024);
         if(selected !== id) return;
         const url=URL.createObjectURL(blob); blobs.add(url);
         const link=document.createElement('a'); link.href=url; link.download=data.name || 'media'; link.click();
       }));
       $('messages').append(row);
     }
     $('messages').scrollTop=$('messages').scrollHeight;
   },e=>status(e.message));
 }
 $('send').onsubmit=async event=>{
   event.preventDefault(); const chat=selected, sender=user?.uid, text=$('text').value.trim(), file=$('file').files[0]; let uploaded;
   if(!chat || !sender || (!text && !file)) return;
   $('submit').disabled=true;
   try {
     if(file) {
       if(file.size > 25*1024*1024 || file.size === 0 || !/^(image|audio|video)\//.test(file.type) || file.type === 'image/svg+xml') throw new Error('Choisis un média de 25 Mo maximum.');
       uploaded=ref(storage,`chats/${chat}/${sender}/${crypto.randomUUID()}`);
       await uploadBytes(uploaded,file,{contentType:file.type});
     }
     await addDoc(collection(db,'chats',chat,'messages'),{sender,text,file:uploaded?.fullPath || '',name:file?.name.slice(0,180) || '',createdAt:serverTimestamp()});
     if(selected === chat) { $('text').value=''; $('file').value=''; } status('Message envoyé.');
   } catch(e) { if(uploaded) await deleteObject(uploaded).catch(()=>{}); status(e.message); }
   finally { $('submit').disabled=false; }
 };
} catch(e) { status(e.message); }
