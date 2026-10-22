package cn.studykid.rag.worker;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 索引 Worker 的配置绑定（RAG-D09 R2-1）。
 *
 * <p>⚠️ {@code tenants} 受 RLS 约束：Worker 以「非特权 + 无 BYPASSRLS」的应用角色连接，
 * 无法跨租户枚举 {@code rag_tenant}。因此<b>待索引租户必须由配置显式给出</b>。
 * 生产环境两种更优做法（见 {@code ReindexRunner} 注释）：
 *   1) 由主平台在创建租户时下发索引任务（事件驱动）；
 *   2) 配置一个具备 BYPASSRLS 的<b>专用扫描角色</b>用于租户枚举（写入仍走应用角色，保持隔离）。
 */
@Component
@ConfigurationProperties(prefix = "rag.indexer")
public class RagIndexerProperties {

    /** 启动即触发重索引（仅 Worker 应用默认开启；检索服务默认关闭）。 */
    private boolean autoStart = false;

    /** 每页扫描的待索引片段数（键集分页）。 */
    private int batchSize = 200;

    /** 显式列出租户 UUID；空列表表示「无租户可索引」，启动重索引跳过。 */
    private List<String> tenants = List.of();

    public boolean isAutoStart() {
        return autoStart;
    }

    public void setAutoStart(boolean autoStart) {
        this.autoStart = autoStart;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public List<String> getTenants() {
        return tenants;
    }

    public void setTenants(List<String> tenants) {
        this.tenants = tenants;
    }
}
