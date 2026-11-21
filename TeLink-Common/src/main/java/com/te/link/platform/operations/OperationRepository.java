package com.te.link.platform.operations;

import com.te.link.platform.log.LogMysqlConfig;
import com.google.gson.*;
import java.sql.*;
import java.util.*;
import java.util.function.LongSupplier;

/** 不可变操作计划 + 原子审批/认领；在任何实际副作用之前持久化执行状态。 */
public class OperationRepository {
    @FunctionalInterface public interface Connections { Connection open() throws SQLException; }
    private final Connections connections;
    private final LongSupplier clock;
    public OperationRepository() { this(() -> DriverManager.getConnection(LogMysqlConfig.MYSQL_URL, LogMysqlConfig.MYSQL_USER, LogMysqlConfig.MYSQL_PASSWORD), System::currentTimeMillis); }
    public OperationRepository(Connections connections, LongSupplier clock) { this.connections=connections; this.clock=clock; }
    public JsonObject propose(JsonObject plan) throws Exception {
        try(Connection c=connections.open()) {
            c.setAutoCommit(false);
            try {
                String key=nodeKey(plan); lockNode(c,key);
                JsonObject existing=one(c,"SELECT * FROM agent_operation WHERE analysis_id=? AND machine_id=? AND action=?",str(plan,"analysisId"),plan.get("machineId").getAsInt(),str(plan,"action"));
                if(existing!=null){c.commit();return view(existing);}
                long now=clock.getAsLong();
                JsonObject pending=one(c,"SELECT * FROM agent_operation WHERE instance_id=? AND machine_id=? AND action=? AND state IN ('PENDING_APPROVAL','APPROVED','RUNNING','VERIFYING') AND (state<>'PENDING_APPROVAL' OR expires_at>?) LIMIT 1",str(plan,"instanceId"),plan.get("machineId").getAsInt(),str(plan,"action"),now);
                if(pending!=null)throw new Conflict("该节点已有同类待审核或执行中的操作，请先处理现有申请。");
                String id=UUID.randomUUID().toString();
                JsonObject stored=plan.deepCopy();stored.addProperty("id",id);stored.addProperty("createdAt",now);stored.addProperty("expiresAt",now+OperationConfig.APPROVAL_SECONDS*1000L);
                String json=stored.toString();JsonArray events=new JsonArray();events.add(event("PENDING_APPROVAL","Agent 提交申请，尚未执行",now));
                update(c,"INSERT INTO agent_operation(id,conversation_id,analysis_id,machine_id,instance_id,deployment_id,action,plan_json,plan_digest,state,events_json,created_at,expires_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,'PENDING_APPROVAL',?,?,?,?)",
                        id,str(plan,"conversationId"),str(plan,"analysisId"),plan.get("machineId").getAsInt(),str(plan,"instanceId"),str(plan,"deploymentId"),str(plan,"action"),json,OperationConfig.hash(json),events.toString(),now,now+OperationConfig.APPROVAL_SECONDS*1000L,now);
                JsonObject result=one(c,"SELECT * FROM agent_operation WHERE id=?",id);c.commit();return view(result);
            }catch(Exception e){c.rollback();throw e;}
        }
    }
    public JsonObject get(String id) throws SQLException {try(Connection c=connections.open()){JsonObject row=one(c,"SELECT * FROM agent_operation WHERE id=?",id);if(row==null)throw new IllegalArgumentException("操作申请不存在");return view(row);}}
    public JsonArray list(String conversation,int page,int size) throws SQLException {
        try(Connection c=connections.open()) {
            JsonArray rows=conversation==null || conversation.isBlank()
                    ? query(c,"SELECT * FROM agent_operation ORDER BY created_at DESC LIMIT ? OFFSET ?",size,(page-1)*size)
                    : query(c,"SELECT * FROM agent_operation WHERE conversation_id=? ORDER BY created_at DESC LIMIT ? OFFSET ?",conversation,size,(page-1)*size);
            JsonArray result=new JsonArray();for(JsonElement row:rows)result.add(view(row.getAsJsonObject()));return result;
        }
    }
    public JsonObject decide(String id,String digest,String decision,String reviewer,String comment) throws Exception {
        JsonObject initial=get(id);
        try(Connection c=connections.open()) {
            c.setAutoCommit(false);
            try {
                JsonObject guard=lockNode(c,nodeKey(initial));
                JsonObject row=one(c,"SELECT * FROM agent_operation WHERE id=? FOR UPDATE",id);
                if(!digest.equals(str(row,"plan_digest")))throw new Conflict("操作计划已变化，请刷新后重新审核。");
                String state=str(row,"state");boolean approve="APPROVE".equals(decision);
                if(!"PENDING_APPROVAL".equals(state)) {
                    if((approve && Set.of("APPROVED","RUNNING","VERIFYING","SUCCEEDED","FAILED","UNKNOWN").contains(state) && !str(row,"reviewer").isBlank()) || (!approve && "REJECTED".equals(state))){c.commit();return view(row);}
                    throw new Conflict("操作状态已更新，不能再次审核。");
                }
                long now=clock.getAsLong();
                if(row.get("expires_at").getAsLong()<=now){transition(c,row,"EXPIRED","审核已过期，请重新分析",null);c.commit();throw new Conflict("审核已过期，请重新分析。");}
                if(approve) {
                    if(guard.get("blocked_until").getAsLong()>now)throw new Conflict("目标节点仍在操作冷却期，请稍后重新分析。");
                    JsonObject busy=one(c,"SELECT id FROM agent_operation WHERE (machine_id=? OR (deployment_id<>'' AND deployment_id=?)) AND state IN ('APPROVED','RUNNING','VERIFYING') LIMIT 1",initial.get("machineId").getAsInt(),str(initial,"deploymentId"));
                    if(busy!=null)throw new Conflict("目标节点已有正在执行的操作。");
                    update(c,"UPDATE agent_operation_node_guard SET blocked_until=? WHERE node_key=?",now+OperationConfig.COOLDOWN_SECONDS*1000L,nodeKey(initial));
                }
                update(c,"UPDATE agent_operation SET reviewer=?,review_comment=?,reviewed_at=?,deadline_at=? WHERE id=?",reviewer,comment,now,approve?now+OperationConfig.EXECUTION_SECONDS*1000L:null,id);
                transition(c,row,approve?"APPROVED":"REJECTED",approve?"用户批准，等待目标节点认领":"用户拒绝，未执行",null);
                JsonObject result=one(c,"SELECT * FROM agent_operation WHERE id=?",id);c.commit();return view(result);
            }catch(Exception e){c.rollback();throw e;}
        }
    }
    public JsonArray approvedFor(int machineId) throws SQLException {try(Connection c=connections.open()){JsonArray result=new JsonArray();for(JsonElement row:query(c,"SELECT * FROM agent_operation WHERE machine_id=? AND state='APPROVED' ORDER BY created_at LIMIT 10",machineId))result.add(view(row.getAsJsonObject()));return result;}}
    public boolean claim(String id,String instance,String fingerprint) throws Exception {
        try(Connection c=connections.open()) {
            c.setAutoCommit(false);
            try {
                JsonObject row=one(c,"SELECT * FROM agent_operation WHERE id=? FOR UPDATE",id);
                if(row==null || !"APPROVED".equals(str(row,"state"))){c.rollback();return false;}
                JsonObject plan=JsonParser.parseString(str(row,"plan_json")).getAsJsonObject();
                if(!instance.equals(str(row,"instance_id")) || !fingerprint.equals(str(plan,"executorFingerprint")) || row.get("deadline_at").getAsLong()<=clock.getAsLong()){
                    transition(c,row,"EXPIRED","节点实例、执行配置已变化或等待执行超时，未执行",null);c.commit();return false;
                }
                update(c,"UPDATE agent_operation SET started_at=?,deadline_at=? WHERE id=?",clock.getAsLong(),clock.getAsLong()+OperationConfig.EXECUTION_SECONDS*1000L,id);
                transition(c,row,"RUNNING","目标节点已认领通过审核的操作",null);c.commit();return true;
            }catch(Exception e){c.rollback();throw e;}
        }
    }
    public boolean finish(String id,Set<String> expected,String state,String message,JsonObject result) throws Exception {
        try(Connection c=connections.open()) {
            c.setAutoCommit(false);
            try {JsonObject row=one(c,"SELECT * FROM agent_operation WHERE id=? FOR UPDATE",id);if(row==null || !expected.contains(str(row,"state"))){c.rollback();return false;}
                transition(c,row,state,message,result);c.commit();return true;
            }catch(Exception e){c.rollback();throw e;}
        }
    }
    public JsonArray unsettled() throws SQLException {try(Connection c=connections.open()){JsonArray result=new JsonArray();for(JsonElement row:query(c,"SELECT * FROM agent_operation WHERE state IN ('PENDING_APPROVAL','APPROVED','RUNNING','VERIFYING') ORDER BY created_at LIMIT 500"))result.add(view(row.getAsJsonObject()));return result;}}
    private JsonObject lockNode(Connection c,String key) throws SQLException {
        update(c,"INSERT INTO agent_operation_node_guard(node_key,blocked_until) VALUES(?,0) ON DUPLICATE KEY UPDATE node_key=VALUES(node_key)",key);
        return one(c,"SELECT * FROM agent_operation_node_guard WHERE node_key=? FOR UPDATE",key);
    }
    private void transition(Connection c,JsonObject row,String state,String message,JsonObject result) throws SQLException {
        long now=clock.getAsLong();JsonArray events=JsonParser.parseString(str(row,"events_json")).getAsJsonArray();events.add(event(state,message,now));
        boolean terminal=Set.of("SUCCEEDED","FAILED","UNKNOWN","EXPIRED","REJECTED").contains(state);
        update(c,"UPDATE agent_operation SET state=?,events_json=?,result_json=COALESCE(?,result_json),finished_at=?,updated_at=? WHERE id=?",state,events.toString(),result==null?null:result.toString(),terminal?now:null,now,str(row,"id"));
    }
    private static JsonObject event(String state,String message,long at){JsonObject e=new JsonObject();e.addProperty("state",state);e.addProperty("message",message);e.addProperty("at",at);return e;}
    private static String nodeKey(JsonObject plan){String deployment=str(plan,"deploymentId");return deployment.isBlank()?"machine:"+plan.get("machineId").getAsInt():"deployment:"+deployment;}
    private static JsonObject view(JsonObject row) {
        JsonObject result=JsonParser.parseString(str(row,"plan_json")).getAsJsonObject();
        result.addProperty("state",str(row,"state"));result.addProperty("planDigest",str(row,"plan_digest"));
        result.addProperty("reviewer",str(row,"reviewer"));result.addProperty("reviewComment",str(row,"review_comment"));
        for(String[] field:new String[][]{{"reviewed_at","reviewedAt"},{"started_at","startedAt"},{"finished_at","finishedAt"},{"deadline_at","deadlineAt"},{"updated_at","updatedAt"}})result.add(field[1],row.get(field[0]));
        result.add("events",JsonParser.parseString(str(row,"events_json")));
        result.add("result",str(row,"result_json").isBlank()?JsonNull.INSTANCE:JsonParser.parseString(str(row,"result_json")));return result;
    }
    public static String str(JsonObject o,String key){return o==null || !o.has(key) || o.get(key).isJsonNull()?"":o.get(key).getAsString();}
    private static int update(Connection c,String sql,Object...args)throws SQLException{try(PreparedStatement s=c.prepareStatement(sql)){bind(s,args);return s.executeUpdate();}}
    private static JsonObject one(Connection c,String sql,Object...args)throws SQLException{JsonArray r=query(c,sql,args);return r.isEmpty()?null:r.get(0).getAsJsonObject();}
    private static JsonArray query(Connection c,String sql,Object...args)throws SQLException{try(PreparedStatement s=c.prepareStatement(sql)){bind(s,args);try(ResultSet rows=s.executeQuery()){JsonArray result=new JsonArray();ResultSetMetaData meta=rows.getMetaData();while(rows.next()){JsonObject row=new JsonObject();for(int i=1;i<=meta.getColumnCount();i++){Object v=rows.getObject(i);String k=meta.getColumnLabel(i).toLowerCase(Locale.ROOT);if(v==null)row.add(k,JsonNull.INSTANCE);else if(v instanceof Number n)row.addProperty(k,n);else row.addProperty(k,v.toString());}result.add(row);}return result;}}}
    private static void bind(PreparedStatement s,Object[] values)throws SQLException{for(int i=0;i<values.length;i++)s.setObject(i+1,values[i]);}
    public static class Conflict extends IllegalStateException {public Conflict(String message){super(message);}}
}
