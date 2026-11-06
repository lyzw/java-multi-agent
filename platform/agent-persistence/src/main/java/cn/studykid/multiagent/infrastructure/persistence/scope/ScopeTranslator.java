package cn.studykid.multiagent.infrastructure.persistence.scope;

import cn.studykid.multiagent.core.security.ScopeFilter;

import org.jspecify.annotations.NonNull;

/**
 * 作用域翻译器 SPI —— 将数据库无关的 {@link ScopeFilter} 翻译为目标存储的查询谓词（12 文 §4.1）。
 *
 * <p>新增一种存储后端 = 实现一个 {@code ScopeTranslator}（如 SQL / Milvus 表达式 / ES bool），
 * 不需要新增一条权限规则。这是「权限语义与方言解耦」的枢纽。
 *
 * @param <Q> 目标存储的谓词表达（如 {@code SqlFragment} / 向量库表达式）
 */
public interface ScopeTranslator<Q> {

    @NonNull Q render(@NonNull ScopeFilter filter);
}
