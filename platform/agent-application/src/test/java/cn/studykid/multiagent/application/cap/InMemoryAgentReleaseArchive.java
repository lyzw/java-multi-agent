package cn.studykid.multiagent.application.cap;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.ports.persistence.AgentReleaseArchivePort;

/**
 * Agent 发布归档的<b>内存桩</b>（2026-11-09 · 权威源定 A）。
 *
 * <p>⚠️ <b>为什么需要它</b>：发布服务在归档能力未装配时<b>fail-closed 拒绝发布</b>
 * （归档缺失会让 {@code run.snapshot_id} 外键链断裂，
 * 发布出去的 Agent 一个 Run 都建不出来）。
 * 于是所有「只测发布事务语义」的单元测试都得提供一个归档实现 ——
 * 否则它们会因为一个与被测行为无关的缺失能力而红。
 *
 * <p>⚠️ <b>本桩只记录，不校验外键</b>：它不是数据库，
 * 不存在 {@code agent} 行是否存在、外键是否成立这类问题。
 * 外键链的真实验证由集成测试（{@code *IT}）承担。
 */
public final class InMemoryAgentReleaseArchive implements AgentReleaseArchivePort {

    /** 归档键：{@code tenantId/agentId/version}。 */
    private final Map<String, ArchivedRelease> archived = new LinkedHashMap<>();

    @Override
    public boolean archiveRelease(@NonNull String tenantId,
                                  @NonNull String agentId,
                                  int version,
                                  @NonNull String definition,
                                  @Nullable String agentConfig) {
        String key = key(tenantId, agentId, version);
        if (archived.containsKey(key)) {
            // ⚠️ 不可变版本行：已存在不覆盖（与 JDBC 实现同口径）。
            return false;
        }
        archived.put(key, new ArchivedRelease(tenantId, agentId, version,
                definition, agentConfig == null ? "{}" : agentConfig));
        return true;
    }

    /**
     * @param tenantId 租户标识
     * @param agentId  Agent 标识
     * @param version  版本号
     * @return 该版本是否已归档
     */
    public boolean contains(@NonNull String tenantId, @NonNull String agentId, int version) {
        return archived.containsKey(key(tenantId, agentId, version));
    }

    /**
     * @param tenantId 租户标识
     * @param agentId  Agent 标识
     * @param version  版本号
     * @return 归档内容；未归档时返回空
     */
    public Optional<ArchivedRelease> find(@NonNull String tenantId,
                                                            @NonNull String agentId,
                                                            int version) {
        return Optional.ofNullable(archived.get(key(tenantId, agentId, version)));
    }

    /**
     * @return 归档条数
     */
    public int size() {
        return archived.size();
    }

    private static @NonNull String key(@NonNull String tenantId, @NonNull String agentId,
                                       int version) {
        return tenantId + "/" + agentId + "/" + version;
    }

    /**
     * 一条归档记录。
     *
     * @param tenantId   租户标识
     * @param agentId    Agent 标识
     * @param version    版本号
     * @param definition 归档的 definition JSON 文本
     * @param agentConfig 归档的运行期配置快照 JSON 文本
     */
    public record ArchivedRelease(@NonNull String tenantId,
                                  @NonNull String agentId,
                                  int version,
                                  @NonNull String definition,
                                  @NonNull String agentConfig) {
    }
}
