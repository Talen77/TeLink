package com.te.link.core.diag;

import java.util.List;

public final class DiagnosisService {
    private final ReactRunner runner = new ReactRunner();
    private final McpToolClient mcp;
    private final NodeSnapshotStore snapshots;

    public DiagnosisService(McpToolClient mcp, NodeSnapshotStore snapshots) {
        this.mcp = mcp;
        this.snapshots = snapshots;
    }

    public DiagnosisView diagnoseLog(String logId, ModelClient model) {
        String report = runner.run(
                model,
                List.of(mcp.tool("query_link_logs_by_log_id")),
                "分析 logId=" + logId
        );
        return DiagnosisView.ok(report);
    }

    public DiagnosisView diagnoseNode(int machineId, ModelClient model) {
        String report = runner.run(
                model,
                List.of(new LockedTool(mcp.tool("query_node_metrics"), machineId),
                        new LockedTool(mcp.tool("query_room_route"), machineId)),
                "分析 machineId=" + machineId
        );
        return DiagnosisView.ok(report);
    }

    public DiagnosisView diagnoseCluster(ModelClient childModel) {
        StringBuilder reports = new StringBuilder();
        for (NodeSnapshot snapshot : snapshots.list()) {
            if (!snapshot.fresh(System.currentTimeMillis())) {
                reports.append(snapshot.machineId()).append(" 不可用\n");
                continue;
            }
            DiagnosisView child = diagnoseNode(snapshot.machineId(), childModel);
            if (ReactRunner.FAILURE.equals(child.text())) {
                return DiagnosisView.failed();
            }
            reports.append(child.text()).append('\n');
        }
        return DiagnosisView.ok(reports.toString().trim());
    }
}
