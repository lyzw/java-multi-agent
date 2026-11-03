package cn.studykid.multiagent.core.security;

/**
 * 权限动作 —— 与 05 文能力层（Agent/Skill/Tool/MCP）授权决策因子互不重叠，
 * 本文（12 文）只管数据层行级租户隔离。
 */
public enum Action {

    /** 单资源读取（详情）。 */
    READ,

    /** 写入（INSERT/UPDATE）。 */
    WRITE,

    /** 删除。 */
    DELETE,

    /** 发布（平台级状态变更）。 */
    PUBLISH,

    /** 集合检索（RAG 检索路径）。 */
    RETRIEVE
}
