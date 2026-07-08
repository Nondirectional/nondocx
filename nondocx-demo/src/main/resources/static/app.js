// nondocx demo 前端逻辑
//
// 三大块:
//   ① OnlyOffice 装配 + 刷新(Phase 3 / 5)
//   ② Agent 对话 fetch POST + ReadableStream 解析 SSE(Phase 4)
//   ③ 文档上传 / 重置(Phase 6 / 7)

// ============ 全局状态 ============
let docEditor = null;        // 当前 DocsAPI.DocEditor 实例
let baseConfig = null;       // 从 /api/doc/config 拉到的 config 基础字段(fileType/title/permissions/editorConfig)
let chatting = false;        // 对话进行中(禁用输入框)

const messagesEl = document.getElementById('messages');
const docNameEl = document.getElementById('doc-name');
const placeholderEl = document.getElementById('oo-placeholder');
const chatForm = document.getElementById('chat-form');
const chatInput = document.getElementById('chat-input');
const sendBtn = document.getElementById('send-btn');

// ============ OnlyOffice 装配 ============

/**
 * 拉取后端 config 并装配 OnlyOffice 编辑器。
 *
 * <p>OnlyOffice 的核心 API: {@code new DocsAPI.DocEditor(htmlNodeId, config)}。
 * config 里的 {@code document.key} 是缓存版本号——key 变了,OO 就重新拉 document.url。
 * 这正是本 demo 的刷新机制:后端 save 后 bump key,前端用新 key 重建编辑器(Phase 5)。
 */
async function loadOnlyOffice() {
  // 1) 从后端拉 config(含当前 key)
  const resp = await fetch('/api/doc/config');
  const config = await resp.json();
  baseConfig = config;  // 缓存基础字段,刷新时本地拼新 config 省一次往返
  docNameEl.textContent = config.document.title;

  // 2) 装配编辑器
  mountEditor(config);
}

/**
 * 用给定 config 创建 DocsAPI.DocEditor 实例,挂到占位 div。
 *
 * @param config OnlyOffice config(document.key/document.url/editorConfig/events)
 */
function mountEditor(config) {
  // 销毁旧实例(若存在)——OO 不支持直接 reload,必须 destroyEditor + new
  destroyEditorIfAny();

  // 清掉 loading 占位文字(编辑器会自己注入 iframe)
  placeholderEl.innerHTML = '';

  docEditor = new DocsAPI.DocEditor('oo-placeholder', {
    ...config,
    width: '100%',
    height: '100%',
    events: {
      // 兜底:若后端漏推 doc_changed,OO 自己检测到版本变化时会触发此事件
      onRequestRefreshFile: () => {
        console.log('[OO] onRequestRefreshFile —— 兜底刷新触发');
        refreshFromBackend();
      },
      onError: (e) => {
        console.error('[OO] 错误', e);
      },
    },
  });
}

/**
 * 刷新编辑器:从后端拉最新 key,销毁旧实例,重建。
 *
 * <p>这是 Phase 5 刷新机制的入口。后端 save_docx 成功后会推 doc_changed 事件(带新 key),
 * 前端收到后调本函数。也可不依赖事件,直接拉 /api/doc/config 重建。
 */
async function refreshFromBackend(newKey) {
  if (newKey && baseConfig) {
    // 快速路径:用 doc_changed 帧里的新 key 本地拼 config,省一次 GET
    const config = {
      ...baseConfig,
      document: { ...baseConfig.document, key: newKey },
    };
    mountEditor(config);
    return;
  }
  // 慢路径/兜底:重新拉完整 config
  await loadOnlyOffice();
}

/** 销毁当前编辑器实例(幂等)。 */
function destroyEditorIfAny() {
  if (docEditor && typeof docEditor.destroyEditor === 'function') {
    try {
      docEditor.destroyEditor();
    } catch (e) {
      console.warn('[OO] destroyEditor 异常', e);
    }
    docEditor = null;
  }
}

