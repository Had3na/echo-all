import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,rmSync,mkdirSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {chromium} from '@playwright/test';
import {PDFDocument,StandardFonts} from 'pdf-lib';
import {createNotesServer} from '../server.mjs';

test('two workspaces: pen, PDF, offline persistence, sync and conflict copies', {timeout:120000},async()=>{
 const temp=mkdtempSync(join(tmpdir(),'echo-browser-')),app=createNotesServer({dataDir:temp,origin:'http://127.0.0.1:4322'});
 app.createUser('browser@example.test','browser-test-password');
 await new Promise(ok=>app.server.listen(4322,'127.0.0.1',ok));
 const browser=await chromium.launch({channel:'msedge',headless:true});
 const a=await browser.newContext({viewport:{width:1440,height:1000}}),b=await browser.newContext({viewport:{width:390,height:844}});
 const errors=[];
 try{
  const page=await a.newPage(),phone=await b.newPage();page.on('pageerror',e=>errors.push(e.message));phone.on('pageerror',e=>errors.push(e.message));
  await page.goto('http://127.0.0.1:4322');await phone.goto('http://127.0.0.1:4322');
  await page.locator('[data-create="ink"]').first().click();await page.locator('#title').fill('Mathématiques · chapitre 1');
  await page.locator('#folder').fill('Mathématiques');
  await page.waitForFunction(()=>document.querySelector('#ink-canvas').width>100);
  await page.locator('#ink-canvas').evaluate(c=>{const r=c.getBoundingClientRect();for(const [type,x,y,pressure] of [['pointerdown',.2,.2,.3],['pointermove',.3,.3,.8],['pointermove',.5,.25,.5],['pointerup',.5,.25,0]])c.dispatchEvent(new PointerEvent(type,{pointerId:1,pointerType:'pen',clientX:r.left+r.width*x,clientY:r.top+r.height*y,pressure,button:0,bubbles:true}));});
  await page.waitForFunction(()=>document.querySelector('#save-status').textContent==='Enregistré sur cet appareil');
  let rows=await page.evaluate(async()=>(await import('./store.mjs')).all());assert.equal(rows[0].doc.pages[0].length,1);assert.ok(Math.abs(rows[0].doc.pages[0][0].points[1][2]-.8)<.00001);
  await page.locator('#undo').click();await page.locator('#redo').click();
  await page.reload();await page.getByRole('button',{name:/Mathématiques · chapitre 1/}).click();
  rows=await page.evaluate(async()=>(await import('./store.mjs')).all());assert.equal(rows[0].doc.pages[0].length,1);
  await page.locator('[data-tool="eraser"]').click();
  await page.locator('#ink-canvas').evaluate(c=>{const r=c.getBoundingClientRect();for(const type of ['pointerdown','pointerup'])c.dispatchEvent(new PointerEvent(type,{pointerId:1,pointerType:'pen',clientX:r.left+r.width*.3,clientY:r.top+r.height*.3,pressure:.5,button:0,bubbles:true}));});
  await page.waitForFunction(()=>document.querySelector('#save-status').textContent==='Enregistré sur cet appareil');
  assert.equal((await page.evaluate(async()=>(await import('./store.mjs')).all()))[0].doc.pages[0].length,0);
  await page.locator('#undo').click();await page.waitForFunction(()=>document.querySelector('#save-status').textContent==='Enregistré sur cet appareil');
  assert.equal((await page.evaluate(async()=>(await import('./store.mjs')).all()))[0].doc.pages[0].length,1);
  const fixture=await PDFDocument.create(),font=await fixture.embedFont(StandardFonts.Helvetica);fixture.addPage([595,842]).drawText('Cours de physique',{font,size:24,x:70,y:730});fixture.addPage([842,595]).drawText('Deuxieme page paysage',{font,size:24,x:50,y:490});
  await page.locator('#pdf-file').setInputFiles({name:'Physique.pdf',mimeType:'application/pdf',buffer:Buffer.from(await fixture.save())});
  await page.waitForFunction(()=>document.querySelector('#page-number').textContent==='1 / 2');
  await page.locator('#next').click();await page.waitForFunction(()=>document.querySelector('#page-number').textContent==='2 / 2');
  await page.locator('[data-tool="pen"]').click();
  await page.locator('#ink-canvas').evaluate(c=>{const r=c.getBoundingClientRect();for(const [type,x]of [['pointerdown',.2],['pointermove',.4],['pointerup',.4]])c.dispatchEvent(new PointerEvent(type,{pointerId:1,pointerType:'pen',clientX:r.left+r.width*x,clientY:r.top+r.height*.4,pressure:.6,button:0,bubbles:true}));});
  const downloadPromise=page.waitForEvent('download');await page.locator('#export').click();const download=await downloadPromise;const saved=await download.path();const {readFileSync}=await import('node:fs');const exported=await PDFDocument.load(readFileSync(saved));assert.equal(exported.getPageCount(),2);assert.ok(exported.getPage(1).getWidth()>exported.getPage(1).getHeight());
  mkdirSync('test-results',{recursive:true});await page.screenshot({path:'test-results/desktop-pdf.png',fullPage:true});
  for(const p of [page,phone])await p.evaluate(async()=>{const s=await import('./sync.mjs');await s.login(location.origin,'browser@example.test','browser-test-password');await s.sync();});
  let remote=await phone.evaluate(async()=>(await import('./store.mjs')).all());assert.equal(remote.length,2);assert.equal((await phone.evaluate(async()=>(await import('./store.mjs')).all('files'))).length,1);
  await phone.reload();await phone.getByRole('button',{name:/Physique/}).click();await phone.waitForFunction(()=>document.querySelector('#page-number').textContent==='1 / 2');await phone.screenshot({path:'test-results/phone-pdf.png',fullPage:true});
  const id=rows[0].id;
  // Keep a newer local edit while an earlier revision is being uploaded.
  await page.evaluate(async id=>{const d=await import('./store.mjs'),row=await d.get(id);row.doc.text='First upload';await d.save(row.doc);},id);
  let release,started;const blocked=new Promise(ok=>release=ok),seen=new Promise(ok=>started=ok);
  await page.route('**/api/notes/'+id,async route=>{if(route.request().method()==='PUT'){started();await blocked;}await route.continue();});
  const uploading=page.evaluate(async()=>{await(await import('./sync.mjs')).sync();});await seen;
  await page.evaluate(async id=>{const d=await import('./store.mjs'),row=await d.get(id);row.doc.text='Typed during upload';await d.save(row.doc);},id);
  release();await uploading;await page.unroute('**/api/notes/'+id);
  const stillPending=await page.evaluate(async id=>(await import('./store.mjs')).get(id),id);assert.equal(stillPending.doc.text,'Typed during upload');assert.equal(stillPending.dirty,true);
  await page.evaluate(async()=>{await(await import('./sync.mjs')).sync();});await phone.evaluate(async()=>{await(await import('./sync.mjs')).sync();});

  await a.setOffline(true);
  await page.evaluate(async id=>{const d=await import('./store.mjs'),row=await d.get(id);row.doc.text='Offline PC';await d.save(row.doc);},id);
  await phone.evaluate(async id=>{const d=await import('./store.mjs'),row=await d.get(id);row.doc.text='Phone edit';await d.save(row.doc);await(await import('./sync.mjs')).sync();},id);
  await a.setOffline(false);
  await page.evaluate(async()=>{await(await import('./sync.mjs')).sync();});
  await page.waitForTimeout(500);
  const conflict=await page.evaluate(async()=>(await import('./store.mjs')).all());assert.ok(conflict.some(r=>r.doc.title.includes('copie en conflit')&&r.doc.text==='Offline PC'));assert.equal(conflict.find(r=>r.id===id).doc.text,'Phone edit');
  await page.evaluate(async()=>{await(await import('./sync.mjs')).sync();});await phone.evaluate(async()=>{await(await import('./sync.mjs')).sync();});
  remote=await phone.evaluate(async()=>(await import('./store.mjs')).all());assert.ok(remote.some(r=>r.doc.text==='Offline PC'));assert.ok(remote.some(r=>r.doc.text==='Phone edit'));
  await page.evaluate(()=>navigator.serviceWorker.ready);await a.setOffline(true);await page.reload();await page.waitForSelector('#new-note');assert.ok((await page.evaluate(async()=>(await import('./store.mjs')).all())).length>=3);
  // Notes and PDF backups can be restored without replacing existing notes.
  const backupPromise=page.waitForEvent('download');await page.locator('#backup').click();const backup=await backupPromise;const pack=readFileSync(await backup.path());
  const beforeRestore=(await phone.evaluate(async()=>(await import('./store.mjs')).all())).length;
  await phone.locator('#backup-file').setInputFiles({name:'notes.json',mimeType:'application/json',buffer:pack});
  await phone.waitForFunction(()=>document.querySelector('#toast').textContent.includes('Notes restaurées'));
  assert.equal((await phone.evaluate(async()=>(await import('./store.mjs')).all())).length,beforeRestore+JSON.parse(pack).notes.filter(n=>!n.deleted).length);
  await page.locator('#new-note').click();await page.locator('#new-dialog [data-create="check"]').click();await page.locator('#check-input').fill('Acheter des cahiers');await page.locator('#check-form button').click();await page.getByRole('checkbox',{name:'Acheter des cahiers'}).check();
  await page.waitForFunction(()=>document.querySelector('#save-status').textContent==='Enregistré sur cet appareil');
  assert.ok((await page.evaluate(async()=>(await import('./store.mjs')).all())).some(r=>r.doc.checks.some(c=>c.text==='Acheter des cahiers'&&c.done)));
  assert.deepEqual(errors,[]);
 }catch(error){mkdirSync('test-results',{recursive:true});await a.pages()[0]?.screenshot({path:'test-results/browser-failure.png',fullPage:true});throw error;}finally{await browser.close();await new Promise(ok=>app.server.close(ok));rmSync(temp,{recursive:true,force:true});}
});
