<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue';
import KnowledgePanel from './KnowledgePanel.vue';
import OperationReviewPanel from './OperationReviewPanel.vue';
import NodeRoomsPanel from './NodeRoomsPanel.vue';
import {
  Activity,
  BarChart3,
  Bell,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  FileSearch,
  MessageSquare,
  RefreshCw,
  Search,
  Server,
  UserRound,
  X
} from 'lucide-vue-next';

const navItems = ['节点管理', '消息管理', '日志中心', '知识库', '操作审核'];
const apiBaseUrl = import.meta.env.VITE_TELINK_ADMIN_API || 'http://127.0.0.1:9090/admin/api';
const nodes = ref([]);
const messageRecords = ref([]);
const messagePage = ref(1);
const messagePageSize = ref(20);
const messageTotal = ref(0);
const persistedMessageTotal = ref(0);
const messageTotalPages = ref(0);
const messageLogIdQuery = ref('');
const messageUidQuery = ref('');
const messageRoomIdQuery = ref('');
const messageKeyword = ref('');
const logCenterRecords = ref([]);
const expandedLogIds = ref([]);
const apiError = ref('');

const statusMap = {
  normal: { text: '正常', className: 'normal', color: '#00b42a' },
  abnormal: { text: '异常', className: 'abnormal', color: '#f53f3f' },
  offline: { text: '离线', className: 'offline', color: '#c9cdd4' },
  restarting: { text: '重启中', className: 'restarting', color: '#ff7d00' }
};

const messageStatusMap = {
  RECEIVED: '已接收',
  ACCEPTED: '已进入队列',
  BUSINESS_PROCESSED: '业务已处理',
  ROUTING: '路由中',
  DELIVERED: '已送达',
  FAILED: '失败'
};

const statusFilter = ref('all');
const keyword = ref('');
const selectedIds = ref([]);
const activePage = ref('节点管理');
const detailNode = ref(null);
const nodeLogMachineId = ref(null);
const nodeLogRooms = ref(null);
let logRequestId = 0;
// Agent 抽屉及流式回复状态。
const agentPanelOpen = ref(false);
const agentRunning = ref(false);
const agentThinkingSeconds = ref(0);
// 用户的路由偏好：auto 交给后端路由，固定值则指定模型。
const agentModelMode = ref('auto');
// 当前实际使用模型，Auto 模式会展示后端路由结果。
const agentModelName = ref('Auto');
const agentMessages = ref([]);
const agentConversationId = ref('');
const agentScopeType = ref('');
const agentScopeMachineIds = ref([]);
const agentScopeLogId = ref('');
const agentInput = ref('');
const logLevelFilter = ref('all');
const logQueryMode = ref('logId');
const logIdQuery = ref('');
const machineIdQuery = ref('');
const logSearchKeyword = ref('');
const refreshing = ref(false);
let refreshTimer;
let agentRequestController;

const filteredNodes = computed(() => {
  const query = keyword.value.trim().toLowerCase();
  return nodes.value.filter((node) => {
    const matchedStatus = statusFilter.value === 'all' || node.status === statusFilter.value;
    const matchedQuery = !query || `${node.id} ${node.machineId} ${node.ip} ${node.port}`.toLowerCase().includes(query);
    return matchedStatus && matchedQuery;
  });
});

const onlineNodes = computed(() => nodes.value.filter((node) => node.status !== 'offline'));
const stats = computed(() => {
  const activeNodes = onlineNodes.value;
  const totalConnections = activeNodes.reduce((sum, node) => sum + node.connections, 0);

  return [
    { label: '节点总数', value: nodes.value.length, unit: '个', icon: Server, tone: 'blue' },
    { label: '累计消息数', value: persistedMessageTotal.value.toLocaleString(), unit: '条', icon: MessageSquare, tone: 'green' },
    { label: '异常节点', value: nodes.value.filter((node) => node.status === 'abnormal').length, unit: '个', icon: Bell, tone: 'red' },
    { label: '当前连接数', value: totalConnections.toLocaleString(), unit: '个', icon: Activity, tone: 'orange' }
  ];
});

const allFilteredSelected = computed(() => {
  return filteredNodes.value.length > 0 && filteredNodes.value.every((node) => selectedIds.value.includes(node.id));
});

const topologyExpanded = ref(false);
const topologyNodes = computed(() => topologyExpanded.value ? nodes.value : nodes.value.slice(0, 8));
const nodePositions = computed(() => {
  const positions = {};
  topologyNodes.value.forEach((node, index) => {
    positions[node.id] = {
      x: 140 + (index % 4) * 210,
      y: 110 + Math.floor(index / 4) * 130
    };
  });
  return positions;
});
const topologyViewBox = computed(() => {
  const rows = Math.max(1, Math.ceil(topologyNodes.value.length / 4));
  return `0 0 900 ${70 + rows * 130}`;
});

function formatCpu(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number.toFixed(5) : '-';
}

function formatNodeTime(value) {
  if (value == null || value === '' || value === '-') {
    return '';
  }
  const date = typeof value === 'number' ? new Date(value) : new Date(value);
  if (Number.isNaN(date.getTime())) {
    return String(value);
  }
  return date.toLocaleString('zh-CN', { hour12: false });
}

let agentThinkingTimer;

function cpuClass(cpu) {
  if (cpu >= 80) {
    return 'high';
  }
  if (cpu >= 60) {
    return 'mid';
  }
  return 'low';
}

function toggleSelectAll(event) {
  const ids = filteredNodes.value.map((node) => node.id);
  if (event.target.checked) {
    selectedIds.value = Array.from(new Set([...selectedIds.value, ...ids]));
    return;
  }
  selectedIds.value = selectedIds.value.filter((id) => !ids.includes(id));
}

function refreshData() {
  refreshing.value = true;
  let request;
  if (activePage.value === '日志中心') {
    request = Promise.all([searchLogs({ preserveExpanded: true }), nodeLogRooms.value?.refresh()]);
  } else if (activePage.value === '消息管理') {
    request = loadMessages();
  } else {
    request = loadNodes();
  }
  request.finally(() => {
    refreshing.value = false;
  });
}

