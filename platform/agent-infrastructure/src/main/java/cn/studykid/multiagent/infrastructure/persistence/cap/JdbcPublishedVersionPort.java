package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
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
        //    故只读 PLATFORM 指针。租户级发布只含本租户对象（Agent），
        //    其版本由调用方按显式版本号解析，不走本端口。
        Optional<ConfigPointer> pointer = pointers.current(ConfigRelease.Scope.PLATFORM, null);
        if (pointer.isEmpty()) {
            // ⚠️ fail-closed：平台尚未发布过任何配置 → 一切对象都不可用。
            //    「没有发布」不等于「对象存在」。
            log.debug("平台当前无发布指针，版本解析一律失败: type={}, id={}",
                    type.code(), objectId);
            return Optional.empty();
        }
        ConfigPointer current = pointer.get();
        if (!current.isPublished() || current.currentReleaseId() == null) {
            log.debug("平台指针尚未指向任何发布，版本解析失败: type={}, id={}",
                    type.code(), objectId);
            return Optional.empty();
        }

        Optional<ConfigRelease> release =
                releases.findByReleaseId(null, current.currentReleaseId());
        if (release.isEmpty()) {
            // ⚠️ 指针指向的发布不存在 = 数据损坏。宁可拒绝，不可放行。
            log.warn("指针指向的发布记录不存在，版本解析失败: releaseId={}, type={}, id={}",
                    current.currentReleaseId(), type.code(), objectId);
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
        return ConfigObjectVersionCodec.findVersion(refs, type.code(), objectId);
    }
}
