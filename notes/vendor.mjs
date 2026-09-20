import { cpSync, mkdirSync, readdirSync, writeFileSync } from 'node:fs';
mkdirSync('web/vendor',{recursive:true});
for(const f of ['pdf.mjs','pdf.worker.mjs']) cpSync('node_modules/pdfjs-dist/legacy/build/'+f,'web/vendor/'+f);
for(const d of ['cmaps','standard_fonts','wasm']) cpSync('node_modules/pdfjs-dist/'+d,'web/vendor/'+d,{recursive:true});
cpSync('node_modules/pdfjs-dist/LICENSE','web/vendor/PDFJS-LICENSE');
cpSync('node_modules/pdf-lib/dist/pdf-lib.min.js','web/vendor/pdf-lib.min.js');
cpSync('node_modules/pdf-lib/LICENSE.md','web/vendor/PDFLIB-LICENSE');

cpSync('node_modules/qrcode-generator/dist/qrcode.js','web/vendor/qrcode.js');
function files(dir){return readdirSync(dir,{withFileTypes:true}).flatMap(e=>e.isDirectory()?files(dir+'/'+e.name):[dir+'/'+e.name]);}
writeFileSync('web/precache.js','self.ECHO_PDF_ASSETS='+JSON.stringify(files('web/vendor').map(p=>'./'+p.slice(4)))+';');
