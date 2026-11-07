package cn.studykid.multiagent.application.modelcatalog;

import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import cn.studykid.multiagent.ports.modelcatalog.ModelCatalogPort;
import cn.studykid.multiagent.ports.modelcatalog.ModelDefinition;
import cn.studykid.multiagent.ports.modelcatalog.ModelScenario;

/**
 * 模型解析器（模型统一配置的核心读取侧 · application 层）。
 *
 * <p><b>唯一职责</b>：把「某场景 + 可选显式模型名」解析成一个<b>确定启用的</b>模型定义。
 * 这是项目各模块（LLM 网关、RAG 嵌入、护栏等）读取模型的<b>唯一入口</b>，
 * 不得再各自硬编码模型名 / 供应商 / 地址。
 *
 * <p><b>⚠️ 解析优先级（同场景内）</b>：
 * <ol>
 *   <li>给了显式 {@code modelName} → 优先在<b>该场景</b>内匹配启用中的同名模型；
 *       场景内没有则退化为「全目录同名启用模型」（跨场景同名模型极少，仅兜底）；</li>
 *   <li>未给显式名 / 显式名未命中 → 取<b>该场景的默认模型</b>；</li>
 *   <li>该场景无默认 → 回落到 {@link ModelScenario#DEFAULT} 场景的默认模型；</li>
 *   <li>都没有 → 返回空（<b>fail-closed：绝不随便挑一个模型</b>）。</li>
 * </ol>
 *
 * <p><b>⚠️ 已禁用模型不参与解析</b>：{@code isEnabled = false} 的模型即使在默认位、
 * 即使被显式点名，也<b>一律跳过</b>——禁用即「此模型当前不可用于任何场景」。
 *
 * <p><b>⚠️ 不缓存</b>：每次解析都读端口（端口实现可自带缓存）。
 * 这让管理后台改完模型配置后<b>下一次调用即刻生效</b>，无需重启、无需清缓存，
 * 与 {@code EffectiveConfigResolver}「读取即合并」的同构纪律一致。
 */
@Service
public class ModelResolver {

    private final ModelCatalogPort catalog;

    public ModelResolver(@NonNull ModelCatalogPort catalog) {
        this.catalog = catalog;
    }

    /**
     * 解析某场景所需模型。
     *
     * @param scenario           场景 code（见 {@link ModelScenario}）
     * @param explicitModelName  调用方显式指定的模型名（可空；空则走默认）
     * @return 启用中的模型定义；无可用模型返回 {@link Optional#empty()}
     */
    public @NonNull Optional<ModelDefinition> resolve(@NonNull String scenario,
                                                     @Nullable String explicitModelName) {
        if (explicitModelName != null && !explicitModelName.isBlank()) {
            Optional<ModelDefinition> hit = catalog.byScenarioAndName(scenario, explicitModelName)
                    .filter(ModelDefinition::isEnabled);
            if (hit.isPresent()) {
                return hit;
            }
            // 退化为全目录同名启用模型（跨场景兜底，避免「场景写错就完全取不到」）
            Optional<ModelDefinition> global = catalog.list().stream()
                    .filter(d -> d.isEnabled()
                            && d.modelName().equals(explicitModelName))
                    .findFirst();
            if (global.isPresent()) {
                return global;
            }
        }
        return defaultForScenario(scenario);
    }

    /**
     * 取某场景的默认模型；该场景无默认时回落 {@link ModelScenario#DEFAULT} 场景的默认。
     *
     * <p>⚠️ 默认模型也必须是启用状态；若默认位上的模型被禁用，
     * 视为「该场景暂无默认」继续回落，不强行返回禁用模型。
     */
    public @NonNull Optional<ModelDefinition> defaultForScenario(@NonNull String scenario) {
        Optional<ModelDefinition> primary = catalog.defaultForScenario(scenario)
                .filter(ModelDefinition::isEnabled);
        if (primary.isPresent()) {
            return primary;
        }
        if (!ModelScenario.DEFAULT.equals(scenario)) {
            return catalog.defaultForScenario(ModelScenario.DEFAULT)
                    .filter(ModelDefinition::isEnabled);
        }
        return Optional.empty();
    }

    /**
     * 按目录主键（catalog id，UUID）解析模型定义（Per-Agent 绑目录 id 用）。
     *
     * <p><b>语义</b>：Agent 的 {@code modelRef} 若直接存的是目录记录的 id（而非模型名），
     * 则走此路径精确取回那条模型的真实标识（{@code modelName}）。
     * 这样「换模型」只需在管理后台改目录记录、或把 Agent 的 {@code modelRef} 指到另一条 id，
     * 而不必在 Agent 定义里硬编码模型名。
     *
     * <p>⚠️ 已禁用模型不解析（与按名解析同口径，禁用即不可用于任何场景）。
     */
    public @NonNull Optional<ModelDefinition> resolveById(@NonNull String id) {
        return catalog.get(id).filter(ModelDefinition::isEnabled);
    }

    /**
     * 解析并强制要求有结果（fail-closed）。
     *
     * <p>调用方在「没有模型就绝不能继续」的语境下使用（如 Worker 真正发起调用前）。
     * 取不到即抛，由调用方按「确定失败」处理。
     */
    public @NonNull ModelDefinition resolveRequired(@NonNull String scenario,
                                                   @Nullable String explicitModelName) {
        return resolve(scenario, explicitModelName).orElseThrow(() ->
                new IllegalStateException("场景 [" + scenario + "] 无可用的模型配置"
                        + (explicitModelName == null || explicitModelName.isBlank()
                        ? "（且未配置默认模型）"
                        : "（显式模型 [" + explicitModelName + "] 未命中且未配置默认模型）")
                        + "——fail-closed 拒绝猜测模型"));
    }

    /** 平台全局默认模型（{@link ModelScenario#DEFAULT} 场景默认，或第一个默认位模型）。 */
    public @NonNull Optional<ModelDefinition> globalDefault() {
        Optional<ModelDefinition> def = defaultForScenario(ModelScenario.DEFAULT);
        if (def.isPresent()) {
            return def;
        }
        return catalog.list().stream()
                .filter(d -> d.isEnabled() && d.isDefault())
                .findFirst();
    }
}
