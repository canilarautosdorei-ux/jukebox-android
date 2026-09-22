const { app, BrowserWindow, shell, session } = require('electron');

const START_URL = 'https://teste.r777b.site/jukebox';

function createWindow() {
  const win = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 900,
    minHeight: 600,
    fullscreen: true,
    autoHideMenuBar: true,
    backgroundColor: '#07090d',
    webPreferences: {
      contextIsolation: true,
      sandbox: true,
      autoplayPolicy: 'no-user-gesture-required'
    }
  });

  win.removeMenu();
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith('https://teste.r777b.site/')) return { action: 'allow' };
    shell.openExternal(url);
    return { action: 'deny' };
  });

  win.webContents.on('will-navigate', (event, url) => {
    try {
      const u = new URL(url);
      if (u.hostname !== 'teste.r777b.site' && !u.hostname.endsWith('.teste.r777b.site')) {
        event.preventDefault();
        shell.openExternal(url);
      }
    } catch (_) {}
  });

  win.loadURL(START_URL, { userAgent: 'JukeboxDesktop/1.4' });

  win.webContents.on('did-fail-load', (_e, _code, _desc, url, isMainFrame) => {
    if (isMainFrame) {
      setTimeout(() => {
        if (!win.isDestroyed()) win.loadURL(START_URL);
      }, 4000);
    }
  });

  win.on('closed', () => {});
}

app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');
app.whenReady().then(() => {
  session.defaultSession.setPermissionRequestHandler((_wc, permission, callback) => {
    callback(['media', 'fullscreen', 'notifications'].includes(permission));
  });
  createWindow();
});

app.on('window-all-closed', () => {
  app.quit();
});