function openLog(node) {
  // 从节点入口进入后，将该节点作为日志中心的默认查询范围。
  selectedIds.value = [node.id];
  detailNode.value = null;
  activePage.value = '日志中心';
  logQueryMode.value = 'machineId';
  machineIdQuery.value = String(node.machineId);
  logLevelFilter.value = 'all';
  logSearchKeyword.value = '';
  searchLogs();
}

function switchPage(item) {
  activePage.value = item;
  apiError.value = '';
  detailNode.value = null;
  if (item === '节点管理') {
    loadNodes();
  } else if (item === '消息管理') {
    loadMessages();
  }
}

async function loadMessages(targetPage = messagePage.value) {
  const params = new URLSearchParams({
    page: String(Math.max(targetPage, 1)),
    pageSize: String(messagePageSize.value)
  });
  if (messageLogIdQuery.value.trim()) {
    params.set('logId', messageLogIdQuery.value.trim());
  }
  if (messageUidQuery.value.trim()) {
    params.set('uid', messageUidQuery.value.trim());
  }
  if (messageRoomIdQuery.value.trim()) {
    params.set('roomId', messageRoomIdQuery.value.trim());
  }
  if (messageKeyword.value.trim()) {
    params.set('keyword', messageKeyword.value.trim());
  }
  try {
    const result = await apiGet(`/messages?${params.toString()}`);
    messageRecords.value = result.items || [];
    messageTotal.value = Number(result.total || 0);
    messagePage.value = Number(result.page || 1);
    messageTotalPages.value = Number(result.totalPages || 0);
    apiError.value = '';
  } catch (error) {
    messageRecords.value = [];
    messageTotal.value = 0;
    messageTotalPages.value = 0;
    apiError.value = '查询消息失败';
  }
}

function searchMessages() {
  messagePage.value = 1;
  loadMessages(1);
}

function resetMessageFilters() {
  messageLogIdQuery.value = '';
  messageUidQuery.value = '';
  messageRoomIdQuery.value = '';
  messageKeyword.value = '';
  searchMessages();
}

function changeMessagePage(targetPage) {
  if (targetPage < 1 || targetPage > messageTotalPages.value || targetPage === messagePage.value) {
    return;
  }
  loadMessages(targetPage);
}

function openMessageLog(message) {
  activePage.value = '日志中心';
  logQueryMode.value = 'logId';
  logIdQuery.value = message.logId;
  logLevelFilter.value = 'all';
  logSearchKeyword.value = '';
  searchLogs();
}

