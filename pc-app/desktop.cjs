const { app, BrowserWindow, dialog, shell } = require('electron');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
let server;
let checkedAt = 0;
async function checkUpdates(manual = false) {
  if (!manual && Date.now() - checkedAt < 86400000) return;
  checkedAt = Date.now();
  try {
    const response = await fetch('https://api.github.com/repos/Had3na/echo-all/releases/latest', {
      headers: { Accept: 'application/vnd.github+json' }, signal: AbortSignal.timeout(15000)
    });
    if (!response.ok) throw new Error('GitHub indisponible');
    const release = await response.json();
    const version = /^v?(\d+)\.(\d+)\.(\d+)$/.exec(release.tag_name);
    if (!version) throw new Error('Version inconnue');
    const newer = version.slice(1).map(Number);
    const current = app.getVersion().split('.').map(Number);
    const index = newer.findIndex((n, i) => n !== current[i]);
    const asset = release.assets?.find(a => /^Echo-All-PC-.*-Setup\.exe$/.test(a.name));
    if (index >= 0 && newer[index] > current[index] && asset) {
      const target = new URL(asset.browser_download_url);
      if (target.origin !== 'https://github.com' || !target.pathname.startsWith('/Had3na/echo-all/releases/download/')) return;
      const answer = await dialog.showMessageBox({
        type: 'info', message: `Echo-All ${release.tag_name} est disponible.`,
        buttons: ['Télécharger', 'Plus tard'], defaultId: 0, cancelId: 1
      });
      if (answer.response === 0) await shell.openExternal(target.href);
    } else if (manual) await dialog.showMessageBox({message: 'Aucune nouvelle version PC disponible.'});
  } catch {
    if (manual) await dialog.showMessageBox({type: 'warning', message: 'Vérification impossible. Réessaie lorsque tu es connecté.'});
  }
}
if (!app.requestSingleInstanceLock()) app.quit();
else app.whenReady().then(async () => {
  process.env.ECHO_DATA_DIR = path.join(app.getPath('userData'), 'media');
  process.env.ECHO_DESKTOP = '1';
  const module = await import(pathToFileURL(path.join(__dirname, 'server.mjs')));
  server = module.server;
  await module.ready;
  const origin = `http://127.0.0.1:${server.address().port}`;
  const window = new BrowserWindow({ width: 1200, height: 820, minWidth: 700,
    webPreferences: { nodeIntegration: false, contextIsolation: true, sandbox: true, preload: path.join(__dirname, "preload.cjs") } });
  window.webContents.setWindowOpenHandler(() => ({action: 'deny'}));
  window.webContents.on('will-navigate', (event, url) => {
    if (new URL(url).origin !== origin) event.preventDefault();
  });
  window.webContents.session.setPermissionRequestHandler((_web, _permission, callback) => callback(false));
  const { Menu } = require('electron');
  Menu.setApplicationMenu(Menu.buildFromTemplate([
    {label: 'Echo-All', submenu: [
      {label: 'Rechercher des mises à jour', click: () => checkUpdates(true)},
      {type: 'separator'}, {role: 'quit'}
    ]},
    {role: 'editMenu'}, {role: 'viewMenu'}
  ]));
    let signingIn = false;
  require('electron').ipcMain.handle('echo:google-login', async event => {
    if (event.senderFrame !== window.webContents.mainFrame ||
        new URL(event.senderFrame.url).origin !== origin ||
        new URL(event.senderFrame.url).pathname !== '/social.html') throw Error('Fenêtre non autorisée');
    if(signingIn) throw Error('Une connexion est déjà en cours.');
    const config = JSON.parse(require('node:fs').readFileSync(path.join(__dirname,'web','firebase-config.json'),'utf8').replace(/^﻿/, ''));
    if(!config.projectId || !config.apiKey) throw Error('Firebase non configuré.');
    signingIn = true;
    try { return await require('./google-login.cjs').googleLogin(config, shell); }
    finally { signingIn = false; }
  });
  await window.loadURL(origin);
  checkUpdates();
  window.on('focus', () => checkUpdates());
  app.on('second-instance', () => { if (window.isMinimized()) window.restore(); window.focus(); });
}).catch(error => { dialog.showErrorBox('Echo-All', error.message); app.quit(); });
app.on('window-all-closed', () => app.quit());
app.on('before-quit', () => server?.close());
