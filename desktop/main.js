const { app, dialog, globalShortcut } = require('electron');
const { spawn, execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const START_URL = 'https://jk.r777b.site/jukebox';
const CONFIG_FILE = 'browser-choice.json';

app.setName('Jukebox');

let browserProcess = null;
let exitShortcutTimer = null;

function configPath() {
  return path.join(app.getPath('userData'), CONFIG_FILE);
}

function readChoice() {
  try {
    const saved = JSON.parse(fs.readFileSync(configPath(), 'utf8'));
    return saved.browser === 'firefox' || saved.browser === 'chrome'
      ? saved.browser
      : null;
  } catch (_) {
    return null;
  }
}

function saveChoice(browser) {
  fs.mkdirSync(app.getPath('userData'), { recursive: true });
  fs.writeFileSync(configPath(), JSON.stringify({ browser }, null, 2));
}

function clearChoice() {
  try {
    fs.unlinkSync(configPath());
  } catch (_) {}
}

function firstExisting(candidates) {
  return candidates.find(candidate => candidate && fs.existsSync(candidate)) || null;
}

function commandPath(commands) {
  for (const command of commands) {
    try {
      const found = execFileSync('which', [command], {
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'ignore']
      }).trim();
      if (found) return found;
    } catch (_) {}
  }
  return null;
}

function findFirefox() {
  if (process.platform === 'win32') {
    return firstExisting([
      path.join(process.env.PROGRAMFILES || '', 'Mozilla Firefox', 'firefox.exe'),
      path.join(process.env['PROGRAMFILES(X86)'] || '', 'Mozilla Firefox', 'firefox.exe'),
      path.join(process.env.LOCALAPPDATA || '', 'Mozilla Firefox', 'firefox.exe')
    ]);
  }

  return commandPath(['firefox', 'firefox-esr']);
}

function findChrome() {
  if (process.platform === 'win32') {
    return firstExisting([
      path.join(process.env.PROGRAMFILES || '', 'Google', 'Chrome', 'Application', 'chrome.exe'),
      path.join(process.env['PROGRAMFILES(X86)'] || '', 'Google', 'Chrome', 'Application', 'chrome.exe'),
      path.join(process.env.LOCALAPPDATA || '', 'Google', 'Chrome', 'Application', 'chrome.exe'),
      path.join(process.env.PROGRAMFILES || '', 'Chromium', 'Application', 'chrome.exe'),
      path.join(process.env.LOCALAPPDATA || '', 'Chromium', 'Application', 'chrome.exe')
    ]);
  }

  return commandPath([
    'google-chrome',
    'google-chrome-stable',
    'chromium',
    'chromium-browser'
  ]);
}

async function askBrowser() {
  const result = await dialog.showMessageBox({
    type: 'question',
    title: 'Jukebox',
    message: 'Qual navegador deseja usar na Jukebox?',
    detail: 'A escolha ficará salva. Nas próximas vezes a Jukebox abrirá diretamente.',
    buttons: ['Firefox', 'Chrome', 'Cancelar'],
    defaultId: 0,
    cancelId: 2,
    noLink: true
  });

  if (result.response === 0) return 'firefox';
  if (result.response === 1) return 'chrome';
  return null;
}

async function showMissingBrowser(browser) {
  const name = browser === 'firefox' ? 'Firefox' : 'Chrome';
  const result = await dialog.showMessageBox({
    type: 'error',
    title: 'Navegador não encontrado',
    message: `${name} não está instalado neste computador.`,
    detail: 'Instale o navegador ou escolha a outra opção.',
    buttons: ['Escolher novamente', 'Fechar'],
    defaultId: 0,
    cancelId: 1,
    noLink: true
  });

  return result.response === 0;
}

function launchFirefox(executable) {
  const profile = path.join(app.getPath('userData'), 'firefox-profile');
  fs.mkdirSync(profile, { recursive: true });

  return spawn(executable, [
    '-no-remote',
    '-profile', profile,
    '--kiosk',
    START_URL
  ], {
    detached: process.platform !== 'win32',
    stdio: 'ignore'
  });
}

function launchChrome(executable) {
  const profile = path.join(app.getPath('userData'), 'chrome-profile');
  fs.mkdirSync(profile, { recursive: true });

  return spawn(executable, [
    `--user-data-dir=${profile}`,
    '--kiosk',
    '--no-first-run',
    '--disable-session-crashed-bubble',
    '--disable-infobars',
    '--autoplay-policy=no-user-gesture-required',
    START_URL
  ], {
    detached: process.platform !== 'win32',
    stdio: 'ignore'
  });
}

function stopBrowser() {
  if (!browserProcess || !browserProcess.pid) return;

  try {
    if (process.platform === 'win32') {
      spawn('taskkill', ['/PID', String(browserProcess.pid), '/T', '/F'], {
        detached: true,
        stdio: 'ignore'
      }).unref();
    } else {
      try {
        process.kill(-browserProcess.pid, 'SIGTERM');
      } catch (_) {
        browserProcess.kill('SIGTERM');
      }
    }
  } catch (_) {}
}

function quitJukebox() {
  if (exitShortcutTimer) {
    clearTimeout(exitShortcutTimer);
    exitShortcutTimer = null;
  }

  try { globalShortcut.unregister('F'); } catch (_) {}
  stopBrowser();

  setTimeout(() => {
    globalShortcut.unregisterAll();
    app.quit();
  }, 350);
}

function registerKioskShortcuts() {
  // Impede Alt+F4 de chegar ao Firefox/Chrome enquanto o launcher estiver ativo.
  globalShortcut.register('Alt+F4', () => {});

  // Sequência de saída: Ctrl + . e, em seguida, F.
  globalShortcut.register('CommandOrControl+.', () => {
    try { globalShortcut.unregister('F'); } catch (_) {}

    globalShortcut.register('F', () => {
      quitJukebox();
    });

    if (exitShortcutTimer) clearTimeout(exitShortcutTimer);
    exitShortcutTimer = setTimeout(() => {
      try { globalShortcut.unregister('F'); } catch (_) {}
      exitShortcutTimer = null;
    }, 2500);
  });
}

async function runLauncher() {
  registerKioskShortcuts();

  let choice = process.argv.includes('--escolher-navegador') ? null : readChoice();

  while (true) {
    if (!choice) choice = await askBrowser();
    if (!choice) {
      globalShortcut.unregisterAll();
      app.quit();
      return;
    }

    const executable = choice === 'firefox' ? findFirefox() : findChrome();
    if (!executable) {
      clearChoice();
      const retry = await showMissingBrowser(choice);
      if (!retry) {
        globalShortcut.unregisterAll();
        app.quit();
        return;
      }
      choice = null;
      continue;
    }

    saveChoice(choice);

    try {
      browserProcess = choice === 'firefox'
        ? launchFirefox(executable)
        : launchChrome(executable);

      // Mantém o launcher residente para bloquear Alt+F4 e ouvir Ctrl+.+F.
      browserProcess.on('error', () => {});
      return;
    } catch (error) {
      clearChoice();
      await dialog.showMessageBox({
        type: 'error',
        title: 'Não foi possível abrir a Jukebox',
        message: 'O navegador não pôde ser iniciado.',
        detail: error && error.message ? error.message : String(error),
        buttons: ['Fechar']
      });
      globalShortcut.unregisterAll();
      app.quit();
      return;
    }
  }
}

app.whenReady().then(runLauncher);

app.on('will-quit', () => {
  globalShortcut.unregisterAll();
});

app.on('window-all-closed', () => {
  // O launcher precisa continuar ativo em segundo plano.
});
