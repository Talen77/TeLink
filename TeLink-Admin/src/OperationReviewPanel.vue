<script setup>
import { computed, ref, watch, nextTick, onMounted, onUnmounted } from 'vue';
const props = defineProps({ apiBaseUrl: { type: String, required: true }, conversationId: { type: String, default: '' }, compact: Boolean });
const items = ref([]), page = ref(1), loading = ref(false), error = ref(''), notice = ref('');
const enabled = ref(true), reviewConfigured = ref(false), selected = ref(null), submitting = ref(false);
const reviewer = ref(''), reviewKey = ref(''), comment = ref(''), confirmation = ref(''), reviewError = ref('');
const dialog = ref(null);
let previousFocus;
let timer, controller, generation = 0, disposed = false;
const labels = { PENDING_APPROVAL: '待人工审核', APPROVED: '已批准 · 等待节点', RUNNING: '执行中', VERIFYING: '等待重启确认', SUCCEEDED: '已完成', FAILED: '执行失败', UNKNOWN: '结果未知 · 请人工检查', EXPIRED: '已过期', REJECTED: '已拒绝' };
const actionName = (action) => action === 'RESTART' ? '重启节点' : '请求 JVM 内存回收';
const time = (value) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—';
const size = (value) => value == null ? '未知' : `${(value / 1024 / 1024).toFixed(0)} MiB`;
const percent = (value) => value == null ? '未知' : `${Number(value).toFixed(1)}%`;
const pending = computed(() => items.value.filter(item => item.state === 'PENDING_APPROVAL').length);
const canReview = computed(() => enabled.value && reviewConfigured.value && !submitting.value && selected.value?.state === 'PENDING_APPROVAL' && selected.value.expiresAt > Date.now() && reviewer.value.trim() && reviewKey.value.trim());
async function load() {
  if (loading.value || disposed || (props.compact && !props.conversationId)) return;
  const version = generation;
  const requestController = new AbortController();
  controller = requestController; loading.value = true;
  const timeout = setTimeout(() => requestController.abort(), 12000);
  try {
    const query = new URLSearchParams({ page: String(page.value), pageSize: '20' });
    if (props.conversationId) query.set('conversationId', props.conversationId);
    const response = await fetch(`${props.apiBaseUrl}/agent/operations?${query}`, { signal: requestController.signal });
    const data = await response.json();
    if (!response.ok) throw new Error(data.message || '获取操作申请失败');
    if (version !== generation || disposed) return;
    items.value = data.items || []; enabled.value = data.enabled; reviewConfigured.value = data.reviewConfigured; error.value = '';
    // 对话框保留打开时的不可变计划，仅同步状态；提交时仍使用已审阅的摘要。
    const latest = items.value.find(item => item.id === selected.value?.id);
    if (latest && selected.value && latest.planDigest === selected.value.planDigest) {
      selected.value = { ...selected.value, state: latest.state, events: latest.events, result: latest.result };
    }
  } catch (e) { if (version === generation && !disposed) error.value = e.name === 'AbortError' ? '获取申请超时，请刷新重试' : e.message; }
  finally { clearTimeout(timeout); if (version === generation) loading.value = false; }
}
function reset() {
  generation++; controller?.abort(); loading.value = false; page.value = 1; items.value = []; selected.value = null; error.value = ''; load();
}
function open(item) { previousFocus = document.activeElement; selected.value = JSON.parse(JSON.stringify(item)); confirmation.value = ''; comment.value = ''; reviewKey.value = ''; reviewError.value = ''; nextTick(() => dialog.value?.focus()); }
function close() { if (submitting.value) return; selected.value = null; reviewKey.value = ''; reviewError.value = ''; previousFocus?.focus(); }
function dialogKeys(event) {
  if (!selected.value) return;
  if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close(); }
  if (event.key !== 'Tab' || !dialog.value) return;
  const controls = [...dialog.value.querySelectorAll('button:not(:disabled), input:not(:disabled), textarea:not(:disabled), summary')].filter(element => element.getClientRects().length);
  const first = controls[0], last = controls.at(-1);
  if (!first) { event.preventDefault(); dialog.value.focus(); return; }
  if (event.shiftKey && (document.activeElement === first || !controls.includes(document.activeElement))) { event.preventDefault(); last.focus(); }
  else if (!event.shiftKey && (document.activeElement === last || !dialog.value.contains(document.activeElement))) { event.preventDefault(); first.focus(); }
}
async function decide(decision) {
  if (!canReview.value || (decision === 'APPROVE' && confirmation.value !== selected.value.confirmationText)) return;
  submitting.value = true; reviewError.value = ''; notice.value = '';
  try {
    const response = await fetch(`${props.apiBaseUrl}/agent/operations/${selected.value.id}/decision`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Operation-Review-Key': reviewKey.value },
      body: JSON.stringify({ decision, planDigest: selected.value.planDigest, reviewer: reviewer.value.trim(), comment: comment.value, confirmationText: confirmation.value }),
      signal: AbortSignal.timeout(15000),
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.message || '审核失败');
    selected.value = result;
    notice.value = decision === 'APPROVE' ? '已批准，目标节点将认领执行。执行状态会自动更新。' : '已拒绝，该申请不会执行。';
    reviewKey.value = ''; await load();
  } catch (e) { reviewError.value = e.name === 'TimeoutError' || e.name === 'AbortError' ? '审核响应超时，结果可能已保存。请刷新确认状态，重复提交不会重复执行。' : e.message; await load(); }
  finally { submitting.value = false; }
}
function changePage(delta) { generation++; controller?.abort(); loading.value = false; page.value += delta; load(); }
watch(() => props.conversationId, reset);
onMounted(() => { load(); timer = setInterval(load, 3000); window.addEventListener('keydown', dialogKeys, true); });
onUnmounted(() => { disposed = true; generation++; clearInterval(timer); controller?.abort(); reviewKey.value = ''; window.removeEventListener('keydown', dialogKeys, true); });
</script>

