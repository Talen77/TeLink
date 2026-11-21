<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { CircleAlert, MessageCircle, RefreshCw, Send, Server, Wifi, WifiOff } from 'lucide-vue-next';

const apiBaseUrl = import.meta.env.VITE_TELINK_ADMIN_API || 'http://127.0.0.1:9090/admin/api';
const nodes = ref([]);
const availableRooms = ref([]);
const selectedEndpoint = ref('');
const connectionState = ref('disconnected');
const errorMessage = ref('');
const createRoomId = ref('');
const joinRoomId = ref('');
const joinedRoomId = ref('');
const content = ref('');
const userId = ref('');
const sentMessages = ref([]);

let socket;
const sentMessageUserIds = new Map();

const connected = computed(() => connectionState.value === 'connected');
const joinedRoomText = computed(() => joinedRoomId.value ? `当前房间：${joinedRoomId.value}` : '当前未进入房间');
const connectionText = computed(() => {
  if (connectionState.value === 'connecting') {
    return '连接中';
  }
  if (connectionState.value === 'connected') {
    return '已连接';
  }
  return '未连接';
});

async function loadNodesAndConnect() {
  disconnect();
  availableRooms.value = [];
  errorMessage.value = '';
  try {
    const response = await fetch(`${apiBaseUrl}/client-nodes`);
    if (!response.ok) {
      throw new Error('读取节点失败');
    }
    nodes.value = await response.json();
    if (nodes.value.length === 0) {
      throw new Error('未配置可用消息节点');
    }
    const node = nodes.value[Math.floor(Math.random() * nodes.value.length)];
    selectedEndpoint.value = node.endpoint;
    connect(node.endpoint);
  } catch (error) {
    connectionState.value = 'disconnected';
    errorMessage.value = error.message || '连接消息节点失败';
  }
}

async function loadAvailableRooms() {
  try {
    const response = await fetch(`${apiBaseUrl}/client-rooms`);
    if (!response.ok) {
      throw new Error('读取房间列表失败');
    }
    availableRooms.value = await response.json();
    if (!availableRooms.value.some((room) => String(room.roomId) === joinRoomId.value)) {
      joinRoomId.value = availableRooms.value.length > 0 ? String(availableRooms.value[0].roomId) : '';
    }
  } catch (error) {
    availableRooms.value = [];
    if (!errorMessage.value) {
      errorMessage.value = error.message || '读取房间列表失败';
    }
  }
}

function connect(endpoint) {
  connectionState.value = 'connecting';
  socket = new WebSocket(endpoint);
  socket.onopen = () => {
    connectionState.value = 'connected';
    errorMessage.value = '';
    loadAvailableRooms();
  };
  socket.onmessage = (event) => {
    try {
      const response = JSON.parse(event.data);
      if (response.type === 'ROOM_CREATED' || response.type === 'ROOM_JOINED') {
        joinedRoomId.value = response.roomId ? String(response.roomId) : joinedRoomId.value;
        loadAvailableRooms();
      }
      if (response.type === 'ROOM_REJECTED' || response.type === 'MESSAGE_REJECTED') {
        errorMessage.value = response.message || '请求被拒绝';
      } else {
        errorMessage.value = '';
      }
      // ACK 响应暂不携带 userId，通过 messageId 找回发送时的用户；下行消息则直接使用服务端 userId。
      const responseUserId = response.userId ?? sentMessageUserIds.get(response.messageId);
      sentMessages.value.unshift({
        id: crypto.randomUUID(),
        messageId: response.messageId || '-',
        logId: response.logId || '-',
        roomId: response.roomId || '-',
        userId: responseUserId ?? '-',
        status: response.type,
        message: response.content || response.message
      });
      if (response.type === 'MESSAGE_DELIVERED' || response.type === 'MESSAGE_REJECTED') {
        sentMessageUserIds.delete(response.messageId);
      }
    } catch {
      errorMessage.value = '消息节点返回了无法识别的数据';
    }
  };
  socket.onerror = () => {
    errorMessage.value = '消息节点连接异常';
  };
  socket.onclose = () => {
    connectionState.value = 'disconnected';
    joinedRoomId.value = '';
  };
}

function disconnect() {
  if (socket) {
    socket.onclose = null;
    socket.close();
    socket = null;
  }
  connectionState.value = 'disconnected';
  joinedRoomId.value = '';
  sentMessageUserIds.clear();
}

function parsePositiveInteger(value, label) {
  const numericValue = Number(value);
  if (!Number.isSafeInteger(numericValue) || numericValue <= 0) {
    errorMessage.value = `${label}必须是正整数`;
    return null;
  }
  return numericValue;
}

function parseOptionalUserId() {
  if (!userId.value.trim()) {
    return undefined;
  }
  return parsePositiveInteger(userId.value.trim(), '用户 ID');
}

