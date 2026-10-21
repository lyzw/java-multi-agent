package cn.studykid.rag.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * RAG 子系统启动入口（RAG-D09 G2 条件②：rag-bootstrap 能启动并自检通过）。
 *
 * <p>组件扫描覆盖整个 {@code cn.studykid.rag} 命名空间，以装配：
 * <ul>
 *   <li>rag-api 的 {@code @RestController}（检索 REST 端点 {@code RagRetrievalController}）；</li>
 *   <li>本包内的 {@code @Configuration}（检索链路装配 {@code RagInfrastructureConfig}）；</li>
 *   <li>本包内的 {@code @Component}（启动自检 {@code RagStartupSelfCheck}）。</li>
 * </ul>
 *
 * <p>⚠️ 本启动器<b>不</b>自建用户体系、<b>不</b>解析令牌——
 * 租户上下文由主平台在工具执行事务内通过 {@code set_config('app.tenant_id', ...)} 注入
 * （RAG-D02 §1.1 单向调用约定：主平台调用 RAG，RAG 不回调主平台）。
 */
@SpringBootApplication(scanBasePackages = "cn.studykid.rag")
public class RagApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
    }
}
