package com.te.link.platform.knowledge;

import com.google.gson.*;
import java.sql.*;
import java.util.*;

/** MySQL 是文档可见性、当前版本和任务状态的权威来源。 */
public class RagRepository {
    @FunctionalInterface public interface Connections { Connection open() throws SQLException; }
    @FunctionalInterface public interface Sender { void send(String topic, String key, String payload) throws Exception; }
    private final Connections connections;
    public RagRepository() { this(() -> DriverManager.getConnection(RagConfig.MYSQL_URL, RagConfig.MYSQL_USER, RagConfig.MYSQL_PASSWORD)); }
    public RagRepository(Connections connections) { this.connections = connections; }
    Connection open() throws SQLException { return connections.open(); }

    public JsonObject upload(String documentId, String title, String component, String service, String applicable,
                             String filename, String path, String hash, long bytes) throws Exception {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                String id = documentId == null ? UUID.randomUUID().toString() : documentId;
                int version = 1;
                if (documentId == null) {
                    update(c, "INSERT INTO rag_document(id,title,component,service_name,applicable_version,latest_version) VALUES(?,?,?,?,?,1)", id,title,component,service,applicable);
                } else {
                    JsonObject doc = one(c,"SELECT * FROM rag_document WHERE id=? AND deleted=FALSE FOR UPDATE", id);
                    if (doc == null) throw new IllegalArgumentException("document not found");
                    version = doc.get("latest_version").getAsInt() + 1;
                    update(c,"UPDATE rag_document SET latest_version=? WHERE id=?",version,id);
                }
                update(c,"INSERT INTO rag_document_version(document_id,version,file_name,storage_path,content_hash,byte_size,status,embedding_model,embedding_dimensions,embedding_profile,index_collection) VALUES(?,?,?,?,?,?,'QUEUED',?,?,?,?)",
                        id,version,filename,path,hash,bytes,RagConfig.EMBEDDING_MODEL,RagConfig.DIMENSIONS,RagConfig.PROFILE,RagConfig.COLLECTION);
                String task = newTask(c,id,version,"INDEX");
                c.commit();
                JsonObject result = new JsonObject(); result.addProperty("documentId",id);result.addProperty("version",version);result.addProperty("taskId",task);result.addProperty("status","QUEUED");return result;
            } catch (Exception e) { c.rollback(); throw e; }
        }
    }

    public JsonObject list(int page, int size) throws SQLException {
        try(Connection c=open()) {
            JsonArray rows = query(c,"SELECT d.*,v.file_name,v.status,v.chunk_count,v.error_message,v.byte_size,v.embedding_profile,v.index_collection FROM rag_document d JOIN rag_document_version v ON v.document_id=d.id AND v.version=d.latest_version WHERE d.deleted=FALSE ORDER BY d.created_at DESC LIMIT ? OFFSET ?",size,(page-1)*size);
            for(JsonElement item:rows) {
                JsonObject row=item.getAsJsonObject();
                row.addProperty("indexCompatible",RagConfig.PROFILE.equals(text(row,"embedding_profile")) && RagConfig.COLLECTION.equals(text(row,"index_collection")));
            }
            JsonObject result=new JsonObject();result.add("items",rows);result.addProperty("total",one(c,"SELECT COUNT(*) AS n FROM rag_document WHERE deleted=FALSE").get("n").getAsLong());return result;
        }
    }
    public JsonObject detail(String id) throws SQLException {
        try(Connection c=open()) {
            JsonObject doc=one(c,"SELECT * FROM rag_document WHERE id=?",id);
            if(doc==null)throw new IllegalArgumentException("document not found");
            JsonArray versions=query(c,"SELECT version,file_name,status,chunk_count,error_message,embedding_model,embedding_dimensions,index_collection,created_at FROM rag_document_version WHERE document_id=? ORDER BY version DESC",id);
            doc.add("versions",versions);
            doc.add("tasks",query(c,"SELECT id,version,kind,state,attempts,error_message,created_at,updated_at FROM rag_ingest_task WHERE document_id=? ORDER BY created_at DESC LIMIT 20",id));return doc;
        }
    }
    public void configure(String id, boolean enabled) throws SQLException {
        try(Connection c=open()) { if(update(c,"UPDATE rag_document SET enabled=? WHERE id=? AND deleted=FALSE",enabled,id)==0)throw new IllegalArgumentException("document not found"); }
    }
    public JsonObject retry(String id) throws Exception {
        try(Connection c=open()) {
            c.setAutoCommit(false);
            try {
                JsonObject doc=one(c,"SELECT * FROM rag_document WHERE id=? AND deleted=FALSE FOR UPDATE",id);
                if(doc==null)throw new IllegalArgumentException("document not found");
                int version=doc.get("latest_version").getAsInt();
                JsonObject v=one(c,"SELECT * FROM rag_document_version WHERE document_id=? AND version=?",id,version);
                if(!"FAILED".equals(text(v,"status")))throw new IllegalArgumentException("only failed versions can be retried");
                if(!RagConfig.PROFILE.equals(text(v,"embedding_profile")) || !RagConfig.COLLECTION.equals(text(v,"index_collection")))throw new IllegalArgumentException("embedding settings changed; reindex as a new version");
                update(c,"UPDATE rag_document_version SET status='QUEUED',error_message=NULL WHERE document_id=? AND version=?",id,version);
                String task=newTask(c,id,version,"INDEX");c.commit();JsonObject r=new JsonObject();r.addProperty("taskId",task);return r;
            } catch(Exception e){c.rollback();throw e;}
        }
    }
    public JsonObject reindex(String id) throws Exception {
        JsonObject v;
        try(Connection c=open()){v=one(c,"SELECT v.* FROM rag_document_version v JOIN rag_document d ON d.id=v.document_id AND d.latest_version=v.version WHERE d.id=? AND d.deleted=FALSE",id);}
        if(v==null)throw new IllegalArgumentException("document not found");
        return upload(id,"","","","",text(v,"file_name"),text(v,"storage_path"),text(v,"content_hash"),v.get("byte_size").getAsLong());
    }
    public void delete(String id) throws Exception {
        try(Connection c=open()) {
            c.setAutoCommit(false);
            try {
                JsonObject doc=one(c,"SELECT * FROM rag_document WHERE id=? FOR UPDATE",id);
                if(doc==null)throw new IllegalArgumentException("document not found");
                if(!doc.get("deleted").getAsBoolean()) {
                    update(c,"UPDATE rag_document SET deleted=TRUE,enabled=FALSE,active_version=NULL WHERE id=?",id);
                    newTask(c,id,doc.get("latest_version").getAsInt(),"DELETE");
                } else {
                    JsonObject cleanup=one(c,"SELECT state FROM rag_ingest_task WHERE document_id=? AND kind='DELETE' ORDER BY created_at DESC LIMIT 1",id);
                    if(cleanup!=null && "FAILED".equals(text(cleanup,"state")))newTask(c,id,doc.get("latest_version").getAsInt(),"DELETE");
                }
                c.commit();
            }catch(Exception e){c.rollback();throw e;}
        }
    }
    public JsonObject task(String id) throws SQLException {
        try(Connection c=open()){return one(c,"SELECT t.*,d.deleted,d.latest_version,v.storage_path,v.file_name,v.embedding_profile,v.index_collection FROM rag_ingest_task t JOIN rag_document d ON d.id=t.document_id JOIN rag_document_version v ON v.document_id=t.document_id AND v.version=t.version WHERE t.id=?",id);}
    }
    public boolean claim(String id,String owner) throws SQLException {
        try(Connection c=open()){return update(c,"UPDATE rag_ingest_task SET state='PROCESSING',attempts=attempts+1,lease_owner=?,lease_until=DATE_ADD(UTC_TIMESTAMP(3), INTERVAL ? SECOND),error_message=NULL WHERE id=? AND state='QUEUED'",owner,120,id)==1;}
    }
    public void heartbeat(String id,String owner) throws SQLException {
        try(Connection c=open()){if(update(c,"UPDATE rag_ingest_task SET lease_until=DATE_ADD(UTC_TIMESTAMP(3), INTERVAL ? SECOND) WHERE id=? AND state='PROCESSING' AND lease_owner=?",120,id,owner)!=1)throw new IllegalStateException("task lease lost");}
    }
    public void assertCurrent(String id,String owner) throws SQLException {
        JsonObject t=task(id);
        if(t==null || !"PROCESSING".equals(text(t,"state")) || !owner.equals(text(t,"lease_owner"))
                || ("INDEX".equals(text(t,"kind")) && (t.get("deleted").getAsBoolean() || t.get("latest_version").getAsInt()!=t.get("version").getAsInt())))throw new Superseded();
    }
    public void stage(String taskId,String owner,String stage) throws SQLException {
        assertCurrent(taskId,owner);JsonObject task=task(taskId);
        try(Connection c=open()){update(c,"UPDATE rag_document_version SET status=? WHERE document_id=? AND version=?",stage,text(task,"document_id"),task.get("version").getAsInt());}
    }
    public void chunks(String taskId,String owner,List<TextChunker.Chunk> chunks) throws Exception {
        assertCurrent(taskId,owner);JsonObject t=task(taskId);String doc=text(t,"document_id");int version=t.get("version").getAsInt();
        try(Connection c=open()){
            c.setAutoCommit(false);
            try {
                update(c,"DELETE FROM rag_chunk WHERE document_id=? AND version=?",doc,version);
                for(var chunk:chunks)update(c,"INSERT INTO rag_chunk(id,document_id,version,ordinal_no,heading,content,content_hash) VALUES(?,?,?,?,?,?,?)",chunk.id(),doc,version,chunk.ordinal(),chunk.heading(),chunk.content(),RagConfig.hash(chunk.content()));
                c.commit();
            }catch(Exception e){c.rollback();throw e;}
        }
    }
    public boolean completeIndex(String id,String owner,int count) throws Exception {
        try(Connection c=open()){
            c.setAutoCommit(false);
            try {
                JsonObject task=one(c,"SELECT * FROM rag_ingest_task WHERE id=? FOR UPDATE",id);
                if(!owner.equals(text(task,"lease_owner")) || !"PROCESSING".equals(text(task,"state"))){c.rollback();return false;}
                String doc=text(task,"document_id");int version=task.get("version").getAsInt();
                JsonObject d=one(c,"SELECT * FROM rag_document WHERE id=? FOR UPDATE",doc);
                boolean current=!d.get("deleted").getAsBoolean() && d.get("latest_version").getAsInt()==version;
                update(c,"UPDATE rag_document_version SET status=?,chunk_count=?,error_message=NULL WHERE document_id=? AND version=?",current?"READY":"SUPERSEDED",count,doc,version);
                if(current)update(c,"UPDATE rag_document SET active_version=? WHERE id=?",version,doc);
                update(c,"UPDATE rag_ingest_task SET state=?,lease_owner=NULL,lease_until=NULL WHERE id=?",current?"SUCCEEDED":"CANCELLED",id);
                c.commit();return current;
            }catch(Exception e){c.rollback();throw e;}
        }
    }
    public void cancel(String id,String owner) throws SQLException {
        try(Connection c=open()){
            c.setAutoCommit(false);
            try {
                JsonObject t=one(c,"SELECT * FROM rag_ingest_task WHERE id=? AND lease_owner=? AND state='PROCESSING' FOR UPDATE",id,owner);
                if(t!=null){
                    update(c,"UPDATE rag_ingest_task SET state='CANCELLED',lease_owner=NULL,lease_until=NULL WHERE id=?",id);
                    if("INDEX".equals(text(t,"kind")))update(c,"UPDATE rag_document_version SET status='SUPERSEDED' WHERE document_id=? AND version=? AND status NOT IN ('READY','DELETED')",text(t,"document_id"),t.get("version").getAsInt());
                }
                c.commit();
            }catch(SQLException e){c.rollback();throw e;}
        }
    }
    /** 等待同文档的其他任务不应耗尽真正的失败重试次数。 */
    public void defer(String id,String owner) throws SQLException {
        try(Connection c=open()){update(c,"UPDATE rag_ingest_task SET state='RETRY',attempts=GREATEST(0,attempts-1),retry_at=DATE_ADD(UTC_TIMESTAMP(3), INTERVAL ? SECOND),lease_owner=NULL,lease_until=NULL WHERE id=? AND state='PROCESSING' AND lease_owner=?",10,id,owner);}
    }
    public void failed(String id,String owner,String error) throws Exception {
        try(Connection c=open()){
            c.setAutoCommit(false);
            try {
                JsonObject t=one(c,"SELECT * FROM rag_ingest_task WHERE id=? FOR UPDATE",id);
                if(t==null || !owner.equals(text(t,"lease_owner")) || !"PROCESSING".equals(text(t,"state"))){c.rollback();return;}
                boolean terminal=t.get("attempts").getAsInt()>=RagConfig.MAX_ATTEMPTS;
                update(c,"UPDATE rag_ingest_task SET state=?,error_message=?,retry_at=DATE_ADD(UTC_TIMESTAMP(3), INTERVAL ? SECOND),lease_owner=NULL,lease_until=NULL WHERE id=?",terminal?"FAILED":"RETRY",shortError(error),10L*t.get("attempts").getAsInt(),id);
                if("INDEX".equals(text(t,"kind")))update(c,"UPDATE rag_document_version SET status=?,error_message=? WHERE document_id=? AND version=?",terminal?"FAILED":"RETRY",shortError(error),text(t,"document_id"),t.get("version").getAsInt());
                if(terminal)outbox(c,id,RagConfig.DLQ_TOPIC,event(t,error));
                c.commit();
            }catch(Exception e){c.rollback();throw e;}
        }
    }
    public JsonArray versions(String doc) throws SQLException {try(Connection c=open()){return query(c,"SELECT * FROM rag_document_version WHERE document_id=?",doc);}}
    public void completeDelete(String id,String owner) throws Exception {
        assertCurrent(id,owner);JsonObject t=task(id);String doc=text(t,"document_id");
        try(Connection c=open()){
            c.setAutoCommit(false);try {
                update(c,"DELETE FROM rag_chunk WHERE document_id=?",doc);
                update(c,"UPDATE rag_document_version SET status='DELETED' WHERE document_id=?",doc);
                update(c,"UPDATE rag_ingest_task SET state='SUCCEEDED',lease_owner=NULL,lease_until=NULL WHERE id=? AND lease_owner=?",id,owner);c.commit();
            }catch(Exception e){c.rollback();throw e;}
        }
    }
    /** 处理同一文档的不同版本和删除时跨进程串行，连接关闭即释放 MySQL 命名锁。 */
    public Connection documentLock(String doc) throws SQLException {
        Connection c=open();
        try {JsonObject result=one(c,"SELECT GET_LOCK(?, 5) AS acquired","rag:"+doc);if(result==null || result.get("acquired").isJsonNull())throw new SQLException("document lock unavailable");if(result.get("acquired").getAsInt()!=1)throw new DocumentBusy();return c;}
        catch(SQLException e){c.close();throw e;}
    }
    public boolean deliverOne(Sender sender) throws Exception {
        try(Connection c=open()){
            c.setAutoCommit(false);
            JsonObject row=one(c,"SELECT * FROM rag_outbox WHERE published_at IS NULL AND available_at<=UTC_TIMESTAMP(3) ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED");
            if(row==null){c.rollback();return false;}
            try {
                sender.send(text(row,"topic"),text(row,"task_id"),text(row,"payload"));
                update(c,"UPDATE rag_outbox SET published_at=CURRENT_TIMESTAMP(3),error_message=NULL WHERE id=?",text(row,"id"));
            }catch(Exception e){update(c,"UPDATE rag_outbox SET attempts=attempts+1,available_at=DATE_ADD(UTC_TIMESTAMP(3), INTERVAL ? SECOND),error_message=? WHERE id=?",5,"Kafka publication failed; will retry",text(row,"id"));}
            c.commit();return true;
        }
    }
    public void recover() throws Exception {
        try(Connection c=open()){
            c.setAutoCommit(false);
            try {
                JsonArray tasks=query(c,"SELECT * FROM rag_ingest_task WHERE (state='RETRY' AND retry_at<=UTC_TIMESTAMP(3)) OR (state='PROCESSING' AND lease_until<UTC_TIMESTAMP(3)) ORDER BY created_at LIMIT 20 FOR UPDATE SKIP LOCKED");
                for(JsonElement element:tasks){JsonObject t=element.getAsJsonObject();String id=text(t,"id");
                    if(t.get("attempts").getAsInt()>=RagConfig.MAX_ATTEMPTS){
                        update(c,"UPDATE rag_ingest_task SET state='FAILED',lease_owner=NULL,error_message='worker lease expired' WHERE id=?",id);
                        if("INDEX".equals(text(t,"kind")))update(c,"UPDATE rag_document_version SET status='FAILED',error_message='worker lease expired' WHERE document_id=? AND version=?",text(t,"document_id"),t.get("version").getAsInt());
                        outbox(c,id,RagConfig.DLQ_TOPIC,event(t,"worker lease expired"));
                    }else{
                        update(c,"UPDATE rag_ingest_task SET state='QUEUED',lease_owner=NULL,lease_until=NULL WHERE id=?",id);
                        outbox(c,id,RagConfig.TOPIC,event(t,null));
                    }
                }c.commit();
            }catch(Exception e){c.rollback();throw e;}
        }
    }
    public JsonArray activeVersions(String component,String service,String applicable) throws SQLException {
        try(Connection c=open()){
            String sql="SELECT d.id,d.active_version FROM rag_document d JOIN rag_document_version v ON v.document_id=d.id AND v.version=d.active_version WHERE d.enabled=TRUE AND d.deleted=FALSE AND v.status='READY' AND v.embedding_profile=? AND v.index_collection=?";
            List<Object> args=new ArrayList<>(List.of(RagConfig.PROFILE,RagConfig.COLLECTION));
            if(!component.isBlank()){sql+=" AND (d.component='' OR d.component=?)";args.add(component);}
            if(!service.isBlank()){sql+=" AND (d.service_name='' OR d.service_name=?)";args.add(service);}
            if(!applicable.isBlank()){sql+=" AND (d.applicable_version='' OR d.applicable_version=?)";args.add(applicable);}
            return query(c,sql,args.toArray());
        }
    }
    public JsonObject visibleChunk(String id) throws SQLException {
        try(Connection c=open()){return one(c,"SELECT c.*,d.title,d.component,d.service_name,d.applicable_version,v.file_name,v.updated_at FROM rag_chunk c JOIN rag_document d ON d.id=c.document_id AND d.active_version=c.version JOIN rag_document_version v ON v.document_id=c.document_id AND v.version=c.version WHERE c.id=? AND d.enabled=TRUE AND d.deleted=FALSE AND v.status='READY' AND v.embedding_profile=? AND v.index_collection=?",id,RagConfig.PROFILE,RagConfig.COLLECTION);}
    }
    private String newTask(Connection c,String doc,int version,String kind) throws SQLException {
        String id=UUID.randomUUID().toString();update(c,"INSERT INTO rag_ingest_task(id,document_id,version,kind,state) VALUES(?,?,?,?,'QUEUED')",id,doc,version,kind);
        JsonObject t=new JsonObject();t.addProperty("id",id);t.addProperty("document_id",doc);t.addProperty("version",version);t.addProperty("kind",kind);outbox(c,id,RagConfig.TOPIC,event(t,null));return id;
    }
    private static String event(JsonObject t,String error){JsonObject e=new JsonObject();e.addProperty("taskId",text(t,"id"));e.addProperty("documentId",text(t,"document_id"));e.add("version",t.get("version"));e.addProperty("kind",text(t,"kind"));if(error!=null)e.addProperty("error",shortError(error));return e.toString();}
    private static void outbox(Connection c,String task,String topic,String payload)throws SQLException {update(c,"INSERT INTO rag_outbox(id,task_id,topic,payload) VALUES(?,?,?,?)",UUID.randomUUID().toString(),task,topic,payload);}
    private static String shortError(String s){return s==null?"processing failed":s.substring(0,Math.min(1000,s.length()));}
    static int update(Connection c,String sql,Object... args)throws SQLException {try(PreparedStatement s=c.prepareStatement(sql)){bind(s,args);return s.executeUpdate();}}
    static JsonObject one(Connection c,String sql,Object... args)throws SQLException {JsonArray rows=query(c,sql,args);return rows.isEmpty()?null:rows.get(0).getAsJsonObject();}
    static JsonArray query(Connection c,String sql,Object... args)throws SQLException {
        try(PreparedStatement s=c.prepareStatement(sql)){bind(s,args);try(ResultSet r=s.executeQuery()){
            JsonArray rows=new JsonArray();ResultSetMetaData m=r.getMetaData();while(r.next()){JsonObject row=new JsonObject();for(int i=1;i<=m.getColumnCount();i++){Object v=r.getObject(i);String key=m.getColumnLabel(i).toLowerCase(Locale.ROOT);if(v==null)row.add(key,JsonNull.INSTANCE);else if(key.equals("enabled") || key.equals("deleted"))row.addProperty(key,r.getBoolean(i));else if(v instanceof Boolean b)row.addProperty(key,b);else if(v instanceof Number n)row.addProperty(key,n);else row.addProperty(key,v.toString());}rows.add(row);}return rows;
        }}
    }
    private static void bind(PreparedStatement s,Object[] args)throws SQLException{for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);}
    public static String text(JsonObject o,String key){return o==null || !o.has(key) || o.get(key).isJsonNull()?"":o.get(key).getAsString();}
    public static class Superseded extends IllegalStateException { public Superseded(){super("task superseded or cancelled");} }
    public static class DocumentBusy extends SQLException { public DocumentBusy(){super("another task is processing this document");} }
}