function escapeAgentHtml(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

function renderAgentInline(value) {
  return escapeAgentHtml(value)
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
}

function renderAgentMarkdown(source) {
  const lines = String(source || '').replace(/\r\n/g, '\n').split('\n');
  const html = [];
  let index = 0;
  const cells = (row) => row.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map((cell) => cell.trim());
  while (index < lines.length) {
    const line = lines[index];
    if (/^\s*\|/.test(line) && index + 1 < lines.length && /^\s*\|?[\s:|-]+\|/.test(lines[index + 1])) {
      const rows = [];
      while (index < lines.length && /^\s*\|/.test(lines[index])) {
        rows.push(lines[index]);
        index += 1;
      }
      const header = cells(rows[0]);
      const body = rows.slice(2).filter((row) => !/^[\s|:-]+$/.test(row)).map(cells);
      html.push(`<table class="agent-table"><thead><tr>${header.map((cell) => `<th>${renderAgentInline(cell)}</th>`).join('')}</tr></thead><tbody>${body.map((row) => `<tr>${row.map((cell) => `<td>${renderAgentInline(cell)}</td>`).join('')}</tr>`).join('')}</tbody></table>`);
      continue;
    }
    const heading = /^(#{1,4})\s+(.*)$/.exec(line);
    if (heading) {
      html.push(`<h${Math.min(heading[1].length + 2, 5)}>${renderAgentInline(heading[2])}</h${Math.min(heading[1].length + 2, 5)}>`);
      index += 1;
      continue;
    }
    if (/^\s*[-*]\s+/.test(line)) {
      const items = [];
      while (index < lines.length && /^\s*[-*]\s+/.test(lines[index])) {
        items.push(lines[index].replace(/^\s*[-*]\s+/, ''));
        index += 1;
      }
      html.push(`<ul>${items.map((item) => `<li>${renderAgentInline(item)}</li>`).join('')}</ul>`);
      continue;
    }
    if (!line.trim()) {
      index += 1;
      continue;
    }
    html.push(`<p>${renderAgentInline(line)}</p>`);
    index += 1;
  }
  return html.join('');
}

function messageStatusText(status) {
  return messageStatusMap[status] || status || '-';
}

function messageStatusClass(status) {
  return String(status || 'UNKNOWN').toLowerCase();
}

function traversedNodesText(nodeIds) {
  if (!Array.isArray(nodeIds) || nodeIds.length === 0) {
    return '-';
  }
  return nodeIds.map((nodeId) => `machineId=${nodeId}`).join(' → ');
}

async function searchLogs({ preserveExpanded = false } = {}) {
  const currentRequest = ++logRequestId;
  if (!preserveExpanded) expandedLogIds.value = [];
  apiError.value = '';
  const target = logQueryMode.value === 'logId'
    ? logIdQuery.value.trim()
    : machineIdQuery.value.trim();
  nodeLogMachineId.value = logQueryMode.value === 'machineId' && /^[0-9]+$/.test(target)
    ? Number(target) : null;
  if (!preserveExpanded) logCenterRecords.value = [];
  if (!target) {
    logCenterRecords.value = [];
    return;
  }
  const params = new URLSearchParams({
    mode: logQueryMode.value,
    level: logLevelFilter.value,
    keyword: logSearchKeyword.value.trim(),
    limit: '100'
  });
  params.set(logQueryMode.value, target);
  try {
    const records = await apiGet(`/logs?${params.toString()}`);
    if (currentRequest === logRequestId) logCenterRecords.value = records;
  } catch (error) {
    if (currentRequest === logRequestId) {
      logCenterRecords.value = [];
      apiError.value = '查询日志失败';
    }
  }
}

function setLogLevel(level) {
  logLevelFilter.value = level;
  if (logQueryMode.value === 'logId' ? logIdQuery.value.trim() : machineIdQuery.value.trim()) {
    searchLogs();
  }
}

function toggleLogDetail(logId) {
  expandedLogIds.value = expandedLogIds.value.includes(logId)
    ? expandedLogIds.value.filter((id) => id !== logId)
    : [...expandedLogIds.value, logId];
}

async function analyzeLogCenter() {
  if (logQueryMode.value !== 'logId' || !logIdQuery.value.trim()) {
    openAgentGuide('请先输入唯一 LogID 后再发起日志智能分析。');
    return;
  }
  const logId = logIdQuery.value.trim();
  await startAgentConversation({
    scopeType: 'log',
    machineIds: [],
    logId,
    prompt: `[logId=${logId}] [模型=${selectedModel()}] 请分析该日志链路。`
  });
}

function openDetail(node) {
  detailNode.value = node;
  // 从节点入口进入后，将该节点作为后续默认分析范围。
  selectedIds.value = [node.id];
}

function closeDetail() {
  detailNode.value = null;
}

function openClusterAnalysis() {
  startAgentConversation({
    scopeType: 'cluster',
    machineIds: [],
    logId: '',
    prompt: '请由主 Agent 发现当前全部节点，分别派出子 Agent 分析各节点的服务器内存、CPU 压力与最近一分钟消息量，再汇总报告及必要的运维建议。'
  });
}

async function openNodeAnalysis(node) {
  const machineIds = node?.machineId != null
    ? [node.machineId]
    : nodes.value.map((node) => node.machineId);
  await startAgentConversation({
    scopeType: 'node',
    machineIds,
    logId: '',
    prompt: `[machineId=${machineIds.join(',')}] [模型=${selectedModel()}] 请分析当前节点状态。`
  });
}

function closeAnalysis() {
  stopAgentRequest();
  agentPanelOpen.value = false;
  stopAgentTimers();
}

function openAgentGuide(content) {
  stopAgentRequest();
  stopAgentTimers();
  agentPanelOpen.value = true;
  agentRunning.value = false;
  agentThinkingSeconds.value = 0;
  agentModelName.value = selectedModel();
  agentConversationId.value = '';
  agentScopeType.value = '';
  agentScopeMachineIds.value = [];
  agentScopeLogId.value = '';
  agentInput.value = '';
  agentMessages.value = [
    {
      role: 'assistant',
      machineIds: [],
      logId: '',
      model: selectedModel(),
      content,
      streaming: false
    }
  ];
}

function showAgentFailure(prompt, machineIds, logId) {
  agentMessages.value = [
    {
      role: 'user',
      machineIds,
      logId,
      model: selectedModel(),
      content: prompt,
      streaming: false
    },
    {
      role: 'assistant',
      machineIds,
      logId,
      model: selectedModel(),
      content: '服务分析失败',
      streaming: false
    }
  ];
}

async function startAgentConversation({ scopeType, machineIds, logId, prompt }) {
  stopAgentRequest();
  stopAgentTimers();
  agentPanelOpen.value = true;
  agentRunning.value = false;
  agentThinkingSeconds.value = 0;
  agentModelName.value = selectedModel();
  agentConversationId.value = '';
  agentScopeType.value = scopeType;
  agentScopeMachineIds.value = machineIds;
  agentScopeLogId.value = logId;
  agentInput.value = '';
  agentMessages.value = [];
  try {
    const session = await apiPost('/agent/session/start', {
      scopeType,
      machineIds,
      logId,
      model: agentModelMode.value
    });
    agentConversationId.value = session.conversationId;
    agentModelName.value = session.model || selectedModel();
    // Redis 恢复会话时优先展示最近十轮可见历史，避免再次发送重复的初始分析请求。
    agentMessages.value = (session.turns || []).flatMap((turn) => [
      {
        role: 'user',
        machineIds,
        logId,
        model: turn.model || agentModelName.value,
        content: turn.userMessage,
        streaming: false
      },
      {
        role: 'assistant',
        machineIds,
        logId,
        model: turn.model || agentModelName.value,
        content: turn.assistantMessage,
        steps: [],
        stepsCollapsed: true,
        streaming: false
      }
    ]);
    if (!session.resumed || scopeType === 'cluster') {
      await sendAgentMessage(prompt);
    }
  } catch (error) {
    showAgentFailure(prompt, machineIds, logId);
  }
}

function startAgentThinkingTimer() {
  agentThinkingTimer = window.setInterval(() => {
    agentThinkingSeconds.value += 1;
  }, 1000);
}

async function sendAgentInput() {
  const content = agentInput.value.trim();
  if (!content) {
    return;
  }
  agentInput.value = '';
  await sendAgentMessage(content);
}

async function sendAgentMessage(content) {
  if (!agentConversationId.value || !content || agentRunning.value) {
    return;
  }
  stopAgentRequest();
  stopAgentTimers();
  agentRunning.value = true;
  agentThinkingSeconds.value = 0;
  const machineIds = [...agentScopeMachineIds.value];
  const logId = agentScopeLogId.value;
  const assistantMessage = {
    role: 'assistant',
    machineIds,
    logId,
    model: agentModelName.value,
    content: '',
    steps: [],
    stepsCollapsed: false,
    streaming: true
  };
  agentMessages.value.push({
    role: 'user',
    machineIds,
    logId,
    model: selectedModel(),
    content,
    streaming: false
  });
  agentMessages.value.push(assistantMessage);
  startAgentThinkingTimer();
  const controller = new AbortController();
  agentRequestController = controller;
  try {
    const result = await apiPostSse(
      '/agent/session/chat',
      {
        conversationId: agentConversationId.value,
        message: content
      },
      {
        signal: controller.signal,
        onMeta(payload) {
          const modelName = payload.modelName || selectedModel();
          assistantMessage.model = modelName;
          agentModelName.value = modelName;
        },
        onStage(payload) {
          upsertAgentStep(assistantMessage, payload);
        },
        onChunk(payload) {
          assistantMessage.content += payload.content || '';
        },
        onFailed(payload) {
          assistantMessage.stepsCollapsed = true;
          assistantMessage.content = payload.content || '服务分析失败';
        },
        onDone() {
          assistantMessage.stepsCollapsed = true;
        }
      }
    );
    if (result.failed && !assistantMessage.content) {
      assistantMessage.content = '服务分析失败';
    }
  } catch (error) {
    if (error?.name !== 'AbortError') {
      assistantMessage.content = assistantMessage.content || '服务分析失败';
    }
  } finally {
    if (agentRequestController === controller) {
      agentRequestController = null;
    }
    assistantMessage.streaming = false;
    assistantMessage.stepsCollapsed = true;
    agentRunning.value = false;
    stopAgentTimers(false);
  }
}

function upsertAgentStep(message, payload) {
  const title = payload.title || '正在执行任务';
  const status = payload.status || 'running';
  const detail = payload.detail || '';
  const existed = message.steps.find((step) => step.title === title);
  if (existed) {
    existed.status = status;
    existed.detail = detail;
    return;
  }
  message.steps.push({
    title,
    status,
    detail
  });
}

function toggleAgentSteps(message) {
  message.stepsCollapsed = !message.stepsCollapsed;
}

function agentStepsTitle(message) {
  if (message.steps?.some((step) => step.status === 'running')) {
    return '正在执行任务';
  }
  if (message.steps?.some((step) => step.status === 'failed')) {
    return '任务执行失败';
  }
  return '任务执行完成';
}

function agentStepStatusText(status) {
  if (status === 'completed') {
    return '完成';
  }
  if (status === 'failed') {
    return '失败';
  }
  return '执行中';
}

function stopAgentRequest() {
  if (agentRequestController) {
    agentRequestController.abort();
    agentRequestController = null;
  }
}

function stopAgentTimers(clearRunning = true) {
  window.clearInterval(agentThinkingTimer);
  if (clearRunning) {
    agentRunning.value = false;
  }
}

function selectedModel() {
  return agentModelMode.value === 'auto' ? 'Auto' : 'deepseek-flash';
}

function agentScopeLabel(message) {
  if (message.logId) {
    return `logId=${message.logId} · ${message.model}`;
  }
  if (message.machineIds?.length) {
    return `machineId=${message.machineIds.join(',')} · ${message.model}`;
  }
  if (agentScopeType.value === 'cluster') return `全部节点 · ${message.model || 'Auto'}`;
  return message.model || '-';
}

function agentInputPlaceholder() {
  if (!agentConversationId.value) {
    return '请先发起一次智能分析会话';
  }
  if (agentScopeType.value === 'cluster') return '继续追问集群状态；每轮重新发现节点并采集指标';
  return agentScopeType.value === 'log'
    ? '继续追问当前 LogID 日志链路'
    : '继续追问当前节点状态';
}

async function apiGet(path) {
  const response = await fetch(`${apiBaseUrl}${path}`);
  if (!response.ok) {
    throw new Error('api request failed');
  }
  return response.json();
}

async function apiPost(path, payload) {
  const response = await fetch(`${apiBaseUrl}${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json'
    },
    body: JSON.stringify(payload)
  });
  if (!response.ok) {
    throw new Error('api request failed');
  }
  return response.json();
}

async function apiPostSse(path, payload, { signal, onMeta, onStage, onChunk, onFailed, onDone }) {
  const response = await fetch(`${apiBaseUrl}${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json'
    },
    body: JSON.stringify(payload),
    signal
  });
  if (!response.ok || !response.body) {
    throw new Error('api request failed');
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let failed = false;
  while (true) {
    const { value, done } = await reader.read();
    if (value) {
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');
      buffer = consumeSseBuffer(buffer, {
        onMeta,
        onStage,
        onChunk,
        onFailed(payload) {
          failed = true;
          onFailed?.(payload);
        },
        onDone
      });
    }
    if (done) {
      buffer += decoder.decode().replace(/\r\n/g, '\n');
      consumeSseBuffer(buffer, {
        onMeta,
        onStage,
        onChunk,
        onFailed(payload) {
          failed = true;
          onFailed?.(payload);
        },
        onDone
      });
      return { failed };
    }
  }
}

function consumeSseBuffer(buffer, handlers) {
  let splitIndex = buffer.indexOf('\n\n');
  let remaining = buffer;
  while (splitIndex !== -1) {
    const rawEvent = remaining.slice(0, splitIndex).trim();
    remaining = remaining.slice(splitIndex + 2);
    if (rawEvent) {
      handleSseEvent(rawEvent, handlers);
    }
    splitIndex = remaining.indexOf('\n\n');
  }
  return remaining;
}

function handleSseEvent(rawEvent, { onMeta, onStage, onChunk, onFailed, onDone }) {
  let eventName = 'message';
  const dataLines = [];
  rawEvent.split('\n').forEach((line) => {
    if (line.startsWith('event:')) {
      eventName = line.slice(6).trim();
      return;
    }
    if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trim());
    }
  });
  const payload = dataLines.length ? JSON.parse(dataLines.join('\n')) : {};
  if (eventName === 'meta') {
    onMeta?.(payload);
    return;
  }
  if (eventName === 'stage') {
    onStage?.(payload);
    return;
  }
  if (eventName === 'chunk') {
    onChunk?.(payload);
    return;
  }
  if (eventName === 'failed') {
    onFailed?.(payload);
    return;
  }
  if (eventName === 'done') {
    onDone?.(payload);
  }
}

