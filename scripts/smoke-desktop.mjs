import { _electron as electron } from '../notes/node_modules/playwright-core/index.mjs';
import path from 'node:path';
const app = await electron.launch({executablePath:path.resolve('pc-app/node_modules/electron/dist/electron.exe'),args:[path.resolve('pc-app')]});
try {
 const page = await app.firstWindow();
 await page.getByRole('heading',{name:'Echo-All',exact:true}).waitFor();
 await page.getByRole('link',{name:'Mon compte et mes amis'}).click();
 await page.getByRole('heading',{name:'Echo-All · Amis'}).waitFor();
 await page.getByText('Configuration Firebase incomplète.').waitFor({timeout:45000});
 await page.screenshot({path:path.resolve('pc-app/dist/account-preview.png'),fullPage:true});
 await page.getByRole('link',{name:'Ma bibliothèque'}).click();
 await page.getByRole('heading',{name:'Echo-All',exact:true}).waitFor();
 console.log('Desktop navigation and unconfigured-account state: PASS');
} finally { await app.close(); }