// ============ Agent 对话(POST + ReadableStream 解析 SSE) ============
//
// 为什么不用 EventSource:浏览器原生 EventSource 只支持 GET,消息只能走 query string
// (中文要 URL 编码、长度受限)。这里用 fetch POST + ReadableStream 手动解析 SSE 流,
// POST body 传消息,响应是 data: {json}\n\n 流——工业界 LLM 对话框的标准做法。

chatForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  const message = chatInput.value.trim();
  if (!message || chatting) return;

  // 1) 渲染用户消息
  appendMsg('user', message);
  chatInput.value = '';
  setChatting(true);

  // 2) 按 SSE 到达顺序渲染助手文字与工具卡片。
  // text 连续到达时续写当前气泡；工具调用后再来的 text 会新建气泡，保持时间轴顺序。
  const assistantState = { currentTextEl: null };

  try {
    // 3) POST 消息,拿 ReadableStream
    const resp = await fetch('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message }),
    });
    if (!resp.ok) {
      const err = await resp.text();
      appendMsg('error', '请求失败:' + err);
      return;
    }

    // 4) 解析 SSE 流:按 "\n\n" 分帧,每帧是 "data: {json}"
    const reader = resp.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      // 按 "\n\n" 切帧(SSE 帧分隔符)
      let sep;
      while ((sep = buffer.indexOf('\n\n')) >= 0) {
        const frame = buffer.slice(0, sep);
        buffer = buffer.slice(sep + 2);
        handleSseFrame(frame, assistantState);
      }
    }
  } catch (err) {
    appendMsg('error', '网络错误:' + err.message);
    console.error(err);
  } finally {
    setChatting(false);
  }
});

/**
 * 处理一个 SSE 帧(形如 "data: {...}")。
 *
 * @param frame 原始帧文本(如 'data: {"type":"text","delta":"你"}')
 * @param assistantState 当前助手渲染状态
 */
function handleSseFrame(frame, assistantState) {
  // 提取 data: 后的 JSON
  const match = frame.match(/^data:\s*(.+)$/s);
  if (!match) return;
  let data;
  try {
    data = JSON.parse(match[1]);
  } catch (e) {
    console.warn('解析 SSE 帧失败:', frame, e);
    return;
  }
  switch (data.type) {
    case 'text':
      // 追加到当前助手气泡(打字机效果)；若前一个事件是工具调用，则新建气泡。
      appendAssistantText(assistantState, data.delta || '');
      scrollToBottom();
      break;
    case 'tool_start':
      // 工具调用:在助手气泡后插一条灰条
      const tcStart = document.createElement('div');
      tcStart.className = 'tool-call';
      tcStart.textContent = '🔧 ' + data.name + '(' + (data.arguments || '') + ')';
      tcStart.dataset.toolName = data.name;
      messagesEl.appendChild(tcStart);
      assistantState.currentTextEl = null;
      scrollToBottom();
      break;
    case 'tool_end':
      // 找到对应的 tool_start 条,补上结果
      const tcEnd = [...messagesEl.querySelectorAll('.tool-call')].pop();
      if (tcEnd && tcEnd.dataset.toolName === data.name) {
        const result = document.createElement('div');
        result.className = 'tool-result';
        result.textContent = '→ ' + (data.result || '');
        tcEnd.appendChild(result);
      }
      break;
    case 'doc_changed':
      // Phase 5:Agent save_docx 成功,用新 key 刷新 OnlyOffice
      console.log('[刷新] save_docx 成功,新 key:', data.key);
      refreshFromBackend(data.key);
      break;
    case 'error':
      appendMsg('error', data.message || '未知错误');
      break;
    case 'done':
      break;
  }
}

// ============ DOM 辅助 ============

function appendAssistantText(state, delta) {
  if (!delta) return;
  if (!state.currentTextEl) {
    state.currentTextEl = appendMsg('assistant', '');
  }
  state.currentTextEl.appendChild(document.createTextNode(delta));
}

