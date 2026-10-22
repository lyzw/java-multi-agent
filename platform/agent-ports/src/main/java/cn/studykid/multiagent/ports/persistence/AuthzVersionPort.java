package cn.studykid.multiagent.ports.persistence;

import java.util.Optional;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.cap.AuthzUnavailableException;
import cn.studykid.multiagent.core.cap.AuthzVersion;

/**
 * 授权版本仓储端口（§6.1 / 06 §1.5 · W-PERM-P0-T3）。
 *
 * <p><b>本接口是「撤权即时全实例失效」的技术基础</b>：
 * 授权被撤销时版本号递增，所有实例下一次调用读到新版本即失效旧授权。
 *
 * <p>⚠️ <b>三个必须遵守的契约</b>：
 * <ol>
 *   <li><b>原子递增</b>：{@link #bump} 必须由 DB 原子操作完成，
 *       <b>禁止</b>「应用先读后加一」——后者在并发撤权下会丢失更新，
 *       表现为「撤了权但版本没变」，等于撤权失效。</li>
 *   <li><b>强一致读</b>：{@link #find} 必须读到权威主库的最新已提交值，
 *       <b>不依赖</b>异步缓存刷新。异步缓存的「最终一致」不满足撤权 SLA。</li>
 *   <li><b>fail-closed</b>：读不到版本（记录不存在或存储不可用）时，
 *       {@link #require} 必须抛 {@link AuthzUnavailableException}，
 *       调用方据此拒绝该次调用，<b>不得</b>降级放行。</li>
 * </ol>
 */
public interface AuthzVersionPort {

    /**
     * 读取当前授权版本（强一致）。
     *
     * @return 版本记录；<b>无记录</b>（该主体从未被授权）时返回空
     */
    @NonNull Optional<AuthzVersion> find(@NonNull String tenantId, @NonNull String principalId);

    /**
     * 读取当前授权版本，<b>读不到即失败</b>（fail-closed）。
     *
     * @throws AuthzUnavailableException 记录不存在或存储不可用 —— 调用方必须拒绝该次调用
     */
    @NonNull AuthzVersion require(@NonNull String tenantId, @NonNull String principalId);

    /**
     * 初始化授权版本记录（若不存在则创建为初始值 1），返回生效后的版本。
     *
     * <p>幂等：已存在时原样返回，不改变版本号。
     *
     * <p>⚠️ 该方法**不**递增版本 —— 它只保证「记录存在且 ≥ 1」。
     * 撤权请用 {@link #bump}。
     */
    @NonNull AuthzVersion initialize(@NonNull String tenantId, @NonNull String principalId);

    /**
     * <b>原子</b>递增授权版本，返回递增后的新版本号。
     *
     * <p>语义：记录不存在时创建为 1 并返回 1；已存在时 {@code +1} 并返回新值。
     * 整个过程由**单条 SQL** 完成，无「读—改—写」窗口。
     *
     * <p>⚠️ 撤权与版本递增必须在<b>同一事务</b>内提交（06 §1.5 第 4 条），
     * 否则会出现「已撤权但版本未变」或反之的中间态。
     */
    long bump(@NonNull String tenantId, @NonNull String principalId);
}