async function loadNodes() {
  try {
    const [records, messagePageResult] = await Promise.all([
      apiGet('/nodes'),
      apiGet('/messages?page=1&pageSize=1').catch(() => ({ total: 0 }))
    ]);
    persistedMessageTotal.value = Number(messagePageResult.total || 0);
    nodes.value = records.map((node) => ({
      id: `node-${node.machineId}`,
      machineId: node.machineId,
      serverName: node.serverName,
      ip: node.hostIp,
      port: node.port,
      status: node.status === 'RUNNING' ? 'normal' : 'offline',
      startTime: node.startTime || '-',
      uptime: node.uptimeSeconds == null ? '-' : `${node.uptimeSeconds}s`,
      nettyMode: node.nettyMode,
      bossThreads: node.bossThreadCount,
      workerThreads: node.workerThreadCount,
      messages: Number(node.traversedMessageCount ?? node.totalMessageCount ?? 0),
      processMessages: Number(node.totalMessageCount || 0),
      messagesLastMinute: node.messagesLastMinute == null ? '未知' : Number(node.messagesLastMinute).toLocaleString(),
      messageWindowSeconds: node.messageWindowSeconds,
      systemCpu: node.systemCpuUsage == null ? '未知' : `${Number(node.systemCpuUsage).toFixed(1)}%`,
      systemMemory: node.systemMemoryUsed == null || node.systemMemoryTotal == null ? '未知' : `${(node.systemMemoryUsed / 1024 ** 3).toFixed(2)} / ${(node.systemMemoryTotal / 1024 ** 3).toFixed(2)} GiB`,
      sampledAt: node.sampledAtMillis == null ? '未知' : new Date(node.sampledAtMillis).toLocaleString(),
      connections: node.connectionCount || 0,
      cpu: Number(node.cpuUsage || 0),
      systemCpuValue: Number(node.systemCpuUsage || 0),
      systemMemoryUsed: Number(node.systemMemoryUsed || 0),
      systemMemoryTotal: Number(node.systemMemoryTotal || 0),
      heapUsed: node.heapUsed == null ? 0 : Math.round(node.heapUsed / 1024 / 1024),
      heapMax: node.heapMax == null ? 0 : Math.round(node.heapMax / 1024 / 1024),
      threadCount: node.threadCount || 0,
      gcCount: node.gcCount || 0,
      gcTimeMs: node.gcTimeMs || 0,
      lastHeartbeat: formatNodeTime(node.lastHeartbeatTime) || formatNodeTime(node.sampledAtMillis) || '-',
      lastError: node.lastErrorMessage || '-'
    }));
    apiError.value = '';
  } catch (error) {
    nodes.value = [];
    apiError.value = '读取节点数据失败';
  }
}

