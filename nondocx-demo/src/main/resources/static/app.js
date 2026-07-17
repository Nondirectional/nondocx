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
let pendingTurnUserEl = null; // 当前请求对应的用户气泡,等服务端 turnId 到达后归属到 run

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
  pendingTurnUserEl = appendMsg('user', message);
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
        handleSseFrame(frame);
      }
    }
  } catch (err) {
    appendMsg('error', '网络错误:' + err.message);
    console.error(err);
  } finally {
    cancelButton.remove();
    pendingTurnUserEl = null;
    setChatting(false);
  }
});

/**
 * 处理一个 SSE 帧(形如 "data: {...}")。
 *
 * @param frame 原始帧文本(如 'data: {"type":"trace","event":"content_delta"}')
 */
function handleSseFrame(frame) {
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

// ============ 对话与执行卡 reducer ============
//
// edit_outcome 是服务端权威的成败系统帧（按 dirty/saved/cancelled/rolled_back 计算），
// 前端以它为准渲染成败口径。Trace 只保存在执行卡的折叠详情中，不直接占据消息时间线。

const timelineRuns = new Map();

function createExecutionSteps() {
  return {
    read: { id: 'read', title: '读取文档', status: 'pending', detail: '' },
    edit: { id: 'edit', title: '修改文档', status: 'pending', detail: '' },
    quality: { id: 'quality', title: '意图复审', status: 'pending', detail: '' },
    save: { id: 'save', title: '保存文档', status: 'pending', detail: '' },
  };
}

function runFor(data, replay) {
  const id = data.turnId || 'history';
  if (!timelineRuns.has(id)) {
    timelineRuns.set(id, {
      id,
      replay,
      status: 'consulting',
      outcome: null,
      prompt: '',
      reply: '',
      result: '',
      quality: '',
      qualityData: null,
      hasWrite: false,
      qualityStarted: false,
      events: [],
      traces: new Map(),
      steps: createExecutionSteps(),
      dom: null,
      shouldCollapse: false,
    });
  }
  return timelineRuns.get(id);
}

function setStep(run, id, status, detail) {
  const step = run.steps[id];
  if (!step) return;
  step.status = status;
  if (detail) step.detail = detail;
}

function ensureRunDom(run) {
  if (run.dom) return;

  let userEl = null;
  if (!run.replay && pendingTurnUserEl) {
    userEl = pendingTurnUserEl;
  } else if (run.prompt) {
    userEl = appendMsg('user', run.prompt);
  }

  const assistantEl = document.createElement('div');
  assistantEl.className = 'msg assistant empty';
  messagesEl.appendChild(assistantEl);

  const executionCard = document.createElement('details');
  executionCard.className = 'execution-card';
  messagesEl.appendChild(executionCard);

  run.dom = { userEl, assistantEl, executionCard };
  scrollToBottom();
}

function updateAssistantBubble(run) {
  ensureRunDom(run);
  const assistantEl = run.dom.assistantEl;
  assistantEl.textContent = run.reply || '';
  assistantEl.classList.toggle('empty', !run.reply);
}

function reduceTimelineEvent(data, replay) {
  if (!data || !data.type || data.type === 'done' || data.type === 'doc_changed') return;
  const run = runFor(data, replay);
  run.events.push(data);
  switch (data.type) {
    case 'assistant':
      // 流式输出可能已经通过 content_delta 完成；空的最终帧不能清空已有回复。
      if (data.message) run.reply = data.message;
      updateAssistantBubble(run);
      break;
    case 'edit_outcome':
      // edit_outcome.status: noop(纯咨询) | saved | rolled_back | cancelled
      // 服务端权威帧：成败口径以此为准，agent 的 assistant 文本仅作辅助。
      run.outcome = data.status || 'noop';
      run.status = ({ saved: 'completed', rolled_back: 'rolled_back', cancelled: 'cancelled', noop: 'completed' })[run.outcome] || 'blocked';
      run.result = data.error || outcomeLabel(run.outcome);
      run.quality = data.qualityReport || '';
      run.qualityData = parseReviewReport(run.quality);
      setStep(run, 'read', 'done', run.hasWrite ? '已读取当前文档' : '已完成文档理解');
      if (run.hasWrite) {
        setStep(run, 'edit', 'done', '修改已执行');
        setStep(run, 'quality', 'done', run.quality ? '意图复审已完成' : '已完成保存');
        setStep(run, 'save', run.outcome === 'saved' ? 'done' : 'failed', run.result);
      } else if (run.qualityStarted) {
        setStep(run, 'quality', 'done', '意图复审已完成');
      }
      run.shouldCollapse = true;
      break;
    case 'error':
      run.status = 'blocked';
      run.result = data.message || '执行失败';
      if (run.hasWrite) {
        setStep(run, 'edit', 'failed', '修改未完成');
        setStep(run, 'save', 'failed', '未保存任何修改');
      } else {
        setStep(run, 'read', 'failed', run.result);
      }
      run.shouldCollapse = true;
      break;
    case 'trace': {
      if (data.event === 'prompt') {
        run.prompt = data.prompt || '';
        setStep(run, 'read', 'active', '正在读取当前文档');
      } else if (data.event === 'tool_start') {
        if (data.tool === 'review_intent') {
          run.qualityStarted = true;
          setStep(run, 'edit', run.hasWrite ? 'done' : 'pending', '修改已执行');
          setStep(run, 'quality', 'active', '正在复审修改是否达成用户期望');
        } else if (isReadonlyTool(data.tool)) {
          setStep(run, 'read', 'active', '正在读取文档信息');
        } else {
          run.hasWrite = true;
          run.status = 'executing';
          setStep(run, 'read', 'done', '文档信息已读取');
          setStep(run, 'edit', 'active', '正在执行文档修改');
          setStep(run, 'save', 'pending', '等待复审完成');
        }
      } else if (data.event === 'tool_end') {
        if (data.tool === 'review_intent') {
          setStep(run, 'quality', 'done', '意图复审已返回');
        } else if (isReadonlyTool(data.tool)) {
          setStep(run, 'read', 'done', '文档信息已读取');
        } else if (run.hasWrite) {
          setStep(run, 'edit', 'active', '正在执行文档修改');
        }
      } else if (data.event === 'content_delta') {
        run.reply += data.delta || '';
        updateAssistantBubble(run);
      }
      const agent = data.agent || 'unknown';
      if (!run.traces.has(agent)) run.traces.set(agent, []);
      run.traces.get(agent).push(data);
      break;
    }
  }
  renderTimelineRun(run);
}

/** 与后端 AgentBridge.isReadonly 同源的只读判定。未知工具视为写（与后端安全默认一致）。 */
function isReadonlyTool(name) {
  if (!name) return false;
  if (['current_document', 'describe_capabilities'].includes(name)) return true;
  return ['view_', 'read_', 'get_', 'list_', 'search_', 'check_'].some(p => name.startsWith(p));
}

function renderTimelineRun(run) {
  ensureRunDom(run);
  const card = run.dom.executionCard;
  const wasInitialized = card.dataset.initialized === 'true';
  card.innerHTML = '';

  const summary = document.createElement('summary');
  summary.textContent = executionSummary(run);
  summary.className = 'execution-summary ' + run.status;
  card.appendChild(summary);

  const body = document.createElement('div');
  body.className = 'execution-body';
  for (const step of visibleSteps(run)) renderExecutionStep(body, step);
  if (run.result) addExecutionText(body, run.result, run.status);
  if (run.quality) renderQualityReport(body, run.qualityData, run.quality);
  renderTracePanel(body, run);
  card.appendChild(body);

  if (!wasInitialized) card.open = !run.replay && !isTerminal(run.status);
  if (run.shouldCollapse) {
    card.open = false;
    run.shouldCollapse = false;
  }
  card.dataset.initialized = 'true';
  scrollToBottom();
}

function statusLabel(status) {
  return ({ consulting: '处理中', executing: '执行中', completed: '已完成', blocked: '执行失败', rolled_back: '未保存', cancelled: '已取消' })[status] || status;
}

function outcomeLabel(outcome) {
  return ({ saved: '文档已保存', noop: '无需修改，已完成回复', cancelled: '已取消，未保存修改', rolled_back: '保存失败，已回滚修改' })[outcome] || '未完成';
}

function executionSummary(run) {
  return statusLabel(run.status) + (run.outcome === 'saved' ? ' · 文档已更新' : run.outcome === 'noop' ? ' · 纯咨询' : '');
}

function isTerminal(status) {
  return ['completed', 'blocked', 'rolled_back', 'cancelled'].includes(status);
}

function visibleSteps(run) {
  return Object.values(run.steps).filter((step) => {
    if (step.id === 'read') return true;
    if (step.id === 'edit') return run.hasWrite || step.status !== 'pending';
    if (step.id === 'quality') return run.hasWrite || run.qualityStarted || step.status !== 'pending';
    return run.hasWrite || step.status !== 'pending';
  });
}

function renderExecutionStep(container, step) {
  const row = document.createElement('div');
  row.className = 'execution-step ' + step.status;
  const icon = document.createElement('span');
  icon.className = 'step-icon';
  icon.textContent = ({ active: '⟳', done: '✓', failed: '×', pending: '○' })[step.status] || '○';
  const title = document.createElement('span');
  title.className = 'step-title';
  title.textContent = step.status === 'active' ? '正在' + step.title : step.title;
  row.append(icon, title);
  if (step.detail) {
    const detail = document.createElement('span');
    detail.className = 'step-detail';
    detail.textContent = step.detail;
    row.appendChild(detail);
  }
  container.appendChild(row);
}

function addExecutionText(container, value, status) {
  const result = document.createElement('div');
  result.className = 'execution-result ' + status;
  result.textContent = value;
  container.appendChild(result);
}

function parseStructuredToolOutput(output) {
  if (!output) return null;
  const match = output.match(/```json\s*([\s\S]*?)\s*```/);
  if (!match) return null;
  try {
    return JSON.parse(match[1]);
  } catch (e) {
    return null;
  }
}

// 复审结论解析：后端 review_intent SubAgent 输出 <verdict>达成|部分达成|未达成</verdict><diff>...</diff>。
function parseReviewReport(report) {
  if (!report) return null;
  const verdictMatch = report.match(/<verdict>\s*(达成|部分达成|未达成)\s*<\/verdict>/);
  if (!verdictMatch) return null;
  const diffMatch = report.match(/<diff>([\s\S]*?)<\/diff>/);
  return {
    verdict: verdictMatch[1],
    diff: diffMatch ? diffMatch[1].trim() : '',
  };
}

function reviewVerdictMeta(verdict) {
  switch (verdict) {
    case '达成':
      return { icon: '✓', tone: 'passed', label: '达成' };
    case '部分达成':
      return { icon: '⚠', tone: 'partial', label: '部分达成' };
    case '未达成':
      return { icon: '✗', tone: 'failed', label: '未达成' };
    default:
      return { icon: '?', tone: 'pending', label: '未知' };
  }
}

function renderQualityReport(container, data, report) {
  const details = document.createElement('details');
  details.className = 'execution-report';
  const summary = document.createElement('summary');
  summary.textContent = data ? reviewSummary(data) : '查看复审结论';
  if (data) {
    const meta = reviewVerdictMeta(data.verdict);
    const badge = document.createElement('div');
    badge.className = 'quality-stats review-verdict ' + meta.tone;
    const icon = document.createElement('strong');
    icon.textContent = meta.icon;
    const label = document.createElement('span');
    label.textContent = '意图达成度：' + meta.label;
    badge.append(icon, label);
    details.appendChild(badge);

    if (data.diff) {
      const diffBox = document.createElement('div');
      diffBox.className = 'quality-checks review-diff';
      const pre = document.createElement('pre');
      pre.textContent = data.diff;
      diffBox.appendChild(pre);
      details.appendChild(diffBox);
    }
  }
  const raw = document.createElement('details');
  raw.className = 'quality-raw';
  const rawSummary = document.createElement('summary');
  rawSummary.textContent = '查看原始复审结论';
  const pre = document.createElement('pre');
  pre.textContent = report;
  raw.append(rawSummary, pre);
  details.appendChild(raw);
  container.appendChild(details);
}

function reviewSummary(data) {
  return '意图复审 · ' + (data.verdict || '未知');
}

function renderTracePanel(container, run) {
  const allEvents = run.events.filter((event) => event.type === 'trace');
  const count = allEvents.length;
  if (!count) return;
  const details = document.createElement('details');
  details.className = 'trace-panel';
  const summary = document.createElement('summary');
  summary.className = 'trace-summary';
  summary.textContent = '查看执行过程（' + toolStepCount(allEvents) + ' 个工具步骤）';
  details.appendChild(summary);
  const steps = document.createElement('div');
  steps.className = 'trace-steps';
  buildTraceSteps(allEvents).forEach((step) => renderTraceStep(steps, step));
  details.appendChild(steps);

  const modelEvents = allEvents.filter((event) => ['prompt', 'thinking_delta', 'content_delta', 'skill_activated'].includes(event.event));
  if (modelEvents.length) renderModelTrace(details, modelEvents);
  container.appendChild(details);
}

const TOOL_LABELS = {
  current_document: '读取当前文档',
  describe_capabilities: '了解可用能力',
  check_quality: '检查文档质量',
  review_intent: '复审意图达成度',
  view_body: '查看正文',
  view_text: '查看文本',
  view_tables: '查看表格',
  insert_paragraph: '插入段落',
  insert_heading: '插入标题',
  replace_run_text: '替换文字',
  update_run_style: '更新文字样式',
  update_paragraph_alignment: '调整段落对齐',
  replace_table_cell_run_text: '修改表格内容',
  apply_tracked_changes: '处理修订',
};

function toolLabel(name) {
  if (TOOL_LABELS[name]) return TOOL_LABELS[name];
  if (!name) return '执行工具';
  return name.replace(/_/g, ' ');
}

function toolStepCount(events) {
  return events.filter((event) => event.event === 'tool_start').length;
}

function buildTraceSteps(events) {
  const steps = [];
  for (const event of events) {
    if (event.event === 'tool_start') {
      steps.push({
        tool: event.tool,
        status: 'active',
        arguments: event.arguments || '',
        result: '',
      });
    } else if (event.event === 'tool_end') {
      let step = null;
      for (let i = steps.length - 1; i >= 0; i--) {
        if (steps[i].tool === event.tool && steps[i].status === 'active') {
          step = steps[i];
          break;
        }
      }
      if (!step) {
        step = { tool: event.tool, status: 'done', arguments: '', result: '' };
        steps.push(step);
      }
      step.result = event.result || '';
      const result = parseStructuredToolOutput(step.result);
      step.status = result && result.success === false ? 'failed' : 'done';
    }
  }
  return steps;
}

function renderTraceStep(container, step) {
  const card = document.createElement('div');
  card.className = 'trace-step ' + step.status;
  const icon = document.createElement('span');
  icon.className = 'trace-step-icon';
  icon.textContent = ({ active: '⟳', done: '✓', failed: '×' })[step.status] || '•';
  const content = document.createElement('div');
  content.className = 'trace-step-content';
  const title = document.createElement('div');
  title.className = 'trace-step-title';
  title.textContent = (step.status === 'active' ? '正在' : '') + toolLabel(step.tool);
  content.appendChild(title);
  const result = parseStructuredToolOutput(step.result);
  const summary = document.createElement('div');
  summary.className = 'trace-step-result';
  summary.textContent = traceStepSummary(step, result);
  content.appendChild(summary);
  if (step.arguments || step.result) {
    const raw = document.createElement('details');
    raw.className = 'trace-step-raw';
    const rawSummary = document.createElement('summary');
    rawSummary.textContent = '查看参数和返回值';
    const pre = document.createElement('pre');
    pre.textContent = (step.arguments ? '[参数]\n' + step.arguments + '\n\n' : '') + (step.result ? '[返回值]\n' + step.result : '');
    raw.append(rawSummary, pre);
    content.appendChild(raw);
  }
  card.append(icon, content);
  container.appendChild(card);
}

function traceStepSummary(step, result) {
  if (!step.result) return '等待返回结果';
  if (step.tool === 'review_intent') {
    const review = parseReviewReport(step.result);
    if (review) return reviewSummary(review);
  }
  if (!result) return firstLine(step.result);
  return result.success === false
    ? firstLine(result.message || '执行失败')
    : firstLine(result.message || '执行完成');
}

function firstLine(value) {
  return String(value).split('\n')[0].slice(0, 180);
}

function renderModelTrace(container, events) {
  const details = document.createElement('details');
  details.className = 'model-trace';
  const summary = document.createElement('summary');
  summary.textContent = '查看模型原始日志';
  const pre = document.createElement('pre');
  pre.textContent = events.map(renderTraceLine).join('\n');
  details.append(summary, pre);
  container.appendChild(details);
}

/** 单条 trace 事件 → 时间线文本行。Skill 激活独立成行，不渲染为普通工具。 */
function renderTraceLine(e) {
  switch (e.event) {
    case 'prompt': return '[请求]\n' + (e.prompt || '');
    case 'tool_start': return '[工具开始] ' + e.tool + '\n' + (e.arguments || '');
    case 'tool_end': return '[工具结果] ' + e.tool + '\n' + (e.result || '');
    case 'skill_activated':
      // Skill 激活：只显示名称/description/注入字符数，不显示正文（后端已不传正文）。
      return '[Skill] ' + (e.skill || '') + '\n' + (e.description || '') + '\n注入 ' + (e.contentLength || 0) + ' 字符';
    case 'thinking_delta': return '[思考]\n' + (e.delta || '');
    case 'content_delta': return '[回复]\n' + (e.delta || '');
    default: return '[' + e.event + ']';
  }
}

// ============ DOM 辅助 ============

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
