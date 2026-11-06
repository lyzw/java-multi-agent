package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.cap.CapObjectType;
import cn.studykid.multiagent.application.cap.ConfigObjectVersionCodec;
import cn.studykid.multiagent.core.cap.ConfigObjectVersion;
import cn.studykid.multiagent.core.cap.ConfigPointer;
import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.ports.persistence.ConfigPointerPort;
import cn.studykid.multiagent.ports.persistence.ConfigReleasePort;
import cn.studykid.multiagent.ports.persistence.PublishedVersionPort;

/**
 * {@link PublishedVersionPort} 的 PostgreSQL 实现（§6.2 / §6.3 第 5 条 · W-PERM-P5）。
 *
 * <p>⚠️ <b>本类在 P5 已被整体替换</b>：
 * P1 的过渡实现退化为「对象级解析」（取该对象最新已发布版本行），
 * 并在「存在 ROLLED_BACK 发布」时一律解析失败 ——
 * 因为没有「当前指针」这一实体，回滚后无法判断「当前该用哪个版本」
 * （P1 的类注释已写明「P5 必须替换本类」）。
 *
 * <p><b>现在的口径（唯一正确口径）</b>：
 * <pre>
 * 当前指针 → 指针指向的 PUBLISHED release → object_versions → id→精确版本
 * </pre>
 *
 * <p>⚠️ <b>绝不以 max(seq) 选择当前版本</b>：
 * {@code config_publish_seq} 的分配序 ≠ 提交序（PUB-2），
 * 且回滚发布保留原 release —— 按 seq 取最大可能选中已回滚的发布。
 * 本类只读指针，读不到就是读不到。
 *
 * <p>⚠️ <b>解析失败必须表现为「空」而非兜底版本</b>：
 * 解析不到即意味着该对象当前不可用，调用方应 fail-closed 拒绝；
 * 若返回一个「默认版本」（如 version=1），未发布的新对象会被当成已发布对象使用 ——
 * 那是一条直接的越权路径。
 *
 * <p>⚠️ <b>平台作用域与租户作用域必须分开解析</b>（P0-5 修复）：
 * PLATFORM 发布头里<b>没有</b>租户对象（Agent）的版本引用
 * （{@code ConfigValidationGate} 明令禁止 PLATFORM 发布携带 Agent 定义），
 * TENANT 发布头里则<b>没有</b>平台对象。
 * 因此租户级对象（Agent）走 {@link #currentTenantVersion} 的 <b>TENANT 指针</b>，
 * 绝不回退到 PLATFORM 指针 —— 那会解析到另一个命名空间去。
 */
public class JdbcPublishedVersionPort implements PublishedVersionPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcPublishedVersionPort.class);

    private final ConfigPointerPort pointers;
    private final ConfigReleasePort releases;

    public JdbcPublishedVersionPort(@NonNull ConfigPointerPort pointers,
                                     @NonNull ConfigReleasePort releases) {
        this.pointers = pointers;
        this.releases = releases;
    }

    /**
     * 便捷构造：直接用同一个 {@link DataSource} 装配两个仓储。
     *
     * @param dataSource 数据源
     */
    public JdbcPublishedVersionPort(@NonNull DataSource dataSource) {
        this(new JdbcConfigPointerRepository(dataSource), new JdbcConfigReleaseRepository(dataSource));
    }

    @Override
    @NonNull
    public Optional<Integer> currentVersion(@NonNull CapObjectType type,
                                            @NonNull String objectId) {
        // ⚠️ 平台对象（Tool / Skill / MCP Server）由**平台级发布**管理，
        //    故只读 PLATFORM 指针。租户级对象（Agent）走 TENANT 指针，
        //    见 {@link #currentTenantVersion}。
        return resolveFromPointer(ConfigRelease.Scope.PLATFORM, null, type.code(), objectId);
    }

    @Override
    @NonNull
    public Optional<Integer> currentTenantVersion(@NonNull String tenantId,
                                                   @NonNull String type,
                                                   @NonNull String objectId) {
        // ⚠️ 绝不回退到 PLATFORM 指针去解析租户对象 ——
        //    PLATFORM 发布头里根本没有 Agent 的版本引用。
        return resolveFromPointer(ConfigRelease.Scope.TENANT, tenantId, type, objectId);
    }

    /**
     * 指针 → 发布头 → object_versions → id→精确版本（§6.3 第 2 条的唯一口径）。
     *
     * @param scope     作用域
     * @param tenantId  租户标识；PLATFORM 作用域为 {@code null}
     * @param type      对象类型标识（{@code tool} / {@code skill} / {@code mcp_server} / {@code agent}）
     * @param objectId  对象逻辑 id
     * @return 当前已发布版本号；解析不出时为 {@link Optional#empty()}（fail-closed）
     */
    private @NonNull Optional<Integer> resolveFromPointer(ConfigRelease.@NonNull Scope scope,
                                                          @Nullable String tenantId,
                                                          @NonNull String type,
                                                          @NonNull String objectId) {
        Optional<ConfigPointer> pointer = pointers.current(scope, tenantId);
        if (pointer.isEmpty()) {
            // ⚠️ fail-closed：该作用域尚未发布过任何配置 → 一切对象都不可用。
            //    「没有发布」不等于「对象存在」。
            log.debug("{} 作用域当前无发布指针，版本解析一律失败: type={}, id={}",
                    scope, type, objectId);
            return Optional.empty();
        }
        ConfigPointer current = pointer.get();
        if (!current.isPublished() || current.currentReleaseId() == null) {
            log.debug("{} 指针尚未指向任何发布，版本解析失败: type={}, id={}",
                    scope, type, objectId);
            return Optional.empty();
        }

        Optional<ConfigRelease> release =
                releases.findByReleaseId(tenantId, current.currentReleaseId());
        if (release.isEmpty()) {
            // ⚠️ 指针指向的发布不存在 = 数据损坏。宁可拒绝，不可放行。
            log.warn("指针指向的发布记录不存在，版本解析失败: releaseId={}, type={}, id={}",
                    current.currentReleaseId(), type, objectId);
            return Optional.empty();
        }
        ConfigRelease releaseRow = release.get();
        if (releaseRow.status() != ConfigRelease.Status.PUBLISHED) {
            // ⚠️ 指针只应指向 PUBLISHED 发布。指向其它状态说明指针与发布头不一致，
            //    按 fail-closed 处理（不猜测「大概还是有效的」）。
            log.warn("指针指向的发布不是 PUBLISHED，版本解析失败: releaseId={}, status={}",
                    current.currentReleaseId(), releaseRow.status());
            return Optional.empty();
        }

        List<ConfigObjectVersion> refs =
                ConfigObjectVersionCodec.parse(releaseRow.objectVersions());
        if (refs.isEmpty()) {
            log.warn("当前发布的 object_versions 不可解析，版本解析失败: releaseId={}",
                    current.currentReleaseId());
            return Optional.empty();
        }
        return ConfigObjectVersionCodec.findVersion(refs, type, objectId);
    }
}