function closeOnEscape(event) {
  if (event.key === 'Escape') {
    closeDetail();
    closeAnalysis();
  }
}

onMounted(() => {
  window.addEventListener('keydown', closeOnEscape);
  loadNodes();
  refreshTimer = window.setInterval(refreshData, 10000);
});

onUnmounted(() => {
  window.removeEventListener('keydown', closeOnEscape);
  window.clearInterval(refreshTimer);
  stopAgentRequest();
  stopAgentTimers();
});
</script>

<template>
  <div class="app-shell">
    <header class="top-nav">
      <div class="brand">
        <span class="brand-mark"><BarChart3 :size="16" /></span>
        <span>推送节点监控平台</span>
      </div>
      <nav class="nav-menu">
        <button
          v-for="item in navItems"
          :key="item"
          class="nav-item"
          :class="{ active: item === activePage }"
          type="button"
          @click="switchPage(item)"
        >
          {{ item }}
        </button>
      </nav>
      <div class="operator">
        <span class="avatar"><UserRound :size="15" /></span>
        <span>Talen</span>
      </div>
    </header>

    <div class="breadcrumb">
      {{ activePage }} /
      <strong>
        {{ activePage === '操作审核' ? '申请与执行记录' : activePage === '知识库' ? '知识文档' : activePage === '日志中心' ? (logQueryMode === 'machineId' ? '节点日志与房间' : 'LogID 日志') : activePage === '消息管理' ? '消息列表' : '节点列表' }}
      </strong>
    </div>
    <div v-if="apiError" class="api-error">{{ apiError }}</div>

    <KnowledgePanel v-if="activePage === '知识库'" :api-base-url="apiBaseUrl" />
    <OperationReviewPanel v-else-if="activePage === '操作审核'" :api-base-url="apiBaseUrl" />
    <main v-else-if="activePage === '节点管理'" class="main-content">
      <section class="stats-row" aria-label="节点统计">
        <article v-for="item in stats" :key="item.label" class="stat-card">
          <span class="stat-icon" :class="item.tone">
            <component :is="item.icon" :size="22" />
          </span>
          <div>
            <div class="stat-label">{{ item.label }}</div>
            <div class="stat-value">
              {{ item.value }}
              <span class="stat-unit">{{ item.unit }}</span>
            </div>
          </div>
        </article>
      </section>

      <section class="panel">
        <div class="panel-header">
          <div class="panel-title">节点拓扑图</div>
          <div class="header-tools">
            <button class="btn btn-text" type="button" @click="openClusterAnalysis">
              {{ agentRunning ? '分析中...' : '智能分析' }}
            </button>
            <span>集群节点状态</span>
          </div>
        </div>
        <div class="topology">
          <svg class="topology-svg" :viewBox="topologyViewBox" :style="{ height: `${70 + Math.ceil(topologyNodes.length / 4) * 130}px` }" role="img" aria-label="推送节点状态">
            <g
              v-for="node in topologyNodes"
              :key="node.id"
              class="node-group"
              @click="openLog(node)"
            >
              <circle
                class="node-circle"
                :cx="nodePositions[node.id].x"
                :cy="nodePositions[node.id].y"
                r="42"
                :stroke="statusMap[node.status].color"
              />
              <circle
                class="node-status-dot"
                :cx="nodePositions[node.id].x + 30"
                :cy="nodePositions[node.id].y - 30"
                r="5"
                :fill="statusMap[node.status].color"
                :style="{ animation: node.status === 'offline' ? 'none' : undefined }"
              />
              <text class="node-label" :x="nodePositions[node.id].x" :y="nodePositions[node.id].y - 5">
                machineId={{ node.machineId }}
              </text>
              <text class="node-sub-label" :x="nodePositions[node.id].x" :y="nodePositions[node.id].y + 12">
                :{{ node.port }}
              </text>
            </g>
          </svg>

          <button
            v-if="nodes.length > 8"
            class="topology-expand"
            type="button"
            @click="topologyExpanded = !topologyExpanded"
          >
            {{ topologyExpanded ? '收起其余节点' : `展开其余 ${nodes.length - 8} 个节点` }}
          </button>
          <div class="topology-legend">
            <div class="legend-item"><span class="legend-dot normal"></span>正常运行</div>
            <div class="legend-item"><span class="legend-dot abnormal"></span>异常告警</div>
            <div class="legend-item"><span class="legend-dot offline"></span>离线状态</div>
          </div>

        </div>
      </section>

      <section class="panel">
        <div class="panel-header">
          <div class="panel-title">节点列表</div>
          <div class="header-tools">共 {{ filteredNodes.length }} 个节点</div>
        </div>

        <div class="table-toolbar">
          <button class="btn btn-default" type="button" @click="refreshData">
            <RefreshCw :size="15" :class="{ spinning: refreshing }" />刷新
          </button>
          <div class="toolbar-right">
            <select v-model="statusFilter" class="select" aria-label="节点状态筛选">
              <option value="all">全部状态</option>
              <option value="normal">正常</option>
              <option value="abnormal">异常</option>
              <option value="offline">离线</option>
            </select>
            <label class="search-box">
              <Search class="search-icon" :size="15" />
              <input v-model="keyword" class="search-input" type="search" placeholder="搜索节点ID/IP/端口" />
            </label>
          </div>
        </div>

        <div class="table-wrap">
          <table class="data-table">
            <thead>
              <tr>
                <th class="checkbox-col">
                  <input type="checkbox" :checked="allFilteredSelected" @change="toggleSelectAll" />
                </th>
                <th>节点ID</th>
                <th>机器ID</th>
                <th>IP地址</th>
                <th>端口</th>
                <th>状态</th>
                <th>经过消息</th>
                <th>连接数</th>
                <th>进程 CPU</th>
                <th>最后上报</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="node in filteredNodes" :key="node.id">
                <td class="checkbox-col">
                  <input v-model="selectedIds" type="checkbox" :value="node.id" />
                </td>
                <td class="node-id"><button class="btn btn-text" type="button" @click="openLog(node)">{{ node.id }}</button></td>
                <td>{{ node.machineId }}</td>
                <td>{{ node.ip }}</td>
                <td>{{ node.port }}</td>
                <td>
                  <span class="status-tag" :class="statusMap[node.status].className">
                    {{ statusMap[node.status].text }}
                  </span>
                </td>
                <td>{{ node.messages.toLocaleString() }}</td>
                <td>{{ node.connections.toLocaleString() }}</td>
                <td>
                  <div class="metric-bar">
                    <div class="bar-track">
                      <div class="bar-fill" :class="cpuClass(node.cpu)" :style="{ width: `${node.cpu}%` }"></div>
                    </div>
                    <span class="bar-text">{{ formatCpu(node.cpu) }}%</span>
                  </div>
                </td>
                <td style="color:#86909c;">{{ node.lastHeartbeat }}</td>
                <td>
                  <div class="action-cell">
                    <button class="btn btn-text" type="button" @click="openLog(node)">日志</button>
                    <button class="btn btn-text" type="button" @click="openDetail(node)">详情</button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

      </section>
    </main>

    <main v-else-if="activePage === '消息管理'" class="main-content message-management-page">
      <section class="message-page-header">
        <div>
          <div class="message-page-title"><MessageSquare :size="20" />消息管理</div>
          <div class="message-page-subtitle">分页查询消息主体、链路标识和实际经过的推送节点</div>
        </div>
        <button class="btn btn-default" type="button" @click="refreshData">
          <RefreshCw :size="15" :class="{ spinning: refreshing }" />刷新
        </button>
      </section>

      <section class="message-query-panel">
        <label>
          <span>LogID</span>
          <input v-model="messageLogIdQuery" type="search" placeholder="输入完整或部分 LogID" @keyup.enter="searchMessages" />
        </label>
        <label>
          <span>发送 UID</span>
          <input v-model="messageUidQuery" inputmode="numeric" type="search" placeholder="输入 UID" @keyup.enter="searchMessages" />
        </label>
        <label>
          <span>RoomID</span>
          <input v-model="messageRoomIdQuery" inputmode="numeric" type="search" placeholder="输入房间 ID" @keyup.enter="searchMessages" />
        </label>
        <label>
          <span>消息内容</span>
          <input v-model="messageKeyword" type="search" placeholder="搜索消息正文" @keyup.enter="searchMessages" />
        </label>
        <div class="message-query-actions">
          <button class="btn btn-default" type="button" @click="resetMessageFilters">重置</button>
          <button class="btn btn-primary" type="button" @click="searchMessages"><Search :size="15" />查询</button>
        </div>
      </section>

      <section class="panel message-list-panel">
        <div class="panel-header">
          <div class="panel-title">消息列表</div>
          <div class="header-tools">共 {{ messageTotal.toLocaleString() }} 条消息</div>
        </div>
        <div class="table-wrap">
          <table class="data-table message-table">
            <thead>
              <tr>
                <th>发送时间</th>
                <th>状态</th>
                <th>LogID</th>
                <th>发送 UID</th>
                <th>RoomID</th>
                <th>消息内容</th>
                <th>经过节点</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="message in messageRecords" :key="message.id">
                <td class="message-time">{{ message.createdAt || '-' }}</td>
                <td>
                  <span class="message-status" :class="messageStatusClass(message.status)">
                    {{ messageStatusText(message.status) }}
                  </span>
                </td>
                <td><code class="message-log-id">{{ message.logId }}</code></td>
                <td>{{ message.uid ?? '-' }}</td>
                <td>{{ message.roomId }}</td>
                <td class="message-content">{{ message.content }}</td>
                <td class="message-route">{{ traversedNodesText(message.traversedNodeIds) }}</td>
                <td>
                  <button class="btn btn-text" type="button" @click="openMessageLog(message)">查看日志</button>
                </td>
              </tr>
              <tr v-if="messageRecords.length === 0">
                <td colspan="8"><div class="empty-state">暂无消息数据</div></td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="message-pagination">
          <span>第 {{ messagePage }} / {{ Math.max(messageTotalPages, 1) }} 页</span>
          <label>
            每页
            <select v-model.number="messagePageSize" class="select" @change="searchMessages">
              <option :value="10">10</option>
              <option :value="20">20</option>
              <option :value="50">50</option>
            </select>
          </label>
          <button
            class="icon-btn"
            type="button"
            title="上一页"
            :disabled="messagePage <= 1"
            @click="changeMessagePage(messagePage - 1)"
          >
            <ChevronLeft :size="16" />
          </button>
          <button
            class="icon-btn"
            type="button"
            title="下一页"
            :disabled="messagePage >= messageTotalPages"
            @click="changeMessagePage(messagePage + 1)"
          >
            <ChevronRight :size="16" />
          </button>
        </div>
      </section>
    </main>

    <main v-else-if="activePage === '日志中心'" class="main-content log-center-page">
      <section class="log-center-header">
        <div>
          <div class="log-center-title"><FileSearch :size="20" />{{ logQueryMode === 'machineId' ? '节点日志与房间' : 'LogID 日志' }}</div>
          <div class="log-center-subtitle">{{ logQueryMode === 'machineId' ? '查看指定节点当前的房间 ID 与运行日志' : '按链路 LogID 或节点机器 ID 检索推送中台日志' }}</div>
        </div>
        <button class="btn btn-default" type="button" @click="refreshData">
          <RefreshCw :size="15" :class="{ spinning: refreshing }" />刷新
        </button>
      </section>

      <section class="log-query-panel">
        <div class="query-row">
          <select v-model="logQueryMode" class="select query-mode-select" aria-label="日志查询类型">
            <option value="logId">LogID</option>
            <option value="machineId">机器ID</option>
          </select>
          <label class="query-input">
            <span class="required-mark">*</span>
            <input
              v-if="logQueryMode === 'logId'"
              v-model="logIdQuery"
              type="search"
              placeholder="输入真实日志链路 LogID"
              @keyup.enter="searchLogs"
            />
            <input
              v-else
              v-model="machineIdQuery"
              type="search"
              inputmode="numeric"
              placeholder="输入机器ID，例如 3"
              @keyup.enter="searchLogs"
            />
          </label>
          <button class="btn btn-default" type="button" @click="analyzeLogCenter">智能分析</button>
          <button class="btn btn-primary" type="button" @click="searchLogs"><Search :size="15" />搜索</button>
        </div>
        <div class="query-row query-filters">
          <span class="filter-label">日志等级</span>
          <button
            v-for="level in ['all', 'INFO', 'WARN', 'ERROR']"
            :key="level"
            class="level-filter"
            :class="{ active: logLevelFilter === level, [level]: level !== 'all' }"
            type="button"
            @click="setLogLevel(level)"
          >
            {{ level === 'all' ? '全部' : level }}
          </button>
          <label class="search-box log-center-keyword">
            <Search class="search-icon" :size="15" />
            <input v-model="logSearchKeyword" class="search-input" type="search" placeholder="搜索日志内容 / logger" />
          </label>
        </div>
      </section>

      <NodeRoomsPanel
        v-if="logQueryMode === 'machineId' && nodeLogMachineId !== null"
        ref="nodeLogRooms"
        :api-base-url="apiBaseUrl"
        :machine-id="nodeLogMachineId"
      />

      <section class="log-result-panel">
        <div class="result-summary">
          <span>搜索结果</span>
          <strong>{{ logCenterRecords.length }} 条</strong>
          <span class="result-scope">
            {{ logQueryMode === 'logId' ? `LogID = ${logIdQuery || '全部'}` : `machine_id = ${nodeLogMachineId ?? '-'}` }}
          </span>
        </div>
        <div class="log-result-head">
          <span>PSM / Logger</span>
          <span>时间</span>
          <span>机器ID</span>
          <span>IP</span>
        </div>
        <div v-if="logCenterRecords.length === 0" class="empty-state">暂无匹配日志</div>
        <article v-for="log in logCenterRecords" :key="log.id" class="log-result-item">
          <button class="log-result-meta" type="button" @click="toggleLogDetail(log.id)">
            <component :is="expandedLogIds.includes(log.id) ? ChevronDown : ChevronRight" :size="16" />
            <span class="log-psm">{{ log.logger }}</span>
            <time>{{ log.time }}</time>
            <span>{{ log.machineId }}</span>
            <span>{{ log.hostIp }}</span>
          </button>
          <div v-if="expandedLogIds.includes(log.id)" class="log-content">
            <span class="log-level" :class="log.level">{{ log.level }}</span>
            <span v-if="log.sourceFilePath" class="log-source">
              {{ log.sourceFilePath }}<template v-if="log.sourceLine">:{{ log.sourceLine }}</template>
            </span>
            <code>logId={{ log.logId }} | {{ log.message }}</code>
          </div>
        </article>
      </section>
    </main>

    <div v-if="detailNode" class="modal-overlay" @click.self="closeDetail">
      <div class="modal detail-modal">
        <div class="modal-header">
          <div class="modal-title">节点详情 - {{ detailNode.id }}</div>
          <button class="icon-btn" type="button" title="关闭" @click="closeDetail"><X :size="16" /></button>
        </div>
        <div class="modal-body detail-body">
          <NodeRoomsPanel :api-base-url="apiBaseUrl" :machine-id="detailNode.machineId" />
          <section class="detail-section">
            <h3>基础信息</h3>
            <div class="detail-grid">
              <div><span>机器ID</span><strong>{{ detailNode.machineId }}</strong></div>
              <div><span>服务名</span><strong>{{ detailNode.serverName }}</strong></div>
              <div><span>节点IP</span><strong>{{ detailNode.ip }}</strong></div>
              <div><span>监听端口</span><strong>{{ detailNode.port }}</strong></div>
              <div><span>运行状态</span><strong>{{ statusMap[detailNode.status].text }}</strong></div>
              <div><span>启动时间</span><strong>{{ detailNode.startTime }}</strong></div>
              <div><span>运行时长</span><strong>{{ detailNode.uptime }}</strong></div>
              <div><span>Netty模式</span><strong>{{ detailNode.nettyMode }}</strong></div>
            </div>
          </section>
          <section class="detail-section">
            <h3>运行指标</h3>
            <div class="meter-list">
              <div class="meter">
                <span>JVM 进程 CPU</span>
                <div class="meter-track"><div class="meter-fill cpu" :style="{ width: `${Math.min(detailNode.cpu, 100)}%` }"></div></div>
                <strong>{{ detailNode.cpu.toFixed(1) }}%</strong>
              </div>
              <div class="meter">
                <span>系统 CPU</span>
                <div class="meter-track"><div class="meter-fill cpu" :style="{ width: `${Math.min(detailNode.systemCpuValue, 100)}%` }"></div></div>
                <strong>{{ detailNode.systemCpu }}</strong>
              </div>
              <div class="meter">
                <span>JVM 堆</span>
                <div class="meter-track"><div class="meter-fill heap" :style="{ width: `${detailNode.heapMax ? Math.min(detailNode.heapUsed / detailNode.heapMax * 100, 100) : 0}%` }"></div></div>
                <strong>{{ detailNode.heapUsed }} / {{ detailNode.heapMax }} MB</strong>
              </div>
              <div class="meter">
                <span>系统内存</span>
                <div class="meter-track"><div class="meter-fill memory" :style="{ width: `${detailNode.systemMemoryTotal ? Math.min(detailNode.systemMemoryUsed / detailNode.systemMemoryTotal * 100, 100) : 0}%` }"></div></div>
                <strong>{{ detailNode.systemMemory }}</strong>
              </div>
            </div>
            <div class="detail-grid">
              <div><span>当前连接数</span><strong>{{ detailNode.connections.toLocaleString() }}</strong></div>
              <div><span>经过本节点</span><strong>{{ detailNode.messages.toLocaleString() }}</strong></div>
              <div><span>本进程收到</span><strong>{{ detailNode.processMessages.toLocaleString() }}</strong></div>
              <div><span>JVM 进程 CPU</span><strong>{{ Number(detailNode.cpu).toFixed(1) }}%</strong></div>
              <div><span>系统 CPU</span><strong>{{ detailNode.systemCpu }}</strong></div>
              <div><span>系统内存（JDK 可见范围）</span><strong>{{ detailNode.systemMemory }}</strong></div>
              <div><span>近一分钟业务消息</span><strong>{{ detailNode.messagesLastMinute }}<small v-if="detailNode.messageWindowSeconds != null && detailNode.messageWindowSeconds < 60">（仅采集 {{ detailNode.messageWindowSeconds }} 秒）</small></strong></div>
              <div><span>指标采样时间</span><strong>{{ detailNode.sampledAt }}</strong></div>
              <div><span>堆内存</span><strong>{{ detailNode.heapUsed }} / {{ detailNode.heapMax }} MB</strong></div>
              <div><span>线程数</span><strong>{{ detailNode.threadCount }}</strong></div>
              <div><span>GC次数</span><strong>{{ detailNode.gcCount }}</strong></div>
              <div><span>GC耗时</span><strong>{{ detailNode.gcTimeMs }} ms</strong></div>
            </div>
          </section>
          <section class="detail-section">
            <h3>Netty与异常</h3>
            <div class="detail-grid">
              <div><span>Boss线程数</span><strong>{{ detailNode.bossThreads }}</strong></div>
              <div><span>Worker线程数</span><strong>{{ detailNode.workerThreads }}</strong></div>
              <div><span>最近心跳</span><strong>{{ detailNode.lastHeartbeat }}</strong></div>
              <div><span>最近错误</span><strong>{{ detailNode.lastError }}</strong></div>
            </div>
          </section>
        </div>
        <div class="modal-footer">
          <button class="btn btn-default" type="button" @click="closeDetail">关闭</button>
          <button class="btn btn-default" type="button" @click="openNodeAnalysis(detailNode)">智能分析</button>
          <button class="btn btn-primary" type="button" @click="openLog(detailNode)">查看日志</button>
        </div>
      </div>
    </div>

    <div v-if="agentPanelOpen" class="modal-overlay agent-overlay" @click.self="closeAnalysis">
      <div class="modal agent-modal">
        <div class="modal-header">
          <div class="modal-title">排障助手</div>
          <div class="agent-runtime">
            <label class="agent-model-select">
              <span>模型</span>
              <select v-model="agentModelMode" :disabled="agentRunning || Boolean(agentConversationId)">
                <option value="auto">Auto</option>
                <option value="deepseek-flash">deepseek-flash</option>
              </select>
            </label>
            <span>使用：{{ agentModelName }}</span>
            <span>已思考 {{ agentThinkingSeconds }}s</span>
            <span v-if="agentRunning" class="agent-running-dot"></span>
          </div>
          <button class="icon-btn" type="button" title="关闭" @click="closeAnalysis"><X :size="16" /></button>
        </div>
        <div class="modal-body agent-body">
          <div class="agent-thread">
            <div
              v-for="(message, index) in agentMessages"
              :key="index"
              class="agent-message"
              :class="message.role"
            >
              <div class="agent-message-head">
                <span>{{ message.role === 'user' ? '你' : '排障助手' }}</span>
                <em>{{ agentScopeLabel(message) }}</em>
              </div>
              <div v-if="message.role === 'assistant' && message.steps?.length" class="agent-steps">
                <button class="agent-steps-toggle" type="button" @click="toggleAgentSteps(message)">
                  <component :is="message.stepsCollapsed ? ChevronRight : ChevronDown" :size="14" />
                  <span>{{ agentStepsTitle(message) }}</span>
                  <em>{{ message.steps.length }} 步</em>
                </button>
                <div v-if="!message.stepsCollapsed" class="agent-step-list">
                  <div
                    v-for="step in message.steps"
                    :key="step.title"
                    class="agent-step"
                    :class="step.status"
                  >
                    <span class="agent-step-dot"></span>
                    <div>
                      <strong>{{ step.title }}</strong>
                      <p>{{ step.detail }}</p>
                    </div>
                    <em>{{ agentStepStatusText(step.status) }}</em>
                  </div>
                </div>
              </div>
              <pre v-if="message.role !== 'assistant'">{{ message.content }}</pre>
              <div v-else class="agent-rich" v-html="renderAgentMarkdown(message.content)"></div>
              <span v-if="message.streaming" class="stream-cursor">|</span>
            </div>
            <OperationReviewPanel v-if="agentConversationId" :key="agentConversationId" :api-base-url="apiBaseUrl" :conversation-id="agentConversationId" compact />
          </div>
        </div>
        <div class="agent-input-row">
          <input
            v-model="agentInput"
            class="agent-input"
            type="text"
            :disabled="agentRunning || !agentConversationId"
            :placeholder="agentInputPlaceholder()"
            @keyup.enter="sendAgentInput"
          />
          <button class="btn btn-default" type="button" @click="closeAnalysis">关闭</button>
          <button
            class="btn btn-primary"
            type="button"
            :disabled="agentRunning || !agentConversationId || !agentInput.trim()"
            @click="sendAgentInput"
          >
            发送
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
