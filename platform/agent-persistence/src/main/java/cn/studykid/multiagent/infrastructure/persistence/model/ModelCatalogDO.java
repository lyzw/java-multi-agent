package cn.studykid.multiagent.infrastructure.persistence.model;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** model_catalog 全局表 DO（平台域，走 opsSqlSessionFactory）。 */
@TableName(value = "model_catalog", autoResultMap = true)
public class ModelCatalogDO {

    @TableField("id")
    private String id;
    @TableField("provider")
    private String provider;
    @TableField("scenario")
    private String scenario;
    @TableField("model_name")
    private String modelName;
    @TableField("display_name")
    private String displayName;
    @TableField("base_url")
    private String baseUrl;
    @TableField("is_default")
    private boolean isDefault;
    @TableField("supports_tools")
    private boolean supportsTools;
    @TableField("supports_streaming")
    private boolean supportsStreaming;
    @TableField("supports_vision")
    private boolean supportsVision;
    @TableField("dimension")
    private Integer dimension;
    @TableField("is_enabled")
    private boolean isEnabled;
    @TableField("sort_order")
    private int sortOrder;
    @TableField("description")
    private String description;
    @TableField("context_window_tokens")
    private int contextWindowTokens;
    @TableField("max_input_tokens")
    private int maxInputTokens;
    @TableField("tokenizer_id")
    private String tokenizerId;
    @TableField("supports_prompt_caching")
    private boolean supportsPromptCaching;
    @TableField("supports_tokenize_api")
    private boolean supportsTokenizeApi;
    @TableField("created_at")
    private Instant createdAt;
    @TableField("updated_at")
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getProvider() { return provider; }
    public void setProvider(String v) { this.provider = v; }
    public String getScenario() { return scenario; }
    public void setScenario(String v) { this.scenario = v; }
    public String getModelName() { return modelName; }
    public void setModelName(String v) { this.modelName = v; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { this.displayName = v; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { this.baseUrl = v; }
    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean v) { this.isDefault = v; }
    public boolean isSupportsTools() { return supportsTools; }
    public void setSupportsTools(boolean v) { this.supportsTools = v; }
    public boolean isSupportsStreaming() { return supportsStreaming; }
    public void setSupportsStreaming(boolean v) { this.supportsStreaming = v; }
    public boolean isSupportsVision() { return supportsVision; }
    public void setSupportsVision(boolean v) { this.supportsVision = v; }
    public Integer getDimension() { return dimension; }
    public void setDimension(Integer v) { this.dimension = v; }
    public boolean isEnabled() { return isEnabled; }
    public void setEnabled(boolean v) { this.isEnabled = v; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int v) { this.sortOrder = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public int getContextWindowTokens() { return contextWindowTokens; }
    public void setContextWindowTokens(int v) { this.contextWindowTokens = v; }
    public int getMaxInputTokens() { return maxInputTokens; }
    public void setMaxInputTokens(int v) { this.maxInputTokens = v; }
    public String getTokenizerId() { return tokenizerId; }
    public void setTokenizerId(String v) { this.tokenizerId = v; }
    public boolean isSupportsPromptCaching() { return supportsPromptCaching; }
    public void setSupportsPromptCaching(boolean v) { this.supportsPromptCaching = v; }
    public boolean isSupportsTokenizeApi() { return supportsTokenizeApi; }
    public void setSupportsTokenizeApi(boolean v) { this.supportsTokenizeApi = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