function appendMsg(cls, text) {
  const div = document.createElement('div');
  div.className = 'msg ' + cls;
  if (text) div.textContent = text;
  messagesEl.appendChild(div);
  scrollToBottom();
  return div;
}

function setChatting(on) {
  chatting = on;
  chatInput.disabled = on;
  sendBtn.disabled = on;
  if (!on) chatInput.focus();
}

function scrollToBottom() {
  messagesEl.scrollTop = messagesEl.scrollHeight;
}

// ============ 文档上传 / 重置(Phase 6 / 7) ============

const uploadBtn = document.getElementById('upload-btn');
const resetBtn = document.getElementById('reset-btn');
const fileInput = document.getElementById('file-input');

// 「上传文档」按钮 → 触发隐藏的 file input
uploadBtn.addEventListener('click', () => fileInput.click());

// 选了文件 → 上传
fileInput.addEventListener('change', async () => {
  const file = fileInput.files[0];
  if (!file) return;
  fileInput.value = ''; // 重置,允许重复选同一文件

  uploadBtn.disabled = true;
  const originalText = uploadBtn.textContent;
  uploadBtn.textContent = '上传中…';

  try {
    const formData = new FormData();
    formData.append('file', file);
    const resp = await fetch('/api/doc/upload', { method: 'POST', body: formData });
    const data = await resp.json();
    if (!resp.ok || !data.ok) {
      appendMsg('error', '上传失败:' + (data.error || '未知错误'));
      return;
    }
    // 成功:清掉对话历史(Agent 记忆已在后端清空),刷新 OnlyOffice
    clearChatHistory();
    docNameEl.textContent = data.filename;
    refreshFromBackend(data.key);
    appendMsg('system', '已加载文档:' + data.filename + '。可以开始对话了。');
  } catch (e) {
    appendMsg('error', '上传出错:' + e.message);
  } finally {
    uploadBtn.disabled = false;
    uploadBtn.textContent = originalText;
  }
});

// 「重置样例」按钮
resetBtn.addEventListener('click', async () => {
  resetBtn.disabled = true;
  try {
    const resp = await fetch('/api/doc/reset', { method: 'POST' });
    const data = await resp.json();
    if (!resp.ok || !data.ok) {
      appendMsg('error', '重置失败:' + (data.error || '未知错误'));
      return;
    }
    clearChatHistory();
    docNameEl.textContent = data.filename;
    refreshFromBackend(data.key);
    appendMsg('system', '已恢复内置样例文档。');
  } catch (e) {
    appendMsg('error', '重置出错:' + e.message);
  } finally {
    resetBtn.disabled = false;
  }
});

/** 清空对话历史(Agent 记忆已在后端 clearMemory,前端同步清显示)。 */
function clearChatHistory() {
  // 保留系统欢迎语,清掉其余消息
  messagesEl.innerHTML = '';
  const welcome = document.createElement('div');
  welcome.className = 'msg system';
  messagesEl.appendChild(welcome);
}

// ============ 启动 ============
// 确认 OnlyOffice api.js 已加载(DocsAPI 全局存在)
if (typeof DocsAPI === 'undefined') {
  placeholderEl.innerHTML =
    '⚠️ 无法连接 OnlyOffice Document Server。<br>请先启动:<br>' +
    '<code>docker run -d --name oo-docs -p 9090:80 ' +
    '-e JWT_ENABLED=false onlyoffice/documentserver</code>';
} else {
  loadOnlyOffice().catch((e) => {
    placeholderEl.textContent = '加载文档失败:' + e.message;
    console.error(e);
  });
}

// 检查 Agent 是否可用(API key 是否配置),不可用则显示提示条
fetch('/api/status')
  .then((r) => r.json())
  .then((data) => {
    if (!data.agentEnabled) {
      document.getElementById('api-key-banner').hidden = false;
    }
  })
  .catch((e) => console.warn('检查状态失败', e));
