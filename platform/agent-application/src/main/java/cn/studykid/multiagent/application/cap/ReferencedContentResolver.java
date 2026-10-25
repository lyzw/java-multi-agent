package cn.studykid.multiagent.application.cap;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.cap.CapResultContractDef;
import cn.studykid.multiagent.core.cap.CapRoutingPolicyDef;
import cn.studykid.multiagent.ports.persistence.CapResultContractPort;
import cn.studykid.multiagent.ports.persistence.CapRoutingPolicyPort;

/**
 * 被引用行（输出契约 / 路由策略）的<b>内容指纹</b>解析器（第二十四批 · A4）。
 *
 * <p>⚠️ <b>它补的是「同一版本行被就地改写」这一支的第二道防线</b>：
 * 编排声明固化的是<b>固定版本引用</b>（{@code id@version}）——
 * 引用没变、版本号没变、编排声明没变，
 * 只有<b>被引用行自己的内容</b>被改了（运维直接 UPDATE）。
 * 「版本行不可变」只是<b>约定</b>，不是机制；
 * 把被引用行的<b>内容指纹</b>承载进闭包绑定，
 * 这一支才会真的被挡住（指纹变 → 在途 Run 失效 → 旧审批不被复用 · MA-T18）。
 *
 * <p>⚠️ <b>为什么单独成类</b>：解析「固定版本引用 → 取那一行的内容指纹」
 * 这件事要被<b>两处</b>用到 ——
 * ① 构建闭包时（把指纹固化进绑定）；
 * ② 判定闭包缓存是否仍然新鲜（被引用行被改写 → 缓存作废）。
 * 两处各写一遍，就有了「两处可能对同一行给出不同解读」的裂缝 ——
 * 而那正是「指纹说没变、行为却变了」这类缺陷的温床。
 *
 * <p>⚠️ <b>取不到一律返回 {@code null}</b>（不抛异常）：
 * 「引用格式非法 / 版本行不存在 / 未发布 / 读取失败」在本层<b>收敛为同一个结论</b>，
 * 由调用方决定各自的 fail-closed 方式
 * （构建闭包 → 拒绝建 Run；缓存新鲜度 → 判定缓存作废）。
 * ⚠️ <b>绝不</b>在此处编造一个指纹 —— 猜出来的指纹会让「内容没变」变成假命题。
 */
public class ReferencedContentResolver {

    private static final Logger log =
            LoggerFactory.getLogger(ReferencedContentResolver.class);

    /** 固定版本引用的分隔符（{@code id@version}）。 */
    private static final String REF_SEPARATOR = "@";

    private final CapRoutingPolicyPort policies;
    private final CapResultContractPort contracts;

    public ReferencedContentResolver(@NonNull CapRoutingPolicyPort policies,
                                     @NonNull CapResultContractPort contracts) {
        this.policies = policies;
        this.contracts = contracts;
    }

    /**
     * 取被引用的<b>路由策略行</b>的内容指纹。
     *
     * @param tenantId  租户标识
     * @param policyRef 固定版本策略引用（{@code id@version}）
     * @return 内容指纹；<b>取不到时返回 {@code null}</b>
     */
    public @Nullable String routingPolicyFingerprint(@NonNull String tenantId,
                                                     @NonNull String policyRef) {
        FixedVersionRef ref = FixedVersionRef.parse(policyRef);
        if (ref == null) {
            log.warn("路由策略引用格式非法，取不到内容指纹: ref={}", policyRef);
            return null;
        }
        try {
            return policies.find(tenantId, ref.id(), ref.version())
                    .filter(CapRoutingPolicyDef::isPublished)
                    .map(CapRoutingPolicyDef::configFingerprint)
                    .orElse(null);
        } catch (RuntimeException e) {
            // ⚠️ 读失败 ≠ 「没有内容」：同样返回 null（调用方 fail-closed），
            //    但绝不能把异常吞成「指纹为空」之外的任何结论。
            log.error("路由策略内容指纹读取失败: tenant={}, ref={}", tenantId, policyRef, e);
            return null;
        }
    }

    /**
     * 取被引用的<b>输出契约行</b>的内容指纹。
     *
     * @param tenantId    租户标识
     * @param contractRef 固定版本契约引用（{@code id@version}）
     * @return 内容指纹；<b>取不到时返回 {@code null}</b>
     */
    public @Nullable String resultContractFingerprint(@NonNull String tenantId,
                                                      @NonNull String contractRef) {
        FixedVersionRef ref = FixedVersionRef.parse(contractRef);
        if (ref == null) {
            log.warn("输出契约引用格式非法，取不到内容指纹: ref={}", contractRef);
            return null;
        }
        try {
            return contracts.find(tenantId, ref.id(), ref.version())
                    .filter(CapResultContractDef::isPublished)
                    .map(CapResultContractDef::configFingerprint)
                    .orElse(null);
        } catch (RuntimeException e) {
            log.error("输出契约内容指纹读取失败: tenant={}, ref={}", tenantId, contractRef, e);
            return null;
        }
    }

    /**
     * 固定版本引用（{@code id@version}）的解析结果。
     *
     * @param id      对象标识
     * @param version 版本号
     */
    record FixedVersionRef(@NonNull String id, int version) {

        /**
         * 解析固定版本引用。
         *
         * <p>⚠️ <b>不抛异常</b>：引用格式非法是<b>配置缺陷</b>，
         * 由调用方收敛为 fail-closed；若在此处抛异常，
         * 会把「配置写错了」变成「解析器崩了」，掩盖真正的原因码。
         *
         * @param raw 原始引用文本
         * @return 解析结果；格式非法时为 {@code null}
         */
        static @Nullable FixedVersionRef parse(@Nullable String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            int separator = raw.lastIndexOf(REF_SEPARATOR);
            if (separator <= 0 || separator == raw.length() - 1) {
                return null;
            }
            String id = raw.substring(0, separator);
            int version;
            try {
                version = Integer.parseInt(raw.substring(separator + 1).trim());
            } catch (NumberFormatException e) {
                // ⚠️ 含 latest 等漂移别名 → 解析不出
                return null;
            }
            if (version <= 0) {
                return null;
            }
            return new FixedVersionRef(id, version);
        }
    }
}
