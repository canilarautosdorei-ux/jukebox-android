const { app, BrowserWindow, shell } = require('electron');

const START_URL = 'https://kiosk.r777b.site';

let mainWindow;

function createWindow() {
  mainWindow = new BrowserWindow({
    fullscreen: true,
    kiosk: true,
    autoHideMenuBar: true,
    frame: false,
    backgroundColor: '#000000',
    webPreferences: {
      javascript: true,
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true
    }
  });

  mainWindow.setMenuBarVisibility(false);
  mainWindow.loadURL(START_URL);

  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    try {
      const target = new URL(url);
      const allowed = target.hostname === 'kiosk.r777b.site' || target.hostname.endsWith('.kiosk.r777b.site');
      if (allowed) {
        mainWindow.loadURL(url);
      } else {
        shell.openExternal(url);
      }
    } catch (_) {}
    return { action: 'deny' };
  });

  mainWindow.webContents.on('will-navigate', (event, url) => {
    try {
      const target = new URL(url);
      const allowed = target.hostname === 'kiosk.r777b.site' || target.hostname.endsWith('.kiosk.r777b.site');
      if (!allowed) {
        event.preventDefault();
        shell.openExternal(url);
      }
    } catch (_) {}
  });

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');

app.whenReady().then(() => {
  app.setName('kiosk WebView');
  createWindow();
});

app.on('window-all-closed', () => {
  app.quit();
});
