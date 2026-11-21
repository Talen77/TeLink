<script setup>
import { ref, onMounted, onUnmounted } from 'vue';
const props = defineProps({ apiBaseUrl: { type: String, required: true } });
const documents = ref([]), total = ref(0), page = ref(1), busy = ref(false), error = ref(''), notice = ref('');
const fileInput = ref(null), title = ref(''), component = ref(''), service = ref(''), applicable = ref('');
const detail = ref(null), query = ref(''), matches = ref([]), searching = ref(false), searchStatus = ref('');
let timer;
const labels = { QUEUED: '排队中', PARSING: '解析中', INDEXING: '建立索引中', READY: '可检索', RETRY: '等待重试', FAILED: '处理失败', SUPERSEDED: '已被新版本取代', DELETED: '已删除' };
async function request(path, options = {}) {
  const response = await fetch(`${props.apiBaseUrl}/knowledge${path}`, options);
  const value = await response.json();
  if (!response.ok) throw new Error(value.message || value.error || '知识服务请求失败');
  return value;
}
async function load() {
  try { const data = await request(`/documents?page=${page.value}&pageSize=20`); documents.value = data.items; total.value = data.total; error.value = ''; }
  catch (e) { error.value = e.message; }
}
async function upload(id, file) {
  if (!file) { error.value = '请选择 Word（.docx）或 TXT 文件'; return; }
  if (!/\.(docx|txt)$/i.test(file.name) || file.size > 10 * 1024 * 1024 || file.size === 0) { error.value = '支持 .docx / .txt，文件须非空且不超过 10 MiB；旧版 .doc 请先转换。'; return; }
  busy.value = true; error.value = ''; notice.value = '';
  try {
    const params = new URLSearchParams({ filename: file.name });
    if (!id) { params.set('title', title.value.trim() || file.name); params.set('component', component.value.trim()); params.set('service', service.value.trim()); params.set('applicableVersion', applicable.value.trim()); }
    const result = await request(`/documents${id ? `/${id}/versions` : ''}?${params}`, { method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: file });
    notice.value = `文件已保存，版本 ${result.version} 已排队。处理完成后才会参与检索。`;
    if (!id) { fileInput.value.value = ''; title.value = ''; }
    await load();
  } catch (e) { error.value = e.message; } finally { busy.value = false; }
}
async function action(doc, operation) {
  if (operation === 'delete' && !window.confirm(`删除“${doc.title}”？它会立即退出检索，原文件与索引随后由队列清理。`)) return;
  busy.value = true; notice.value = ''; error.value = '';
  try {
    if (operation === 'toggle') await request(`/documents/${doc.id}`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ enabled: !doc.enabled }) });
    else if (operation === 'delete') await request(`/documents/${doc.id}`, { method: 'DELETE' });
    else await request(`/documents/${doc.id}/${operation}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
    notice.value = operation === 'delete' ? '已退出检索，清理任务已排队。' : operation === 'toggle' ? '文档启用状态已更新。' : '处理任务已排队。';
    await load();
  } catch (e) { error.value = e.message; } finally { busy.value = false; }
}
async function showDetail(doc) { try { detail.value = await request(`/documents/${doc.id}`); } catch (e) { error.value = e.message; } }
async function search() {
  if (!query.value.trim()) return;
  searching.value = true; error.value = ''; matches.value = [];
  try {
    const data = await request('/search', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ query: query.value, topK: 5 }) });
    matches.value = data.matches; searchStatus.value = data.matches.length ? `检索到 ${data.matches.length} 个片段` : data.status === 'no_active_knowledge' ? '暂无可检索的有效知识版本' : '没有检索结果';
  } catch (e) { error.value = e.message; } finally { searching.value = false; }
}
function turnPage(delta) { page.value += delta; load(); }
onMounted(() => { load(); timer = window.setInterval(load, 5000); });
onUnmounted(() => window.clearInterval(timer));
</script>

<template>
  <main class="main-content knowledge-page">
    <div class="knowledge-heading"><div><h2>知识库</h2><p>上传排障手册、架构说明与故障复盘，供日志和节点 Agent 检索参考。</p></div><button class="btn btn-default" @click="load">刷新状态</button></div>
    <div v-if="error" role="alert" class="knowledge-error">{{ error }}</div>
    <div v-if="notice" role="status" class="knowledge-notice">{{ notice }}</div>
    <section class="panel knowledge-upload">
      <h3>上传知识文档</h3>
      <div class="knowledge-form">
        <label>文件 <input ref="fileInput" type="file" accept=".docx,.txt" :disabled="busy" /></label>
        <label>标题 <input v-model="title" maxlength="255" placeholder="留空使用文件名" /></label>
        <label>组件 <input v-model="component" maxlength="100" placeholder="例如 jvm、kafka、redis" /></label>
        <label>适用服务 <input v-model="service" maxlength="100" placeholder="留空表示通用" /></label>
        <label>适用版本 <input v-model="applicable" maxlength="100" placeholder="留空表示通用" /></label>
      </div>
      <div class="knowledge-upload-footer"><span>支持 .docx / .txt，最大 10 MiB。上传后异步解析和建立索引；Word 图片中的文字不参与检索。</span><button class="btn btn-primary" :disabled="busy" @click="upload(null, fileInput?.files?.[0])">{{ busy ? '处理中…' : '上传并建立索引' }}</button></div>
    </section>
    <section class="panel knowledge-documents">
      <div class="panel-header"><h3>文档管理</h3><span>{{ total }} 篇文档</span></div>
      <div class="knowledge-table-wrap"><table class="knowledge-table"><thead><tr><th>文档</th><th>适用范围</th><th>处理状态</th><th>检索版本</th><th>操作</th></tr></thead><tbody>
        <tr v-if="!documents.length"><td colspan="5" class="knowledge-empty">暂无文档。上传后可在这里查看处理进度和失败原因。</td></tr>
        <tr v-for="doc in documents" :key="doc.id">
          <td><button class="knowledge-title" @click="showDetail(doc)">{{ doc.title }}</button><small>{{ doc.file_name }}</small><small>{{ Math.ceil(doc.byte_size / 1024) }} KiB · 最新 v{{ doc.latest_version }}</small></td>
          <td>{{ doc.component || '通用组件' }}<small>{{ doc.service_name || '通用服务' }} · {{ doc.applicable_version || '通用版本' }}</small></td>
          <td><span class="knowledge-status" :class="doc.status.toLowerCase()">{{ labels[doc.status] || doc.status }}</span><small v-if="doc.error_message" class="knowledge-error-text">{{ doc.error_message }}</small><small v-if="!doc.indexCompatible" class="knowledge-error-text">索引配置已变化，请重新建立索引。</small></td>
          <td>{{ doc.enabled ? (doc.active_version ? `v${doc.active_version}` : '尚未生效') : '已禁用' }}<small>{{ doc.chunk_count }} 个片段</small></td>
          <td><div class="knowledge-actions"><button class="btn btn-text" :disabled="busy" @click="action(doc, 'toggle')">{{ doc.enabled ? '禁用' : '启用' }}</button><button v-if="doc.status === 'FAILED'" class="btn btn-text" :disabled="busy" @click="action(doc, 'retry')">重试</button><button class="btn btn-text" :disabled="busy" @click="action(doc, 'reindex')">重新索引</button><label class="knowledge-replace">上传新版本<input type="file" accept=".docx,.txt" :disabled="busy" @change="upload(doc.id, $event.target.files[0]); $event.target.value = ''" /></label><button class="btn btn-text knowledge-danger" :disabled="busy" @click="action(doc, 'delete')">删除</button></div></td>
        </tr>
      </tbody></table></div>
      <div class="knowledge-pagination"><button class="btn btn-default" :disabled="page <= 1" @click="turnPage(-1)">上一页</button><span>第 {{ page }} 页</span><button class="btn btn-default" :disabled="page * 20 >= total" @click="turnPage(1)">下一页</button></div>
    </section>
    <section class="panel knowledge-search"><h3>检索验证</h3><p>输入真实异常或诊断问题，检查 Agent 能检索到哪些知识。</p><form @submit.prevent="search"><input v-model="query" maxlength="4000" placeholder="例如：Full GC 后堆内存仍持续偏高，如何排查？" /><button class="btn btn-primary" :disabled="searching">{{ searching ? '检索中…' : '检索知识' }}</button></form><p role="status">{{ searchStatus }}</p><article v-for="match in matches" :key="match.chunkId" class="knowledge-match"><strong>{{ match.title }} · v{{ match.version }}</strong><small>{{ match.heading }} · {{ match.fileName }}</small><p>{{ match.content }}</p><small>片段 {{ match.chunkId }}</small></article></section>
    <div v-if="detail" class="knowledge-overlay" @click.self="detail = null"><section class="knowledge-detail"><div class="knowledge-heading"><h3>{{ detail.title }}</h3><button class="btn btn-default" @click="detail = null">关闭</button></div><p>文档 ID：{{ detail.id }}</p><h4>版本</h4><article v-for="version in detail.versions" :key="version.version"><strong>v{{ version.version }} · {{ labels[version.status] || version.status }}</strong><p>{{ version.file_name }} · {{ version.chunk_count }} 个片段</p><p v-if="version.error_message" class="knowledge-error-text">{{ version.error_message }}</p></article><h4>最近处理任务</h4><article v-for="task in detail.tasks" :key="task.id"><strong>v{{ task.version }} · {{ task.kind }} · {{ task.state }}</strong><p>尝试次数 {{ task.attempts }} · {{ task.id }}</p><p class="knowledge-error-text">{{ task.error_message }}</p></article></section></div>
  </main>
</template>

<style scoped>
.knowledge-page { display: flex; flex-direction: column; gap: 20px; }
.knowledge-heading { display: flex; justify-content: space-between; align-items: center; gap: 16px; }
.knowledge-heading h2, h3 { margin: 0 0 8px; }.knowledge-heading p,.knowledge-search>p { color: #6b7280; margin: 0; }
.knowledge-upload,.knowledge-search { padding: 24px; }.knowledge-form { display: grid; grid-template-columns: repeat(auto-fit,minmax(170px,1fr)); gap: 16px; margin: 20px 0; }
.knowledge-form label { display: flex; flex-direction: column; gap: 8px; font-size: 13px; color: #4b5563; }
.knowledge-form input,.knowledge-search input { box-sizing: border-box; width: 100%; border: 1px solid #d1d5db; border-radius: 6px; padding: 10px; background: white; color: #1f2937; }
.knowledge-upload-footer { display:flex; align-items:center; justify-content:space-between; gap:20px; }.knowledge-upload-footer span,small { font-size:12px; color:#6b7280; }
.knowledge-table-wrap { overflow-x:auto; }.knowledge-table { width:100%; border-collapse:collapse; text-align:left; }.knowledge-table th,.knowledge-table td { padding:16px; border-bottom:1px solid #f0f1f4; vertical-align:top; }.knowledge-table th { font-size:12px; color:#6b7280; background:#f8fafc; }
.knowledge-table small,.knowledge-match small { display:block; margin-top:6px; }.knowledge-title { border:0; padding:0; color:#165dff; background:transparent; cursor:pointer; text-align:left; overflow-wrap:anywhere; font-weight:600; }
.knowledge-status { padding:4px 8px; background:#eef2ff; color:#334155; border-radius:4px; font-size:12px; }.knowledge-status.ready { background:#ecfdf5; color:#047857; }.knowledge-status.failed { background:#fff1f2; color:#be123c; }
.knowledge-actions { display:flex; flex-wrap:wrap; gap:6px; max-width:280px; }.knowledge-actions button { padding:4px; }.knowledge-replace { cursor:pointer; color:#165dff; padding:4px; font-size:12px; }.knowledge-replace input { display:none; }
.knowledge-danger,.knowledge-error-text { color:#b42318!important; overflow-wrap:anywhere; max-width:280px; }.knowledge-error,.knowledge-notice { padding:14px 18px; border-radius:6px; background:#fff1f2; color:#b42318; }.knowledge-notice { background:#eff6ff; color:#1d4ed8; }
.knowledge-empty { text-align:center; padding:40px!important; color:#6b7280; }.knowledge-pagination { display:flex; align-items:center; justify-content:flex-end; gap:16px; padding:16px; }.knowledge-search form { display:flex; gap:12px; margin:20px 0; }.knowledge-search form input { flex:1; }.knowledge-match { padding:16px; border:1px solid #e5e7eb; margin-top:12px; border-radius:6px; }.knowledge-match p { white-space:pre-wrap; line-height:1.7; overflow-wrap:anywhere; }
.knowledge-overlay { position:fixed; inset:0; background:#11182766; z-index:1100; display:grid; place-items:center; padding:24px; }.knowledge-detail { background:white; width:min(720px,100%); max-height:85vh; overflow:auto; padding:24px; border-radius:12px; }.knowledge-detail article { border-top:1px solid #e5e7eb; padding:12px 0; }.knowledge-detail p { overflow-wrap:anywhere; font-size:13px; }.knowledge-page button:disabled { opacity:.5; cursor:not-allowed; }
@media(max-width:700px){.knowledge-heading,.knowledge-upload-footer { align-items:flex-start; flex-direction:column; }.knowledge-search form { flex-direction:column; }}
</style>
