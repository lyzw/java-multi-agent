package cn.studykid.rag.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import cn.studykid.rag.core.embedding.EmbeddingInputTemplate;

/**
 * Embedding 输入模板配置（RAG-D28 §2.4 / P3-1）。
 *
 * <p>⚠️ <b>版本 fail-closed</b>：模板版本必须在装配期校验——
 * 未知版本若静默回退到 v1，评测会把「换了模板」误归因到切分/模型，
 * 而留痕列写的却是新版本号（配置说新的、实际跑旧的，C4 假失败同款）。
 * 目前仅实现 v1，其余版本一律拒绝启动。
 */
@Component
@ConfigurationProperties(prefix = "rag.embedding.input")
public class RagEmbeddingInputProperties {

    /** 模板版本；默认 v1，未知版本装配期抛错。 */
    private String templateVersion = EmbeddingInputTemplate.VERSION;

    /**
     * 是否对向量做 L2 归一化断言（P3-2 消费；本配置先预留，false 表示未启用）。
     *
     * <p>⚠️ 单独列出是为了让 P3-2 不需要再改配置形态。
     */
    private boolean normalize = false;

    /**
     * MRL 截断后的真实输出维度（D29 DEC-P2；1024 为项目选定档）。
     *
     * <p>⚠️ 仅作留痕与 P3-5 校验的入参，本批不改向量列。
     */
    private int realOutputDimension = 1024;

    public @org.jspecify.annotations.NonNull String getTemplateVersion() {
        return templateVersion;
    }

    public void setTemplateVersion(String templateVersion) {
        this.templateVersion = templateVersion;
    }

    public boolean isNormalize() {
        return normalize;
    }

    public void setNormalize(boolean normalize) {
        this.normalize = normalize;
    }

    public int getRealOutputDimension() {
        return realOutputDimension;
    }

    public void setRealOutputDimension(int realOutputDimension) {
        this.realOutputDimension = realOutputDimension;
    }

    /** 装配期调用：未知模板版本直接抛错（fail-closed，不回退 v1）。 */
    public void validate() {
        if (!EmbeddingInputTemplate.VERSION.equals(templateVersion)) {
            throw new IllegalStateException("未知的 embedding 输入模板版本：" + templateVersion
                    + "（当前仅支持 " + EmbeddingInputTemplate.VERSION
                    + "；未知版本不得静默回退，否则留痕与实际输入漂移）");
        }
    }
}
