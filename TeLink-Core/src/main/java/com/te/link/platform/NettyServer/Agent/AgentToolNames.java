package com.te.link.platform.NettyServer.Agent;

/** Agent 依赖工具协议名称，不依赖服务端实现。 */
public final class AgentToolNames {
    public static final String QUERY_LOGS = "query_push_logs_by_log_id";
    public static final String QUERY_CODE = "query_project_code";
    public static final String LIST_NODES = "list_push_nodes";
    public static final String QUERY_NODE = "query_node_metrics";
    public static final String SEARCH_KNOWLEDGE = "search_knowledge";
    private AgentToolNames() { }
}
