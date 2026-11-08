(function () {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const THEME_KEY = 'harness-phone-theme';
  const native = window.HarnessNative || {};
  let themePref = 'system';
  let shellMessage = '';
  let status = { state: 'Stopped' };
  let config = { mode: 'direct', target: '', code: '' };
  let deviceSignature = '';
  let pollTimer = null;
  let uiLanguage = 'zh';
  let fontSize = 14;
  try {
    if (native.getUiLanguage) uiLanguage = native.getUiLanguage() === 'en' ? 'en' : 'zh';
    if (native.getFontSize) fontSize = parseInt(native.getFontSize(), 10) || 14;
  } catch (_) {}
  fontSize = Math.max(10, Math.min(28, fontSize));
  document.documentElement.style.setProperty('--offline-font-size', fontSize + 'px');

  function applyOfflineFont(value) {
    fontSize = Math.max(10, Math.min(28, parseInt(value, 10) || 14));
    document.documentElement.style.setProperty('--offline-font-size', fontSize + 'px');
    try { if (native.setFontSize) native.setFontSize(fontSize); } catch (_) {}
    const readout = $('offlineFontSize');
    if (readout) readout.textContent = String(fontSize);
  }

  const OFFLINE_EN = {
    '设置': 'Settings',
    '外观': 'Appearance',
    '浅色': 'Light',
    '深色': 'Dark',
    '跟随系统': 'System',
    '连接 HARNESS': 'Connect to HARNESS',
    '连接设置': 'Connection settings',
    '读取中…': 'Reading…',
    '登录 Tailscale': 'Sign in to Tailscale',
    '退出登录': 'Sign out',
    '连接方式': 'Connection method',
    '内置 Tailscale': 'Built-in Tailscale',
    '局域网直连': 'Direct LAN',
    '登录后自动读取 Tailscale 设备。': 'Devices appear after you sign in.',
    '手动输入 IP（可选）': 'Enter IP manually (optional)',
    '配对码': 'Pairing code',
    '保存并连接': 'Save and connect',
    '当前连接': 'Current connection',
    '尚未连接': 'Not connected',
    '刷新页面': 'Refresh',
    '重新配对': 'Re-pair',
    '探索未至之境': 'Explore the Unknown',
    '预览版': 'Preview',
    '默认工作区': 'Default workspace',
    '标准模式': 'Standard mode',
    '描述你想要构建的内容…': 'Describe what you want to build…',
    '关闭': 'Close',
    '账户与余额': 'Account & balance',
    '未登录 · 请先连接': 'Not signed in · Connect first',
    '账号与余额': 'Account & balance',
    '通用设置': 'General',
    '内置插件': 'Built-in plugins',
    'Agent 预设': 'Agent presets',
    '通用': 'General',
    '权限': 'Permission',
    '工作区内修改': 'Modify in workspace',
    '工作步骤展示': 'Work step details',
    '详细': 'Detailed',
    '工作步骤收起时机': 'Collapse work steps',
    '回答结束后': 'After response',
    '显示代码工作视图': 'Show code workspace',
    '繁忙时的发送行为': 'Send behavior when busy',
    '排队发送': 'Queue message',
    '性能与用量': 'Performance & usage',
    '上传 Session Log': 'Upload Session Log',
    '模型': 'Models',
    '连接后显示电脑上的模型与推理等级。': 'Models and reasoning levels appear after connecting.',
    '选择模型': 'Choose model',
    '连接后管理电脑上的插件。': 'Manage desktop plugins after connecting.',
    '插件列表': 'Plugin list',
    '连接后选择 Agent 预设。': 'Choose an Agent preset after connecting.',
    '选择预设': 'Choose preset',
    '更多账号信息': 'More account info',
    '充值余额': 'Top up balance',
    '查询用量': 'Usage',
    '已安装插件': 'Installed plugins',
    '插件市场': 'Plugin marketplace',
    '推理等级': 'Reasoning level',
    '默认预设': 'Default preset',
    '代码代理': 'Code agent',
    '语言': 'Language',
    '中文': 'Chinese',
    '请先连接': 'Please connect first',
    '下载的文件': 'Downloaded files',
    '用文件管理器打开下载文件夹': 'Open the Downloads folder in your file manager.',
    '暂无会话': 'No conversations',
    '连接后显示电脑上的会话': 'Conversations appear after connecting.',
    '更多': 'More',
    '新建会话': 'New conversation',
    '插件': 'Plugins',
    '自动化任务': 'Automation',
    '会话': 'Conversations',
    '搜索会话': 'Search conversations',
    '未登录': 'Not signed in',
    '点击进入设置': 'Open settings',
    '在线': 'Online',
    '离线': 'Offline',
    '刷新页面': 'Refresh',
    '重新配对': 'Re-pair',
    '设备': 'Device',
  };

  function localizeOffline() {
    if (uiLanguage !== 'en') return;
    document.documentElement.lang = 'en';
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    let node;
    while ((node = walker.nextNode())) {
      const raw = node.nodeValue || '';
      const key = raw.trim();
      if (!key || !OFFLINE_EN[key]) continue;
      node.nodeValue = raw.replace(key, OFFLINE_EN[key]);
    }
    document.querySelectorAll('[placeholder],[aria-label]').forEach((element) => {
      ['placeholder', 'aria-label'].forEach((attribute) => {
        const value = element.getAttribute(attribute);
        if (value && OFFLINE_EN[value]) element.setAttribute(attribute, OFFLINE_EN[value]);
      });
    });
  }

  function call(method, ...args) {
    try {
      if (native && typeof native[method] === 'function') return native[method](...args);
    } catch (_) {}
    return undefined;
  }

  function readJson(method, fallback) {
    try {
      const raw = call(method);
      if (raw) return JSON.parse(raw);
    } catch (_) {}
    return fallback;
  }

  function systemDark() {
    const nativeTheme = call('systemTheme');
    if (nativeTheme === 'dark') return true;
    if (nativeTheme === 'light') return false;
    return !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
  }

  function effectiveDark() {
    if (themePref === 'dark') return true;
    if (themePref === 'light') return false;
    return systemDark();
  }

  function applyTheme() {
    const dark = effectiveDark();
    document.body.dataset.theme = dark ? 'dark' : 'light';
    document.documentElement.style.colorScheme = dark ? 'dark' : 'light';
    document.querySelectorAll('[data-theme-choice]').forEach((button) => {
      button.classList.toggle('selected', button.getAttribute('data-theme-choice') === themePref);
    });
    call('setSystemBarsDark', dark);
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute('content', dark ? '#151517' : '#ffffff');
  }

  function setTheme(value) {
    themePref = value === 'light' || value === 'dark' ? value : 'system';
    try { localStorage.setItem(THEME_KEY, themePref); } catch (_) {}
    call('setThemePreference', themePref);
    applyTheme();
  }

  function loadTheme() {
    let saved = '';
    try { saved = localStorage.getItem(THEME_KEY) || ''; } catch (_) {}
    const nativeSaved = call('getThemePreference');
    if (nativeSaved === 'light' || nativeSaved === 'dark' || nativeSaved === 'system') saved = nativeSaved;
    themePref = saved || 'system';
    applyTheme();
  }

  function toast(text) {
    const node = $('toast');
    if (!node) return;
    node.textContent = text;
    node.classList.add('show');
    clearTimeout(node.__timer);
    node.__timer = setTimeout(() => node.classList.remove('show'), 2400);
  }

  function openSettings() {
    $('settingsOverlay').classList.remove('hidden');
    selectSettingsTab('general');
  }

  function closeSettings() {
    $('settingsOverlay').classList.add('hidden');
  }

  function currentTarget() {
    if ($('manualCheck').checked) return $('manualTarget').value.trim();
    const selected = document.querySelector('input[name="hp-device"]:checked');
    return selected ? selected.value : '';
  }

  function currentMode() {
    const selected = document.querySelector('#modeSwitch [data-mode].selected');
    return selected ? selected.getAttribute('data-mode') : 'tsnet';
  }

  function selectMode(mode) {
    document.querySelectorAll('#modeSwitch [data-mode]').forEach((button) => {
      button.classList.toggle('selected', button.getAttribute('data-mode') === mode);
    });
    if (mode === 'direct') {
      $('manualCheck').checked = true;
      $('manualTarget').style.display = '';
    }
  }

  function setMessage(message) {
    shellMessage = message || '';
    renderStatus();
    // The status card sits behind the settings sheet, so mirror the message
    // into the panel: the user may well be editing the settings right now.
    const notice = $('connNotice');
    if (notice) {
      notice.textContent = shellMessage;
      notice.classList.toggle('hidden', !shellMessage);
    }
  }

  function renderStatus() {
    const state = status.state || 'Stopped';
    const running = state === 'Running';
    const account = status.account || '';
    const statusText = $('statusText');
    const hint = $('statusHint');
    const card = $('offlineCard');
    let title = '';
    let detail = '';
    let busy = false;
    if (shellMessage) {
      const lines = shellMessage.split('\n').filter(Boolean);
      title = lines[0] || '';
      detail = lines.length > 1 ? lines.slice(1).join('\n') : '';
      busy = true;
    } else if (running) {
      title = (uiLanguage === 'en' ? 'Tailscale connected' : 'Tailscale 已连接') + (account ? ' · ' + account : '');
      detail = uiLanguage === 'en' ? 'Loading the HARNESS page…' : '正在加载电脑上的 HARNESS 页面…';
      busy = true;
    } else if (state === 'Starting') {
      title = uiLanguage === 'en' ? 'Restoring the Tailscale sign-in…' : '正在恢复 Tailscale 登录…';
      detail = uiLanguage === 'en' ? 'The saved sign-in is coming back, this takes a moment.' : '已保存的登录信息正在恢复，请稍候。';
      busy = true;
    } else if (state === 'NeedsLogin' || status.authUrl) {
      title = uiLanguage === 'en' ? 'Tailscale sign-in required' : '需要登录 Tailscale';
      detail = uiLanguage === 'en' ? 'Sign in once and the phone connects by itself from then on.' : '登录一次之后，手机就能自己连上电脑。';
    } else {
      title = uiLanguage === 'en' ? 'Not connected yet' : '尚未连接';
      detail = uiLanguage === 'en' ? 'Set the computer address or sign in to Tailscale to start.' : '配置电脑地址或登录 Tailscale 后开始。';
    }
    if (statusText) statusText.textContent = title;
    if (hint) hint.textContent = detail;
    if (card) {
      card.classList.toggle('is-idle', !busy);
      card.hidden = false;
    }
    $('tsStatus').textContent = running
      ? ((uiLanguage === 'en' ? 'Connected' : '已连接') + (account ? ' · ' + account : ''))
      : (state === 'Starting' ? (uiLanguage === 'en' ? 'Restoring…' : '正在恢复…')
        : (state === 'NeedsLogin' || status.authUrl ? (uiLanguage === 'en' ? 'Sign-in required' : '需要登录') : state));
    $('loginBtn').classList.toggle('hidden', running);
    $('logoutBtn').classList.toggle('hidden', !running);
    const quickLogin = $('quickLogin');
    if (quickLogin) {
      quickLogin.classList.toggle('hidden', running || !(state === 'NeedsLogin' || status.authUrl));
    }
    const modeLabel = $('modeLabel');
    if (modeLabel) {
      modeLabel.textContent = (!config.target && !config.code && state === 'Stopped')
        ? (uiLanguage === 'en' ? 'Not connected' : '尚未连接')
        : (running ? (uiLanguage === 'en' ? 'Tailscale connected' : 'Tailscale 已连接')
          : (currentMode() === 'tsnet' ? (uiLanguage === 'en' ? 'Built-in Tailscale' : '内置 Tailscale') : (uiLanguage === 'en' ? 'Direct LAN' : '局域网直连')));
    }
    $('currentConnection').textContent = config.target
      ? (config.mode === 'tsnet' ? 'Tailscale · ' : (uiLanguage === 'en' ? 'Direct · ' : '直连 · ')) + config.target
      : (uiLanguage === 'en' ? 'Not connected' : '尚未连接');
    renderDevices();
    if (uiLanguage === 'en') localizeOffline();
  }

  function renderDevices() {
    const box = $('devices');
    const devices = Array.isArray(status.devices) ? status.devices : [];
    const signature = JSON.stringify(devices);
    if (signature !== deviceSignature) {
      deviceSignature = signature;
      box.innerHTML = '';
      devices.forEach((device, index) => {
        const label = document.createElement('label');
        label.className = 'device';
        const checked = config.target === device.address || (!config.target && index === 0);
        label.innerHTML = '<input type="radio" name="hp-device" value="' + escapeHtml(device.address) + '"' + (checked ? ' checked' : '') + '>' +
          '<span><b>' + escapeHtml(device.name || device.dnsName || device.address) + '</b><small>' +
          escapeHtml(device.address) + (device.online ? (uiLanguage === 'en' ? ' · Online' : ' · 在线') : (uiLanguage === 'en' ? ' · Offline' : ' · 离线')) + '</small></span>';
        box.appendChild(label);
      });
    }
    const hasDevices = devices.length > 0;
    $('deviceHint').textContent = hasDevices
      ? (uiLanguage === 'en' ? ('Found ' + devices.length + ' device(s) from your Tailscale account.') : ('从 Tailscale 账号读取到 ' + devices.length + ' 台设备。'))
      : (uiLanguage === 'en' ? 'Devices appear after sign-in; you can also connect directly.' : '登录后自动读取 Tailscale 设备；也可以手动直连。');
  }

  function escapeHtml(value) {
    return String(value || '').replace(/[&<>"']/g, (ch) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[ch]));
  }

  function loadConfig() {
    config = readJson('connectionConfig', {});
    if (!config.mode) config.mode = 'direct';
    if (!config.target) config.target = '';
    if (!config.code) config.code = '';
    if (!$('pairCode').value && config.code) $('pairCode').value = config.code;
    if (!$('manualTarget').value && config.target) {
      $('manualTarget').value = config.target;
    }
    $('manualCheck').checked = config.mode === 'direct' && !!config.target;
    selectMode(config.mode === 'direct' ? 'direct' : 'tsnet');
  }

  function refresh() {
    status = readJson('tsnetStatus', { state: 'Stopped' });
    renderStatus();
  }

  function connect() {
    const code = $('pairCode').value.trim();
    if (!/^\d{6}$/.test(code)) {
      toast('请输入 6 位配对码');
      $('pairCode').focus();
      return;
    }
    const manual = $('manualCheck').checked;
    const target = currentTarget();
    const mode = currentMode();
    if (!target) {
      toast(manual ? '请输入电脑 IP 或地址' : '请先在设备列表里选择电脑');
      return;
    }
    if (mode === 'direct') {
      call('connectDirect', target, code);
      toast('正在直接连接 ' + target + '…');
      return;
    }
    if ((status.state || '') !== 'Running') {
      toast('请先登录 Tailscale，等状态变为已连接');
      return;
    }
    call('tsnetConnect', target, code);
    toast('正在通过 Tailscale 连接…');
  }

  function setSidebarOpen(open) {
    document.body.classList.toggle('side-open', open);
    const backdrop = $('sideBackdrop');
    if (backdrop) backdrop.hidden = !open;
    const drawer = $('sideDrawer');
    if (drawer) drawer.setAttribute('aria-hidden', open ? 'false' : 'true');
  }

  function selectSettingsTab(name) {
    document.querySelectorAll('[data-settings-tab]').forEach((button) => {
      button.classList.toggle('selected', button.getAttribute('data-settings-tab') === name);
    });
    document.querySelectorAll('[data-settings-panel]').forEach((panel) => {
      panel.hidden = panel.getAttribute('data-settings-panel') !== name;
    });
  }

  function bind() {
    const menuButton = $('menuButton');
    if (menuButton) menuButton.addEventListener('click', () => setSidebarOpen(true));
    const backdrop = $('sideBackdrop');
    if (backdrop) backdrop.addEventListener('click', () => setSidebarOpen(false));
    $('openSettings').addEventListener('click', () => {
      setSidebarOpen(false);
      openSettings();
    });
    document.querySelectorAll('[data-settings-tab]').forEach((button) => {
      button.addEventListener('click', () => selectSettingsTab(button.getAttribute('data-settings-tab')));
    });
    document.querySelectorAll('[data-online-only]').forEach((element) => {
      element.addEventListener('click', () => {
        toast(uiLanguage === 'en' ? 'Please connect first' : '请先连接');
      });
    });
    document.querySelectorAll('[data-requires-online]').forEach((element) => {
      element.addEventListener('click', () => {
        toast(uiLanguage === 'en' ? 'Please connect first' : '请先连接');
      });
    });
    document.querySelectorAll('[data-language]').forEach((button) => {
      const language = button.getAttribute('data-language');
      button.classList.toggle('selected', language === uiLanguage);
      button.addEventListener('click', () => {
        try {
          if (native.setUiLanguage) native.setUiLanguage(language);
        } catch (_) {}
        window.location.reload();
      });
    });
    const languageSelect = $('languageSelect');
    const languageMenu = $('languageMenu');
    const languageLabel = $('languageLabel');
    if (languageLabel) languageLabel.textContent = uiLanguage === 'en' ? 'English' : '中文';
    if (languageSelect && languageMenu) {
      languageSelect.addEventListener('click', (event) => {
        event.stopPropagation();
        const open = languageMenu.classList.contains('hidden');
        languageMenu.classList.toggle('hidden', !open);
        languageSelect.setAttribute('aria-expanded', open ? 'true' : 'false');
      });
      document.addEventListener('click', (event) => {
        if (languageMenu.contains(event.target) || languageSelect.contains(event.target)) return;
        languageMenu.classList.add('hidden');
        languageSelect.setAttribute('aria-expanded', 'false');
      });
    }
    const versionNode = $('appVersion');
    if (versionNode) {
      const version = call('appVersion');
      versionNode.textContent = version || '—';
    }
    const updateButton = $('checkUpdateBtn');
    if (updateButton) {
      updateButton.addEventListener('click', () => {
        call('checkUpdate');
        toast(uiLanguage === 'en' ? 'Checking for updates…' : '正在检查更新…');
      });
    }
    const fontReadout = $('offlineFontSize');
    if (fontReadout) fontReadout.textContent = String(fontSize);
    document.querySelectorAll('[data-font-step]').forEach((button) => {
      button.addEventListener('click', () => {
        applyOfflineFont(fontSize + Number(button.getAttribute('data-font-step')));
      });
    });
    const downloadsButton = $('offlineOpenDownloads');
    if (downloadsButton) {
      downloadsButton.addEventListener('click', () => {
        try {
          if (native.openDownloadsFolder) native.openDownloadsFolder();
          else toast(uiLanguage === 'en' ? 'File manager unavailable' : '无法打开文件管理器');
        } catch (_) {
          toast(uiLanguage === 'en' ? 'File manager unavailable' : '无法打开文件管理器');
        }
      });
    }
    const quickConnect = $('quickConnect');
    if (quickConnect) quickConnect.addEventListener('click', openSettings);
    const quickLogin = $('quickLogin');
    if (quickLogin) {
      quickLogin.addEventListener('click', () => {
        call('tsnetLogin');
        toast('正在打开 Tailscale 登录页…');
      });
    }
    $('closeSettings').addEventListener('click', closeSettings);
    $('settingsOverlay').addEventListener('click', (event) => {
      if (event.target === $('settingsOverlay')) closeSettings();
    });
    document.querySelectorAll('[data-theme-choice]').forEach((button) => {
      button.addEventListener('click', () => setTheme(button.getAttribute('data-theme-choice')));
    });
    $('loginBtn').addEventListener('click', () => {
      call('tsnetLogin');
      toast('正在打开 Tailscale 登录页…');
    });
    $('logoutBtn').addEventListener('click', () => {
      call('tsnetLogout');
      toast('已退出 Tailscale');
    });
    $('connectBtn').addEventListener('click', connect);
    $('refreshBtn').addEventListener('click', () => call('refreshPage'));
    $('repairBtn').addEventListener('click', () => call('rePair'));
    $('manualCheck').addEventListener('change', () => {
      $('manualTarget').style.display = $('manualCheck').checked ? '' : 'none';
    });
    document.querySelectorAll('#modeSwitch [data-mode]').forEach((button) => {
      button.addEventListener('click', () => {
        selectMode(button.getAttribute('data-mode'));
        renderStatus();
      });
    });
    window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
      if (themePref === 'system') applyTheme();
    });
  }

  window.HarnessOffline = {
    setMessage,
    refresh,
    openSettings
  };

  loadTheme();
  localizeOffline();
  selectSettingsTab('general');
  bind();
  loadConfig();
  refresh();
  setTimeout(() => call('autoConnectFromShell'), 300);
  setInterval(() => {
    if (!document.hidden) call('autoConnectFromShell');
  }, 1000);
  $('manualTarget').style.display = $('manualCheck').checked ? '' : 'none';
  pollTimer = setInterval(refresh, 1500);
})();
