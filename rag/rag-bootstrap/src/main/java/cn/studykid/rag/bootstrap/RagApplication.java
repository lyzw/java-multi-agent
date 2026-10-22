package cn.studykid.rag.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * RAG 子系统启动入口（RAG-D09 G2 条件②：rag-bootstrap 能启动并自检通过）。
 *
 * <p>组件扫描<b>显式限定</b>为检索侧包（bootstrap / api / application / infrastructure），
 * <b>刻意排除 {@code cn.studykid.rag.worker}</b>：入库与索引流水线由独立的 rag-worker 应用托管，
 * 检索服务不应误托管其索引 bean（见 {@code RagInfrastructureConfig} 的 R3 注释与
 * {@code ReindexRunner} 的 {@code @ConditionalOnProperty} 守卫）。
 *
 * <p>⚠️ 本启动器<b>不</b>自建用户体系、<b>不</b>解析令牌——
 * 租户上下文由主平台在工具执行事务内通过 {@code set_config('app.tenant_id', ...)} 注入
 * （RAG-D02 §1.1 单向调用约定：主平台调用 RAG，RAG 不回调主平台）。
 */
@SpringBootApplication(scanBasePackages = {
        "cn.studykid.rag.bootstrap",
        "cn.studykid.rag.api",
        "cn.studykid.rag.application",
        "cn.studykid.rag.infrastructure"
})
public class RagApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
    }
}
