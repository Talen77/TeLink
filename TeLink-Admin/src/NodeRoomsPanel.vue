<script setup>
import { onMounted, onUnmounted, ref, watch } from 'vue';
import { RefreshCw } from 'lucide-vue-next';

const props = defineProps({
  apiBaseUrl: { type: String, required: true },
  machineId: { type: [Number, String], required: true }
});
const data = ref(null);
const loading = ref(false);
const error = ref('');
let controller;
let requestId = 0;
let refreshTimer;

async function refresh(reset = false) {
  const current = ++requestId;
  controller?.abort();
  const request = new AbortController();
  controller = request;
  if (reset) data.value = null;
  loading.value = true;
  error.value = '';
  const timeout = window.setTimeout(() => request.abort(), 8000);
  try {
    const response = await fetch(`${props.apiBaseUrl}/nodes/${encodeURIComponent(props.machineId)}/rooms`, { signal: request.signal });
    if (!response.ok) throw new Error(response.status === 404 ? '该节点不存在或已被移除' : '读取房间失败，请重试');
    const result = await response.json();
    if (result.machineId !== Number(props.machineId) || !Array.isArray(result.roomIds)) throw new Error('房间数据格式异常，请重试');
    if (current === requestId) data.value = result;
  } catch (failure) {
    if (current === requestId) {
      data.value = null;
      error.value = failure.name === 'AbortError' ? '读取房间超时，请重试'
        : failure instanceof TypeError ? '读取房间失败，请检查网络后重试' : failure.message;
    }
  } finally {
    window.clearTimeout(timeout);
    if (current === requestId) loading.value = false;
  }
}

watch(() => [props.machineId, props.apiBaseUrl], () => refresh(true), { immediate: true });
onMounted(() => { refreshTimer = window.setInterval(() => refresh(), 10000); });
onUnmounted(() => {
  ++requestId;
  controller?.abort();
  window.clearInterval(refreshTimer);
});
defineExpose({ refresh });
</script>

<template>
  <section class="node-rooms-panel" aria-label="节点当前房间" :aria-busy="loading">
    <div class="node-rooms-header">
      <div class="node-rooms-title">
        <h3>当前房间</h3>
        <span>machineId={{ machineId }}</span>
        <strong v-if="data?.nodeStatus === 'RUNNING'">{{ data.roomIds.length }} 个</strong>
      </div>
      <button class="btn btn-text" type="button" :disabled="loading" @click="refresh()">
        <RefreshCw :size="14" :class="{ spinning: loading }" />刷新房间
      </button>
    </div>
    <div class="node-rooms-content" aria-live="polite">
      <p v-if="error" class="node-rooms-error" role="alert">{{ error }}</p>
      <p v-else-if="!data">正在读取当前房间…</p>
      <p v-else-if="data.nodeStatus !== 'RUNNING'">节点离线或状态已过期，当前房间信息不可用。</p>
      <p v-else-if="data.roomIds.length === 0">该节点当前没有活跃房间。</p>
      <ul v-else class="node-room-ids" aria-label="房间 ID 列表">
        <li v-for="roomId in data.roomIds" :key="roomId"><span>房间</span><code>{{ roomId }}</code></li>
      </ul>
      <div class="node-rooms-note">
        每 10 秒刷新 · 同一房间可同时出现在多个节点
        <span v-if="data"> · 最近读取 {{ new Date(data.queriedAtMillis).toLocaleTimeString() }}</span>
      </div>
    </div>
  </section>
</template>

<style scoped>
.node-rooms-panel { border: 1px solid #e5e6eb; border-radius: 6px; background: #fff; overflow: hidden; }
.node-rooms-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding: 10px 14px; background: #f7f8fa; flex-wrap: wrap; }
.node-rooms-title { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.node-rooms-title h3 { margin: 0; font-size: 14px; color: #1f2329; }
.node-rooms-title span, .node-rooms-note { font-size: 12px; color: #86909c; }
.node-rooms-title strong { color: #165dff; font-size: 13px; }
.node-rooms-content { padding: 14px; }
.node-rooms-content p { margin: 0 0 12px; color: #4e5969; font-size: 13px; }
.node-rooms-content .node-rooms-error { color: #cb272d; }
.node-room-ids { display: flex; flex-wrap: wrap; gap: 8px; max-height: 180px; overflow-y: auto; padding: 0; margin: 0 0 12px; list-style: none; }
.node-room-ids li { display: flex; gap: 6px; align-items: baseline; min-width: 0; max-width: 100%; padding: 5px 10px; border: 1px solid #dbe6ff; border-radius: 4px; background: #f2f6ff; color: #165dff; }
.node-room-ids span { font-size: 12px; color: #4e5969; }
.node-room-ids code { overflow-wrap: anywhere; user-select: text; font-size: 13px; }
.node-rooms-note { line-height: 1.7; }
</style>
