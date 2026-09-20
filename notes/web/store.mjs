const opened = new Promise((resolve,reject) => {
  const request=indexedDB.open('echo-notes-v1',1);
  request.onupgradeneeded=()=>{const db=request.result; db.createObjectStore('notes',{keyPath:'id'}); db.createObjectStore('files',{keyPath:'id'});};
  request.onsuccess=()=>resolve(request.result); request.onerror=()=>reject(request.error);
});
export async function all(store='notes') { const db=await opened; return new Promise((ok,no)=>{const r=db.transaction(store).objectStore(store).getAll(); r.onsuccess=()=>ok(r.result);r.onerror=()=>no(r.error);}); }
export async function get(id,store='notes') { const db=await opened;return new Promise((ok,no)=>{const r=db.transaction(store).objectStore(store).get(id);r.onsuccess=()=>ok(r.result);r.onerror=()=>no(r.error);}); }
export async function put(value,store='notes') { const db=await opened;return new Promise((ok,no)=>{const t=db.transaction(store,'readwrite');t.objectStore(store).put(value);t.oncomplete=ok;t.onerror=()=>no(t.error);t.onabort=()=>no(t.error);}); }
export async function mutate(id,fn) { const db=await opened;return new Promise((ok,no)=>{let result;const t=db.transaction('notes','readwrite'),s=t.objectStore('notes'),r=s.get(id);r.onsuccess=()=>{result=fn(r.result);if(result)s.put(result);};t.oncomplete=()=>ok(result);t.onerror=()=>no(t.error);t.onabort=()=>no(t.error);}); }
export async function save(doc) { return mutate(doc.id,old=>({id:doc.id,doc:structuredClone(doc),rev:old?.rev||0,version:(old?.version||0)+1,dirty:true})); }
export function afterUpload(current,uploaded,rev) {return {...current,rev,dirty:current.version!==uploaded.version};}
export async function conflict(id,remote) {
  const db=await opened;
  return new Promise((ok,no)=>{let copy;const t=db.transaction('notes','readwrite'),s=t.objectStore('notes'),r=s.get(id);
    r.onsuccess=()=>{const local=r.result;if(!local)return;copy={...structuredClone(local),id:crypto.randomUUID(),rev:0,version:1,dirty:true};copy.doc.id=copy.id;copy.doc.title=(copy.doc.title+' · copie en conflit').slice(0,200);copy.doc.deleted=false;copy.doc.updatedAt=Date.now();s.put(copy);s.put({id,doc:remote.doc,rev:remote.rev,version:0,dirty:false});};
    t.oncomplete=()=>ok(copy);t.onerror=()=>no(t.error);t.onabort=()=>no(t.error);
  });
}
export async function digest(blob) {return [...new Uint8Array(await crypto.subtle.digest('SHA-256',await blob.arrayBuffer()))].map(x=>x.toString(16).padStart(2,'0')).join('');}
