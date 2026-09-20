import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const source = path.join(here,'..','social-web');
for(const [from,to] of [['index.html','social.html'],['social.mjs','social.mjs']]) fs.copyFileSync(path.join(source,from),path.join(here,'web',to));
const config = path.join(source,'firebase-config.json');
fs.copyFileSync(fs.existsSync(config)?config:path.join(source,'firebase-config.example.json'),path.join(here,'web','firebase-config.json'));
