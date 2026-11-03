package cn.studykid.rag.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * RAG 入库 / 索引 Worker 启动入口（RAG-D09 R2-1：可部署索引制品）。
 *
 * <p>组件扫描<b>仅限 {@code cn.studykid.rag.worker}</b>，刻意不扫描
 * rag-api / rag-bootstrap / rag-infrastructure（其 bean 经 {@code @Bean} 显式装配），
 * 以避免：
 * <ul>
 *   <li>误托管 rag-api 的检索 REST 端点（Worker 不对外提供检索服务）；</li>
 *   <li>与 rag-bootstrap 重复装配检索链路 bean 造成冲突。</li>
 * </ul>
 *
 * <p>⚠️ 本应用<b>不</b>解析令牌、<b>不</b>自建用户体系——
 *   租户上下文由 {@code ChunkIndexer}/{@code CorpusRepository} 在事务内
 *   通过 {@code set_config('app.tenant_id', ...)} 注入（RAG-D02 §1.1 单向调用约定）。
 */
/*
 * ⚠️ {@code @EnableScheduling} 是 V1.0.2 新增的**必需品**：
 *    摄取任务消费循环（IngestJobConsumer）与僵尸清扫（IngestJobSweeper）
 *    都以 {@code @Scheduled} 驱动。没有它，应用<b>照常启动、照常健康</b>，
 *    但任务永远停在 PENDING——这是最难排查的静默失败形态
 *    （无异常、无日志、无告警，只有用户抱怨「上传后一直没反应」）。
 */
@SpringBootApplication(scanBasePackages = "cn.studykid.rag.worker")
@EnableScheduling
public class RagWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagWorkerApplication.class, args);
    }
}
