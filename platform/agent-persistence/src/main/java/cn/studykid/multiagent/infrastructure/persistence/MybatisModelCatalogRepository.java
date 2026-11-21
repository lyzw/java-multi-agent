package cn.studykid.multiagent.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.infrastructure.persistence.mapper.ModelCatalogMapper;
import cn.studykid.multiagent.infrastructure.persistence.model.ModelCatalogDO;
import cn.studykid.multiagent.ports.modelcatalog.ModelCatalogPort;
import cn.studykid.multiagent.ports.modelcatalog.ModelContextCapabilities;
import cn.studykid.multiagent.ports.modelcatalog.ModelDefinition;

/**
 * ModelCatalog Port MyBatis-Plus 实现（W2 平台域迁移，opsSqlSessionFactory）。
 *
 * <p>⚠️ 全局表 model_catalog，无 tenant_id、无 RLS；走 opsSqlSessionFactory。
 * ⚠️ DB 5 列（context_window_tokens / max_input_tokens / tokenizer_id /
 * supports_prompt_caching / supports_tokenize_api）在 Port 里收敛成一个
 * {@link ModelContextCapabilities} 对象 —— 本 Repository 做这个 5↔1 映射。
 */
@Repository
@org.springframework.context.annotation.Primary
// Mapper 绑定在 opsSqlSessionFactory：两个开关必须同时打开，否则本 Bean 不注册。
@ConditionalOnExpression("'${persistence.mybatis.enabled:false}'.equals('true') "
        + "&& '${agent.platform-ops.datasource.enabled:false}'.equals('true')")
public class MybatisModelCatalogRepository implements ModelCatalogPort {

    private final ModelCatalogMapper mapper;
    private final DataSource dataSource;

    public MybatisModelCatalogRepository(
            @NonNull ModelCatalogMapper mapper,
            @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper;
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull List<ModelDefinition> list() {
        return mapper.listAll().stream().map(this::toDefinition).toList();
    }

    @Override
    public @NonNull Optional<ModelDefinition> get(@NonNull String id) {
        return mapper.findById(id).map(this::toDefinition);
    }

    @Override
    public @NonNull ModelDefinition save(@NonNull ModelDefinition def) {
        ModelCatalogDO m = toDO(def);
        if (m.getId() == null || m.getId().isBlank()) {
            m.setId(UUID.randomUUID().toString());
        }
        mapper.upsert(m);
        return mapper.findById(m.getId()).map(this::toDefinition)
                .orElseThrow(() -> new IllegalStateException("model_catalog upsert 后回读失败: " + m.getId()));
    }

    @Override
    public void delete(@NonNull String id) {
        mapper.delete(id);
    }

    @Override
    public @NonNull Optional<ModelDefinition> defaultForScenario(@NonNull String scenario) {
        return mapper.defaultForScenario(scenario).map(this::toDefinition);
    }

    @Override
    public @NonNull Optional<ModelDefinition> byScenarioAndName(@NonNull String scenario, @NonNull String modelName) {
        return mapper.byScenarioAndName(scenario, modelName).map(this::toDefinition);
    }

    // ── DO ↔ Port record ──

    private ModelCatalogDO toDO(ModelDefinition d) {
        ModelCatalogDO m = new ModelCatalogDO();
        m.setId(d.id());
        m.setProvider(d.provider());
        m.setScenario(d.scenario());
        m.setModelName(d.modelName());
        m.setDisplayName(d.displayName());
        m.setBaseUrl(d.baseUrl());
        m.setDefault(d.isDefault());
        m.setSupportsTools(d.supportsTools());
        m.setSupportsStreaming(d.supportsStreaming());
        m.setSupportsVision(d.supportsVision());
        m.setDimension(d.dimension());
        m.setEnabled(d.isEnabled());
        m.setSortOrder(d.sortOrder());
        m.setDescription(d.description());
        ModelContextCapabilities c = d.contextCapabilities();
        m.setContextWindowTokens(c.contextWindowTokens());
        m.setMaxInputTokens(c.maxInputTokens());
        m.setTokenizerId(c.tokenizerId());
        m.setSupportsPromptCaching(c.supportsPromptCaching());
        m.setSupportsTokenizeApi(c.supportsTokenizeApi());
        return m;
    }

    private ModelDefinition toDefinition(ModelCatalogDO m) {
        ModelContextCapabilities caps = new ModelContextCapabilities(
                m.getContextWindowTokens(), m.getMaxInputTokens(),
                m.getTokenizerId(), m.isSupportsPromptCaching(), m.isSupportsTokenizeApi());
        return new ModelDefinition(
                m.getId(), m.getProvider(), m.getScenario(), m.getModelName(),
                m.getDisplayName(), m.getBaseUrl(), m.isDefault(),
                m.isSupportsTools(), m.isSupportsStreaming(), m.isSupportsVision(),
                m.getDimension(), m.isEnabled(), m.getSortOrder(), m.getDescription(),
                m.getCreatedAt(), m.getUpdatedAt(), caps);
    }
}