function sendRoomAction(type, roomValue) {
  if (!connected.value || !socket) {
    errorMessage.value = '消息节点未连接';
    return;
  }
  const numericRoomId = parsePositiveInteger(roomValue, '房间 ID');
  if (!numericRoomId) {
    return;
  }
  const numericUserId = parseOptionalUserId();
  if (userId.value.trim() && !numericUserId) {
    return;
  }
  socket.send(JSON.stringify({
    type,
    userId: numericUserId,
    roomId: numericRoomId
  }));
  errorMessage.value = '';
}

function createRoom() {
  sendRoomAction('CREATE_ROOM', createRoomId.value.trim());
}

function joinRoom() {
  if (!joinRoomId.value) {
    errorMessage.value = '请先选择可加入的房间';
    return;
  }
  sendRoomAction('JOIN_ROOM', joinRoomId.value);
}

function sendMessage() {
  if (!connected.value || !socket) {
    errorMessage.value = '消息节点未连接';
    return;
  }
  if (!joinedRoomId.value) {
    errorMessage.value = '请先新建房间或加入房间';
    return;
  }
  if (!content.value.trim()) {
    errorMessage.value = '请输入消息内容';
    return;
  }
  const numericUserId = parseOptionalUserId();
  if (userId.value.trim() && !numericUserId) {
    return;
  }
  const messageId = crypto.randomUUID();
  sentMessageUserIds.set(messageId, numericUserId);
  socket.send(JSON.stringify({
    type: 'CHAT_MESSAGE',
    messageId,
    userId: numericUserId,
    roomId: Number(joinedRoomId.value),
    content: content.value.trim(),
    sentAt: Date.now()
  }));
  content.value = '';
  errorMessage.value = '';
}

onMounted(() => {
  loadNodesAndConnect();
  loadAvailableRooms();
});
onUnmounted(disconnect);
</script>

<template>
  <main class="client-shell">
    <section class="client-header">
      <div class="brand">
        <span class="brand-icon"><MessageCircle :size="20" /></span>
        <div>
          <h1>弹幕消息客户端</h1>
          <p>向房间发送消息</p>
        </div>
      </div>
      <div class="connection" :class="connectionState">
        <component :is="connected ? Wifi : WifiOff" :size="16" />
        <span>{{ connectionText }}</span>
      </div>
    </section>

    <section class="connection-panel">
      <div>
        <span class="field-label">消息节点</span>
        <strong>{{ selectedEndpoint || '未发现节点' }}</strong>
      </div>
      <button class="icon-button" type="button" title="重新选择节点" @click="loadNodesAndConnect">
        <RefreshCw :size="17" />
      </button>
    </section>

    <section v-if="errorMessage" class="error-panel">
      <CircleAlert :size="16" />
      <span>{{ errorMessage }}</span>
    </section>

    <section class="room-panel">
      <div class="room-panel-header">
        <div>
          <span class="field-label">入房状态</span>
          <strong>{{ joinedRoomText }}</strong>
        </div>
        <button class="icon-button" type="button" title="刷新房间列表" @click="loadAvailableRooms">
          <RefreshCw :size="17" />
        </button>
      </div>

      <div class="room-actions">
        <label>
          <span class="field-label">新建房间</span>
          <input v-model="createRoomId" inputmode="numeric" type="text" placeholder="输入新的房间 ID" />
        </label>
        <button class="secondary-button" type="button" :disabled="!connected" @click="createRoom">
          新建并进入
        </button>

        <label>
          <span class="field-label">加入已有房间</span>
          <select v-model="joinRoomId" :disabled="availableRooms.length === 0">
            <option value="">请选择房间</option>
            <option v-for="room in availableRooms" :key="room.roomId" :value="String(room.roomId)">
              房间 {{ room.roomId }}（节点 {{ room.machineIds.join(', ') }}）
            </option>
          </select>
        </label>
        <button class="secondary-button" type="button" :disabled="!connected || availableRooms.length === 0" @click="joinRoom">
          加入房间
        </button>
      </div>
    </section>

    <section class="message-panel">
      <label>
        <span class="field-label">用户 ID</span>
        <input v-model="userId" inputmode="numeric" type="text" placeholder="可选" />
      </label>
      <label>
        <span class="field-label">当前房间</span>
        <input :value="joinedRoomId || '尚未入房'" type="text" readonly />
      </label>
      <label class="content-field">
        <span class="field-label">消息内容</span>
        <textarea v-model="content" rows="5" placeholder="输入要发送的消息"></textarea>
      </label>
      <button class="send-button" type="button" :disabled="!connected || !joinedRoomId" @click="sendMessage">
        <Send :size="17" />发送消息
      </button>
    </section>

    <section class="result-panel">
      <div class="result-title"><Server :size="16" />发送结果</div>
      <div v-if="sentMessages.length === 0" class="empty-state">暂无服务端确认结果</div>
      <article v-for="message in sentMessages" :key="message.id" class="result-row">
        <div>
          <strong>{{ message.status }}</strong>
          <p>{{ message.message }}</p>
        </div>
        <span>roomId={{ message.roomId }}</span>
        <span>userId={{ message.userId }}</span>
        <span>messageId={{ message.messageId }}</span>
        <span>logId={{ message.logId }}</span>
      </article>
    </section>
  </main>
</template>