<template>
  <section class="operation-panel" :class="{ compact }" aria-label="操作审核">
    <header class="operation-heading"><div><h2>{{ compact ? '本次对话的操作申请' : '操作审核' }}</h2><p>核对节点、知识依据和影响，批准后由目标节点执行。{{ pending ? `本页 ${pending} 项待审核。` : '' }}</p></div><button class="btn btn-default" :disabled="loading" @click="load">刷新</button></header>
    <p v-if="error" class="operation-alert danger" role="alert">{{ error }}</p>
    <p v-if="notice" class="operation-alert" role="status">{{ notice }}</p>
    <p v-if="!enabled" class="operation-alert">人工审核操作尚未启用。请完成后台操作配置后重启服务。</p>
    <p v-else-if="!reviewConfigured && !error" class="operation-alert">服务端尚未配置审核密钥，当前不能批准或拒绝申请。</p>
    <div v-if="!items.length && !error" class="operation-empty">{{ loading ? '正在获取操作申请…' : '暂无操作申请。Agent 判断有必要且知识依据充分时，会在这里生成审核卡片。' }}</div>
    <article v-for="item in items" :key="item.id" class="operation-card">
      <div class="operation-card-head"><h3>{{ actionName(item.action) }} <span>节点 {{ item.machineId }}</span></h3><span class="operation-status" :class="item.state.toLowerCase()">{{ labels[item.state] || item.state }}</span></div>
      <p class="operation-reason">{{ item.reason }}</p>
      <p class="operation-impact">{{ item.impact }}</p>
      <div class="operation-meta"><span>{{ item.deploymentId || item.metrics?.hostIp || '未配置部署标识' }}</span><span>{{ time(item.createdAt) }}</span><span v-if="item.reviewer">审核人：{{ item.reviewer }}</span></div>
      <div class="operation-card-foot"><span>{{ item.knowledgeReferences?.length || 0 }} 条知识依据 · {{ item.state === 'PENDING_APPROVAL' ? `审核截止 ${time(item.expiresAt)}` : `更新于 ${time(item.updatedAt)}` }}</span><button class="btn" :class="item.state === 'PENDING_APPROVAL' ? 'btn-primary' : 'btn-default'" @click="open(item)">{{ item.state === 'PENDING_APPROVAL' ? '查看并审核' : '查看记录' }}</button></div>
      <p v-if="item.result?.message" class="operation-result">{{ item.result.message }}</p>
    </article>
    <footer v-if="items.length || page > 1" class="operation-pagination"><button class="btn btn-default" :disabled="page === 1 || loading" @click="changePage(-1)">上一页</button><span>第 {{ page }} 页</span><button class="btn btn-default" :disabled="items.length < 20 || loading" @click="changePage(1)">下一页</button></footer>
    <Teleport to="body">
      <div v-if="selected" class="operation-overlay" @click.self="close">
        <section ref="dialog" class="operation-dialog" role="dialog" aria-modal="true" aria-labelledby="operation-review-title" tabindex="-1">
          <header class="operation-heading"><div><h2 id="operation-review-title">{{ actionName(selected.action) }} · 节点 {{ selected.machineId }}</h2><span class="operation-status" :class="selected.state.toLowerCase()">{{ labels[selected.state] }}</span></div><button class="btn btn-default" :disabled="submitting" @click="close">关闭</button></header>
          <p class="operation-impact">{{ selected.impact }}</p>
          <dl class="operation-facts"><div><dt>目标部署</dt><dd>{{ selected.deploymentId || '未配置' }} · {{ selected.metrics?.hostIp }}</dd></div><div><dt>节点实例</dt><dd>{{ selected.instanceId }}</dd></div><div><dt>申请原因</dt><dd>{{ selected.reason }}</dd></div><div><dt>预期效果</dt><dd>{{ selected.expectedOutcome }}</dd></div><div><dt>审核截止</dt><dd>{{ time(selected.expiresAt) }}</dd></div></dl>
          <h3>申请时的节点快照</h3>
          <div class="operation-metrics"><div>系统 CPU<strong>{{ percent(selected.metrics?.systemCpuUsage) }}</strong></div><div>系统内存<strong>{{ size(selected.metrics?.systemMemoryUsed) }} / {{ size(selected.metrics?.systemMemoryTotal) }}</strong></div><div>JVM 堆<strong>{{ size(selected.metrics?.heapUsed) }} / {{ size(selected.metrics?.heapMax) }}</strong></div><div>最近一分钟消息<strong>{{ selected.metrics?.messagesLastMinute ?? '未知' }} <small>观察 {{ selected.metrics?.messageWindowSeconds ?? '未知' }} 秒</small></strong></div></div>
          <p class="operation-caption">采样时间 {{ time(selected.metrics?.sampledAtMillis) }}。批准时后台会复核节点是否在线及实例、执行配置是否变化。</p>
          <h3>知识依据</h3>
          <details v-for="reference in selected.knowledgeReferences" :key="reference.chunkId" class="operation-reference"><summary>{{ reference.title }} · v{{ reference.version }} · {{ reference.heading || '知识片段' }}</summary><p>{{ reference.content || '该引用没有保存正文，请在知识库查看原文。' }}</p><small>适用服务：{{ reference.service || '通用' }} · 适用版本：{{ reference.applicableVersion || '通用' }}<br />片段 {{ reference.chunkId }}</small></details>
          <details v-if="selected.logEvidence?.length" class="operation-reference"><summary>相关日志证据（{{ selected.logEvidence.length }} 条）</summary><pre>{{ JSON.stringify(selected.logEvidence, null, 2) }}</pre></details>
          <h3>处理记录</h3><ol class="operation-events"><li v-for="(event, index) in selected.events" :key="index"><time>{{ time(event.at) }}</time><span>{{ event.message }}</span></li></ol>
          <p v-if="selected.reviewComment">审核备注：{{ selected.reviewComment }}</p>
          <p v-if="selected.result?.message" class="operation-alert">{{ selected.result.message }}</p>
          <details v-if="selected.result" class="operation-reference"><summary>执行结果与观测数据</summary><pre>{{ JSON.stringify(selected.result, null, 2) }}</pre></details>
          <form v-if="selected.state === 'PENDING_APPROVAL'" class="operation-review-form" @submit.prevent="decide('APPROVE')">
            <h3>人工审核</h3><p class="operation-caption">审核密钥由服务管理员提供，仅用于本次审核，不保存到浏览器，也不发送给 Agent。审核人名称用于记录备注。</p>
            <div class="operation-form-row"><label>审核人<input v-model="reviewer" maxlength="100" autocomplete="off" placeholder="输入姓名或工号" :disabled="submitting" /></label><label>审核密钥<input v-model="reviewKey" type="password" autocomplete="off" placeholder="输入管理端审核密钥" :disabled="submitting" /></label></div>
            <label>审核备注（选填）<textarea v-model="comment" maxlength="1000" rows="2" :disabled="submitting" /></label>
            <label>批准前请输入：<strong>{{ selected.confirmationText }}</strong><input v-model="confirmation" autocomplete="off" :placeholder="selected.confirmationText" :disabled="submitting" /></label>
            <p v-if="reviewError" class="operation-alert danger" role="alert">{{ reviewError }}</p>
            <div class="operation-form-actions"><button type="button" class="btn btn-default" :disabled="!canReview" @click="decide('REJECT')">拒绝申请</button><button class="btn btn-primary" type="submit" :disabled="!canReview || confirmation !== selected.confirmationText">{{ submitting ? '正在提交…' : '批准并执行' }}</button></div>
          </form>
          <p v-else-if="reviewError" class="operation-alert danger" role="alert">{{ reviewError }}</p>
        </section>
      </div>
    </Teleport>
  </section>
