/* 全站目录与检索索引 · 由构建脚本生成，维护时改这里 */
window.KB_SITE = {
  "title": "Java 后端转型知识库",
  "pages": [
    {
      "id": "index",
      "file": "index.html",
      "group": "总览",
      "title": "总入口 · 学习路径",
      "sub": "知识库导航、阅读路径、证据约定与维护说明",
      "ai": false,
      "chapters": [
        {
          "id": "kb-index",
          "title": "★ 知识库由哪些文件组成",
          "subs": [],
          "keywords": "docs/Java后端转型知识库/ assets/ assets/handbook.css assets/site-data.js assets/handbook.js site-data.js HTML A1A1 Token Embedding A2A2 A3A3 project-overview.html environment-config.html java-skill-transfer.html runtime-flows.html troubleshooting.html reference.html ai-01-fundamentals.html ai-02-decisions.html ai-03-hard-problems.html ai-10-quality-evaluation.html ai-11-cost-governance.html ai-12-observability-runbook.html handbook.css handbook.js index.html"
        },
        {
          "id": "ch0",
          "title": "0. 使用说明与证据约定",
          "subs": [
            {
              "id": "ch0-1",
              "title": "0.1 按目标选择阅读路径"
            },
            {
              "id": "ch0-2",
              "title": "0.2 事实来源与标注图例"
            },
            {
              "id": "ch0-3",
              "title": "0.3 代码块与示例的约定"
            },
            {
              "id": "ch0-4",
              "title": "0.4 维护说明"
            }
          ],
          "keywords": "pom.xml *.java application.yml *.sql scripts/*.sh .github/workflows/ci.yml *.env.example deploy/sql/README.md docs/** rag/docs/** 问题记录/** 审查文档/** <口令> <host> <MUST-FILL> *.env platform/ rag/ TST-01 <section class=\"chapter\"> MANIFEST README Java Flyway MUST FILL HTML ci.yml env.example"
        },
        {
          "id": "kb-ai-path",
          "title": "★ AI 十二册怎么读",
          "subs": [],
          "keywords": "CRUD ai-01-fundamentals.html environment-config.html runtime-flows.html ai-02-decisions.html reference.html ai-03-hard-problems.html troubleshooting.html ai-10-quality-evaluation.html ai-11-cost-governance.html ai-12-observability-runbook.html"
        },
        {
          "id": "kb-boundary",
          "title": "★ 版本关系与事实边界",
          "subs": [],
          "keywords": "HTML reference.html"
        }
      ]
    },
    {
      "id": "01-project-overview",
      "file": "01-project-overview.html",
      "group": "基础六篇",
      "title": "第一篇 · 认识项目",
      "sub": "项目全景、技术架构、目录与模块地图（第 1–3 章）",
      "ai": false,
      "chapters": [
        {
          "id": "ch1",
          "title": "1. 项目全景：业务、边界与进程拓扑",
          "subs": [
            {
              "id": "ch1-1",
              "title": "1.1 业务定位与首期场景"
            },
            {
              "id": "ch1-2",
              "title": "1.2 系统与进程拓扑"
            },
            {
              "id": "ch1-3",
              "title": "1.3 端口与服务清单"
            },
            {
              "id": "ch1-4",
              "title": "1.4 三组容易混淆的「双」"
            }
          ],
          "keywords": "docs/业务架构图.md docs/系统架构图.md get_student_profile record_weakness student_id + run_id + knowledge_point admin-console aud=admin-console-api platform docs/P0/P0/P0-D00_决策基线与业务场景定义.md rag/docs/RAG-D00_决策基线与范围边界.md assignment-copilot ${RAG_SERVER_PORT:8081} agent-pg17 agent docker port /admin/ platform/ cn.studykid.multiagent rag/ cn.studykid.rag clean verify agent_dev study_rag platform.env rag.env frontend/ admin-console/ Java SaaS Agent Tenant Control Plane Platform Tool Skill Spring Boot Maven Vite Antd HTTP REST Worker Outbox RabbitMQ Quorum Queue MiniMax OpenAI Scheduled SKIP LOCKED HttpRagRetrievalGateway RAG_SERVER_PORT RabbitMQ5672 Docling Prometheus Grafana9090 www.w3.org cors.disable application.yml vite.config.ts platform.env.example dev-backend.sh server.port dev-postgres.sh dev-rabbitmq.sh rag-it-env.sh docker-compose.yml e2e-index-generation.sh"
        },
        {
          "id": "ch2",
          "title": "2. 技术架构：六边形、事实源与五条链路",
          "subs": [
            {
              "id": "ch2-1",
              "title": "2.1 六边形分层与依赖方向"
            },
            {
              "id": "ch2-2",
              "title": "2.2 PostgreSQL 是唯一事实源：Outbox + 租约 + Worker"
            },
            {
              "id": "ch2-3",
              "title": "2.3 租户隔离：RLS 与正在进行的迁移"
            },
            {
              "id": "ch2-4",
              "title": "2.4 双身份域与执行票证"
            },
            {
              "id": "ch2-5",
              "title": "2.5 五条执行链路与请求级 SSE"
            },
            {
              "id": "ch2-6",
              "title": "2.6 贯穿全项目的设计原则"
            }
          ],
          "keywords": "agent-core agent-ports agent-infrastructure agent-persistence platform-bootstrap ModuleDependencyDirectionTest DatabaseBoundaryTest java.sql.* org.springframework.jdbc.* AgentCorePurityTest docs/系统架构图.md docs/P0/P2/P2-D02_Outbox发布与MQ消费.md outbox_event OutboxRelay OutboxPublisher tenantId tenant_id + run_id + message_id RunRecoverySweeper agent.messaging.mode=database agent_message_queue agent.mq.* EventRoutedMessageHandler TraceCollectMessageHandler tenant_id = current_tenant_id() app.tenant_id TenantSessionBinder set_config(..., true) ScopeFilter TenantWithShared TenantOnly Unrestricted None() docs/developer-onboarding.md Jdbc*Repository rlsDependent() AUTHZ_MISMATCH=0 JwtTokenService platform.security.execution-ticket.* code reason_code platform.platform_audit_event /api/agent/chat/stream /api/knowledge/agent/chat/stream begin() finish() PromptGuard.wrapToolResult(\"rag\", …) context.checkpoint answer → usage → context.checkpoint? → done done AGENT_CONTEXT_STATE_HMAC_KEY ScopeFilter.None() execution_snapshot id@version ClosureResolutionException platform_ops run_id/user_id/student_id Spring Bean TenantContextScopeFilter EffectiveClosure JDBC Flyway Hikari Code Review PostgreSQL Outbox Worker RUNNING UNKNOWN RabbitMQ FORCE String SESSION_VAR SQL_SET_TENANT SELECT NonNull Connection TenantContext SQLException PreparedStatement None Jdbc Repository Gate AUTHZ_MISMATCH Agent PromptGuard HMAC BYPASSRLS"
        },
        {
          "id": "ch3",
          "title": "3. 目录结构与模块地图",
          "subs": [
            {
              "id": "ch3-1",
              "title": "3.1 仓库根目录"
            },
            {
              "id": "ch3-2",
              "title": "3.2 platform reactor：13 个模块"
            },
            {
              "id": "ch3-3",
              "title": "3.3 rag reactor：10 个模块"
            },
            {
              "id": "ch3-4",
              "title": "3.4 支撑目录与测试分布"
            }
          ],
          "keywords": "README.md Makefile docker-compose.yml CONTRIBUTING.md monitoring/ deploy/docling/ docs/P0/P1/P1-D10_工程基线与本地开发手册.md §6 RunState TenantContext ScopeFilter CapAgentDef ConfigRelease RoutingEngine PolicyCodec PublishGuards RunCreationPort MessageSender ModelGateway RequestAssemblyPort AgentChatEvent CompactionPipeline ContextBudget RagHitQuota ContextAssembler ItTenantProvisioner ItTargetDatabase PlatformDdl RunCreationService ConfigPublishService L1/L2AttributionEvaluator PromptGuard MiniMaxModelGateway JwtTokenService RabbitMessageSender HttpRagRetrievalGateway Jdbc*Repository TenantSessionBinder PgQueueRepository MemoryRecallService MemoryConsolidator RetrievalService VectorSearchEngine RunExecutionLoop ToolInvocationGate OutboxPublisher OutboxRelay AgentChatOrchestrator RunController RunEventStreamer AgentChatController SecurityConfiguration PlatformApplication ApplicationConfiguration WorkerLoopConfig application.yml agent-infra agent-infrastructure platform/pom.xml <modules> Chunk ChunkRole Citation EditionCoordinate TrustTier CorpusRepository EmbeddingPort DocumentParserPort PremisePreprocessor HeadingHierarchyChunker HybridChunkerClient IngestionPipeline IngestJobOrchestrator RetrievalOrchestrator GroundedGenerationService OpenAiCompatibleEmbeddingPort DoclingDocumentParserPort LocalPortGate ChineseTokenizer JdbcRetrievalRepository VectorColumn RagStartupSelfCheck LocalObjectStoreAdapter RagWorkerApplication @EnableScheduling IngestJobConsumer FOR UPDATE SKIP LOCKED RagRetrievalController POST /api/v1/retrieval /generate RagManagementController RagApplication"
        }
      ]
    },
    {
      "id": "02-environment-config",
      "file": "02-environment-config.html",
      "group": "基础六篇",
      "title": "第二篇 · 环境与配置",
      "sub": "开发环境、三层配置、数据库初始化、启动与联调（第 4–7 章）",
      "ai": false,
      "chapters": [
        {
          "id": "ch4",
          "title": "4. 开发环境、依赖与前置检查",
          "subs": [
            {
              "id": "ch4-1",
              "title": "4.1 版本矩阵"
            },
            {
              "id": "ch4-2",
              "title": "4.2 JAVA_HOME：脚本里有三种取值"
            },
            {
              "id": "ch4-3",
              "title": "4.3 前置检查清单"
            },
            {
              "id": "ch4-4",
              "title": "4.4 环境层面的已知限制"
            }
          ],
          "keywords": "-parameters -proc:full /Users/zhouwei/software/apache-maven-3.10.0/bin spring-boot-starter-amqp Spring AI 2.0 ChatClient ToolCallingAdvisor jOOQ 3.20+ # ① 集成测试 / 反证脚本 / 上下文压缩签核包（推荐用于命令行） /usr/libexec/java_home -v 21 /opt/homebrew/opt/openjdk@21 java -version mvn -v mvn -o .m2-local .m2repo RMQ_MIRROR=docker.1ms.run zsh: command not found: npm ~/.zshrc nvm.sh *.env.example *.env <MUST-FILL> jwt.env git status psql scripts/tools/*.java docker exec <container> psql agent_dev SELECT current_database() vitest pool: forks + singleFork: true rag-it-env.sh rag-pg-s2 JDK21 Maven3 Users Spring Boot4 BOMADR Framework JacksonJackson PostgreSQL17 RabbitMQ4 Queue Node Vite Vitest Antd KaTeX ReDoS JAVA_HOME PATH Maven Java Contents Home Docker RabbitMQ Docling RMQ_MIRROR MUST FILL Names Status Ports JDBC PG17 PG16 SELECT maven.compiler.release pom.xml apache-maven-3.10.0 tools.jackson.core dev-postgres.sh dev-rabbitmq.sh v24.21.0 package.json jdk-21.0.12.jdk dev-backend.sh dev-rag.sh docker.1ms.run env.example"
        },
        {
          "id": "ch5",
          "title": "5. 配置项全解：三层模型与逐项清单",
          "subs": [
            {
              "id": "ch5-1",
              "title": "5.1 配置从哪里来：三层模型"
            },
            {
              "id": "ch5-2",
              "title": "5.2 env 文件的格式纪律"
            },
            {
              "id": "ch5-3",
              "title": "5.3 platform.env.example 逐项"
            },
            {
              "id": "ch5-4",
              "title": "5.4 rag.env.example 逐项"
            },
            {
              "id": "ch5-5",
              "title": "5.5 jwt.env：一份文件、两个进程、四个变量"
            },
            {
              "id": "ch5-6",
              "title": "5.6 platform 的 application.yml 分组"
            },
            {
              "id": "ch5-7",
              "title": "5.7 rag 的两份 yml 与一致性纪律"
            },
            {
              "id": "ch5-8",
              "title": "5.8 配置层的五个高频坑"
            }
          ],
          "keywords": "model_catalog AGENT_MODEL_MINIMAX_API_KEY KEY=VALUE export $(...) if…fi application-dev.yml spring.profiles.active application-{profile}.yml @Profile @ConditionalOnProperty SPRING_PROFILES_ACTIVE=dev app_agent ACCESS_REFUSED rabbitmq platform.env.example RagStartupSelfCheck current_user AuthConfiguration dev-backend.sh dev-rag.sh source ./jwt.env rag-worker YamlPropertiesFactoryBean tenant-slugs switch-guard.rabbit-residual-check server: model-catalog agent.context.max-state-chars ${RAG_SERVER_PORT:8081} search_path TO rag, public spring.flyway.enabled=false @EnableScheduling rag.embedding.* MiniMax-M2.5: MiniMax-M2 \"[MiniMax-M2.5]\" platform.security.* \"30\" SERVER_PORT RAG_SERVER_PORT FAILED / CONFIG_UNAVAILABLE IDEA EnvFile ENV_VAR Value VALUE Base64 JWKS Spring Profile ConditionalOnProperty SPRING_PROFILES_ACTIVE SERVER_PORT8080application SPRING_DATASOURCE_USERNAME PASSWORD MUST FILL SPRING_DATASOURCE_DRIVER_CLASS_NAMEorg RabbitMQRABBITMQ_USER VHOSTagent RABBITMQ_PASSWORD DOWN AGENT_MESSAGING_MODEdatabase AGENT_PLATFORM_OPS_DATASOURCE_URL USERNAMEagent_dev Hikari AGENT_PLATFORM_OPS_DATASOURCE_PASSWORD BYPASSRLS AGENT_MODEL_MINIMAX_BASE_URLhttps AGENT_MODEL_MINIMAX_DEFAULT_MODELMiniMax AGENT_CONTEXT_ PostgreSQLRAG_PG_URLjdbc NOSUPERUSER NOBYPASSRLS RAG_PG_PASSWORD USERNAME RAG_OPS_DATASOURCE_URL USERNAMEstudy_rag RAG_OPS_DATASOURCE_PASSWORD EmbeddingPort RAG_EMBEDDING_MODEL API_KEY RAG_EMBEDDING_DIMENSION VECTOR_TYPE4096 BATCH_SIZE TIMEOUT_SECONDS8 RAG_RETRIEVAL_MIN_SCORE0 Docling RAG_DOCLING_BASE_URLhttp"
        },
        {
          "id": "ch6",
          "title": "6. 数据库初始化、角色与结构基线",
          "subs": [
            {
              "id": "ch6-1",
              "title": "6.1 唯一初始化序列：01 → 02 → 03 → 04"
            },
            {
              "id": "ch6-2",
              "title": "6.2 结构基线与数字冲突"
            },
            {
              "id": "ch6-3",
              "title": "6.3 角色体系：谁可以做什么"
            },
            {
              "id": "ch6-4",
              "title": "6.4 集成测试必须用独立库"
            }
          ],
          "keywords": "deploy/sql/README.md psql -v ON_ERROR_STOP=1 -f … CREATE ROLE study_rag execution_snapshot.context_policy platform.review_task CREATE TABLE CREATE POLICY SchemaStructureTest SchemaDriftIT 03_platform_roles.sql REVOKE 07_*.sql 07_execution_snapshot_context_policy.sql 07_model_catalog_context_upgrade.sql IF NOT EXISTS @Primary ai_agent /api/platform/v1/** SPRING_DATASOURCE_USERNAME app_agent rag_app ON CONFLICT DO UPDATE ItTenantProvisioner.provision(adminPool, slug) execution_snapshot @BeforeAll agent.worker.tenant-slugs IT_HOST=127.0.0.1 IT_PORT=5433 IT_ADMIN_USER=agent it-env.sh README Flyway ALTER DEFAULT PRIVILEGES CREATEDB CREATEROLE ON_ERROR_STOP CREATE ROLE TABLE POLICY13 EXISTS Primary PLATFORM GRANT BYPASSRLS FORCE CONFLICT UPDATE IT_HOST IT_PORT IT_ADMIN_USER IT_RAG_ AI_AGENT_PASSWORD ItTenantProvisioner BeforeAll Maven POLICY NOBYPASSRLS NOSUPERUSER LOGIN www.w3.org init-db.sh rag-it-env.sh dev-it-db.sh init-it-db.sh"
        },
        {
          "id": "ch7",
          "title": "7. 启动步骤、验证与联调",
          "subs": [
            {
              "id": "ch7-1",
              "title": "7.1 一键方式（最快）"
            },
            {
              "id": "ch7-2",
              "title": "7.2 分步启动（推荐理解顺序）"
            },
            {
              "id": "ch7-3",
              "title": "7.3 两个 jar：一个必须避开的命令陷阱"
            },
            {
              "id": "ch7-4",
              "title": "7.4 存活判据与健康检查"
            },
            {
              "id": "ch7-5",
              "title": "7.5 前端代理顺序与同源约束"
            },
            {
              "id": "ch7-6",
              "title": "7.6 停止服务"
            }
          ],
          "keywords": "scripts/gen-jwt-keys.sh chmod 600 RMQ_MIRROR=docker.1ms.run ./scripts/dev-rabbitmq.sh start ./scripts/dev-postgres.sh start status scripts/init-db.sh platform|rag … dev-postgres.sh start ./scripts/dev-postgres.sh verify ./scripts/dev-seed.sh --list dev-seed-platform-users.sh --commit cd platform && mvn -o clean verify cd rag && mvn -o clean verify mvn -o -pl platform-bootstrap -am package -DskipTests scripts/dev-backend.sh -exec.jar scripts/dev-rag.sh [端口] rag-worker cd frontend && npm run dev cd admin-console && npm run dev platform-bootstrap spring-boot-maven-plugin:repackage <classifier>exec</classifier> @SpringBootTest platform-bootstrap-1.0.1-SNAPSHOT.jar platform-bootstrap-1.0.1-SNAPSHOT-exec.jar Start-Class: …PlatformApplication docs/本地启动指南.md §0 / §4.1 platform-bootstrap/pom.xml clean dev-backend.sh {\"kid\":…,\"alg\":\"RSA\",…} docs/本地启动指南.md §4.3 /actuator/health /api/auth/jwks vite.config.ts /api/v1 /api SecurityConfiguration cors.disable() cors.allowOrigins(\"*\") VITE_DEV_API_TARGET=http://… npm run dev # 前端 / 后端进程：Ctrl+C；或按端口结束 RabbitMQ Ollama Dtest QuickstartDemoTest RMQ_MIRROR DskipTests MANIFEST SpringBootTest SNAPSHOT Main Class Start PlatformApplication JAVA_HOME HTML Tenant Authorization VITE_DEV_API_TARGET Ctrl VITE_DEV_RAG_API_TARGET VITE_DEV_RAG_TARGET dev-env-up.sh env.example gen-jwt-keys.sh jwt.env docker.1ms.run dev-rabbitmq.sh dev-postgres.sh init-db.sh dev-seed.sh dev-seed-platform-users.sh platform.env exec.jar dev-rag.sh pom.xml spring.profiles.active server.port rag.env rag-bootstrap-1.0.1-SNAPSHOT.jar it-env.sh cors.disable cors.allowOrigins"
        }
      ]
    },
    {
      "id": "03-java-skill-transfer",
      "file": "03-java-skill-transfer.html",
      "group": "基础六篇",
      "title": "第三篇 · 经验迁移",
      "sub": "复用 / 调整 / 补充矩阵、八个心智模型、编码红线（第 8–10 章）",
      "ai": false,
      "chapters": [
        {
          "id": "ch8",
          "title": "8. 知识迁移矩阵：复用 / 调整 / 补充",
          "subs": [],
          "keywords": "@Configuration platform-bootstrap @Autowired @Qualifier @Primary agent-api @RestControllerAdvice tenantId code reason_code Jdbc*Repository ?::enum ?::uuid INSERT…RETURNING executeQuery authz_decision_log @Transactional TransactionalConnectionHolder app.tenant_id POISONED IN (SELECT … LIMIT … FOR UPDATE SKIP LOCKED) SseEmitter done @Async status varchar RunState requireTransitionTo() WAITING_APPROVAL run_step application-{profile}.yml @ConditionalOnProperty <testcase> skipped=0 usage_record cost_amount revisionCode=\"\" \"未指定\" ScopeTranslator clean verify clean Spring Boot Configuration Bean SpringBootApplication Autowired Qualifier Primary DataSource Controller MyBatis Jdbc INSERT RETURNING SELECT Transactional Repository COMMIT ROLLBACK MySQL FORCE BYPASSRLS LIMIT UPDATE SKIP LOCKED REST EventSource Authorization Async ThreadLocal FAILED RUNNING ConditionalOnProperty Mockito Micrometer Prometheus CRUD TOCTOU HTTP Server Maven cn.studykid.multiagent"
        },
        {
          "id": "ch9",
          "title": "9. 必须重新建立的八个心智模型",
          "subs": [
            {
              "id": "ch9-1",
              "title": "9.1 「数据库连接」不等于「请求上下文」"
            },
            {
              "id": "ch9-2",
              "title": "9.2 「消息发出去」不等于「任务会执行」"
            },
            {
              "id": "ch9-3",
              "title": "9.3 「配置改了就生效」在这里是双刃剑"
            },
            {
              "id": "ch9-4",
              "title": "9.4 「查不到数据」要分四种情况"
            },
            {
              "id": "ch9-5",
              "title": "9.5 「LLM 调用」是预算问题，不只是 HTTP 调用"
            },
            {
              "id": "ch9-6",
              "title": "9.6 「答案对」不等于「链路对」"
            },
            {
              "id": "ch9-7",
              "title": "9.7 「降级」在这个项目里通常是错误答案"
            },
            {
              "id": "ch9-8",
              "title": "9.8 「测试全绿」只是候选证据"
            }
          ],
          "keywords": "WHERE tenant_id = ? set_config('app.tenant_id', ?, true) execution_snapshot id@version approver_id <> requester_id count(*) app.tenant_id SELECT current_setting('app.tenant_id', true) hard-ceiling saved = (before − after) × price_input − summaryCost RequestAssemblyPort input.legacyRequest() ON CONFLICT (trace_id) DO UPDATE agent_trace_metric.attribution_correct agent_eval_sample RequestAssemblyException LocalPortGate 问题记录/07-测试基建与验证方法论.md @EnabledIfEnvironmentVariable skipped=0 ThreadLocal WHERE Repository Outbox Worker FORCE SELECT BYPASSRLS FROM HTTP USER CONFLICT UPDATE Trace EnabledIfEnvironmentVariable input.legacyRequest agent.context.enabled"
        },
        {
          "id": "ch10",
          "title": "10. 编码红线与高频坑清单",
          "subs": [
            {
              "id": "ch10-1",
              "title": "10.1 数据访问红线"
            },
            {
              "id": "ch10-2",
              "title": "10.2 装配、消息与业务语义红线"
            },
            {
              "id": "ch10-3",
              "title": "10.3 FORCE RLS 下的领取查询范式（Sev-0 级）"
            },
            {
              "id": "ch10-4",
              "title": "10.4 测试与脚本的高频坑"
            }
          ],
          "keywords": "set_config('app.tenant_id',?,true) app_agent ai_agent ?::enum ?::uuid INSERT…RETURNING executeQuery authz_decision_log run_step fencing_token agent_retention 03_platform_roles.sql ON CONFLICT DO UPDATE SchemaDriftIT POISONED @Autowired @Qualifier @Primary agent.run.execute scenario run.input_payload.scenario jsonb_typeof(...)='string' PLATFORM_JWT_PRIVATE_KEY JWT_PRIVATE_KEY AgentOverrides IN (SELECT … LIMIT … FOR UPDATE SKIP LOCKED) -- ❌ 错误范式：FORCE RLS 下 LIMIT 可能静默失效 MATERIALIZED Tests run: <testcase> tests mvn -q mvn … | tee log set -o pipefail @EnabledIfEnvironmentVariable skipped=0 subprocess IT_PG_URL source scripts/it-env.sh TestRestTemplate @SpringBootTest(RANDOM_PORT) @AutoConfigureRestTestClient RestTestClient @MockitoBean clean finally git diff --check doesNotMatch(regex) grep -aE Pattern.find() raw() stripCommentsKeepStrings *-rv-*.py scripts/*-counterproof.sh m3-context-policy-counterproof.sh g2-b-counterproof.sh m8a-context-state-counterproof.sh RV-* Repository NOBYPASSRLS INSERT RETURNING SELECT REVOKE CONFLICT UPDATE COMMIT ROLLBACK Autowired Bean Qualifier Primary DataSource NoUniqueBeanDefinition JDBC Hikari Flyway PENDING FORCE LIMIT SKIP LOCKED SubPlan PgQueueRepository JdbcRunClaimRepository RUNNING WHERE FROM WITH Tests"
        }
      ]
    },
    {
      "id": "04-runtime-flows",
      "file": "04-runtime-flows.html",
      "group": "基础六篇",
      "title": "第四篇 · 运行链路",
      "sub": "Run 生命周期、请求级 SSE 与上下文压缩、RAG 检索（第 11–13 章）",
      "ai": false,
      "chapters": [
        {
          "id": "ch11",
          "title": "11. 一次 Run 的完整生命周期",
          "subs": [
            {
              "id": "ch11-1",
              "title": "11.1 端到端流程"
            },
            {
              "id": "ch11-2",
              "title": "11.2 分阶段对照表"
            },
            {
              "id": "ch11-3",
              "title": "11.3 三处必须自己能说清的一致性边界"
            },
            {
              "id": "ch11-4",
              "title": "11.4 端到端验证清单（不要只看「Run 建出来了」）"
            }
          ],
          "keywords": "execution_snapshot outbox_event run_step usage_record RequestAssemblyException run_event OutboxPublisher OutboxPublisher.publishOne tenantId QUEUED → RUNNING → 终态 agent_version config_version authz_version effective_closure cost_amount HTTP RunPOST COMMIT OutboxRelay RabbitMQ RunExecutionLoopRequestAssemblyPort ModelGatewayToolInvocationGate SUCCEEDED FAILED CANCELLED TIMED_OUT Last Event Outbox RunRecoverySweeper RUNNING UNKNOWN RunCreationServicerun OutboxBatchoutbox_event ToolInvocationGate ContextRequestAssemblyAdapterrun_step Worker Agent QUEUED BEGIN www.w3.org message.tenantId log.error sender.send event.eventType outbox.markOutboxPublished event.outboxId clock.instant"
        },
        {
          "id": "ch12",
          "title": "12. 请求级 SSE 与上下文压缩链路",
          "subs": [
            {
              "id": "ch12-1",
              "title": "12.1 五条执行链路的定位"
            },
            {
              "id": "ch12-2",
              "title": "12.2 SSE v1.1 协议与两条链"
            },
            {
              "id": "ch12-3",
              "title": "12.3 上下文压缩管线与硬不变量"
            },
            {
              "id": "ch12-4",
              "title": "12.4 门禁、里程碑与当前状态"
            },
            {
              "id": "ch12-5",
              "title": "12.5 前端消费位置（便于联调定位）"
            }
          ],
          "keywords": "agent-routing RoutingEngine ModelAgentRouter begin() finish() context.checkpoint answer → usage → context.checkpoint? → done done SEND/STOP/RETRY GET /api/runs/{runId}/events SseEmitter Last-Event-ID agent-context CompactionPipeline ContextBudget BudgetAllocator RagHitQuota legacyRequest saved = (before − after) × price_input − summaryCost enabled=false strategy=OFF TRIM_ONLY docs/上下文压缩G2验收签核包_v1.0.md rag/docs/RAG-D09_阶段门禁与主平台接入计划.md rag/docs/RAG-D09 §0.3 fetch + response.body.getReader() IncrementalSseParser Agent TRUE Outbox RUNNING HMAC SEND STOP RETRY Last Event EventSource Authorization USER G3SSE Server GATE RunProgressPage POST GeneralChatPage AgentChatPage v1.1 client.ts response.body.getReader"
        },
        {
          "id": "ch13",
          "title": "13. RAG 链路：从 PDF 到可溯源答案",
          "subs": [
            {
              "id": "ch13-1",
              "title": "13.1 两个进程、两套连接、一条共享库"
            },
            {
              "id": "ch13-2",
              "title": "13.2 入库到检索的七步"
            },
            {
              "id": "ch13-3",
              "title": "13.3 教辅语料的四个特有约束"
            },
            {
              "id": "ch13-4",
              "title": "13.4 对外 HTTP 契约速查"
            },
            {
              "id": "ch13-5",
              "title": "13.5 跨服务契约：最容易「假绿」的地方"
            }
          ],
          "keywords": "@Scheduled FOR UPDATE SKIP LOCKED cn.studykid.rag.worker study_rag rag.embedding.* rag.ops.* rag-worker ingest-jobs POST /api/v1/management/sources /documents /documents/preview DUP_SOURCE / DUP_EDITION / DUP_CARRIER DoclingDocumentParserPort BlockRoleResolver IngestionPipeline rag.premise.enabled rag-premise chunk_policy OpenAiCompatibleEmbeddingPort ChunkIndexer IndexGenerationBuilder text_jieba tsvector RetrievalOrchestrator GroundedGenerationService hitIds generator=EXCERPT_ASSEMBLY disclaimer EditionScope ChunkRole TrustTier VERIFIED rag.retrieval.min-score BUILDING → ACTIVE → RETIRED scripts/e2e-index-generation.sh X-Tenant-Id X-Rag-Scope: TENANT|PLATFORM|ALL X-Tenant-Min-Trust-Tier rag_ops rag/rag-api/pom.xml rag/ SseEmitter text/event-stream produces GET /ingest-jobs[/{id}] HttpRagRetrievalGateway agent.rag.base-url /api/v1/retrieval revisionCode=\"\" \"未指定\" PromptGuard.wrapToolResult(\"rag\", …) scenario run.input_payload.scenario Scheduled UPDATE SKIP LOCKED Worker PENDING HTTP POST DUP_SOURCE DUP_EDITION DUP_CARRIER IngestJob MinerU MiddleJson BlockChunker EXCERPT_ASSEMBLY STRICT PREFERRED_WITH_FALLBACK EXPLAIN IN_REVIEW BUILDING ACTIVE RETIRED Tenant UUID Scope TENANT PLATFORM Trust Tier PATCH Server PromptGuard CROSS_EDITION rag.embedding rag.ops e2e-index-generation.sh"
        }
      ]
    },
    {
      "id": "05-troubleshooting",
      "file": "05-troubleshooting.html",
      "group": "基础六篇",
      "title": "第五篇 · 问题排查",
      "sub": "五段式方法论、六类诊断表、十例事故复盘、提交清单（第 14–17 章）",
      "ai": false,
      "chapters": [
        {
          "id": "ch14",
          "title": "14. 排查总方法论",
          "subs": [
            {
              "id": "ch14-1",
              "title": "14.1 五段式记录模板"
            },
            {
              "id": "ch14-2",
              "title": "14.2 核心概念：静默失真（Silent Distortion）"
            },
            {
              "id": "ch14-3",
              "title": "14.3 三级验收"
            },
            {
              "id": "ch14-4",
              "title": "14.4 反向验证（撤点 / 反证）"
            },
            {
              "id": "ch14-5",
              "title": "14.5 假绿识别清单"
            },
            {
              "id": "ch14-6",
              "title": "14.6 判绿口径与 CI 纪律"
            },
            {
              "id": "ch14-7",
              "title": "14.7 排查决策树（先看哪一层）"
            }
          ],
          "keywords": "问题记录/README.md current_setting('app.tenant_id') current_setting current_user mvn -q <testcase> skipped=0 docs/上下文压缩完整方案_设计与实施_v1.0.md §7.3 usage_record rag/docs/RAG-D08_评测体系与质量门禁.md # S1-M3 反证脚本：钉住 execution_snapshot.context_policy 双写入口不是假绿。 finally MAX_INJECT_HITS=16 git diff --check *-rv-*.py scripts/*-counterproof.sh doc-c*.py RV-* @EnabledIfEnvironmentVariable(IT_PG_URL) BUILD SUCCESS tests execution_snapshot data:event: delta ai_agent app_agent clean AUTHZ_MISMATCH=0 rlsDependent() Tests run: N, Failures: 0, Errors: 0, Skipped: 0 < 1000 */target/surefire-reports/TEST-*.xml */target/failsafe-reports/ docs/RLS到应用层权限迁移-问题方案与方法论归档.md README POST Silent Distortion COMMIT JOIN Server Worker Bean JdbcAgentManageRepository AgentRepositoryIT SIGTERM SIGINT OLD_MANAGE String ContextPolicyReader NEW_MANAGE MAX_INJECT_HITS EnabledIfEnvironmentVariable IT_PG_URL BUILD SUCCESS Agent HTTP BYPASSRLS AUTHZ_MISMATCH Dtest Test Tests TestCase MessagingCanaryWiringTest RealBrokerEndToEndIT Dfailsafe Failures Errors Skipped TEST FORCE EnableScheduling ROLLBACK app.tenant_id m3-context-policy-counterproof.sh counterproof.sh ci.yml ci-it.log www.w3.org"
        },
        {
          "id": "ch15",
          "title": "15. 分类诊断手册（现象 / 原因 / 定位 / 解决 / 验证）",
          "subs": [
            {
              "id": "ch15-1",
              "title": "15.1 启动与配置类"
            },
            {
              "id": "ch15-2",
              "title": "15.2 身份与权限类"
            },
            {
              "id": "ch15-3",
              "title": "15.3 数据库与 RLS 类"
            },
            {
              "id": "ch15-4",
              "title": "15.4 跨服务与契约类"
            },
            {
              "id": "ch15-5",
              "title": "15.5 消息、Worker 与运行链路类"
            },
            {
              "id": "ch15-6",
              "title": "15.6 测试、构建与产物类"
            }
          ],
          "keywords": "auth.jwt.private-key 未配置 env | grep -E 'SPRING_PROFILES|JWT_PRIVATE' SPRING_PROFILES_ACTIVE=dev gen-jwt-keys.sh curl /api/auth/jwks no main manifest attribute java -jar unzip -p <jar> META-INF/MANIFEST.MF -exec.jar scripts/dev-backend.sh Start-Class: …PlatformApplication ACCESS_REFUSED RABBITMQ_PASSWORD ./scripts/dev-rabbitmq.sh status dev-rabbitmq.sh verify RagStartupSelfCheck SELECT current_user, rolbypassrls … rag_app rag_ops RAG_OPS_ENABLED=false check-rag-ops-role.sh FAILED / CONFIG_UNAVAILABLE SELECT count(*) FROM execution_snapshot MiniMax-M2.5: grep -n 'MiniMax' application.yml ApplicationConfigKeyAlignmentTest \"[MiniMax-M2.5]\" dottedModelIdKeepsItsPricing RAG_EMBEDDING_BASE_URL curl <base>/embeddings RAG_EMBEDDING_DIMENSION RAG_EMBEDDING_DIMENSION / VECTOR_TYPE rag_chunk_embedding deploy/sql/rag/ Port 8080 already in use lsof -i:8080 /api/v1 /api vite.config.ts curl localhost:5173/api/auth/jwks env | grep SERVER_PORT gen-jwt-keys.sh --check invalid_credentials dev-seed.sh --diagnose <账号> --list ./scripts/check-tenant-registry.sh code /api/v1/retrieval grep closure ToolInvocationGate CapabilityClosureGuard AUTHZ_MISMATCH=0 scoped shadowEnabled AuthzShadowConfiguration AuthzShadowWiringTest rlsDependent() authz_decision_log AuthzDecisionLogSchemaTest insertWithReturningIsDenied SELECT current_database(), inet_server_port() docker ps agent-pg17 app.tenant_id SELECT current_setting('app.tenant_id', true) app_agent ai_agent SELECT SchemaDriftIT ON CONFLICT DO UPDATE IN (SELECT … LIMIT … FOR UPDATE SKIP LOCKED) WITH candidates AS MATERIALIZED (…) UPDATE … FROM candidates TransactionalConnectionHolder POISONED WHERE BEGIN…PREPARE…ROLLBACK att.tenant_id = op.tenant_id op.tenant_id = ?::uuid ALL_PREPARE_OK function max(uuid) does not exist MAX() rs.getString platform.review_task source scripts/it-env.sh --probe init-db.sh EXPECT_* context_policy revisionCode=\"\" \"未指定\" agent.rag.base-url POST localhost:8081/api/v1/retrieval set_config('app.tenant_id')"
        },
        {
          "id": "ch16",
          "title": "16. 真实事故复盘（十例）",
          "subs": [],
          "keywords": "execution_capability_snapshot FAILED / CONFIG_UNAVAILABLE config.flag.cap-dual-write if (!dualWriteSwitch.isEnabled()) return; git log -L log.debug 问题记录/01-配置与开关.md:8-52 cap_authz_version catch (RuntimeException) it-* CapabilitySnapshotWriteChainIT 问题记录/02-数据一致性与写链.md:8-47 ConfigEndpointIT private HikariDataSource adminPool minimumIdle maximumPoolSize pg_stat_activity private static @AfterAll 问题记录/07-测试基建与验证方法论.md:10-48 cap_agent_def @BeforeEach execution_snapshot it-cap-it-a/b apiPublishedAgentCanCreateRun POST /api/agents → /versions → /api/runs 问题记录/07-测试基建与验证方法论.md:117-138, 326-347 data:event: delta mvn -q [INFO] Tests run: SseEmitter curl -N <testcase> SseEventBuilder 问题记录/07-测试基建与验证方法论.md:142-157, 443-466 None TaintVerifier row.tenantId == 行租户 SELECT tenant_id::text rs.getString RuleBasedPolicyEngine JdbcResultQueryRepositoryShadowTest docs/RLS到应用层权限迁移-问题方案与方法论归档.md:37-49 tenant-slugs agent_dev claim scripts/dev-it-db.sh agent_it ItTenantProvisioner ItTenantProvisionerIT GovernanceQueryIT docs/RLS到应用层权限迁移-问题方案与方法论归档.md:134-148 ToolInvocationGate.invokeAllLegacy scoped CapabilityClosureGuard AuthzShadowConfiguration AuthzShadowWiringTest 审查文档/AI全流程审查报告_2026-10-31.md:18 AI全流程修复报告_2026-10-31.md:41-74 ingest-jobs knowledge-points queryTenantId() set_config rag_current_tenant() listIngestJobs ctx.requireTenant() rag_knowledge_point rag_ops tenant_id = rag_global_tenant_id() RagSessionScope.apply 问题记录/E2E端到端测试_2026-11-10/缺陷修复报告_2026-11-10.md:12-38 usage_record.input_tokens/output_tokens cost_amount maxLlmCalls att.tenant_id = op.tenant_id op.tenant_id = ?::uuid JoinTenantPredicateArchitectureTest snapshot.allowsLlmCall() BEGIN…PREPARE…ROLLBACK ALL_PREPARE_OK docs/RLS到应用层权限迁移-问题方案与方法论归档.md:83-96 审查文档/AI全流程审查报告_2026-10-31.md:20,63 FAILED CONFIG_UNAVAILABLE Value WARN Agent PLATFORM RuntimeException FATAL"
        },
        {
          "id": "ch17",
          "title": "17. 防御性实践与提交前清单",
          "subs": [
            {
              "id": "ch17-1",
              "title": "17.1 提交前 Checklist"
            },
            {
              "id": "ch17-2",
              "title": "17.2 变更类型 → 必做验证矩阵"
            },
            {
              "id": "ch17-3",
              "title": "17.3 工程规范（分散在文档中，无独立 CONTRIBUTING）"
            },
            {
              "id": "ch17-4",
              "title": "17.4 症状 → 章节速查"
            }
          ],
          "keywords": "?::type authz_decision_log <testcase> skipped=0 clean verify SchemaStructureTest SchemaDriftIT it-env.sh --probe npm run typecheck npm test feature/<模块>-<简述> main <type>(<scope>): <简述> type feat/fix/refactor/docs/test/build/chore -parameters mvn test Checklist JDBC Hikari Flyway ALTER EXPECT_ RETURNING COMMIT Repository JOIN TIERED Server CONTRIBUTING Review it-env.sh"
        }
      ]
    },
    {
      "id": "06-reference",
      "file": "06-reference.html",
      "group": "基础六篇",
      "title": "第六篇 · 速查与事实边界",
      "sub": "命令速查、文档索引、冲突与未确认清单、附录（第 18–20 章 + 附录 A/B/C）",
      "ai": false,
      "chapters": [
        {
          "id": "ch18",
          "title": "18. 常用命令速查",
          "subs": [
            {
              "id": "ch18-1",
              "title": "18.1 环境与依赖服务"
            },
            {
              "id": "ch18-2",
              "title": "18.2 构建与测试（双 reactor）"
            },
            {
              "id": "ch18-3",
              "title": "18.3 判绿与报告位置"
            },
            {
              "id": "ch18-4",
              "title": "18.4 数据库、IT 库与巡检"
            },
            {
              "id": "ch18-5",
              "title": "18.5 服务启停与联调"
            },
            {
              "id": "ch18-6",
              "title": "18.6 前端、灰度、反证与巡检"
            }
          ],
          "keywords": "# 完整构建（CI 等价，含 IT；在各自 reactor 根目录执行） IT_HOST=127.0.0.1 IT_PORT=5433 IT_ADMIN_USER=agent it-env.sh AI_AGENT_PASSWORD clean mvn … | tee set -o pipefail <testcase> tests Tests run: N, Failures: 0, Errors: 0, Skipped: 0 agent_it --recreate study_rag_it IT_RAG_* SEED_ACCOUNT_PASSWORD bash <文件> -rw-r--r-- compare-db-schema.sh c15-*-counterproof.sh c16-1-counterproof.sh Names Status Ports Dtest Test Tests TestCase MessagingCanaryWiringTest RealBrokerEndToEndIT Dfailsafe JAVA_HOME QuickstartDemoTest DskipTests StatisticsEndpointIT Dsurefire IT_HOST IT_PORT IT_ADMIN_USER Maven TEST Failures Errors Skipped IT_RAG_ Accept GRAFANA_PG_ RMQ_MIRROR VITE_DEV_API_TARGET dev-postgres.sh dev-rabbitmq.sh dev-env-up.sh docker.1ms.run gen-jwt-keys.sh pom.xml rag-it-env.sh ci-unit.log ci-it.log failsafe-summary.xml init-db.sh dev-it-db.sh init-it-db.sh dev-seed.sh dev-seed-platform-users.sh check-tenant-registry.sh check-rag-ops-role.sh check-cap-release-head.sh quality-schema-check.sh dev-backend.sh platform.env jwt.env exec.jar dev-rag.sh rag.env cap-gray-step.sh cap-gray-metrics-check.sh cap-enable-global.sh m3-context-policy-counterproof.sh g2-b-counterproof.sh g2-c-replay-counterproof.sh m8a-context-state-counterproof.sh doc-table-check.py behavior-verify.sh concurrency-verify.sh e2e-index-generation.sh counterproof.sh"
        },
        {
          "id": "ch19",
          "title": "19. 文档索引与交叉引用",
          "subs": [
            {
              "id": "ch19-1",
              "title": "19.1 架构与决策"
            },
            {
              "id": "ch19-2",
              "title": "19.2 配置、启动与工程基线（含滞后提示）"
            },
            {
              "id": "ch19-3",
              "title": "19.3 门禁、验收与复盘"
            },
            {
              "id": "ch19-4",
              "title": "19.4 RAG 文档（RAG-D00 ~ D26）"
            },
            {
              "id": "ch19-5",
              "title": "19.5 前端与其它"
            },
            {
              "id": "ch19-6",
              "title": "19.6 v2.3 新增：质量 / 成本 / 可观测证据源"
            },
            {
              "id": "ch19-7",
              "title": "19.7 v2.4 新增：记忆 / 网关 / 流式 / MCP 运行时证据源"
            },
            {
              "id": "ch19-8",
              "title": "19.8 v2.5 新增：测试 / 向量运维 / 增强路线证据源"
            },
            {
              "id": "ch19-9",
              "title": "19.9 v2.6 新增：执行安全与调度一致性证据源"
            }
          ],
          "keywords": "<testcase> rag/docs/ search_knowledge_base RAG-D07 RAG-D08 RAG-D05 POST /v1/convert/file ERD1 Boot Queue GATE Outbox Tenant Control Plane README Flyway ModelResolver Scope Nginx Prometheus POST developer-onboarding.md platform.env.example rag.env.example jwt.env.example ci.yml context.checkpoint v0.1-draft vite.config.ts agent-quality.json EVAL_JUDGE_COST usage_record agent_trace_metric attribution_correct tenant_token_quota BudgetPort ModelAttributionJudge AuditTamperProofGuard CapEnforcementMetrics"
        },
        {
          "id": "ch20",
          "title": "20. 已确认冲突与「未确认」清单",
          "subs": [
            {
              "id": "ch20-1",
              "title": "20.1 明确的文档 / 产物冲突"
            },
            {
              "id": "ch20-2",
              "title": "20.2 证据空白 / 需现场核对"
            },
            {
              "id": "ch20-3",
              "title": "20.3 本次勘察的方法与边界"
            },
            {
              "id": "ch20-4",
              "title": "20.4 v2.3 新增：质量 / 成本 / 可观测的未落地与冲突"
            },
            {
              "id": "ch20-5",
              "title": "20.5 v2.4 新增：运行时四册涉及的未落地与冲突"
            },
            {
              "id": "ch20-6",
              "title": "20.6 v2.5 新增：测试 / 向量 / 路线的未落地与口径修正"
            },
            {
              "id": "ch20-7",
              "title": "20.7 v2.6 新增：执行安全与调度的未落地与装配风险"
            }
          ],
          "keywords": "docs/本地启动指南.md …-SNAPSHOT.jar dev-backend.sh …-SNAPSHOT-exec.jar deploy/sql/README.md init-db.sh EXPECT_TABLES=71 it-env.sh --probe platform.review_task 01_platform_ddl.sql 01_rag_ddl.sql developer-onboarding.md rlsDependent() rag-api/pom.xml rag/ RAG-D05 JdbcRetrievalRepository platform/pom.xml *IT.java RAG-D09 /actuator/health /api/auth/jwks <testcase> mvn dependency:tree V1__baseline_schema.sql *-rv-*.py *-counterproof.sh RV-* rag-pg-s2 rag-it-env.sh 06/08/09/10/11/13_*_alter.sql export-rls-policies.sh rag_import_pdf.py dev-seed-rag-corpus.sql README.md Makefile docker-compose.yml CONTRIBUTING.md @ConditionalOnProperty assignment-copilot HEAD 306f114 AuditTamperProofGuard AgentMetrics EVAL_JUDGE_COST tenant_token_quota Grafana rule_files AgentChatOrchestrator AgentChatConfiguration platform.env rag.env jwt.env SNAPSHOT MANIFEST Main Class Flyway README EXPECT_TABLES CREATE TABLE71 TABLE ScopeFilter SSErag SseEmitter HTTP Framework Micrometer Jackson Lombok Boot CONTRIBUTING Spring ConditionalOnProperty Agent TODO Maven Docker HTML HEAD POLICY exec.jar init-db.sh it-env.sh pom.xml counterproof.sh v2.4 A13 A14 A15 A16 b770141 W-PERM-P4 governance01 滞后 MCP 已落地 Skill 运行时 student_memory embedding 未建 fallback 链 Last-Event-ID sequenceNo stdio oauth v2.5 A17 A18 A19 systemPromptRef 引用式提示词库 明确拒绝 inlineInstructions 真实供应商回归 HNSW 刻意不存在 RETIRED 清理 未确认 sequenceNo 测试数字不写死 472 90 113 40 v2.6 A20 A21 ToolInvocationGate ToolOperationState UNKNOWN 八态 可为空 装配风险 closureGuard runBudget approvalRequestSettings 审批权威 CancelGuard 租约续期 长任务 RabbitMQ CI 覆盖 reconcile 对账"
        },
        {
          "id": "appendix",
          "title": "附. 附录：术语表与配置键索引",
          "subs": [
            {
              "id": "appendix-a",
              "title": "附录 A · 术语表"
            },
            {
              "id": "appendix-b",
              "title": "附录 B · 配置键索引（按前缀检索）"
            },
            {
              "id": "appendix-c",
              "title": "附录 C · 知识点与章节对照"
            }
          ],
          "keywords": "Agent FORCE RLSPG BYPASSRLS TenantWithShared TenantOnly Unrestricted None2 Skill Tool Worker ACTIVE RETIRED SPRING_DATASOURCE_ SPRING_PROFILES_ACTIVE SERVER_PORTplatform RABBITMQ_ AGENT_PLATFORM_OPS_ AGENT_MODEL_MINIMAX_ AGENT_CONTEXT_ HMAC TTL5 TTL2 JWT_PRIVATE_KEY PLATFORM_JWT_PRIVATE_KEYjwt RAG_TENANT PLATFORM_JWT_PUBLIC_KEYjwt RAG_PG_ RAG_OPS_ RAG_EMBEDDING_ RAG_LOCAL_PORTS_ENABLEDrag RAG_RETRIEVAL_MIN_SCORE MIN_TRUST_TIERyml RAG_DOCLING_ RAG_PREMISE_ IT_PG_URL IT_RAG_ AI_AGENT_PASSWORDit VITE_DEV_API_TARGET VITE_DEV_RAG_ MANIFEST SKIP LOCKED BUILDING EXCERPT_ASSEMBLY SERVER_PORT PLATFORM_JWT_PRIVATE_KEY PLATFORM_JWT_PUBLIC_KEY RAG_LOCAL_PORTS_ENABLED MIN_TRUST_TIER AI_AGENT_PASSWORD app.tenant_id context.checkpoint v1.1 platform.env agent.messaging agent.mq application.yml agent.worker agent.quality agent.authz agent.recovery agent.run pricing.models config.flag auth.jwt auth.platform-jwt platform.security jwt.env rag.env rag.indexer rag.ingest it-env.sh rag-it-env.sh"
        }
      ]
    },
    {
      "id": "ai-01-fundamentals",
      "file": "ai-01-fundamentals.html",
      "group": "AI 专题",
      "title": "A1 · AI 知识点精讲",
      "sub": "LLM / Token / 上下文窗口 / Embedding / RRF / 幻觉 / 评测 / 成本，全部落到本项目实体",
      "ai": true,
      "chapters": [
        {
          "id": "ai1",
          "title": "A1. AI 知识点精讲：从概念到本仓库的落点",
          "subs": [
            {
              "id": "ai1-1",
              "title": "A1.1 为什么 AI 系统不能按 CRUD 的思路验收"
            },
            {
              "id": "ai1-2",
              "title": "A1.2 一次 LLM 调用在本项目里长什么样"
            },
            {
              "id": "ai1-3",
              "title": "A1.3 Token、上下文窗口与「估算误差」"
            },
            {
              "id": "ai1-4",
              "title": "A1.4 Prompt 的结构、四种角色与「稳定前缀」"
            },
            {
              "id": "ai1-5",
              "title": "A1.5 工具调用（Tool Calling）：不是「让框架自动执行」"
            },
            {
              "id": "ai1-6",
              "title": "A1.6 Embedding、向量列与「维度上限」这个坑"
            },
            {
              "id": "ai1-7",
              "title": "A1.7 混合检索、RRF 融合与分数门禁"
            },
            {
              "id": "ai1-8",
              "title": "A1.8 幻觉、接地与「不可信数据」原则"
            },
            {
              "id": "ai1-9",
              "title": "A1.9 评测体系：L0–L3、样本量与 LLM-as-judge"
            },
            {
              "id": "ai1-10",
              "title": "A1.10 成本模型：业务账、评估账与压缩净省"
            },
            {
              "id": "ai1-11",
              "title": "A1.11 概念 → 本项目落点 → 证据 速查表"
            }
          ],
          "keywords": "静默失真 docs/上下文压缩完整方案_设计与实施_v1.0.md §0.2 docs/问题记录/README.md §「静默失败分类 M1–M4」 审查文档/AI全流程审查报告_2026-10-31.md RequestAssemblyPort.assemble(input) ModelRequest legacyRequest RunExecutionLoop.reserveModelCost():1650-1691 estimatedInput × INPUT_TOKEN_ESTIMATE_SAFETY_FACTOR MiniMaxModelGateway OpenAiCompatibleModelGateway POST /chat/completions FakeModelGateway settleModelCost():1707-1739 response.usage() ModelPricing.cost() usage_record releaseModelCost() modelCallId() runId + 领取代次 + 调用序号 usage_record.operation_id public.usage_record FORCE ROW LEVEL SECURITY idx_usage_operation_once deploy/sql/platform/01_platform_ddl.sql:3024-3038, 4529 platform/agent-core/.../config/ModelPricing.java:28-74 docs/租户级Token计费系统设计方案_v1.0.md §0.1 model_catalog model_pricing agent.pricing.models context_window_tokens 32768 07_model_catalog_context_upgrade.sql max_input_tokens 28672 ContextBudget.hardLimit() hard-ceiling-tokens 128000 AGENT_CONTEXT_HARD_CEILING_TOKENS HeuristicTokenCounter TokenEstimationCalibrator prompt_tokens min-samples=30 alpha=0.20 0.80–2.00 tokenizer_id / supports_prompt_caching = false estimate ContextLayout.countRendered docs/上下文压缩完整方案_设计与实施_v1.0.md §0.2 / §1.2 / §2.2 / §5.2 PromptGuard systemBase / toolSchemas / firstUserTurn / currentInput ragCap / memoryCap / summaryCap agent.context.enabled=false RequestAssemblyPort input.legacyRequest() RequestAssemblyResult.java:17-20, 79-81 docs/上下文压缩完整方案_设计与实施_v1.0.md §5.1 / §5.2 ADR-013 D27 / D38 / D41 ADR-011 §3.3 ToolCallback ToolCallingAdvisor CapExecutionFlow EffectiveClosure ExecutionAdmissionChecker ToolInvocationGate fencing_token PromptGuard.wrapToolResult(toolName, payload) <tool_result trust=\"untrusted\"> @Retryable record_weakness pom.xml docs/P0/P1/ADR-011_技术基线_Java21_SpringBoot41.md §3.3 / §2.2 docs/Agent设计总览.md §4 docs/P0/P1/ADR-001_主MQ选型_RabbitMQ.md §5 pgvector ≥ 0.8.7 pg_extension WHERE extname='vector' postgres:17 CREATE EXTENSION vector pgvector/pgvector:pg17 BGE-M3 / 1024 维 embedding=bge-m3(1024) qwen3-embedding:8b / 4096 维 /embeddings RAG_EMBEDDING_DIMENSION=4096 RAG_EMBEDDING_VECTOR_TYPE=halfvec halfvec(4096) deploy/sql/rag/04_migrate_embedding_halfvec_4096.sql VectorColumn.MAX_INDEXABLE_DIMENSIONS_HALFVEC = 4000 vector rag-worker"
        }
      ]
    },
    {
      "id": "ai-02-decisions",
      "file": "ai-02-decisions.html",
      "group": "AI 专题",
      "title": "A2 · 技术选型决策",
      "sub": "ADR 全清单、备选方案与取舍理由、状态与未定案清单",
      "ai": true,
      "chapters": [
        {
          "id": "ai2",
          "title": "A2. 技术选型决策：备选、取舍理由、状态与未定案",
          "subs": [
            {
              "id": "a2-1",
              "title": "A2.1 先看清决策材料的分布与「编号真相」"
            },
            {
              "id": "a2-2",
              "title": "A2.2 决策记录模板，以及「怎么判断一条决策还有效」"
            },
            {
              "id": "a2-3",
              "title": "A2.3 ADR-011：Java 21 + Spring Boot 4.1.1"
            },
            {
              "id": "a2-4",
              "title": "A2.4 ADR-001 / DEC-12：主 MQ 为什么是 RabbitMQ 4.3.6"
            },
            {
              "id": "a2-5",
              "title": "A2.5 P4-D17：为什么又新增一条 database 消息通道"
            },
            {
              "id": "a2-6",
              "title": "A2.6 PG 事实源 + Outbox + Worker：没有 ADR，但有红线"
            },
            {
              "id": "a2-7",
              "title": "A2.7 ADR-013：上下文压缩统一入口与全窗口预算"
            },
            {
              "id": "a2-8",
              "title": "A2.8 RAG-D00 / D02：向量库、嵌入模型与规模边界"
            },
            {
              "id": "a2-9",
              "title": "A2.9 模型统一配置与网关：单一事实源，但有一条 fail-soft 尾巴"
            },
            {
              "id": "a2-10",
              "title": "A2.10 Agent 框架：自研 vs 主流 harness，结论是「不推翻重做」"
            },
            {
              "id": "a2-11",
              "title": "A2.11 「看起来像选型、其实没定」清单"
            },
            {
              "id": "a2-12",
              "title": "A2.12 决策状态总表，以及「归因错误」的示范"
            }
          ],
          "keywords": "docs/Java SaaS Agent 平台：规划与技术架构设计方案 v3.1.md P0-D00 docs/模型统一配置架构设计.md:149 docs/上下文压缩开发计划_v1.0.md:71 true false agent.context.enabled application.yml:232 platform.env.example:43 max-state-chars 8000 RRF_K=60 agent.messaging.mode docs/P0/P1/ADR-001 application.yml:232, 250, 448 问题记录/11-文档与代码漂移.md DOC-10 Spring Boot 3.x @Retryable AgentCorePurityTest StateMachineTest ConfigFailClosedTest agent.run.dispatch agent.run.execute agent.run.dead x-delivery-limit=3 runId/tenantId/attempt/fencingToken/enqueuedAt agent.run.reconcile ToolOperationReconciliationService P4-R022_RabbitMQ停维残余风险安全复核登记.md YamlPropertiesFactoryBean delivery-limit: 3 record_weakness ApplicationConfigTest @RabbitListener rabbitmq database agent_message_queue x-delivery-limit=3 ⇄ attempts agent.run.dead ⇄ agent_message_dead_letter MessageSender / MessageHandler / QueueDepthProbe TenantRoutingMessageSender MessagingSwitchGuard outbox_event application.yml:448 mode: \"${AGENT_MESSAGING_MODE:database}\" EventRoutedMessageHandler eventType \"agent.trace.collect\" TraceCollectMessageHandler REJECTED docs/P0/P4/P4-D17_消息通道可切换.md §1–§7 docs/项目进度台账.md:2302 P0-D03_MVP范围与非目标清单.md §4 RUN_REQUESTED fencing_token UNKNOWN deployment.pilot.max-tenants=1 tenant_id publisher-confirm-type: correlated OutboxMessage P2-D02 §6.2 claimSpecificRun ContextBudgetTrimmer ContextAssembler agent-context enabled=false agent_eval_sample EmbeddingPort context.estimation_error_ratio RoundSplitter agent-ports firstUserTurn targetRatio × window StructuredState seq > upToSeq VERBATIM_VIOLATION onContextCompacted state contextState RagHitQuota RequestAssemblyPort ModelRequest computed_at execution_snapshot.context_policy answer → usage → context.checkpoint? → done FOR UPDATE SKIP LOCKED @Scheduled zhparser pg_jieba tsvector('simple',…)"
        }
      ]
    },
    {
      "id": "ai-03-hard-problems",
      "file": "ai-03-hard-problems.html",
      "group": "AI 专题",
      "title": "A3 · 特殊 AI 问题处理",
      "sub": "上下文压缩、注入防护、契约失配、成本污染、静默失真与反向验证",
      "ai": true,
      "chapters": [
        {
          "id": "ai3",
          "title": "A3. 特殊 AI 问题处理：现象、根因、定位、修复、验证",
          "subs": [
            {
              "id": "a3-1",
              "title": "A3.1 总纲：AI 系统的主要失效形态是「不报错」"
            },
            {
              "id": "a3-2",
              "title": "A3.2 上下文窗口与压缩：本项目最复杂的 AI 问题"
            },
            {
              "id": "a3-3",
              "title": "A3.3 提示词注入：压缩态围栏上的一个真实洞"
            },
            {
              "id": "a3-4",
              "title": "A3.4 跨进程 AI 服务契约失配：revisionCode=\"\""
            },
            {
              "id": "a3-5",
              "title": "A3.5 成本与评测的账目污染：三个「不报错但数字错」"
            },
            {
              "id": "a3-6",
              "title": "A3.6 检索质量的静默退化：量纲、门禁与版本降权"
            },
            {
              "id": "a3-7",
              "title": "A3.7 「测试全绿」在 AI 系统里为什么不够：三级验收与反向验证"
            },
            {
              "id": "a3-8",
              "title": "A3.8 静默失真案例矩阵（十例，五段式）"
            },
            {
              "id": "a3-9",
              "title": "A3.9 AI 链路排查清单（与传统 Java 问题的差异对照）"
            },
            {
              "id": "a3-10",
              "title": "A3.10 本册的未确认与证据空白"
            }
          ],
          "keywords": "@EnableScheduling docs/问题记录/README.md §「静默失败分类 M1–M4」 docs/上下文压缩完整方案_设计与实施_v1.0.md §7.3 docs/Java后端转型培训手册.html §14.2 contextWindow prompt_tokens ContextAssembler generationParams ConfigValidationGate ModelRequest ContextBudgetTrimmer RunExecutionLoop run_message.content jsonb AgentChatOrchestrator ConsoleChatController WorkerChildAgentRunner upToSeq=-1 ModelAgentRouter.askModel() ROUTER_MAX_TOKENS=192 RagHitQuota chunkId score desc → locator → chunkId → text maxHitTokens chunkId + locator afterTokens ≤ tokenBudget beforeTokens MAX_INJECT_HITS=8 / MAX_HIT_TEXT=1200 contextState policyVersion max-state-chars upToTurn > 当前 history 长度 domain=\"l2cs1\" AGENT_CONTEXT_STATE_HMAC_KEY RequestAssemblyException RUN_FAILED ChildAgentResult.failed(reasonCode, \"\") get_student_profile maxInputTokens=16000 triggerRatio=0.70 targetRatio=0.40 recentTurns=6 上下文压缩G2验收签核包_v1.0.md §3.3 实施记录_v1.0.md §11.2 clean verify 07_model_catalog_context_upgrade.sql 07_execution_snapshot_context_policy.sql agent_it agent_dev context_compaction ContextCheckpointPort VerbatimSpanExtractor docs/P0/P1/ADR-013 docs/上下文压缩完整方案_设计与实施_v1.0.md docs/上下文压缩SSE协议v1.1变更说明.md docs/上下文压缩G2验收签核包_v1.0.md platform/agent-context/** platform/agent-ports/.../ports/contextwiring/** PromptGuard.sanitize() user_input tool_result memory_context <compacted_context trust=\"untrusted\"> sanitize() <compacted_context [compacted_context [/compacted_context] PromptGuardCompactedContextTest 5/5 </compacted_context> 护栏设计综合评估文档1.md:139 wrapToolResult AgentChatOrchestrator.java:526, 595-598 revisionCode=\"\" EditionCoordinate EditionDto.revisionCode @NotBlank edition.revisionCode = \"\" RagUnavailableException RAG_UNAVAILABLE Skill(\"rag\",…,\"error\") null edition.put(\"revisionCode\", \"未指定\") edition.get(\"revisionCode\").asString() == \"未指定\" describeDefaultEdition() :235-243 data:event: delta 问题记录/07-测试基建与验证方法论.md TST-20 MiniMax-M2.5: MiniMax-M2 ModelPricingPolicy.pricingOf() settleModelCost \"[MiniMax-M2.5]\":"
        }
      ]
    },
    {
      "id": "ai-04-agent-orchestration",
      "file": "ai-04-agent-orchestration.html",
      "group": "AI 专题",
      "title": "A4 · 多级 Agent 编排与路由",
      "sub": "委派状态机、RouteAgent 规则转发、Skill/Tool/MCP 统一配置、生命周期与安全验收",
      "ai": true,
      "chapters": [
        {
          "id": "a4",
          "title": "A4. 多级 Agent 编排与路由：从委派状态机到规则转发引擎",
          "subs": [
            {
              "id": "a4-1",
              "title": "A4.1 先分清三层概念：编排、路由、能力配置"
            },
            {
              "id": "a4-2",
              "title": "A4.2 委派调用状态机：ADR-012 冻结的是「现状」，不是新设计"
            },
            {
              "id": "a4-3",
              "title": "A4.3 RouteAgent 规则转发：纯函数引擎 + 发布期静态守卫"
            },
            {
              "id": "a4-4",
              "title": "A4.4 Skill / Tool / MCP 统一配置：依赖方向与四者区别"
            },
            {
              "id": "a4-5",
              "title": "A4.5 配置数据模型与生命周期：从六张表到三步发布"
            },
            {
              "id": "a4-6",
              "title": "A4.6 安全测试与验收矩阵：八个端到端场景 + 多级 Agent 补充用例"
            },
            {
              "id": "a4-7",
              "title": "A4.7 评审决议项：五条前置决策，逐条「已决策 ≠ 已落地」"
            },
            {
              "id": "a4-8",
              "title": "A4.8 代码落地对照表：哪些找得到，哪些只有文档"
            },
            {
              "id": "a4-9",
              "title": "A4.9 本册的未闭环缺口与证据空白"
            }
          ],
          "keywords": "ADR-012 委派调用状态机 RouteAgent 规则转发 agent-routing RoutingEngine PredicateEvaluator PublishGuards RULES_ONLY RULE_FIRST SEMANTIC V1-V10 纯函数 DNF 4096 重放不变式 RoutingReplayIT SkillDefinition ToolDefinition McpServerDef AgentDefinition ToolInvocationGate AgentOverrides enabledSkills configFingerprint SkillDescription cap_tool_def cap_agent_def cap_config_release childAgentRefs assignment-copilot math-tutor 三步发布 DRAFT VALIDATED PUBLISHED DEPRECATED IS NOT DISTINCT FROM S1-S8 MA-T24a T24i 1212 例 mvn verify 已决策≠已落地 00_修订说明与评审决议项 06_配置数据模型 08_安全测试与验收矩阵 10_RouteAgent规则转发设计 11_RouteAgent请求示例模拟"
        }
      ]
    },
    {
      "id": "ai-05-config-governance",
      "file": "ai-05-config-governance.html",
      "group": "AI 专题",
      "title": "A5 · 配置治理与权限体系",
      "sub": "RLS→应用层迁移、rlsDependent 判绿、分层授权、配置生命周期、本地判定性能基准",
      "ai": true,
      "chapters": [
        {
          "id": "a5",
          "title": "A5. 配置治理与权限体系：从 RLS 迁移到配置生命周期",
          "subs": [
            {
              "id": "a5-1",
              "title": "A5.1 为什么要迁：八个 PG 耦合点与迁移实质"
            },
            {
              "id": "a5-2",
              "title": "A5.2 目标态 ScopeFilter：一个 sealed 接口，四种作用域"
            },
            {
              "id": "a5-3",
              "title": "A5.3 影子权限与真正的判绿口径：rlsDependent=0"
            },
            {
              "id": "a5-4",
              "title": "A5.4 迁移进度：文档停在 5 仓，代码已接 39 仓"
            },
            {
              "id": "a5-5",
              "title": "A5.5 分层授权：四字段决策模型与「ALLOW ≠ 成功」"
            },
            {
              "id": "a5-6",
              "title": "A5.6 授权决策日志：为什么「禁 RETURNING」是一条铁律"
            },
            {
              "id": "a5-7",
              "title": "A5.7 配置数据模型、生命周期与三步发布"
            },
            {
              "id": "a5-8",
              "title": "A5.8 FORCE RLS 的坑：静默 0 行与 owner 也受约束"
            },
            {
              "id": "a5-9",
              "title": "A5.9 本地判定性能基准：p99 < 1ms 缺前提就没意义"
            },
            {
              "id": "a5-10",
              "title": "A5.10 V1 冻结判定：是「冻结候选」，不是「已冻结」"
            },
            {
              "id": "a5-11",
              "title": "A5.11 迁移方法论：十条总体 + 五条增量"
            }
          ],
          "keywords": "RLS 应用层权限迁移 ScopeFilter TenantWithShared TenantOnly Unrestricted None ScopeTranslator SqlScopeTranslator AuthzShadowHooks AuthzShadowReporter AUTHZ_MISMATCH rlsDependent AuthzSamplingPolicy AuthzShadowLiveIT ScopeBypassArchitectureTest 39 仓 D1 D13 FORCE RLS app.tenant_id NOBYPASSRLS app_agent 禁 RETURNING authz_decision_log AuthzDecisionLogGuard SelfApprovalException IS NOT DISTINCT FROM configFingerprint authzVersion cap_config_release p99 5489 闭包含数 LocalDecisionBenchmark V1 冻结候选 cap-authorization-enforced 十条方法论 影子对照 归档 M11 M12 M13 M14 M15 05_分层授权决策规范 06_配置数据模型 12_RLS迁移方案 13_迁移实施计划 P6R3_本地判定性能基准 P6_V1冻结判定报告"
        }
      ]
    },
    {
      "id": "ai-06-incident-library",
      "file": "ai-06-incident-library.html",
      "group": "AI 专题",
      "title": "A6 · 真实事故案例库",
      "sub": "32 条新案例，五段式可回溯（20 条强证据完整 + 12 条部分证据）",
      "ai": true,
      "chapters": [
        {
          "id": "a6",
          "title": "A6. 真实事故案例库：32 条新案例，五段式可回溯",
          "subs": [
            {
              "id": "a6-1",
              "title": "A6.1 A 组 · 静默失真（静默 0 行 / 静默回滚 / 数字写歪）"
            },
            {
              "id": "a6-2",
              "title": "A6.2 B 组 · 假绿与假红（含「从没测过」的假绿）"
            },
            {
              "id": "a6-3",
              "title": "A6.3 C 组 · 跨进程契约失配与 API 语义"
            },
            {
              "id": "a6-4",
              "title": "A6.4 D 组 · RLS / 权限 / 功能空转"
            },
            {
              "id": "a6-5",
              "title": "A6.5 E 组 · 构建产物与依赖"
            },
            {
              "id": "a6-6",
              "title": "A6.6 F 组 · 事务 / Outbox / MQ / 并发与租约"
            },
            {
              "id": "a6-7",
              "title": "A6.7 G 组 · RAG 检索质量与成本计费"
            },
            {
              "id": "a6-8",
              "title": "A6.8 部分证据案例（12 条：有现象/根因，缺验证或方案未落地）"
            },
            {
              "id": "a6-9",
              "title": "A6.9 未找到证据 / 无法确认，以及证据源文件索引"
            }
          ],
          "keywords": "事故案例 五段式 现象 可能原因 定位方式 解决方案 验证步骤 A1 A2 A3 A4 A5 B1 B2 B3 B4 B5 B6 B7 B8 C1 C2 C3 C4 C5 C6 D1 D2 E1 E2 E3 E4 E5 F1 F2 F3 F4 G1 G2 revisionCode 429 ASYNC DispatcherType.ASYNC execution-ticket TENANT_NOT_FOUND pg_has_role search_path %L information_schema ON_ERROR_STOP NoSuchMethodError -am EXPLANATION REVOKE DELETE UUID.nameUUIDFromBytes halfvec 4096 1024 HNSW 4000 maxLlmCalls allowsLlmCall appendix_pieces text_jieba 287 片段 签名 密钥指纹 0deff367 340be452 反证 39/0 102/0 12 项 0 违规 问题记录 审查文档 RAG检索与端到端实测问题记录 端到端实测 2026-11-10 修复报告 评测报告"
        }
      ]
    },
    {
      "id": "ai-07-corpus-engineering",
      "file": "ai-07-corpus-engineering.html",
      "group": "AI 专题",
      "title": "A7 · RAG 语料工程化与数据流水线",
      "sub": "从原始 PDF 到可检索向量：十段工程链、两套解析后端、V1.0→V1.0.2 与 10 条文档失真",
      "chapters": [
        {
          "id": "a7",
          "title": "A7. RAG 语料工程化与数据流水线：从原始 PDF 到可检索向量",
          "subs": [
            {
              "id": "a7-1",
              "title": "A7.1 七段流水线总览与「整册失败」纪律"
            },
            {
              "id": "a7-2",
              "title": "A7.2 切分方案调研：为什么是「MinerU 解析 + 标题层级切分」"
            },
            {
              "id": "a7-3",
              "title": "A7.3 rag-premise 模块：把解析与切分做成可替换的两段工序"
            },
            {
              "id": "a7-4",
              "title": "A7.4 线上化方案演进：V1.0 → V1.0.1 → 评审反转 → V1.0.2"
            },
            {
              "id": "a7-5",
              "title": "A7.5 录入交互与语料管理 UI：从「对象中心」到「任务中心」"
            },
            {
              "id": "a7-6",
              "title": "A7.6 入库操作手册：面向运维的实际步骤与坑"
            },
            {
              "id": "a7-7",
              "title": "A7.7 检索准入：四轴闸门、六个 WHERE 条件与两个真实缺陷"
            },
            {
              "id": "a7-8",
              "title": "A7.8 数据模型：三层来源、4096 维、五轴版本与 8 值状态机"
            },
            {
              "id": "a7-9",
              "title": "A7.9 十条文档失真清单：以代码 / 真库为准绳"
            },
            {
              "id": "a7-10",
              "title": "A7.10 未找到证据 / 无法确认"
            }
          ],
          "keywords": "RAG 语料工程 数据流水线 PDF 切分 MinerU Docling heading hybrid rag-premise 162页 189秒 3099块 2042片段 972 20页每段 PaddleStructureV3 chunkId contentHash ChunkPolicy 512 0.15 线上化 V1.0 V1.0.1 V1.0.2 评审 P1 反转 迁移脚本 05 06 07 08 09 10 11 12 13 D25 真库演练 交互优化 1258断言 四域13页 dry-run 入库操作手册 401 400 200MB reindex ADMISSIBLE_TEMPLATE 四轴闸门 版权 PENDING trust_tier 枚举序 halfvec 4096 4000 ANN 五轴版本 8值状态机 10类角色 离线脚本 rag_import_pdf 文档失真"
        }
      ],
      "ai": true
    },
    {
      "id": "ai-08-data-governance",
      "file": "ai-08-data-governance.html",
      "group": "AI 专题",
      "title": "A8 · 数据源权威性与合规治理",
      "sub": "TrustTier 五级、检索四轴闸门、租户三档作用域与 rag_ops BYPASSRLS 通道",
      "chapters": [
        {
          "id": "a8",
          "title": "A8. 数据源权威性与合规治理：从信任等级到租户三档作用域",
          "subs": [
            {
              "id": "a8-1",
              "title": "A8.1 信任等级：TrustTier 五级与冲突仲裁语义"
            },
            {
              "id": "a8-2",
              "title": "A8.2 版本坐标与生效机制：五轴 + 时间窗"
            },
            {
              "id": "a8-3",
              "title": "A8.3 检索期四轴硬过滤：ADMISSIBLE_TEMPLATE 单一真源"
            },
            {
              "id": "a8-4",
              "title": "A8.4 入库期闸门：抽检、载体不高于来源、职责分离"
            },
            {
              "id": "a8-5",
              "title": "A8.5 合规治理：版权分级、审计追加写、保留与删除边界"
            },
            {
              "id": "a8-6",
              "title": "A8.6 租户三档作用域：TENANT / PLATFORM / ALL"
            },
            {
              "id": "a8-7",
              "title": "A8.7 rag_ops BYPASSRLS 通道：边界三道与启动自检"
            },
            {
              "id": "a8-8",
              "title": "A8.8 两份「未实现」提案：平台目录 Gate 与 admin-console 松绑"
            },
            {
              "id": "a8-9",
              "title": "A8.9 代码落地对照总表与未找到证据"
            }
          ],
          "keywords": "D07 权威性 合规 治理 TrustTier OFFICIAL_PUBLISHER standaloneSupportAllowed SourceVerification VerificationStatus evidenceRef rag_source trust_tier 三轴 五轴版本 rag_edition effective_from 出版社 出版机构 ADMISSIBLE_TEMPLATE G-0 G-3 G-4 staleness 版权 license_status LICENSED FAIR_USE_QUOTATION PENDING InspectionGuard DocumentVerification SegregationOfDuties 职责分离 rag_access_audit append-only 42501 审计 保留 删除 RetentionPurgeService erasure_certificate ON DELETE CASCADE RESTRICTED RagScope GLOBAL_TENANT_ID TENANT PLATFORM ALL scope_ck RagManagementContext X-Rag-Scope 501 rag_ops BYPASSRLS 04_rag_ops_role GuardedRagOpsDataSource RagStartupSelfCheck P13 反证 PlatformRagGovernanceTicketService 票据 目录 Gate HI-1 CATALOG_ admin-console 松绑 executionTenantId 血缘"
        }
      ],
      "ai": true
    },
    {
      "id": "ai-09-quality-assurance",
      "file": "ai-09-quality-assurance.html",
      "group": "AI 专题",
      "title": "A9 · 测试、守卫与质量保障体系",
      "sub": "三级验收、两套 IT 独立库、27 守卫、37 反证脚本、132 组时点与 CI 判绿口径",
      "chapters": [
        {
          "id": "a9",
          "title": "A9. 测试、守卫与质量保障体系：从用例规模到反证工程",
          "subs": [
            {
              "id": "a9-1",
              "title": "A9.1 三级验收与规模口径"
            },
            {
              "id": "a9-2",
              "title": "A9.2 IT 独立库体系：两套 reactor、两套库、两套守门写法"
            },
            {
              "id": "a9-3",
              "title": "A9.3 架构守卫与源码守卫：27 个类守什么"
            },
            {
              "id": "a9-4",
              "title": "A9.4 守卫怎么写才不失真：七条代码级纪律"
            },
            {
              "id": "a9-5",
              "title": "A9.5 反向验证（撤点反证）体系：37 脚本、中断安全与三件套"
            },
            {
              "id": "a9-6",
              "title": "A9.6 CI 与判绿口径：三个必红/假绿缺陷"
            },
            {
              "id": "a9-7",
              "title": "A9.7 护栏设计评估：11 层防线、成熟度 B- 与红队死代码"
            },
            {
              "id": "a9-8",
              "title": "A9.8 十三条独有方法论增量（13 册未写入 / 未系统呈现）"
            },
            {
              "id": "a9-9",
              "title": "A9.9 未找到证据 / 无法确认，以及提交前质量清单"
            }
          ],
          "keywords": "质量保障 三级验收 行为单测 链路E2E 闭环 3209 641 756 65 XML testcase skipped IT独立库 it-env.sh rag-it-env.sh --probe --reset app_agent ItTenantProvisioner 行数不增长 execution_snapshot @BeforeAll 四道隔离闸 27守卫 ModuleDependencyDirectionTest DatabaseBoundaryTest AgentCorePurityTest ApplicationConfigKeyAlignmentTest L2ProductionGuardTest ScopeBypassArchitectureTest SchemaDriftIT RealBrokerEndToEndIT 防空转反向断言 Pattern.find doesNotMatch stripCommentsKeepStrings raw() 反证 counterproof.sh 中断安全 trap RV 132组 三件套 git diff --check CI pipefail set -o pipefail skipped=0 纯排除 needs.result 护栏 11层 B- 红队死代码 wrapToolResult fail-closed 门禁 同义反复 inode 确定性分桶 非特权真库IT"
        }
      ],
      "ai": true
    },
    {
      "id": "ai-10-quality-evaluation",
      "file": "ai-10-quality-evaluation.html",
      "group": "AI 专题",
      "title": "A10 · 对话质量评估、判定与归因体系",
      "sub": "L1 链路完整性与 L2 归因判分、questionId 白名单、judge 成本分账、机器判分不污染人工抽样、幻觉检测与告警闭环",
      "chapters": [
        {
          "id": "a10",
          "title": "A10. 对话质量评估、判定与归因体系：从 Run 终态到可证伪的质量信号",
          "subs": [
            {
              "id": "a10-1",
              "title": "A10.1 两套「L1/L2」语境：先分清再谈判分"
            },
            {
              "id": "a10-2",
              "title": "A10.2 评估链路全景：从 Run 终态到审查任务"
            },
            {
              "id": "a10-3",
              "title": "A10.3 L1 链路完整性判分：判链路，不判答案"
            },
            {
              "id": "a10-4",
              "title": "A10.4 L2 归因判分：8 值 Outcome 与 fail-closed"
            },
            {
              "id": "a10-5",
              "title": "A10.5 questionId 白名单与标准答案解析"
            },
            {
              "id": "a10-6",
              "title": "A10.6 成本隔离铁律：judge 成本绝不混 usage_record / cost_amount"
            },
            {
              "id": "a10-7",
              "title": "A10.7 机器判分不污染人工抽样：唯一落点与真库反证"
            },
            {
              "id": "a10-8",
              "title": "A10.8 幻觉检测与编造出处：编造引用即 Sev-0"
            },
            {
              "id": "a10-9",
              "title": "A10.9 告警与审查闭环：不猜基线，n<30 不告警"
            },
            {
              "id": "a10-10",
              "title": "A10.10 未落地 / 未确认：把「骨架」和「已跑通」分开"
            }
          ],
          "keywords": "L1 L2 可信度 L0 L3 TRACE_LINK_L1 L1_GAP L2AttributionEvaluator ModelAttributionJudge HallucinationDetector EvaluationCostBudget EVAL_JUDGE_COST agent_eval_sample agent_trace_metric attribution_correct questionId QuestionIdTag ScenarioTag reference-answers.json 空题库 8值Outcome fail-closed 确定性抽样 floorMod 编造出处 Sev-0 QualityAlertEvaluator review_task uq_review_task_open MIN_SAMPLE_SIZE n<30 ReviewAttributionHook NoOp run_message 回放 BR-01"
        }
      ],
      "ai": true
    },
    {
      "id": "ai-11-cost-governance",
      "file": "ai-11-cost-governance.html",
      "group": "AI 专题",
      "title": "A11 · 租户级成本治理与 Token 计费",
      "sub": "金额预算三表、usage_record 幂等账、per-1k 单价与方括号字面键、预留—结算—释放、judge 分账、G2 压缩成本实测",
      "chapters": [
        {
          "id": "a11",
          "title": "A11. 租户级成本治理与 Token 计费：已落地的金额账 vs 仅设计的 Token 配额",
          "subs": [
            {
              "id": "a11-1",
              "title": "A11.1 两套账本对照：别把设计稿当实现"
            },
            {
              "id": "a11-2",
              "title": "A11.2 usage_record 事实账：字段、幂等与租户隔离"
            },
            {
              "id": "a11-3",
              "title": "A11.3 一次模型调用的成本时序：预留 → 结算 → 释放"
            },
            {
              "id": "a11-4",
              "title": "A11.4 模型定价配置：per-1k 单价与方括号字面键"
            },
            {
              "id": "a11-5",
              "title": "A11.5 记 0 的诚实语义：未配价与上游不回 usage"
            },
            {
              "id": "a11-6",
              "title": "A11.6 分账铁律：judge 成本绝不混 usage_record / cost_amount"
            },
            {
              "id": "a11-7",
              "title": "A11.7 上下文压缩的成本实证：G2 双臂 input_tokens 真下降"
            },
            {
              "id": "a11-8",
              "title": "A11.8 租户配额与旁路：哪些真接通了，哪些没有"
            },
            {
              "id": "a11-9",
              "title": "A11.9 已落地的守卫与测试：账目正确性的证据"
            },
            {
              "id": "a11-10",
              "title": "A11.10 未落地 / 未确认：设计稿与实现的完整差距"
            }
          ],
          "keywords": "usage_record budget_account budget_reservation tenant_token_quota tenant_token_account token_quota_reservation TokenQuotaPort agent.billing.* operation_id idx_usage_operation_once step_id ON CONFLICT modelCallId reserveModelCost settleModelCost releaseModelCost BUDGET_EXHAUSTED MAX_COST agent.pricing.models per-1k MiniMax-M2.5 方括号字面键 Spring Binder 拆层 ModelPricingPolicy ModelPricing HALF_UP 占位价 recordModelUsage cost_amount EVAL_JUDGE_COST 45747 31082 15102 6280 G2 ContextCompressionG2GuardTest tenant_quota ConsoleBudgetPort BudgetReconciliationService BudgetReservationIT dottedModelIdKeepsItsPricing 71表 44 RLS 52策略 待评审"
        }
      ],
      "ai": true
    },
    {
      "id": "ai-12-observability-runbook",
      "file": "ai-12-observability-runbook.html",
      "group": "AI 专题",
      "title": "A12 · 可观测性、审计链与运维 Runbook",
      "sub": "两套指标体系、Grafana 16 面板、日志上下文净化、审计防篡改真相、禁 RETURNING 铁律、告警与 fail-closed 门禁、CAP 灰度与密钥轮换",
      "chapters": [
        {
          "id": "a12",
          "title": "A12. 可观测性、审计链与运维 Runbook：从指标到不可抵赖的证据",
          "subs": [
            {
              "id": "a12-1",
              "title": "A12.1 两套指标体系：自研 AgentMetrics（空转）vs Micrometer（真实出口）"
            },
            {
              "id": "a12-2",
              "title": "A12.2 指标清单与日志上下文：看见系统且不泄露"
            },
            {
              "id": "a12-3",
              "title": "A12.3 Grafana 16 面板：清单、数据源与文档口径差"
            },
            {
              "id": "a12-4",
              "title": "A12.4 审计链真相：不是哈希链，是 append-only 触发器 + 凭据隔离"
            },
            {
              "id": "a12-5",
              "title": "A12.5 禁 RETURNING 铁律：authz_decision_log 只 INSERT 无 SELECT"
            },
            {
              "id": "a12-6",
              "title": "A12.6 审计守卫空转：红队死代码的活例"
            },
            {
              "id": "a12-7",
              "title": "A12.7 告警与 fail-closed 门禁：开关怎么安全落地"
            },
            {
              "id": "a12-8",
              "title": "A12.8 运维 Runbook：CAP 三段灰度与全量开启"
            },
            {
              "id": "a12-9",
              "title": "A12.9 密钥轮换与环境治理：明文即泄露"
            },
            {
              "id": "a12-10",
              "title": "A12.10 未落地 / 未确认：把「配了」和「生效了」分开"
            }
          ],
          "keywords": "AgentMetrics Micrometer ai.model.calls.total /actuator/prometheus /metrics PUBLIC_ENDPOINTS LogContext MDC sanitize traceId Grafana agent-quality.json 16面板 13面板 authz_decision_log audit_record platform_audit_event forbid_audit_mutation append-only agent_retention AuditChainIT RETURNING AuthzDecisionLogSchemaTest AuditTamperProofGuard GUARDED_TABLES CapEnforcementMetrics cap.enforcement_status QualityAlertEvaluator SLA-2 SLA-3 SLA-7 rule_files prometheus.yml CAP_运维检查清单 三段灰度 cap-gray-step.sh kid 双密钥 JWT_PRIVATE_KEY AI_AGENT_PASSWORD 密钥轮换 fail-closed"
        }
      ],
      "ai": true
    },
    {
      "id": "ai-13-agent-memory",
      "file": "ai-13-agent-memory.html",
      "group": "AI 专题",
      "title": "A13 · Agent 记忆机制与跨 Run 状态",
      "sub": "事件日志与聚合记忆分离、强度衰减与读时重算、单次出现永不召回、记忆围栏与二次注入、降级纪律与模块边界守卫",
      "ai": true,
      "chapters": [
        {
          "id": "a13",
          "title": "A13. Agent 记忆机制与跨 Run 状态：从事件日志到带衰减的记忆条目",
          "subs": [
            {
              "id": "a13-1",
              "title": "A13.1 为什么记忆是 AI 系统的新状态类型：五类记忆盘点与本项目落点"
            },
            {
              "id": "a13-2",
              "title": "A13.2 事件与状态分离：weakness_record 与 student_memory 为什么是两张表"
            },
            {
              "id": "a13-3",
              "title": "A13.3 强度衰减模型：base(n)、λ=0.01 与「单次出现永不召回」"
            },
            {
              "id": "a13-4",
              "title": "A13.4 幂等闸与读时重算：last_run_id 判重与 strength 快照"
            },
            {
              "id": "a13-5",
              "title": "A13.5 召回路径：MAX_RECALL_SCAN=50、支配序预排与 0.40 门槛"
            },
            {
              "id": "a13-6",
              "title": "A13.6 记忆必须围栏：二次注入传播路径与 MEMORY_PREAMBLE 的双重设计"
            },
            {
              "id": "a13-7",
              "title": "A13.7 降级纪律：记忆失败不穿透，Run 不能因为增强功能挂掉"
            },
            {
              "id": "a13-8",
              "title": "A13.8 保留期与删除权：ErasurePort 独立凭据清理链"
            },
            {
              "id": "a13-9",
              "title": "A13.9 P2 语义召回与 P3 会话记忆：已落 / 未落边界"
            },
            {
              "id": "a13-10",
              "title": "A13.10 模块边界守卫与「机制正确但漏接线」事故"
            },
            {
              "id": "a13-summary",
              "title": "A13.11 未落地 / 未确认小结"
            }
          ],
          "keywords": "student_memory weakness_record agent-memory MemoryEntry MemoryStrength MemoryRecallService MemoryConsolidator MemoryPolicy JdbcMemoryRepository PgVectorAvailability EmbeddingPort MemoryConfiguration MemoryModuleBoundaryTest MemoryWiringTest RunExecutionLoopMemoryTest MemoryRecallIT base(n) DECAY_LAMBDA λ=0.01 半衰期 69.3 天 NATURAL_FORGET_DAYS 180 天 FUTURE_TOLERANCE_SECONDS recall-min-strength 0.40 recall-max-items recall-max-tokens MAX_RECALL_SCAN memory_uniq MEMORY_OPEN MEMORY_PREAMBLE memory_context trust=untrusted sanitize 二次注入 自我强化 evidence_count last_run_id strength 快照 读时重算 ErasurePort purgeExpiredMemories KEY_MEMORY_MONTHS agent.memory AGENT_MEMORY_ENABLED 保留期 被遗忘权 P2 向量召回 P3 会话记忆 过程记忆 工作记忆 情景记忆 语义记忆 P4-D08 R-1 漏接线 假绿"
        }
      ]
    },
    {
      "id": "ai-14-model-gateway",
      "file": "ai-14-model-gateway.html",
      "group": "AI 专题",
      "title": "A14 · 模型统一配置与模型网关",
      "sub": "model_catalog 单一事实源、解析优先级四级 fail-closed、ModelErrorKind 六分类与 retryable、凭据脱敏不挂 cause、MiniMax tool_call_id 事故与七种故障注入",
      "ai": true,
      "chapters": [
        {
          "id": "a14",
          "title": "A14. 模型统一配置与模型网关：单一事实源、归一化与凭据边界",
          "subs": [
            {
              "id": "a14-1",
              "title": "A14.1 单一事实源：model_catalog 表、「密钥与目录分离」铁律与无租户无 RLS 的理由"
            },
            {
              "id": "a14-2",
              "title": "A14.2 场景模型：ModelScenario 七个内置场景与字符串常量的取舍"
            },
            {
              "id": "a14-3",
              "title": "A14.3 解析优先级四级：显式名 → 同名 → 场景默认 → default，fail-closed 不猜模型"
            },
            {
              "id": "a14-4",
              "title": "A14.4 网关抽象：AbstractModelGateway 的三层职责与观察者装饰"
            },
            {
              "id": "a14-5",
              "title": "A14.5 ModelErrorKind 六分类与 retryable 语义：误标 = 无限重试"
            },
            {
              "id": "a14-6",
              "title": "A14.6 凭据脱敏：双正则、***REDACTED*** 与 safeMessage 不挂 cause"
            },
            {
              "id": "a14-7",
              "title": "A14.7 MDC 与线程边界：模型调用为什么要 LogContext.wrap"
            },
            {
              "id": "a14-8",
              "title": "A14.8 真实供应商的坑：MiniMax tool_call_id 成对结构事故"
            },
            {
              "id": "a14-9",
              "title": "A14.9 能力探测的诚实性：未探测前恒 false"
            },
            {
              "id": "a14-10",
              "title": "A14.10 未落地清单：fallback 链 / 网关重试 / 模型熔断 / Prompt Registry"
            },
            {
              "id": "a14-11",
              "title": "A14.11 用 Fake 网关做测试：七种故障注入与「假密钥进异常消息」的证明法"
            }
          ],
          "keywords": "model_catalog model_provider_credential encrypted_key AES-256-GCM key_checksum model_catalog_scenario_default_uq ModelResolver ModelScenario ModelDefinition resolveRequired fail-closed 不缓存 已禁用模型 AbstractModelGateway MiniMaxModelGateway OpenAiCompatibleModelGateway FakeModelGateway ModelErrorKind TIMEOUT RATE_LIMITED PROVIDER_ERROR INVALID_REQUEST UNAUTHORIZED CONTENT_BLOCKED retryable classify callWithTimeout ObservableModelGateway ModelCallObserver CredentialRedactor REDACTED safeMessage 不挂 cause ModelCredentialProvider JdbcModelCatalogRepository AdminModelConfigController AdminModelCredentialController InternalModelConfigController AgentModelConfigAssembler RootAgentModelConfigProvider WorkerChildAgentModelConfigProvider tool_call_id 2013 invalid params ThinkFilter streamChat ChatStreamGateway FaultMode FAKE_SECRET ModelGatewayContractTest MiniMaxModelGatewayStreamTest WorkerRealModelGatewayResolutionTest LogContext MDC fallback 链 网关重试 熔断 Prompt Registry prompt_template supports_prompt_caching tokenizer_id R10 未探测恒 false @Retryable 双重重试"
        }
      ]
    },
    {
      "id": "ai-15-streaming-sse",
      "file": "ai-15-streaming-sse.html",
      "group": "AI 专题",
      "title": "A15 · 流式输出与前端 SSE 消费",
      "sub": "ChatStreamGateway 独立端口、13 类事件协议、双身份域双端点与 thinking 可见性、超时三层对齐、前端增量解析与状态机、truncated 诚实语义",
      "ai": true,
      "chapters": [
        {
          "id": "a15",
          "title": "A15. 流式输出与前端 SSE 消费：从供应商 token 流到浏览器增量渲染",
          "subs": [
            {
              "id": "a15-1",
              "title": "A15.1 为什么要独立 ChatStreamGateway 端口：不污染领域契约"
            },
            {
              "id": "a15-2",
              "title": "A15.2 供应商侧 SSE 解析：stream=true 覆盖、逐行读取、[DONE] 与 ThinkFilter"
            },
            {
              "id": "a15-3",
              "title": "A15.3 13 类事件协议：sealed interface 的事件清单与终态语义"
            },
            {
              "id": "a15-4",
              "title": "A15.4 双身份域双端点：平台链与租户链、thinking 草稿可见性边界"
            },
            {
              "id": "a15-5",
              "title": "A15.5 后端 SSE 实现纪律：SseEmitter、虚拟线程、反代缓冲与 ASYNC 放行"
            },
            {
              "id": "a15-6",
              "title": "A15.6 超时三层对齐与失败口径分层：180s / 150s / 120s"
            },
            {
              "id": "a15-7",
              "title": "A15.7 前端增量解析器：IncrementalSseParser 与逐字段解析"
            },
            {
              "id": "a15-8",
              "title": "A15.8 前端状态机：useReducer 六种 Action 与乐观 UI"
            },
            {
              "id": "a15-9",
              "title": "A15.9 双工程同构与压缩态 carry-over：架构测试锁协议、contextState 只上送 committed"
            },
            {
              "id": "a15-10",
              "title": "A15.10 未落地清单：断线重连、Last-Event-ID 续传、事件序列号与重放"
            }
          ],
          "keywords": "ChatStreamGateway streamChat StreamDeltaConsumer onDelta onThinking MiniMaxModelGateway stream=true streamAndParse ThinkFilter AgentChatEvent sealed interface run.start route rag.query rag.hits skill tool handoff thinking delta answer usage error done truncated contextStateVersion ContextCheckpoint M6 M8 AgentChatOrchestrator AgentChatController ConsoleChatController PLATFORM_STREAM_PATH TENANT_STREAM_PATH /api/agent/chat/stream /api/knowledge/agent/chat/stream aud 身份不入参 AgentChatIdentityResolver thinking 草稿 恒丢弃 SseEmitter X-Accel-Buffering DispatcherType.ASYNC 虚拟线程 SSE_TIMEOUT_MS 180s AGENT_CHAT_TIMEOUT_MS 150s AGENT_LIVE_TIMEOUT_MS 120s 超时对齐 4xx error+done sse.ts parseFrame IncrementalSseParser Last-Event-ID 断线重连 sequenceNo RunEventStreamer agentChatState agentChatReducer useAgentChat AbortController controllerRef 乐观 UI SEND STREAM FAIL STOP RETRY NEW_SESSION truncated 三态 live sample 能力未接入 不退化成假流 sessionStorage agentChat.architecture.test.ts MarkdownAnswer admin-console aiChat 双工程同构 contextState committed carry-over 事件序列号 重放 AGENT_CONTEXT_STATE_HMAC_KEY D49 第14事件 S3-M8-b-2 六项校验 exp policyVersion tenant digest max-state-chars upToTurn 不透明base64 HMAC 签名 线序 usage answer done 标量审计 CONTEXT_CHECKPOINT CONTEXT_ASSEMBLED"
        }
      ]
    },
    {
      "id": "ai-16-mcp-runtime",
      "file": "ai-16-mcp-runtime.html",
      "group": "AI 专题",
      "title": "A16 · MCP 客户端实现与运行时防护",
      "sub": "HttpMcpTransport 两条决策、SSRF 三件套与二次解析复验、云元数据地址绝对禁止、按 server 熔断、输出不可信在 Gate 边界可信化、MCP 工具共用唯一执行入口",
      "ai": true,
      "chapters": [
        {
          "id": "a16",
          "title": "A16. MCP 客户端实现与运行时防护：传输、SSRF 三件套与按 server 熔断",
          "subs": [
            {
              "id": "a16-1",
              "title": "A16.1 定位纠偏：A4 讲配置模型，本册讲客户端运行时"
            },
            {
              "id": "a16-2",
              "title": "A16.2 端口面与装配：六个端口、可空处理器与「未装配 = 只有原生工具」"
            },
            {
              "id": "a16-3",
              "title": "A16.3 HttpMcpTransport：协议流程、「不用 HttpClient」与「不做连接池」的两条决策"
            },
            {
              "id": "a16-4",
              "title": "A16.4 SSRF 三件套：解析后校验、二次解析复验、连接地址=被校验地址"
            },
            {
              "id": "a16-5",
              "title": "A16.5 fail-closed 语义：一个 IP 被禁即整体拒绝"
            },
            {
              "id": "a16-6",
              "title": "A16.6 地址策略：信任级别、私有 CIDR 与云元数据地址的绝对禁止"
            },
            {
              "id": "a16-7",
              "title": "A16.7 熔断器：按 server 维度、CLOSED/OPEN/HALF_OPEN 与快速失败"
            },
            {
              "id": "a16-8",
              "title": "A16.8 输出不可信：可信化在 Gate 边界，不在传输层"
            },
            {
              "id": "a16-9",
              "title": "A16.9 唯一执行入口：MCP 工具与原生工具共用 ToolInvocationGate"
            },
            {
              "id": "a16-10",
              "title": "A16.10 未落地 / 未确认清单"
            }
          ],
          "keywords": "MCP McpTransportPort McpAddressResolverPort McpCredentialPort McpHandlerProvider McpManifestPort McpTransportException HttpMcpTransport McpEndpointGuard McpCircuitBreaker SystemMcpAddressResolver DbMcpHandlerProvider TransportMcpManifestPort UnconfiguredMcpManifestPort McpBackedToolHandler RefMcpCredentialPort McpTransportOptions McpAddressPolicy McpServerConfigValidator McpOpAllowlistNormalizer McpUntrustedOutput HttpUntrustedOutput UntrustedOutputEnvelope ParamPolicyEngine SSRF DNS rebinding 二次解析复验 解析后校验 连接地址=被校验地址 不用 HttpClient 按域名连接 SSLSocketFactory SNI endpoint identification 不做连接池 Connection close 凭据轮换 逐请求解析 169.254.169.254 169.254.170.2 fd00:ec2::254 100.100.100.200 云元数据 绝对禁止 trustTier internal 私有 CIDR fail-closed 整体拒绝 CLOSED OPEN HALF_OPEN 按 server 熔断 快速失败 阈值 5 30s 输出不可信 原样透传 Gate 边界 可信化 ToolInvocationGate mcpHandlers 可空 不另开执行入口 八道闸门 cap_mcp_server_def dynamic_discovery CHECK stdio http sse token oauth none W-PERM-P4 b770141 McpEndpointGuardTest McpCredentialSecurityTest McpAddressPolicyTest McpServerConfigValidatorTest McpSecurityBehaviorTest McpUntrustedOutputTest McpOpAllowlistNormalizerTest governance 01 滞后 Skill 运行时"
        }
      ]
    },
    {
      "id": "ai-17-test-engineering",
      "file": "ai-17-test-engineering.html",
      "group": "AI 专题",
      "title": "A17 · AI 测试工程与故障注入",
      "sub": "Fake 网关七态故障注入、假密钥证明脱敏、错误归一化契约矩阵、IT 独立库与守门写法、源码级守卫的边界、CI 反假绿四件套",
      "ai": true,
      "chapters": [
        {
          "id": "a17",
          "title": "A17. AI 测试工程与故障注入：无网络、无凭据、可证伪",
          "subs": [
            {
              "id": "a17-1",
              "title": "A17.1 为什么 AI 测试必须 Mock 供应商：三条硬理由与一条边界"
            },
            {
              "id": "a17-2",
              "title": "A17.2 七态故障注入：把「供应商会怎么坏」变成枚举"
            },
            {
              "id": "a17-3",
              "title": "A17.3 用假密钥证明脱敏：把安全属性写成可执行断言"
            },
            {
              "id": "a17-4",
              "title": "A17.4 错误归一化契约矩阵：六分类 × retryable 的断言表"
            },
            {
              "id": "a17-5",
              "title": "A17.5 流式与一次性路径的语义同源：别让两条路径各坏各的"
            },
            {
              "id": "a17-6",
              "title": "A17.6 IT 独立库与守门写法：把「隔离」变成可验证命题"
            },
            {
              "id": "a17-7",
              "title": "A17.7 源码级守卫的边界：什么能用源码扫描、什么必须上真库"
            },
            {
              "id": "a17-8",
              "title": "A17.8 CI 的四条反假绿纪律：pipefail、跳过数上限、job 职责分离、非特权角色"
            },
            {
              "id": "a17-9",
              "title": "A17.9 测试规模与结构：472 单测 / 90 IT / 113 前端测试的分布含义"
            },
            {
              "id": "a17-10",
              "title": "A17.10 本册的未落地与证据边界"
            }
          ],
          "keywords": "FakeModelGateway FaultMode NONE TIMEOUT RATE_LIMITED PROVIDER_ERROR INVALID_REQUEST UNAUTHORIZED CONTENT_BLOCKED FAKE_SECRET 假密钥 脱敏 AT-MODEL-001 AT-MODEL-002 ModelGatewayContractTest Nested 错误归一化 retryable 契约矩阵 MiniMaxModelGatewayStreamTest 语义同源 流式 一次性 it-env.sh IT_PG_URL agent_it agent_dev fail-closed 同名即拒绝 rag-it-env.sh IT_RAG_PG_URL study_rag_it rag_it_app NOBYPASSRLS rag_it_admin rag_it_ops ReingestDisciplineGuardTest 源码扫描 text_jieba ON CONFLICT DO UPDATE embed_model 守卫边界 AgentCorePurityTest 领域层纯净度 CI ci.yml pipefail set -o pipefail bash -e skipped 跳过数上限 480 60 非特权角色 app_agent superuser RLS 假绿 反假绿 472 90 113 40 counterproof 测试规模"
        }
      ]
    },
    {
      "id": "ai-18-vector-ops",
      "file": "ai-18-vector-ops.html",
      "group": "AI 专题",
      "title": "A18 · 向量库运维与索引代际",
      "sub": "维度是契约、halfvec 4000 上限与「能存不能索引」、BUILDING/ACTIVE/RETIRED 蓝绿代际、异步构建 Worker 四条纪律、text_jieba 预分词与重灌纪律",
      "ai": true,
      "chapters": [
        {
          "id": "a18",
          "title": "A18. 向量库运维与索引代际：维度契约、蓝绿重建与嵌入一致性",
          "subs": [
            {
              "id": "a18-1",
              "title": "A18.1 维度是契约不是常量：从「写库时才炸」到「启动即失败」"
            },
            {
              "id": "a18-2",
              "title": "A18.2 halfvec 4000 维上限与「能存不能索引」：一个必须摊开的取舍"
            },
            {
              "id": "a18-3",
              "title": "A18.3 嵌入端口：批大小、超时与「每条向量都要校验」"
            },
            {
              "id": "a18-4",
              "title": "A18.4 蓝绿代际：BUILDING / ACTIVE / RETIRED 状态机"
            },
            {
              "id": "a18-5",
              "title": "A18.5 索引构建 Worker：四条纪律与「为什么没有分布式锁」"
            },
            {
              "id": "a18-6",
              "title": "A18.6 重灌纪律：text_jieba 与 embed_model 两个必须同时更新的列"
            },
            {
              "id": "a18-7",
              "title": "A18.7 中文全文检索：为什么必须在应用侧预分词"
            },
            {
              "id": "a18-8",
              "title": "A18.8 换模型的完整操作顺序：一个可执行的 Runbook"
            },
            {
              "id": "a18-9",
              "title": "A18.9 与 pgvector 扩展的交互：启动自检与「官方镜像陷阱」"
            },
            {
              "id": "a18-10",
              "title": "A18.10 未落地与证据边界"
            }
          ],
          "keywords": "VectorColumn vector halfvec 2000 4000 MAX_INDEXABLE_DIMENSIONS requireDimension castPlaceholder 类型白名单 维度契约 expectedDimension 写库时才炸 启动即失败 HNSW IVFFlat rag_chunk_embedding_hnsw_idx 刻意不存在 能存不能索引 全表精确扫描 MRL 二进制量化 bit 64000 OpenAiCompatibleEmbeddingPort DEFAULT_BATCH_SIZE 16 DEFAULT_TIMEOUT 120s LocalDeterministicEmbeddingPort rag_index_generation IndexGeneration BUILDING ACTIVE RETIRED 蓝绿 代际 回滚 至多一个 ACTIVE indexed_chunks total_chunks scope IndexGenerationBuildWorker fixedDelay fixedRate 异常不让线程死掉 租户清单 RLS FORCE 全局桶 BYPASSRLS 无分布式锁 幂等 upsertEmbeddingForGeneration 按代际号取模分片 text_jieba embed_model EXCLUDED 重灌纪律 待索引判据 静默陈旧 中文分词 jieba to_tsvector simple GIN rag_chunk_jieba_idx pgvector CREATE EXTENSION postgres17 官方镜像陷阱 RagStartupSelfCheck PgVectorAvailability fail-soft 换模型 Runbook 零停机 分数基线 静默退化 RAG-04"
        }
      ]
    },
    {
      "id": "ai-19-roadmap",
      "file": "ai-19-roadmap.html",
      "group": "AI 专题",
      "title": "A19 · 能力缺口与增强路线",
      "sub": "引用式提示词库的 fail-closed 设计、缺口四分类与优先级、可复用增强模式、不建议做的方向、接手者第一周路线",
      "ai": true,
      "chapters": [
        {
          "id": "a19",
          "title": "A19. 能力缺口与增强路线：从「没有」到「该怎么补」",
          "subs": [
            {
              "id": "a19-1",
              "title": "A19.1 引用式提示词库：一个「已定义但被明确拒绝」的缺口"
            },
            {
              "id": "a19-2",
              "title": "A19.2 缺口全景：按「性质」而非「热度」分类"
            },
            {
              "id": "a19-3",
              "title": "A19.3 A 类：刻意不做的六项，及其「什么条件下该重评」"
            },
            {
              "id": "a19-4",
              "title": "A19.4 C 类优先补：三项结构缺口的实施要点"
            },
            {
              "id": "a19-5",
              "title": "A19.5 可复用的五种增强模式：每次扩展都该遵守的形态"
            },
            {
              "id": "a19-6",
              "title": "A19.6 D 类：证据空白清单与勘察方法"
            },
            {
              "id": "a19-7",
              "title": "A19.7 不建议做的事：三类常见但错误的扩展方向"
            },
            {
              "id": "a19-8",
              "title": "A19.8 接手者的第一周路线：从「看懂」到「能改」"
            }
          ],
          "keywords": "systemPromptRef inlineInstructions cap_agent_def.instructions AgentModelConfigAssembler 引用式提示词库 拒绝执行 fail-closed 声明坏了 没声明 静默忽略 静默降级 明确拒绝 prompt_template PromptTemplate 提示词模板 零命中 CAP 发布 三步发布 缺口分类 刻意不做 分期未到 结构缺口 证据空白 重评条件 fallback 链 网关重试 模型熔断 SSE 断线重连 dynamic_discovery HNSW 事件序列号 sequenceNo 重放 MCP Server 暴露 五种增强模式 端口可空注入 增强降级不穿透 fail-closed未知 唯一信任边界 双写同一事务 派生列失效 勘察方法 L2 判分 n30 RETIRED 清理 oauth 凭据 流式故障矩阵 rag SSE 进度流 Spring AI 全家桶 WebSocket 热更新 第一周 D1 D7 转型者 成熟度 边界清晰 失败可解释 取舍留痕"
        }
      ]
    },
    {
      "id": "ai-20-tool-gate",
      "file": "ai-20-tool-gate.html",
      "group": "AI 专题",
      "title": "A20 · 工具调用闸门与副作用安全",
      "sub": "八道闸门的执行顺序、风险三级与 fail-closed 兜底、ToolOperation 八态状态机、UNKNOWN 的三种禁止、fencing token 与 DB 触发器双防线、幂等键命名空间",
      "ai": true,
      "chapters": [
        {
          "id": "a20",
          "title": "A20. 工具调用闸门与副作用安全：不可绕过的边界",
          "subs": [
            {
              "id": "a20-1",
              "title": "A20.1 执行链八步：顺序本身就是安全设计"
            },
            {
              "id": "a20-2",
              "title": "A20.2 风险三级与 fail-closed 兜底：未登记即最高风险"
            },
            {
              "id": "a20-3",
              "title": "A20.3 「只能收紧不能放松」：配置与代码的 maxWith 合并"
            },
            {
              "id": "a20-4",
              "title": "A20.4 审批的默认方向：恒 FAIL_CLOSED 而非 APPROVED"
            },
            {
              "id": "a20-5",
              "title": "A20.5 ToolOperation 八态状态机：为什么「未知」必须是独立状态"
            },
            {
              "id": "a20-6",
              "title": "A20.6 幂等键：命名空间不可省略，分隔符避开管道符"
            },
            {
              "id": "a20-7",
              "title": "A20.7 账本：为什么 UNKNOWN 必须落库而不是只记内存"
            },
            {
              "id": "a20-8",
              "title": "A20.8 数据库第二道防线：CHECK 约束与禁止新增 attempt 的触发器"
            },
            {
              "id": "a20-9",
              "title": "A20.9 fencing token：租约只能「劝阻」，fencing 才能「阻止」"
            },
            {
              "id": "a20-10",
              "title": "A20.10 「呈现层不是防线」：能力闭包为什么必须在执行侧再校验"
            },
            {
              "id": "a20-11",
              "title": "A20.11 一个真实教训：Worker 上下文缺角色 = 授权链路根本没接上"
            },
            {
              "id": "a20-12",
              "title": "A20.12 未落地与证据边界"
            }
          ],
          "keywords": "ToolInvocationGate 八道闸门 Schema 校验 执行时重新授权 幂等键 账本 审批 预算 执行 账本更新 ToolCallingAdvisor 不可绕过 ADR-011 ToolRiskLevel READ WRITE_PROFILE WRITE_EXTERNAL fail-closed 未登记即最高风险 risk-level-map defaultApproval hasSideEffect requiresIdempotencyKey RiskBasedApprovalPolicy maxWith 只能收紧不能放松 红线15 R-020 ApprovalRequirement NOT_REQUIRED OPTIONAL REQUIRED agent.approval.required-by-risk NO_APPROVAL_PORT DENY_ALL_APPROVALS FAIL_CLOSED APPROVED NO_CANCEL CancelGuard 构造器膨胀 F-6 ToolOperationState PENDING IN_FLIGHT SUCCEEDED FAILED UNKNOWN RECONCILED_SUCCEEDED RECONCILED_ABSENT MANUAL_DISPOSED 八态 状态机 UNKNOWN 三条禁止 盲目重试 Sev-0 R-003 R-007 三层 Attempt Run attempt ToolOperation ToolOperationAttempt 对账 reconcile 幂等键 IdempotencyKey 命名空间 run.create tool.record_weakness tool.invoke 分隔符 管道符 PII 白名单 agent_ref RunToolLedgerPort register recordResult register 返回既有操作 状态机即契约 forbid_attempt_after_terminal enforce_fencing_on_write restrict_violation result_unknown CHECK 约束 tool_operation tool_operation_attempt fencing_token 单调递增 世代号 僵尸 Worker SSE_DIRECT_LEASE_OWNER 能力闭包 CapabilityClosureGuard 呈现层不是防线 注入诱导 假防线 GUARDED_TABLES AUTHZ_MISMATCH Worker 角色 Roles.WORKER TOOL_INVOKE RoleBasedAuthorizationService 空角色集 P4-D08 R-4 授权链路没接上 可为空 生产必须传入 approvalRequestSettings runBudget closureGuard mcpHandlers httpToolHandlers 装配测试"
        }
      ]
    },
    {
      "id": "ai-21-dispatch-lease",
      "file": "ai-21-dispatch-lease.html",
      "group": "AI 专题",
      "title": "A21 · 消息投递、租约与恢复",
      "sub": "Outbox 事务性投递与「谁驱动发布器」、SKIP LOCKED 领取、租约与 fencing 接管、五种执行结局到 MQ 处置的映射、背压、恢复扫描与 SSE 直连租约",
      "ai": true,
      "chapters": [
        {
          "id": "a21",
          "title": "A21. 消息投递、租约与恢复：从「任务落库」到「谁在跑它」",
          "subs": [
            {
              "id": "a21-1",
              "title": "A21.1 Outbox 模式：为什么不能「建完 Run 直接发 MQ」"
            },
            {
              "id": "a21-2",
              "title": "A21.2 「逻辑写了但没有调用方」：一个比 bug 更难发现的失效"
            },
            {
              "id": "a21-3",
              "title": "A21.3 多租户发布与背压：隔离的反面是「一坏全坏」"
            },
            {
              "id": "a21-4",
              "title": "A21.4 「重复消息必须丢弃而非重试」：at-least-once 的正确消费姿势"
            },
            {
              "id": "a21-5",
              "title": "A21.5 SKIP LOCKED 领取：多 Worker 不争抢的正确写法"
            },
            {
              "id": "a21-6",
              "title": "A21.6 租约与接管：30 秒的默认值意味着什么"
            },
            {
              "id": "a21-7",
              "title": "A21.7 五种执行结局 → MQ 处置：一张映射表决定「重投还是不重投」"
            },
            {
              "id": "a21-8",
              "title": "A21.8 SSE 直连租约：一个必须存在的「例外所有者」"
            },
            {
              "id": "a21-9",
              "title": "A21.9 停机与资源关闭：为什么「停机窗口内的 DB 异常」要降级"
            },
            {
              "id": "a21-10",
              "title": "A21.10 未落地与证据边界"
            }
          ],
          "keywords": "Outbox OutboxRelay OutboxPublisher TenantOutboxPublishers outbox_event 同事务落库 幽灵任务 200ms 轮询 at-least-once 重复是正常形态 零调用方 @RabbitListener @Scheduled publishOnce relayOnce start stop 可测性 多租户发布 单租户失败不影响其他租户 一坏全坏 BackpressureController BackpressurePolicy 高水位 整轮跳过 拉长间隔 RunDispatchConsumer 重复消息丢弃而非重试 领取失败已被别人领走 消费路径越短 RunExecutor Outcome SUCCEEDED FAILED CANCELLED RETRYABLE_FAILURE UNKNOWN ack nack 重投 绝不重投 重复副作用 SKIP LOCKED FOR UPDATE 领取缩容 FORCE RLS 绑定租户 空转 DEFAULT_LEASE 30s RunRecoverySweeper 接管 崩溃恢复 60s 租约续期 长任务 SSE_DIRECT_LEASE_OWNER agent-chat-sse lease_owner DISTINCT FROM 两条独立防线 跨模块契约 字面量反查 漂移 停机 shuttingDown stopped HikariDataSource has been closed 告警疲劳 STOP_TIMEOUT_MS 30s 优雅停机 强杀 RabbitMQ CI 覆盖 E-1 E-2 E-3 database 通道"
        }
      ]
    }
  ]
};
