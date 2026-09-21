import fs from 'node:fs';
const gradle=fs.readFileSync('app/build.gradle.kts','utf8');
const code=Number(/versionCode = ([0-9]+)/.exec(gradle)[1]);
const version=/versionName = "([^"]+)"/.exec(gradle)[1];
const desktop=JSON.parse(fs.readFileSync('pc-app/package.json','utf8').replace(/^﻿/,'')).version;
if(version!==desktop) throw Error('Versions Android et PC différentes');
const root='app/build/outputs/apk/release';
const names=fs.readdirSync(root).filter(name=>name.endsWith('.apk'));
const apks={};
for(const abi of ['arm64-v8a','armeabi-v7a']) {
 const file=names.find(name=>name.includes(abi) && !name.includes('unsigned'));
 if(!file) throw Error(`APK signé manquant : ${abi}`);
 apks[abi]=`https://github.com/Had3na/echo-all/releases/download/v${version}/${file}`;
}
fs.mkdirSync('release',{recursive:true});
fs.writeFileSync('release/version.json',JSON.stringify({versionCode:code,versionName:version,apk:apks['arm64-v8a'],apks,notes:'Comptes Google, amis, conversations et version Windows.'},null,2));