</template>

<style scoped>
.operation-panel{margin:24px 28px;display:flex;flex-direction:column;gap:16px}.operation-panel.compact{margin:20px 0 0;border-top:1px solid #e5e7eb;padding-top:20px}.operation-heading,.operation-card-head,.operation-card-foot,.operation-pagination{display:flex;justify-content:space-between;align-items:center;gap:16px}.operation-heading h2{font-size:20px;margin:0 0 8px}.compact h2{font-size:16px}.operation-heading p,.operation-caption{font-size:13px;color:#667085;line-height:1.6;margin:4px 0}.operation-card{padding:22px;background:#fff;border:1px solid #e4e7ec;border-radius:10px}.operation-card h3{font-size:16px;margin:0}.operation-card h3 span{font-weight:400;margin-left:12px;color:#667085}.operation-status{display:inline-block;flex-shrink:0;font-size:12px;border-radius:5px;padding:5px 9px;background:#f2f4f7;color:#475467}.operation-status.pending_approval{background:#fff4dc;color:#965a00}.operation-status.approved,.operation-status.running,.operation-status.verifying{background:#eff4ff;color:#175cd3}.operation-status.succeeded{background:#eafaf1;color:#087443}.operation-status.unknown,.operation-status.failed{background:#fef3f2;color:#b42318}.operation-reason{line-height:1.7;white-space:pre-wrap;overflow-wrap:anywhere}.operation-impact{padding:12px 14px;background:#fff8eb;border-left:3px solid #e9ad37;font-size:13px;line-height:1.7;color:#805610}.operation-meta{display:flex;gap:16px;flex-wrap:wrap;color:#667085;font-size:12px;margin:16px 0}.operation-card-foot{font-size:12px;color:#667085;border-top:1px solid #f0f1f3;padding-top:14px}.operation-result{color:#175cd3;font-size:13px;line-height:1.6}.operation-empty{text-align:center;padding:35px 20px;background:#fff;border:1px dashed #d0d5dd;border-radius:8px;color:#667085;line-height:1.8}.operation-alert{padding:12px 16px;background:#eff4ff;color:#175cd3;border-radius:6px;line-height:1.6}.operation-alert.danger{background:#fef3f2;color:#b42318}.operation-pagination{justify-content:flex-end;font-size:13px}.operation-overlay{position:fixed;inset:0;z-index:1400;background:#10182877;display:grid;place-items:center;padding:24px}.operation-dialog{width:min(800px,100%);max-height:90vh;overflow:auto;background:white;border-radius:12px;padding:26px;color:#1f2937;box-shadow:0 12px 40px #10182833}.operation-dialog h3{font-size:15px;margin:24px 0 12px}.operation-facts{font-size:13px;margin:18px 0}.operation-facts>div{display:grid;grid-template-columns:90px 1fr;gap:12px;margin:12px 0}.operation-facts dt{color:#667085}.operation-facts dd{margin:0;line-height:1.7;white-space:pre-wrap;overflow-wrap:anywhere}.operation-metrics{display:grid;grid-template-columns:1fr 1fr;gap:10px;font-size:12px;color:#667085}.operation-metrics>div{padding:12px;background:#f8fafc;border:1px solid #eaecf0;border-radius:6px}.operation-metrics strong{display:block;color:#344054;font-size:14px;margin-top:8px}.operation-metrics small{font-weight:400}.operation-reference{border:1px solid #e4e7ec;border-radius:6px;padding:12px;margin-bottom:8px;font-size:13px;overflow-wrap:anywhere}.operation-reference summary{cursor:pointer;color:#175cd3;line-height:1.6}.operation-reference p,.operation-reference pre{white-space:pre-wrap;line-height:1.7;max-height:260px;overflow:auto;font:inherit;overflow-wrap:anywhere}.operation-reference small{color:#667085}.operation-events{padding:0;list-style:none;font-size:13px}.operation-events li{display:flex;gap:14px;line-height:1.7;margin:9px 0}.operation-events time{color:#667085;min-width:155px}.operation-review-form{margin-top:20px;border-top:1px solid #eaecf0}.operation-review-form label{display:flex;flex-direction:column;gap:8px;font-size:13px;margin:16px 0;color:#344054}.operation-review-form input,.operation-review-form textarea{width:100%;padding:10px 12px;border:1px solid #d0d5dd;border-radius:6px;background:white;color:#1f2937;font:inherit}.operation-form-row{display:grid;grid-template-columns:1fr 1fr;gap:16px}.operation-form-actions{display:flex;justify-content:flex-end;gap:12px;margin-top:20px}.operation-panel button:disabled,.operation-dialog button:disabled{opacity:.5;cursor:not-allowed}.operation-review-form input:focus,.operation-review-form textarea:focus{outline:2px solid #b2ccff;border-color:#528bff}@media(max-width:650px){.operation-panel{margin:16px}.operation-card-head,.operation-card-foot,.operation-heading{align-items:flex-start;flex-wrap:wrap}.operation-dialog{padding:18px}.operation-overlay{padding:12px}.operation-form-row,.operation-metrics{grid-template-columns:1fr}.operation-events li{flex-direction:column;gap:3px}}
</style>
