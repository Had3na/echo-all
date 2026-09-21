const {contextBridge,ipcRenderer}=require('electron');
contextBridge.exposeInMainWorld('echoDesktop',{
 googleLogin:()=>ipcRenderer.invoke('echo:google-login'),
 addFolder:()=>ipcRenderer.invoke('echo:add-folder'),
 openNotes:()=>ipcRenderer.invoke('echo:open-notes'),
 checkUpdates:()=>ipcRenderer.invoke('echo:check-updates'),
 getVersion:()=>ipcRenderer.invoke('echo:version'),
 openSocial:()=>ipcRenderer.invoke('echo:open-social'),
 returnToLibrary:()=>ipcRenderer.invoke('echo:return-library'),
 getSettings:()=>ipcRenderer.invoke('echo:settings'),
 setNotesWifi:enabled=>ipcRenderer.invoke('echo:notes-wifi',enabled)
});
