package cn.studykid.multiagent.infrastructure.persistence.model;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** platform.sys_dictionary DO（平台域，走 BYPASSRLS opsSqlSessionFactory）。 */
@TableName(value = "platform.sys_dictionary", autoResultMap = true)
public class DictionaryDO {

    @TableField("dict_code")
    private String dictCode;

    @TableField("dict_name")
    private String dictName;

    @TableField("description")
    private String description;

    @TableField("built_in")
    private boolean builtIn;

    @TableField("enabled")
    private boolean enabled;

    @TableField("sort_order")
    private int sortOrder;

    @TableField("updated_at")
    private Instant updatedAt;

    @TableField("version")
    private int version;

    public String getDictCode() { return dictCode; }
    public void setDictCode(String v) { this.dictCode = v; }
    public String getDictName() { return dictName; }
    public void setDictName(String v) { this.dictName = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public boolean isBuiltIn() { return builtIn; }
    public void setBuiltIn(boolean v) { this.builtIn = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int v) { this.sortOrder = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public int getVersion() { return version; }
    public void setVersion(int v) { this.version = v; }
}
