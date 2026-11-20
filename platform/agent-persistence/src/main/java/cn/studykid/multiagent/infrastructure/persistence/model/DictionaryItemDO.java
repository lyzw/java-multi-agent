package cn.studykid.multiagent.infrastructure.persistence.model;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** platform.sys_dictionary_item DO（平台域）。 */
@TableName(value = "platform.sys_dictionary_item", autoResultMap = true)
public class DictionaryItemDO {

    @TableField("dict_code")
    private String dictCode;

    @TableField("item_code")
    private String itemCode;

    @TableField("item_label")
    private String itemLabel;

    @TableField("sort_order")
    private int sortOrder;

    @TableField("built_in")
    private boolean builtIn;

    @TableField("enabled")
    private boolean enabled;

    @TableField("updated_at")
    private Instant updatedAt;

    @TableField("version")
    private int version;

    public String getDictCode() { return dictCode; }
    public void setDictCode(String v) { this.dictCode = v; }
    public String getItemCode() { return itemCode; }
    public void setItemCode(String v) { this.itemCode = v; }
    public String getItemLabel() { return itemLabel; }
    public void setItemLabel(String v) { this.itemLabel = v; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int v) { this.sortOrder = v; }
    public boolean isBuiltIn() { return builtIn; }
    public void setBuiltIn(boolean v) { this.builtIn = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public int getVersion() { return version; }
    public void setVersion(int v) { this.version = v; }
}
