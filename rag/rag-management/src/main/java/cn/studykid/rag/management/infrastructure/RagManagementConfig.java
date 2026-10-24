package cn.studykid.rag.management.infrastructure;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import cn.studykid.rag.infrastructure.indexing.ChunkIndexer;
import cn.studykid.rag.management.ports.CorpusIndexingPort;
import cn.studykid.rag.management.ports.CorpusManagementPort;
import cn.studykid.rag.management.ports.DictionaryPort;

/**
 * 管理面装配（RAG-D07 §2 管理面入口）。
 *
 * <p>装配内容：
 * <pre>
 *   CorpusManagementPort → JdbcCorpusManagementRepository
 *   CorpusIndexingPort   → ChunkIndexerIndexingAdapter（委托给基础设施层的 ChunkIndexer）
 * </pre>
 *
 * <p><b>⚠️ 事务模板的必要性</b>：管理侧所有 SQL 都以
 * {@code rag_current_tenant()} 收口，而该函数读的是<b>会话变量</b>
 * {@code app.tenant_id}——{@code set_config(..., is_local=true)} 只在
 * <b>当前事务内</b>有效。若脱离事务（自动提交模式），变量立即失效，
 * RLS 把所有行过滤掉，表现为「管理台永远是空的」且<b>不报错</b>。
 * 因此这里复用与检索侧同构的 {@link TransactionTemplate}。
 *
 * <p><b>⚠️ 与检索侧事务模板的关系</b>：两者都从同一个
 * {@link PlatformTransactionManager} 创建，是<b>同一事务管理器</b>下的两个
 * 独立模板实例。模板本身无状态，可安全共享；此处单独定义是为了让
 * 管理面在装配上<b>自洽</b>——即使检索侧装配被裁剪，管理面仍能工作。
 */
@Configuration
public class RagManagementConfig {

    /**
     * 管理面事务模板。
     *
     * <p>⚠️ 用 {@code @Qualifier} 显式限定 bean 名：检索侧
     * （{@code RagInfrastructureConfig}）也定义了 {@link TransactionTemplate}，
     * 两个同类型 bean 并存时，按类型注入会抛
     * {@code NoUniqueBeanDefinitionException}。
     * 显式限定比依赖「参数名回退匹配」更稳——后者依赖 {@code -parameters}
     * 编译参数，一旦被移除就会静默变成歧义失败。
     *
     * @param txManager 事务管理器
     * @return 事务模板
     */
    @Bean
    @Qualifier("ragManagementTransactionTemplate")
    public TransactionTemplate ragManagementTransactionTemplate(
            PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }

    /**
     * 语料数据管理端口的 JDBC 实现。
     *
     * @param jdbcTemplate JDBC 模板
     * @param transactionTemplate 管理面事务模板
     * @return 管理端口
     */
    @Bean
    public CorpusManagementPort corpusManagementPort(
            JdbcTemplate jdbcTemplate,
            @Qualifier("ragManagementTransactionTemplate")
            TransactionTemplate transactionTemplate) {
        return new JdbcCorpusManagementRepository(jdbcTemplate, transactionTemplate);
    }

    /**
     * 语料重索引端口。
     *
     * <p><b>⚠️ 依赖的是基础设施层的 {@link ChunkIndexer} 实现</b>：
     * 索引逻辑只有一份（{@code rag-infrastructure}），
     * 管理面与 worker 共用——「哪些片段算待索引」这条 fail-closed 口径
     * 若有两份实现，未校验语料就可能经由其中一条路径进索引。
     *
     * <p><b>⚠️ 本类不定义 {@link ChunkIndexer} bean</b>：
     * 它需要 {@code EmbeddingPort} 与 {@code CorpusRepository}，
     * 而这两个 bean 的归属方是<b>启动装配</b>（rag-bootstrap / rag-worker），
     * 管理面只「消费」而不「生产」——
     * 否则管理面会替启动器决定用哪个嵌入模型。
     *
     * @param chunkIndexer 基础设施层的片段索引器
     * @return 重索引端口
     */
    @Bean
    public CorpusIndexingPort corpusIndexingPort(ChunkIndexer chunkIndexer) {
        return new ChunkIndexerIndexingAdapter(chunkIndexer);
    }

    /**
     * 字典端口的 JDBC 实现（Point 2：枚举下拉的数据源）。
     *
     * <p><b>⚠️ 复用管理面事务模板</b>：动态字典的读写同样以
     * {@code rag_current_tenant()} 收口，必须在设置了 {@code app.tenant_id} 的
     * 事务内执行——与 {@link CorpusManagementPort} 同一把锁，
     * 故共用 {@code ragManagementTransactionTemplate}。
     *
     * @param jdbcTemplate JDBC 模板
     * @param transactionTemplate 管理面事务模板
     * @return 字典端口
     */
    @Bean
    public DictionaryPort dictionaryPort(
            JdbcTemplate jdbcTemplate,
            @Qualifier("ragManagementTransactionTemplate")
            TransactionTemplate transactionTemplate) {
        return new JdbcDictionaryRepository(jdbcTemplate, transactionTemplate);
    }
}
