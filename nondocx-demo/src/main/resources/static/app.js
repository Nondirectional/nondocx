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
 * <p>这是 Phase 5 刷新机制的入口。后端受限保存成功后会推 doc_changed 事件(带新 key),
 * 前端收到后调本函数。也可不依赖事件,直接拉 /api/doc/config 重建。
 */
async function refreshFromBackend(newKey) {
  if (newKey && baseConfig) {
    // 快速路径:用 doc_changed 帧里的新 key 本地拼 config,省一次 GET
    const documentUrl = urlWithKey(baseConfig.document.url, newKey);
    const config = {
      ...baseConfig,
      document: { ...baseConfig.document, key: newKey, url: documentUrl },
    };
    baseConfig = config;
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

function urlWithKey(url, key) {
  const u = new URL(url, window.location.href);
  u.searchParams.set('key', key);
  return u.toString();
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
  const cancelButton = document.createElement('button');
  cancelButton.type = 'button';
  cancelButton.className = 'tool-btn';
  cancelButton.textContent = '取消';
  cancelButton.addEventListener('click', async () => {
    cancelButton.disabled = true;
    await fetch('/api/cancel', { method: 'POST' });
  });
  chatForm.appendChild(cancelButton);

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
    cancelButton.remove();
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
  reduceTimelineEvent(data, false);
  switch (data.type) {
    case 'doc_changed': {
      // save 成功，用新 key 刷新 OnlyOffice
      console.log('[刷新] save 成功,新 key:', data.key);
      refreshFromBackend(data.key);
      break;
    }
  }
}

// ============ SubAgent 实施时间线 reducer ============

const timelineRuns = new Map();

function runFor(data, replay) {
  const id = data.turnId || 'history';
  if (!timelineRuns.has(id)) {
    timelineRuns.set(id, { id, replay, status: 'consulting', events: [], traces: new Map(), token: null, assignments: [], operations: [] });
  }
  return timelineRuns.get(id);
}

function reduceTimelineEvent(data, replay) {
  if (!data || !data.type || data.type === 'done' || data.type === 'doc_changed') return;
  const run = runFor(data, replay);
  run.events.push(data);
  switch (data.type) {
    case 'assistant': run.reply = data.message || ''; break;
    case 'subagent_result':
      run.status = data.success ? 'completed' : 'rolled_back';
      run.result = data.error || '';
      run.quality = data.qualityReport || '';
      break;
    case 'error': run.status = 'blocked'; run.message = data.message || '未知错误'; break;
    case 'trace': {
      if (data.event === 'tool_start' && data.tool === 'invoke_subagent') run.status = 'executing';
      const agent = data.agent || 'unknown';
      if (!run.traces.has(agent)) run.traces.set(agent, []);
      run.traces.get(agent).push(data);
      break;
    }
  }
  renderTimelineRun(run);
}

function renderTimelineRun(run) {
  let card = document.getElementById('timeline-' + run.id);
  if (!card) {
    card = document.createElement('details');
    card.id = 'timeline-' + run.id;
    card.className = 'timeline-run';
    messagesEl.appendChild(card);
  }
  card.open = !run.replay && !['completed', 'blocked', 'rolled_back'].includes(run.status);
  card.innerHTML = '';
  const summary = document.createElement('summary');
  summary.textContent = statusLabel(run.status) + ' · ' + run.id;
  summary.className = 'timeline-summary ' + run.status;
  card.appendChild(summary);
  addTimelineText(card, '主 Agent', run.reply);
  addTimelineText(card, '实施结果', run.result || run.message);
  addTimelineText(card, '质量验收', run.quality);
  for (const [agent, events] of run.traces) renderTimelineTrace(card, agent, events, card.open);
  scrollToBottom();
}

function statusLabel(status) {
  return ({ consulting: '处理中', executing: '实施中', completed: '已实施', blocked: '已阻断', rolled_back: '已回滚' })[status] || status;
}

function addTimelineText(card, title, value) {
  if (!value) return;
  const section = document.createElement('section'); section.className = 'timeline-section';
  const label = document.createElement('strong'); label.textContent = title;
  const pre = document.createElement('pre'); pre.textContent = value;
  section.append(label, pre); card.appendChild(section);
}

function renderTimelineTrace(card, agent, events, open) {
  const details = document.createElement('details'); details.className = 'timeline-trace'; details.open = open;
  const summary = document.createElement('summary'); summary.textContent = 'Trace · ' + agent; details.appendChild(summary);
  const pre = document.createElement('pre');
  pre.textContent = events.map(e => e.event === 'prompt' ? '[Prompt]\n' + (e.prompt || '') : e.event === 'tool_start' ? '[工具] ' + e.tool + '\n' + (e.arguments || '') : e.event === 'tool_end' ? '[结果] ' + e.tool + '\n' + (e.result || '') : e.event === 'thinking_delta' ? e.delta || '' : e.event === 'content_delta' ? e.delta || '' : '[' + e.event + ']').join('');
  details.appendChild(pre); card.appendChild(details);
}

// ============ DOM 辅助 ============

/**
 * 处理 step 帧：按 turnId 创建或更新进度卡。
 *
 * <p>每个 step 帧携带 turnId（关联同一张卡）、phase（analyze/plan/commit）、status、title。
 * 前端用 turnId 缓存当前卡片引用，收到新 step 时追加或更新对应行。
 *
 * <p>进度卡结构：
 * <pre>
 * ┌ progress-card ─────────────────────┐
 * │ ✅ 分析文档结构                     │
 * │    4 个段落，1 个表格                │
 * │ ✅ 生成编辑计划                      │
 * │    └ 插入 H1 标题「项目周报」，居中    │
 * │ ⏳ 执行中...                        │
 * └────────────────────────────────────┘
 * </pre>
 */

// turnId → 卡片 DOM 元素的缓存
const progressCards = {};

function handleStepFrame(data) {
  const turnId = data.turnId;
  // 首次收到该 turnId 的帧：创建新卡片
  if (!progressCards[turnId]) {
    const card = document.createElement('div');
    card.className = 'progress-card';
    messagesEl.appendChild(card);
    progressCards[turnId] = card;
  }
  const card = progressCards[turnId];

  // 把上一行的 active 改为 done（视觉收尾）
  const prevActive = card.querySelector('.step-row.active');
  if (prevActive) {
    prevActive.classList.remove('active');
    prevActive.classList.add('done');
    const icon = prevActive.querySelector('.step-icon');
    if (icon) icon.textContent = '✅';
  }

  // 创建当前阶段行
  const row = document.createElement('div');
  const failed = data.status === 'failed';
  row.className = 'step-row ' + (failed ? 'failed' : 'done');
  row.dataset.phase = data.phase;

  // 图标
  const icon = document.createElement('span');
  icon.className = 'step-icon';
  icon.textContent = failed ? '❌' : '✅';
  row.appendChild(icon);

  // 标题
  const title = document.createElement('span');
  title.className = 'step-title';
  title.textContent = failed ? data.title : data.title;
  row.appendChild(title);

  // detail（分析摘要 / 提交结果）
  if (data.detail) {
    const detail = document.createElement('div');
    detail.className = 'step-detail';
    detail.textContent = data.detail;
    row.appendChild(detail);
  }

  // 操作清单（计划阶段）
  if (data.operations && data.operations.length > 0) {
    const opsEl = document.createElement('div');
    opsEl.className = 'step-ops';
    for (const op of data.operations) {
      const opEl = document.createElement('div');
      opEl.className = 'step-op';
      const opIcon = op.status === 'approved' ? '▸' :
                     op.status === 'warned' ? '⚠' :
                     op.status === 'blocked' ? '✗' :
                     op.status === 'skipped' ? '⊘' : '▸';
      opEl.textContent = opIcon + ' ' + op.description;
      opsEl.appendChild(opEl);
    }
    row.appendChild(opsEl);
  }

  // 失败原因
  if (failed && data.error) {
    const errEl = document.createElement('div');
    errEl.className = 'step-error';
    errEl.textContent = data.error;
    row.appendChild(errEl);
  }

  card.appendChild(row);
  scrollToBottom();
}

// ============ LLM trace 折叠区 ============
//
// trace 帧穿插在 PLAN 阶段进行中（prompt → thinking_delta×N → content_delta×N → complete），
// 与阶段级 step 帧共存。前端在 progress-card 内为 LLM 推理过程渲染一个折叠区：
//   <details class="trace-panel">
//     <summary>LLM 推理过程</summary>
//     <div class="trace-tabs"><button>Response</button><button>Thinking</button></div>
//     <pre class="trace-prompt">...</pre>            ← prompt 只读块（可复制）
//     <div class="trace-response"></div>             ← response 逐字追加
//     <div class="trace-thinking" hidden></div>      ← thinking 逐字追加（默认隐藏）
//   </details>
// 按 turnId + agent 缓存，确保同一专家的 delta 续写同一区域。

// trace 折叠区缓存：key = turnId + '|' + agent
const tracePanels = {};

function handleTraceFrame(data) {
  const turnId = data.turnId;
  const agent = data.agent || 'unknown';
  const cacheKey = turnId + '|' + agent;

  // 确保对应的 progress-card 存在（trace 可能在 step 之前到达）
  if (!progressCards[turnId]) {
    const card = document.createElement('div');
    card.className = 'progress-card';
    messagesEl.appendChild(card);
    progressCards[turnId] = card;
  }
  const card = progressCards[turnId];

  // 首次收到该专家的 trace：在卡片内创建折叠区
  if (!tracePanels[cacheKey]) {
    const panel = buildTracePanel(agent);
    card.appendChild(panel.el);
    tracePanels[cacheKey] = panel;
  }
  const panel = tracePanels[cacheKey];

  switch (data.event) {
    case 'prompt':
      panel.promptEl.textContent = data.prompt || '';
      break;
    case 'content_delta':
      panel.responseEl.append(document.createTextNode(data.delta || ''));
      panel.showTab('response');
      break;
    case 'thinking_delta':
      panel.thinkingEl.append(document.createTextNode(data.delta || ''));
      break;
    case 'tool_start':
      panel.responseEl.append(document.createTextNode('\n[调用工具] ' + (data.tool || '') + '\n' + (data.arguments || '') + '\n'));
      break;
    case 'tool_end':
      panel.responseEl.append(document.createTextNode('[工具结果] ' + (data.tool || '') + '\n' + (data.result || '') + '\n'));
      break;
    case 'complete':
      if (data.success === false) {
        panel.markError(data.error || 'LLM 调用失败');
      } else {
        panel.markComplete(data.usage);
      }
      break;
  }
  scrollToBottom();
}

/**
 * 构建 trace 折叠区 DOM（含 response/thinking 双 tab 切换）。
 *
 * @param agent 专家名
 * @return {{el, promptEl, responseEl, thinkingEl, showTab, markError, markComplete}}
 */
function buildTracePanel(agent) {
  const el = document.createElement('details');
  el.className = 'trace-panel';
  el.open = true; // 默认展开，让用户第一时间看到推理过程

  const summary = document.createElement('summary');
  summary.className = 'trace-summary';
  summary.textContent = '🧠 LLM 推理过程（' + agent + '）';
  el.appendChild(summary);

  // tab 切换条
  const tabs = document.createElement('div');
  tabs.className = 'trace-tabs';
  const responseBtn = document.createElement('button');
  responseBtn.type = 'button';
  responseBtn.className = 'trace-tab active';
  responseBtn.textContent = 'Response';
  const thinkingBtn = document.createElement('button');
  thinkingBtn.type = 'button';
  thinkingBtn.className = 'trace-tab';
  thinkingBtn.textContent = 'Thinking';
  tabs.appendChild(responseBtn);
  tabs.appendChild(thinkingBtn);
  el.appendChild(tabs);

  // prompt 只读块
  const promptLabel = document.createElement('div');
  promptLabel.className = 'trace-section-label';
  promptLabel.textContent = 'Prompt';
  el.appendChild(promptLabel);
  const promptEl = document.createElement('pre');
  promptEl.className = 'trace-prompt';
  el.appendChild(promptEl);

  // response 区域（默认激活）
  const responseLabel = document.createElement('div');
  responseLabel.className = 'trace-section-label';
  responseLabel.textContent = 'Response';
  el.appendChild(responseLabel);
  const responseEl = document.createElement('div');
  responseEl.className = 'trace-response trace-stream';
  el.appendChild(responseEl);

  // thinking 区域（默认隐藏）
  const thinkingLabel = document.createElement('div');
  thinkingLabel.className = 'trace-section-label';
  thinkingLabel.textContent = 'Thinking';
  thinkingLabel.hidden = true;
  el.appendChild(thinkingLabel);
  const thinkingEl = document.createElement('div');
  thinkingEl.className = 'trace-thinking trace-stream';
  thinkingEl.hidden = true;
  el.appendChild(thinkingEl);

  // tab 切换逻辑
  responseBtn.addEventListener('click', () => showTab('response'));
  thinkingBtn.addEventListener('click', () => showTab('thinking'));

  function showTab(which) {
    const isResponse = which === 'response';
    responseBtn.classList.toggle('active', isResponse);
    thinkingBtn.classList.toggle('active', !isResponse);
    responseEl.hidden = !isResponse;
    thinkingEl.hidden = isResponse;
    responseLabel.hidden = !isResponse;
    thinkingLabel.hidden = isResponse;
  }

  function markError(msg) {
    summary.textContent = '❌ LLM 推理失败（' + agent + '）';
    const errEl = document.createElement('div');
    errEl.className = 'trace-error';
    errEl.textContent = msg;
    el.appendChild(errEl);
  }

  function markComplete(usage) {
    if (usage) {
      const u = document.createElement('div');
      u.className = 'trace-usage';
      u.textContent = usage;
      el.appendChild(u);
    }
  }

  return { el, promptEl, responseEl, thinkingEl, showTab, markError, markComplete };
}

function appendAssistantText(state, delta) {  if (!delta) return;
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

/** 页面重载后重放服务端 JSONL；坏行跳过，不影响当前对话。 */
async function replayTrace() {
  try {
    const resp = await fetch('/api/trace');
    if (!resp.ok) return;
    const text = await resp.text();
    for (const line of text.split('\n')) {
      if (!line.trim()) continue;
      try {
        reduceTimelineEvent(JSON.parse(line), true);
      } catch (e) {
        console.warn('跳过无法回放的 trace 事件', e);
      }
    }
  } catch (e) {
    console.warn('trace 回放失败', e);
  }
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

replayTrace();
