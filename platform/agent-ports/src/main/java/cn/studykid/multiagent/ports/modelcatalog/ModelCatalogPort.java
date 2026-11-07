package cn.studykid.multiagent.ports.modelcatalog;

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NonNull;

/**
 * 统一模型目录端口（模型配置的唯一事实来源）。
 *
 * <p><b>⚠️ 平台全局、无租户隔离</b>：模型是平台级资源，由超级管理员在管理后台统一维护，
 * 所有租户共用，故本端口<b>不绑定 tenant_id</b>、不走 RLS——
 * 这与「管理后台集中管理」的语义一致（参考 {@code ModelProviders} 的全局登记口径）。
 *
 * <p><b>⚠️ 不承载凭据</b>：实现只读写 {@link ModelDefinition}（不含 API Key），
 * 密钥由 {@code ModelCredentialProvider} 按 provider 从环境变量解析。
 *
 * <p><b>⚠️ 解析语义集中在此端口之上</b>：本端口只提供「存 / 取 / 查」原语，
 * 「场景 → 默认模型」「显式模型名 → 启用模型」的解析策略在 application 层
 * 的 {@code ModelResolver}，避免把业务规则塞进持久化。
 */
public interface ModelCatalogPort {

    /** 列出全部模型定义（按场景、排序权重升序）。 */
    @NonNull List<ModelDefinition> list();

    /** 按主键取单条；不存在返回空。 */
    @NonNull Optional<ModelDefinition> get(@NonNull String id);

    /**
     * 新增或更新一条模型定义（同 {@code id} 覆盖写）。
     *
     * <p>新增（{@code id == null}）时由实现生成主键并回填 {@code createdAt}/{@code updatedAt}。
     */
    @NonNull ModelDefinition save(@NonNull ModelDefinition definition);

    /** 按主键删除；不存在静默忽略。 */
    void delete(@NonNull String id);

    /**
     * 取某场景的<b>默认</b>模型（{@code is_default = true} 且启用）。
     *
     * <p>⚠️ 不在此处做「场景缺失回落 default 场景」——回落是
     * {@code ModelResolver} 的职责，本方法只回答「这个场景有没有默认模型」。
     */
    @NonNull Optional<ModelDefinition> defaultForScenario(@NonNull String scenario);

    /** 按场景 + 模型名取启用中的模型定义；不存在返回空。 */
    @NonNull Optional<ModelDefinition> byScenarioAndName(@NonNull String scenario,
                                                        @NonNull String modelName);
}
