const { app, BrowserWindow, Menu, shell, session } = require('electron');
const path = require('path');

const START_URL = 'https://teste.r777b.site/jukebox';
const START_HOST = 'teste.r777b.site';
const RETRY_MS = 4000;

let mainWindow = null;
let reconnectTimer = null;
let showingError = false;
let loadingErrorPage = false;

function isAllowedInternalUrl(rawUrl) {
  try {
    const u = new URL(rawUrl);
    return u.protocol === 'https:' &&
      (u.hostname === START_HOST || u.hostname.endsWith('.' + START_HOST));
  } catch (_) {
    return false;
  }
}

function clearReconnectTimer() {
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
}

function scheduleReconnect(delay = RETRY_MS) {
  clearReconnectTimer();
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    loadJukebox(true);
  }, delay);
}

async function showConnectionError() {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  if (loadingErrorPage) return;

  showingError = true;
  loadingErrorPage = true;
  clearReconnectTimer();

  try {
    await mainWindow.loadFile(path.join(__dirname, 'error.html'));
  } catch (_) {
    // Não mostra detalhes técnicos ao usuário.
  } finally {
    loadingErrorPage = false;
  }

  scheduleReconnect();
}

async function loadJukebox(forceNoCache = false) {
  if (!mainWindow || mainWindow.isDestroyed()) return;

  clearReconnectTimer();
  showingError = false;

  try {
    if (forceNoCache) {
      await mainWindow.webContents.session.clearCache();
    }
    await mainWindow.loadURL(START_URL);
  } catch (_) {
    await showConnectionError();
  }
}

function createWindow() {
  Menu.setApplicationMenu(null);

  mainWindow = new BrowserWindow({
    title: 'Jukebox',
    fullscreen: true,
    autoHideMenuBar: true,
    backgroundColor: '#070b12',
    show: false,
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      sandbox: true,
      webSecurity: true,
      partition: 'persist:jukebox'
    }
  });

  mainWindow.setMenuBarVisibility(false);

  mainWindow.once('ready-to-show', () => {
    if (mainWindow && !mainWindow.isDestroyed()) mainWindow.show();
  });

  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (isAllowedInternalUrl(url)) {
      mainWindow.loadURL(url).catch(() => showConnectionError());
    } else if (/^https?:/i.test(url)) {
      shell.openExternal(url).catch(() => {});
    }
    return { action: 'deny' };
  });

  mainWindow.webContents.on('will-navigate', (event, url) => {
    if (url === 'jukebox://retry') {
      event.preventDefault();
      loadJukebox(true);
      return;
    }

    if (url.startsWith('file://')) return;

    if (!isAllowedInternalUrl(url)) {
      event.preventDefault();
      if (/^https?:/i.test(url)) shell.openExternal(url).catch(() => {});
    }
  });

  mainWindow.webContents.on('did-fail-load', (_event, errorCode, _errorDescription, _validatedURL, isMainFrame) => {
    if (!isMainFrame || errorCode === -3 || loadingErrorPage) return;
    showConnectionError();
  });

  mainWindow.webContents.on('did-finish-load', () => {
    const current = mainWindow?.webContents.getURL() || '';
    if (isAllowedInternalUrl(current)) {
      showingError = false;
      clearReconnectTimer();
    }
  });

  mainWindow.webContents.on('render-process-gone', () => {
    showConnectionError();
  });

  mainWindow.on('unresponsive', () => {
    showConnectionError();
  });

  mainWindow.on('closed', () => {
    clearReconnectTimer();
    mainWindow = null;
  });

  loadJukebox(false);
}

app.on('certificate-error', (event, webContents, _url, _error, _certificate, callback) => {
  if (mainWindow && webContents.id === mainWindow.webContents.id) {
    event.preventDefault();
    callback(false);
    showConnectionError();
    return;
  }
  callback(false);
});

app.whenReady().then(async () => {
  const ses = session.fromPartition('persist:jukebox');
  ses.setUserAgent(ses.getUserAgent() + ' JukeboxDesktop/1.1');
  createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
    else if (showingError) loadJukebox(true);
  });
});

app.on('second-instance', () => {
  if (mainWindow) {
    if (mainWindow.isMinimized()) mainWindow.restore();
    mainWindow.focus();
  }
});

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
}

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
