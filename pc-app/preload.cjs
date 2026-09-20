const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('echoDesktop', {
  googleLogin: () => ipcRenderer.invoke('echo:google-login')
});
