import fs from 'node:fs';
for(const key of ['KEYSTORE','STORE_PASSWORD','KEY_ALIAS','KEY_PASSWORD','FIREBASE_ANDROID']) {
 if(!process.env[key]) throw new Error('Secret GitHub manquant : '+key);
}
const escape = value => Array.from(value).map(char => {
 const units = [];
 for (let i=0;i<char.length;i++) units.push(String.fromCharCode(92)+'u'+char.charCodeAt(i).toString(16).padStart(4,'0'));
 return units.join('');
}).join('');
fs.writeFileSync('app/release.keystore',Buffer.from(process.env.KEYSTORE,'base64'));
fs.writeFileSync('keystore.properties',[
 'storeFile=release.keystore',
 'storePassword='+escape(process.env.STORE_PASSWORD),
 'keyAlias='+escape(process.env.KEY_ALIAS),
 'keyPassword='+escape(process.env.KEY_PASSWORD)].join(String.fromCharCode(10)));
const config=JSON.parse(process.env.FIREBASE_ANDROID);
fs.writeFileSync('app/google-services.json',JSON.stringify(config));
