import { before, after, beforeEach, test } from 'node:test';
import fs from 'node:fs';
import { initializeTestEnvironment, assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import { doc, setDoc, getDoc, updateDoc, serverTimestamp } from 'firebase/firestore';
import { ref, uploadBytes, getBytes } from 'firebase/storage';
let env;
before(async()=>{
 env=await initializeTestEnvironment({projectId:'demo-echo',
   firestore:{rules:fs.readFileSync(new URL('./firestore.rules',import.meta.url),'utf8')},
   storage:{rules:fs.readFileSync(new URL('./storage.rules',import.meta.url),'utf8')}});
});
after(async()=>env?.cleanup());
beforeEach(async()=>{
 await env.clearFirestore(); await env.clearStorage();
 await env.withSecurityRulesDisabled(async context=>{
   const db=context.firestore();
   for(const id of ['alice','bob','mallory']) await setDoc(doc(db,'users',id),{name:id});
   await setDoc(doc(db,'chats','accepted'),{sender:'alice',recipient:'bob',members:['alice','bob'],status:'accepted'});
   await setDoc(doc(db,'chats','pending'),{sender:'alice',recipient:'bob',members:['alice','bob'],status:'pending'});
 });
});
const db=id=>env.authenticatedContext(id).firestore();
const message=sender=>({sender,text:'Salut',file:'',name:'',createdAt:serverTimestamp()});
test('seul le destinataire peut accepter une invitation',async()=>{
 await assertFails(updateDoc(doc(db('alice'),'chats','pending'),{status:'accepted'}));
 await assertSucceeds(updateDoc(doc(db('bob'),'chats','pending'),{status:'accepted'}));
});
test('un tiers et les visiteurs ne lisent pas une conversation',async()=>{
 await assertFails(getDoc(doc(db('mallory'),'chats','accepted')));
 await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(),'chats','accepted')));
 await assertSucceeds(getDoc(doc(db('bob'),'chats','accepted')));
});
test('les messages exigent une amitié acceptée et un expéditeur authentique',async()=>{
 await assertFails(setDoc(doc(db('alice'),'chats','pending','messages','m'),message('alice')));
 await assertFails(setDoc(doc(db('alice'),'chats','accepted','messages','m'),message('bob')));
 await assertFails(setDoc(doc(db('mallory'),'chats','accepted','messages','m'),message('mallory')));
 await assertSucceeds(setDoc(doc(db('alice'),'chats','accepted','messages','m'),message('alice')));
});
test('les membres et profils ne peuvent pas être usurpés',async()=>{
 await assertFails(updateDoc(doc(db('alice'),'chats','accepted'),{members:['alice','mallory']}));
 await assertFails(setDoc(doc(db('alice'),'users','bob'),{name:'Usurpation'}));
 await assertFails(setDoc(doc(db('alice'),'users','alice'),{name:'Alice',email:'private@example.com'}));
});
test('un blocage interdit les nouveaux messages et leur lecture',async()=>{
 await assertSucceeds(setDoc(doc(db('alice'),'chats','accepted','messages','m'),message('alice')));
 await assertSucceeds(updateDoc(doc(db('bob'),'chats','accepted'),{status:'blocked'}));
 await assertFails(getDoc(doc(db('alice'),'chats','accepted','messages','m')));
 await assertFails(setDoc(doc(db('alice'),'chats','accepted','messages','n'),message('alice')));
});
test('fichiers privés, types restreints et propriétaire vérifié',async()=>{
 const storage=id=>env.authenticatedContext(id).storage();
 await assertSucceeds(uploadBytes(ref(storage('alice'),'chats/accepted/alice/photo'),new Uint8Array([1,2]),{contentType:'image/png'}));
 await assertFails(getBytes(ref(storage('mallory'),'chats/accepted/alice/photo')));
 await assertSucceeds(getBytes(ref(storage('bob'),'chats/accepted/alice/photo')));
 await assertFails(uploadBytes(ref(storage('bob'),'chats/accepted/alice/forged'),new Uint8Array([1]),{contentType:'image/png'}));
 await assertFails(uploadBytes(ref(storage('alice'),'chats/accepted/alice/html'),new Uint8Array([1]),{contentType:'text/html'}));
 await assertFails(uploadBytes(ref(storage('alice'),'chats/pending/alice/photo'),new Uint8Array([1]),{contentType:'image/png'}));
});
