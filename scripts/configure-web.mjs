import fs from 'node:fs';
if(!process.env.FIREBASE_WEB) throw new Error('Secret GitHub manquant : FIREBASE_WEB_JSON');
const config = JSON.parse(process.env.FIREBASE_WEB);
if(!config.apiKey || !config.projectId) throw new Error('Configuration Firebase incomplète');
fs.writeFileSync('social-web/firebase-config.json',JSON.stringify(config));
