const { app, BrowserWindow, dialog, shell } = require('electron');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
let server, notesServer, notesWindow, socialWindow;
if (process.env.ECHO_TEST_PROFILE) app.setPath("userData", process.env.ECHO_TEST_PROFILE);
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
  const window = new BrowserWindow({ width: 1440, height: 960, minWidth: 780, minHeight: 700, backgroundColor: "#131216", autoHideMenuBar: true, show: false, icon: path.join(__dirname, "web", "echo-logo.png"),
    webPreferences: { nodeIntegration: false, contextIsolation: true, sandbox: true, backgroundThrottling: false, preload: path.join(__dirname, "preload.cjs") } });
  window.webContents.setWindowOpenHandler(() => ({action: 'deny'}));
  window.webContents.on('will-navigate', (event, url) => {
    if (new URL(url).origin !== origin) event.preventDefault();
  });
  window.webContents.session.setPermissionRequestHandler((web, permission, callback) => callback(web === window.webContents && permission === 'speaker-selection'));
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
    if ((event.sender !== window.webContents && event.sender !== socialWindow?.webContents) || event.senderFrame !== event.sender.mainFrame ||
        new URL(event.senderFrame.url).origin !== origin ||
        new URL(event.senderFrame.url).pathname !== '/social.html') throw Error('Fenêtre non autorisée');
    if(signingIn) throw Error('Une connexion est déjà en cours.');
    const config = JSON.parse(require('node:fs').readFileSync(path.join(__dirname,'web','firebase-config.json'),'utf8').replace(/^﻿/, ''));
    if(!config.projectId || !config.apiKey) throw Error('Firebase non configuré.');
    signingIn = true;
    try { return await require('./google-login.cjs').googleLogin(config, shell); }
    finally { signingIn = false; }
  });
  const { ipcMain } = require("electron");
  const fs = require("node:fs"), settingsFile = path.join(app.getPath("userData"), "desktop-settings.json");
  let desktopSettings = {notesWifi:false};
  try { desktopSettings.notesWifi = JSON.parse(fs.readFileSync(settingsFile, "utf8")).notesWifi === true; } catch {}
  const notesWifiAtStartup = desktopSettings.notesWifi;
  function trusted(event) {
    if (event.senderFrame !== window.webContents.mainFrame || new URL(event.senderFrame.url).origin !== origin) throw Error("Fenêtre non autorisée.");
  }
  ipcMain.handle("echo:settings", event => { trusted(event); return desktopSettings; });
  ipcMain.handle("echo:notes-wifi", (event, enabled) => { trusted(event); desktopSettings.notesWifi = enabled === true; fs.writeFileSync(settingsFile, JSON.stringify(desktopSettings)); return true; });
  ipcMain.handle("echo:open-social", async event => {
    trusted(event);
    if (socialWindow && !socialWindow.isDestroyed()) { socialWindow.focus(); return; }
    socialWindow = new BrowserWindow({width:1000,height:820,minWidth:700,backgroundColor:"#131216",autoHideMenuBar:true,
      webPreferences:{nodeIntegration:false,contextIsolation:true,sandbox:true,preload:path.join(__dirname,"preload.cjs")}});
    socialWindow.on("closed",()=>{socialWindow=null;});
    socialWindow.webContents.setWindowOpenHandler(()=>({action:"deny"}));
    socialWindow.webContents.on("will-navigate",(event,url)=>{if(new URL(url).origin!==origin)event.preventDefault();});
    await socialWindow.loadURL(origin+"/social.html");
  });
  ipcMain.handle("echo:return-library", event => {
    if ((event.sender !== window.webContents && event.sender !== socialWindow?.webContents) || event.senderFrame !== event.sender.mainFrame || new URL(event.senderFrame.url).origin !== origin) throw Error("Fenêtre non autorisée.");
    if (event.sender === socialWindow?.webContents) { socialWindow.close(); if(window.isMinimized())window.restore(); window.focus(); } else window.loadURL(origin);
  });
  ipcMain.handle("echo:version", event => { trusted(event); return app.getVersion(); });
  ipcMain.handle("echo:check-updates", event => { trusted(event); return checkUpdates(true); });
  ipcMain.handle("echo:add-folder", async event => {
    trusted(event);
    const choice = await dialog.showOpenDialog(window, { title: "Ajouter un dossier à Echo-All", properties: ["openDirectory", "multiSelections"] });
    if (choice.canceled) return false;
    for (const folder of choice.filePaths) module.addMediaFolder(folder);
    return choice.filePaths.length > 0;
  });
  ipcMain.handle("echo:open-notes", async event => {
    trusted(event);
    if (notesWindow && !notesWindow.isDestroyed()) { if (notesWindow.isMinimized()) notesWindow.restore(); notesWindow.focus(); return; }
    if (!notesServer) {
      const notesModule = await import(pathToFileURL(path.join(__dirname, "notes", "server.mjs")));
      const instance = notesModule.createNotesServer({ dataDir: path.join(app.getPath("userData"), "notes"), allowLocal: true, lanEnabled: notesWifiAtStartup });
      await new Promise((resolve, reject) => { instance.server.once("error", reject); instance.server.listen(4319, notesWifiAtStartup ? "0.0.0.0" : "127.0.0.1", resolve); }).catch(error => { instance.server.close(); throw Error("Notes ne peut pas démarrer. Ferme une autre instance de Notes utilisant le port 4319. " + error.message); });
      notesServer = instance.server;
    }
    const notesOrigin = "http://127.0.0.1:4319";
    notesWindow = new BrowserWindow({ width: 1300, height: 900, minWidth: 750, minHeight: 650, title: "Echo-All · Notes", backgroundColor: "#131216", autoHideMenuBar: true,
      webPreferences: { nodeIntegration: false, contextIsolation: true, sandbox: true } });
    notesWindow.webContents.setWindowOpenHandler(() => ({action:"deny"}));
    notesWindow.webContents.on("will-navigate", (event, url) => { if (new URL(url).origin !== notesOrigin) event.preventDefault(); });
    await notesWindow.loadURL(notesOrigin);
  });
  await window.loadURL(origin);
  window.show();
  checkUpdates();
  window.on('focus', () => checkUpdates());
  app.on('second-instance', () => { if (window.isMinimized()) window.restore(); window.focus(); });
}).catch(error => { dialog.showErrorBox('Echo-All', error.message); app.quit(); });
app.on('window-all-closed', () => app.quit());
app.on('will-quit', () => { server?.close(); notesServer?.close(); });
