-- =====================================================================
-- platform 种子数据（pg_dump --data-only --inserts 导出）
-- 【来源】由 02_platform_seed / 04_capability_seed 灌入基准库后导出，再空库复放验证。
--   ⚠️ 不是从远程 agent_dev 导的：远程有 11 个租户（9 个是 stat-it-* / probe-* 测试残留）、
--   95 行 agent，直接导会把测试垃圾当基线固化进生产初始化。
-- 【内容】1 租户（**平台侧登记 + 配额 + 业务侧镜像**三处齐）+ 1 预算账户
--   + 3 agent/agent_version + 能力目录
--   （Tool 6 / Skill 4 / MCP 2 / cap_agent_def 3 / 子引用边 2）
--   + 平台权限矩阵（角色 6 / 权限 24 / 绑定 53）+ 系统字典（类 12 / 项 61）
--   ⚠️ 权限矩阵的运行期权威源是代码（PlatformRolePermissionMatrix），本表只是治理副本；
--   两边必须同步 —— 新增 platform.tenant.execution_ticket（执行票证，仅超级管理员）
--   时，代码与本文件同批改，否则「权限矩阵」页与审计检索会各说一套。
-- 【插入序】按 FK 依赖排好：platform.tenant → platform.tenant_quota → public.tenant
--   → agent → agent_version → cap_agent_def → cap_agent_child_ref
-- 【不含】principal / 管理员口令哈希 —— 属环境相关，见 04_platform_admin_grant.sql
--   平台账号（platform_user + platform_user_role）本文件一律不含：口令哈希只能由
--   服务端同一 PasswordHasher 现场计算，绝不入库。安装通道见
--   scripts/dev-seed-platform-users.sh（幂等，dry-run 默认）。
-- 【非 dump 内容】文件末尾有一段时间性附录（发布头 + 指针），**不是 pg_dump 导出**：
--   定义表的 lifecycle_state='PUBLISHED' 不等于运行期可用，运行期只认
--   指针 → 发布头 → object_versions。少了那段，复放出来的库「有目录、不能跑」。
--   ⚠️ 重新导出本文件会丢掉它 —— 附录里注明了与 patch_cap_release_head.sql 同源。
-- =====================================================================

--
-- PostgreSQL database dump
--

\restrict 41TLVhigOe5lQmRdbPsgLPeZN8wrjMog2DCAZIV1YKh5a6eYzhYlxffWYxZ5eyi

-- Dumped from database version 17.11 (Debian 17.11-1.pgdg13+2)
-- Dumped by pg_dump version 17.11 (Debian 17.11-1.pgdg13+2)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Data for Name: platform_permission; Type: TABLE DATA; Schema: platform; Owner: -
--

INSERT INTO platform.platform_permission VALUES ('1d78d87b-f21c-4ed4-80cf-1827f7211a14', 'platform.user.read', '查看平台用户基本信息（脱敏）');
INSERT INTO platform.platform_permission VALUES ('4e5cb113-9b34-4408-93b2-35cb6794918a', 'platform.user.manage', '管理平台用户（创建/禁用/重置凭据等写操作）');
INSERT INTO platform.platform_permission VALUES ('45e83063-ab27-4e51-ad55-6fe8c437cf96', 'platform.role.read', '查看平台角色与权限矩阵');
INSERT INTO platform.platform_permission VALUES ('976b8e55-1eef-4085-9c2e-7f433747e946', 'platform.role.assign', '授予平台角色');
INSERT INTO platform.platform_permission VALUES ('2e3616d6-9431-4771-abdb-7ad869e25712', 'platform.role.revoke', '撤销平台角色');
INSERT INTO platform.platform_permission VALUES ('f1b1c6cd-925f-4f34-a660-f86b091c51e4', 'platform.tenant.create', '创建新租户');
INSERT INTO platform.platform_permission VALUES ('e2fc870e-b478-4a86-95ac-07cef1fccf58', 'platform.tenant.read', '查看租户生命周期与配置（脱敏）');
INSERT INTO platform.platform_permission VALUES ('03692592-276b-47be-83be-a4505d788a25', 'platform.tenant.update', '修改租户配置与套餐');
INSERT INTO platform.platform_permission VALUES ('f55ed500-ca38-49a1-820f-61c88a1cb278', 'platform.tenant.suspend', '停用（暂停）租户');
INSERT INTO platform.platform_permission VALUES ('76e85956-4a4e-4bd7-a8b2-fcd4974880dc', 'platform.tenant.resume', '恢复（启用）租户');
INSERT INTO platform.platform_permission VALUES ('e5e1149b-bfc8-42a6-bc66-d4c78075199e', 'platform.tenant.decommission', '退役（销毁）租户');
INSERT INTO platform.platform_permission VALUES ('0c00508c-67fd-4a73-8fad-5df7a5f03e6a', 'platform.tenant.quota.read', '查看租户配额');
INSERT INTO platform.platform_permission VALUES ('562dc2e1-264d-422e-b9d2-dc647a6f1d99', 'platform.tenant.quota.update', '调整租户配额');
INSERT INTO platform.platform_permission VALUES ('d53e6582-feb4-4863-a33e-c04c85df0056', 'platform.tenant.capability.grant', '授予租户能力（如 Tool/Skill/MCP 授权）');
INSERT INTO platform.platform_permission VALUES ('028714d2-b4c2-406e-8815-5472509abfd2', 'platform.tenant.capability.revoke', '撤销租户能力授权');
INSERT INTO platform.platform_permission VALUES ('433881fc-afe9-430c-b630-0e2d394fe097', 'platform.tenant.capability.read', '查看租户已授权能力');
INSERT INTO platform.platform_permission VALUES ('e77d46fa-c981-40fd-8b97-9d54bc136a70', 'platform.tenant.execution_ticket', '为租户成员签发短时执行票证（跨身份域执行，可扮演租户发起 Run）');
INSERT INTO platform.platform_permission VALUES ('a4a7467f-28a4-44cc-8737-6109749afcf1', 'platform.resource.read', '查看全局资源目录（Tool/MCP/模型等）');
INSERT INTO platform.platform_permission VALUES ('e3144eda-784f-46c3-8a1b-8ff5b3691c71', 'platform.resource.manage', '管理全局资源目录可用性');
INSERT INTO platform.platform_permission VALUES ('130d41a3-178a-4f08-8ea8-68a4a0753538', 'platform.policy.read', '查看平台安全/访问策略');
INSERT INTO platform.platform_permission VALUES ('860b2036-eedd-496e-b628-75d5ba73044f', 'platform.policy.publish', '发布平台安全/访问策略');
INSERT INTO platform.platform_permission VALUES ('54fd41f9-4409-4d97-a149-157406b4dc68', 'platform.audit.read', '查看平台审计日志');
INSERT INTO platform.platform_permission VALUES ('606f53f6-6198-4d31-94ab-bf8dc4faa372', 'platform.audit.export', '导出平台审计日志');
INSERT INTO platform.platform_permission VALUES ('915763a4-3628-4505-9aca-adb06ef4af0f', 'platform.support.diagnostics.read', '查看脱敏诊断与运维信息');


--
-- Data for Name: platform_role; Type: TABLE DATA; Schema: platform; Owner: -
--

INSERT INTO platform.platform_role VALUES ('2730a4bb-4d53-43cb-8da1-2aa5f89f09d3', 'PLATFORM_SUPER_ADMIN', '平台超级管理员（最高权限，敏感操作强 MFA/重认证）', true);
INSERT INTO platform.platform_role VALUES ('fd3d06b4-ffe2-418f-947a-4cdc381c29aa', 'TENANT_LIFECYCLE_ADMIN', '租户生命周期管理员（创建/启停/套餐/配额/能力授权）', true);
INSERT INTO platform.platform_role VALUES ('50bd8ec3-0054-4f9e-93a8-91210ac1f3c7', 'PLATFORM_RESOURCE_ADMIN', '平台资源管理员（全局 Tool/MCP/模型目录/资源可用性）', true);
INSERT INTO platform.platform_role VALUES ('ea5c1706-3c8e-49bd-8c89-1bfcf745504e', 'PLATFORM_SECURITY_ADMIN', '平台安全管理员（认证/访问/授权策略与风险处置）', true);
INSERT INTO platform.platform_role VALUES ('23ab04b8-9cfc-4b26-95b6-db99c6bd9787', 'PLATFORM_AUDITOR', '平台审计员（只读平台审计与脱敏租户生命周期）', true);
INSERT INTO platform.platform_role VALUES ('7ea29178-b986-49aa-82fd-e78995a01085', 'PLATFORM_SUPPORT', '平台支持人员（只读脱敏诊断与运维信息）', true);


--
-- Data for Name: platform_role_permission; Type: TABLE DATA; Schema: platform; Owner: -
--

INSERT INTO platform.platform_role_permission VALUES ('2730a4bb-4d53-43cb-8da1-2aa5f89f09d3', '1d78d87b-f21c-4ed4-80cf-1827f7211a14');
INSERT INTO platform.platform_role_permission VALUES ('2730a4bb-4d53-43cb-8da1-2aa5f89f09d3', '4e5cb113-9b34-4408-93b2-35cb6794918a');
INSERT INTO platform.platform_role_permission VALUES ('2730a4bb-4d53-43cb-8da1-2aa5f89f09d3', '45e83063-ab27-4e51-ad55-6fe8c437cf96');
INSERT INTO platform.platform_role_permission VALUES ('2730a4bb-4d53-43cb-8da1-2aa5f89f09d3', '976b8e55-1eef-4085-9c2e-7f433747e946');
INSERT INTO platform.platform_role_permission VALUES ('2730a4bb-4d53-43cb-8da1-2aa5f89f09d3', '2e3616d6-9431-4771-abdb-7ad869e25712');
INSERT INTO platform.platform_role_permission VALUES ('2730a4bb-4d53-43cb-8da1-2aa5f89f09d3', 'f1b1c6cd-925f-4f34-a660-f86b091c51e4');
INSERT INTO platform.platform_role_permission VALUES ('2730a4bb-4d53-43cb-8da1-2aa5f89f09d3', 'e2fc870e-b478-4a86-95ac-07cef1fccf58');
INSERT INTO platform.platform_role_permission VALUES ('2730a4bb-4d53-43cb-8da1-2aa5f89f09d3', '03692592-276b-47be-83be-a4505d788a25');
INSERT INTO platform.platform_role_permission VALUES ('2730a4bb-4d53-43cb-8da1-2aa5f89f09d3', 'f55ed500-ca38-49a1-820f-61c88a1cb278');
INSERT INTO platform.platform_role_permission VALUES ('2730a4bb-4d53-43cb-8da1-2aa5f89f09d3', '76e85956-4a4e-4bd7-a8b2-fcd4974880dc');
INSERT INTO platform.platform_role_permission VALUES ('2730a4bb-4d53-43cb-8da1-2aa5f89f09d3', 'e5e1149b-bfc8-42a6-bc66-d4c78075199e');
INSERT INTO platform.platform_role_permission VALUES ('2730a4bb-4d53-43cb-8da1-2aa5f89f09d3', '0c00508c-67fd-4a73-8fad-5df7a5f03e6a');
INSERT INTO platform.platform_role_permission VALUES ('2730a4bb-4d53-43cb-8da1-2aa5f89f09d3', '562dc2e1-264d-422e-b9d2-dc647a6f1d99');
INSERT INTO platform.platform_role_permission VALUES ('2730a4bb-4d53-43cb-8da1-2aa5f89f09d3', 'd53e6582-feb4-4863-a33e-c04c85df0056');
INSERT INTO platform.platform_role_permission VALUES ('2730a4bb-4d53-43cb-8da1-2aa5f89f09d3', '028714d2-b4c2-406e-8815-5472509abfd2');
INSERT INTO platform.platform_role_permission VALUES ('2730a4bb-4d53-43cb-8da1-2aa5f89f09d3', '433881fc-afe9-430c-b630-0e2d394fe097');
INSERT INTO platform.platform_role_permission VALUES ('2730a4bb-4d53-43cb-8da1-2aa5f89f09d3', 'a4a7467f-28a4-44cc-8737-6109749afcf1');
INSERT INTO platform.platform_role_permission VALUES ('2730a4bb-4d53-43cb-8da1-2aa5f89f09d3', 'e3144eda-784f-46c3-8a1b-8ff5b3691c71');
INSERT INTO platform.platform_role_permission VALUES ('2730a4bb-4d53-43cb-8da1-2aa5f89f09d3', '130d41a3-178a-4f08-8ea8-68a4a0753538');
INSERT INTO platform.platform_role_permission VALUES ('2730a4bb-4d53-43cb-8da1-2aa5f89f09d3', '860b2036-eedd-496e-b628-75d5ba73044f');
INSERT INTO platform.platform_role_permission VALUES ('2730a4bb-4d53-43cb-8da1-2aa5f89f09d3', '54fd41f9-4409-4d97-a149-157406b4dc68');
INSERT INTO platform.platform_role_permission VALUES ('2730a4bb-4d53-43cb-8da1-2aa5f89f09d3', '606f53f6-6198-4d31-94ab-bf8dc4faa372');
INSERT INTO platform.platform_role_permission VALUES ('2730a4bb-4d53-43cb-8da1-2aa5f89f09d3', '915763a4-3628-4505-9aca-adb06ef4af0f');
-- ⚠️ 执行票证**只**绑定超级管理员：其余角色能看租户、能启停租户，但不等于能扮演租户成员发起执行。
INSERT INTO platform.platform_role_permission VALUES ('2730a4bb-4d53-43cb-8da1-2aa5f89f09d3', 'e77d46fa-c981-40fd-8b97-9d54bc136a70');
INSERT INTO platform.platform_role_permission VALUES ('fd3d06b4-ffe2-418f-947a-4cdc381c29aa', 'f1b1c6cd-925f-4f34-a660-f86b091c51e4');
INSERT INTO platform.platform_role_permission VALUES ('fd3d06b4-ffe2-418f-947a-4cdc381c29aa', 'e2fc870e-b478-4a86-95ac-07cef1fccf58');
INSERT INTO platform.platform_role_permission VALUES ('fd3d06b4-ffe2-418f-947a-4cdc381c29aa', '03692592-276b-47be-83be-a4505d788a25');
INSERT INTO platform.platform_role_permission VALUES ('fd3d06b4-ffe2-418f-947a-4cdc381c29aa', 'f55ed500-ca38-49a1-820f-61c88a1cb278');
INSERT INTO platform.platform_role_permission VALUES ('fd3d06b4-ffe2-418f-947a-4cdc381c29aa', '76e85956-4a4e-4bd7-a8b2-fcd4974880dc');
INSERT INTO platform.platform_role_permission VALUES ('fd3d06b4-ffe2-418f-947a-4cdc381c29aa', 'e5e1149b-bfc8-42a6-bc66-d4c78075199e');
INSERT INTO platform.platform_role_permission VALUES ('fd3d06b4-ffe2-418f-947a-4cdc381c29aa', '0c00508c-67fd-4a73-8fad-5df7a5f03e6a');
INSERT INTO platform.platform_role_permission VALUES ('fd3d06b4-ffe2-418f-947a-4cdc381c29aa', '562dc2e1-264d-422e-b9d2-dc647a6f1d99');
INSERT INTO platform.platform_role_permission VALUES ('fd3d06b4-ffe2-418f-947a-4cdc381c29aa', 'd53e6582-feb4-4863-a33e-c04c85df0056');
INSERT INTO platform.platform_role_permission VALUES ('fd3d06b4-ffe2-418f-947a-4cdc381c29aa', '028714d2-b4c2-406e-8815-5472509abfd2');
INSERT INTO platform.platform_role_permission VALUES ('fd3d06b4-ffe2-418f-947a-4cdc381c29aa', '433881fc-afe9-430c-b630-0e2d394fe097');
INSERT INTO platform.platform_role_permission VALUES ('50bd8ec3-0054-4f9e-93a8-91210ac1f3c7', '433881fc-afe9-430c-b630-0e2d394fe097');
INSERT INTO platform.platform_role_permission VALUES ('50bd8ec3-0054-4f9e-93a8-91210ac1f3c7', 'a4a7467f-28a4-44cc-8737-6109749afcf1');
INSERT INTO platform.platform_role_permission VALUES ('50bd8ec3-0054-4f9e-93a8-91210ac1f3c7', 'e3144eda-784f-46c3-8a1b-8ff5b3691c71');
INSERT INTO platform.platform_role_permission VALUES ('ea5c1706-3c8e-49bd-8c89-1bfcf745504e', '130d41a3-178a-4f08-8ea8-68a4a0753538');
INSERT INTO platform.platform_role_permission VALUES ('ea5c1706-3c8e-49bd-8c89-1bfcf745504e', '860b2036-eedd-496e-b628-75d5ba73044f');
INSERT INTO platform.platform_role_permission VALUES ('ea5c1706-3c8e-49bd-8c89-1bfcf745504e', '54fd41f9-4409-4d97-a149-157406b4dc68');
INSERT INTO platform.platform_role_permission VALUES ('ea5c1706-3c8e-49bd-8c89-1bfcf745504e', '915763a4-3628-4505-9aca-adb06ef4af0f');
INSERT INTO platform.platform_role_permission VALUES ('23ab04b8-9cfc-4b26-95b6-db99c6bd9787', 'e2fc870e-b478-4a86-95ac-07cef1fccf58');
INSERT INTO platform.platform_role_permission VALUES ('23ab04b8-9cfc-4b26-95b6-db99c6bd9787', '0c00508c-67fd-4a73-8fad-5df7a5f03e6a');
INSERT INTO platform.platform_role_permission VALUES ('23ab04b8-9cfc-4b26-95b6-db99c6bd9787', '433881fc-afe9-430c-b630-0e2d394fe097');
INSERT INTO platform.platform_role_permission VALUES ('23ab04b8-9cfc-4b26-95b6-db99c6bd9787', 'a4a7467f-28a4-44cc-8737-6109749afcf1');
INSERT INTO platform.platform_role_permission VALUES ('23ab04b8-9cfc-4b26-95b6-db99c6bd9787', '54fd41f9-4409-4d97-a149-157406b4dc68');
INSERT INTO platform.platform_role_permission VALUES ('23ab04b8-9cfc-4b26-95b6-db99c6bd9787', '606f53f6-6198-4d31-94ab-bf8dc4faa372');
INSERT INTO platform.platform_role_permission VALUES ('7ea29178-b986-49aa-82fd-e78995a01085', 'e2fc870e-b478-4a86-95ac-07cef1fccf58');
INSERT INTO platform.platform_role_permission VALUES ('7ea29178-b986-49aa-82fd-e78995a01085', '0c00508c-67fd-4a73-8fad-5df7a5f03e6a');
INSERT INTO platform.platform_role_permission VALUES ('7ea29178-b986-49aa-82fd-e78995a01085', '433881fc-afe9-430c-b630-0e2d394fe097');
INSERT INTO platform.platform_role_permission VALUES ('7ea29178-b986-49aa-82fd-e78995a01085', 'a4a7467f-28a4-44cc-8737-6109749afcf1');
INSERT INTO platform.platform_role_permission VALUES ('7ea29178-b986-49aa-82fd-e78995a01085', '915763a4-3628-4505-9aca-adb06ef4af0f');


--
-- Data for Name: sys_dictionary; Type: TABLE DATA; Schema: platform; Owner: -
--

INSERT INTO platform.sys_dictionary VALUES ('b4778591-4966-4cf5-ae07-d99cb08035b4', 'http.method', 'HTTP 方法', 'Tool kind=HTTP 时允许的请求方法（后端按枚举校验）', true, true, 10, '2026-10-27 14:39:23.531179+00', '2026-10-27 14:39:23.531179+00', 0);
INSERT INTO platform.sys_dictionary VALUES ('d4260d5e-b77b-4ec0-ab8b-78a5f0fd171f', 'tool.risk_level', '工具风险等级', 'READ 只读 / WRITE_PROFILE 内部写 / WRITE_EXTERNAL 外部写', true, true, 20, '2026-10-27 14:39:23.531179+00', '2026-10-27 14:39:23.531179+00', 0);
INSERT INTO platform.sys_dictionary VALUES ('11d94dcd-b7cd-48be-b408-bc81ff9ccd06', 'agent.orchestration_mode', 'Agent 编排模式', 'LEAF 叶子 / ROUTER 路由 / SEQUENTIAL 串行', true, true, 30, '2026-10-27 14:39:23.531179+00', '2026-10-27 14:39:23.531179+00', 0);
INSERT INTO platform.sys_dictionary VALUES ('ce59db0a-4ff4-4023-ae7d-d5a9ed5dbad7', 'agent.failure_policy', '失败策略', 'FAIL_FAST / CONTINUE / COMPENSATE', true, true, 40, '2026-10-27 14:39:23.531179+00', '2026-10-27 14:39:23.531179+00', 0);
INSERT INTO platform.sys_dictionary VALUES ('cc147855-61da-4a98-95c6-0acf9a6e3167', 'rag.source_type', '语料来源类型', '教材 / 教辅 / 论文 / 其他', false, true, 50, '2026-10-27 14:39:23.531179+00', '2026-10-27 14:39:23.531179+00', 0);
INSERT INTO platform.sys_dictionary VALUES ('6d724728-892b-4f5e-a380-d578f6476913', 'rag.trust_tier', '语料权威等级', '准入门槛按此分级（后端 TrustTier 枚举）', true, true, 60, '2026-10-27 14:39:23.531179+00', '2026-10-27 14:39:23.531179+00', 0);
INSERT INTO platform.sys_dictionary VALUES ('a6f84f0e-5d10-45a3-a399-185fa8ec2146', 'rag.verification_method', '校验手段', 'ISBN / DOI / 人工比对 / 官方哈希', true, true, 70, '2026-10-27 14:39:23.531179+00', '2026-10-27 14:39:23.531179+00', 0);
INSERT INTO platform.sys_dictionary VALUES ('f6d30d30-57a4-47ce-966b-6ac734af6f12', 'rag.license_status', '版权状态', '已获授权 / 合理引用 / 未取得授权 / 禁止使用', true, true, 80, '2026-10-27 14:39:23.531179+00', '2026-10-27 14:39:23.531179+00', 0);
INSERT INTO platform.sys_dictionary VALUES ('d6ad117e-6c32-4b22-8092-c904f8249122', 'rag.subject', '学科', '教辅版本坐标系 · 学科轴', false, true, 90, '2026-10-27 14:39:23.531179+00', '2026-10-27 14:39:23.531179+00', 0);
INSERT INTO platform.sys_dictionary VALUES ('c2f35701-cbcb-4edb-a46c-cbe7811dd576', 'rag.grade', '年级', '教辅版本坐标系 · 年级轴', false, true, 100, '2026-10-27 14:39:23.531179+00', '2026-10-27 14:39:23.531179+00', 0);
INSERT INTO platform.sys_dictionary VALUES ('3c272b6e-c1d9-471d-9b63-3503c971118d', 'rag.term', '学期', '教辅版本坐标系 · 学期轴', false, true, 110, '2026-10-27 14:39:23.531179+00', '2026-10-27 14:39:23.531179+00', 0);
INSERT INTO platform.sys_dictionary VALUES ('a8b13e85-5646-4b33-ae12-25b8d28e0b5a', 'rag.publisher', '出版社', '教辅版本坐标系 · 出版社轴', false, true, 120, '2026-10-27 14:39:23.531179+00', '2026-10-27 14:39:23.531179+00', 0);


--
-- Data for Name: sys_dictionary_item; Type: TABLE DATA; Schema: platform; Owner: -
--

INSERT INTO platform.sys_dictionary_item VALUES ('9201a13a-08b0-436b-a8e9-840336429d4e', 'http.method', 'GET', 'GET', 10, true, true, '2026-10-27 14:39:23.531448+00', '2026-10-27 14:39:23.531448+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('6d868bf3-837f-46a2-be1a-3055630a9046', 'http.method', 'POST', 'POST', 20, true, true, '2026-10-27 14:39:23.531448+00', '2026-10-27 14:39:23.531448+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('07fe04a0-cc7d-4618-9742-dc7f14170d82', 'http.method', 'PUT', 'PUT', 30, true, true, '2026-10-27 14:39:23.531448+00', '2026-10-27 14:39:23.531448+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('e85604e5-eb2a-4613-942d-6450d60b7625', 'http.method', 'PATCH', 'PATCH', 40, true, true, '2026-10-27 14:39:23.531448+00', '2026-10-27 14:39:23.531448+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('be43ce55-c202-456d-99fa-b61142c6fca3', 'http.method', 'DELETE', 'DELETE', 50, true, true, '2026-10-27 14:39:23.531448+00', '2026-10-27 14:39:23.531448+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('096e8f10-1edb-493b-baad-042e4d1b97df', 'http.method', 'HEAD', 'HEAD', 60, true, true, '2026-10-27 14:39:23.531448+00', '2026-10-27 14:39:23.531448+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('242e1726-4d04-4275-9c6a-1004df1683ac', 'http.method', 'OPTIONS', 'OPTIONS', 70, true, true, '2026-10-27 14:39:23.531448+00', '2026-10-27 14:39:23.531448+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('f2058fbb-46b5-4682-9aee-45cd979d1287', 'tool.risk_level', 'READ', 'READ（只读）', 10, true, true, '2026-10-27 14:39:23.531829+00', '2026-10-27 14:39:23.531829+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('2d20579a-2e9e-489d-b303-283ec82af635', 'tool.risk_level', 'WRITE_PROFILE', 'WRITE_PROFILE（内部写）', 20, true, true, '2026-10-27 14:39:23.531829+00', '2026-10-27 14:39:23.531829+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('727b15fb-1478-4ecd-9c30-5e1921e2562b', 'tool.risk_level', 'WRITE_EXTERNAL', 'WRITE_EXTERNAL（外部写）', 30, true, true, '2026-10-27 14:39:23.531829+00', '2026-10-27 14:39:23.531829+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('a2c1f8c1-5335-4d75-9c14-1b88a9ba5c64', 'agent.orchestration_mode', 'LEAF', 'LEAF（叶子，不再委派）', 10, true, true, '2026-10-27 14:39:23.531988+00', '2026-10-27 14:39:23.531988+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('1da19520-4e92-4270-969c-ccfb3d9c8b92', 'agent.orchestration_mode', 'ROUTER', 'ROUTER（按条件路由）', 20, true, true, '2026-10-27 14:39:23.531988+00', '2026-10-27 14:39:23.531988+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('cc07c110-c82c-443f-a9df-8857eeca662a', 'agent.orchestration_mode', 'SEQUENTIAL', 'SEQUENTIAL（串行执行）', 30, true, true, '2026-10-27 14:39:23.531988+00', '2026-10-27 14:39:23.531988+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('fa8b5286-02e3-4056-95fc-4d90f3eab021', 'agent.failure_policy', 'FAIL_FAST', 'FAIL_FAST（立即失败）', 10, true, true, '2026-10-27 14:39:23.532143+00', '2026-10-27 14:39:23.532143+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('cc9e4868-10ef-4ae5-852a-6b3a2afa74ab', 'agent.failure_policy', 'CONTINUE', 'CONTINUE（继续）', 20, true, true, '2026-10-27 14:39:23.532143+00', '2026-10-27 14:39:23.532143+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('346de825-ad81-42e2-a1ba-d814b5e40c7d', 'agent.failure_policy', 'COMPENSATE', 'COMPENSATE（补偿）', 30, true, true, '2026-10-27 14:39:23.532143+00', '2026-10-27 14:39:23.532143+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('e180750f-dd24-4982-8afb-c6820d05bce0', 'rag.source_type', 'TEXTBOOK', '教材', 10, false, true, '2026-10-27 14:39:23.532276+00', '2026-10-27 14:39:23.532276+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('0b21e1c6-84d6-4782-b35c-ce460f64c051', 'rag.source_type', 'WORKBOOK', '教辅', 20, false, true, '2026-10-27 14:39:23.532276+00', '2026-10-27 14:39:23.532276+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('d7468281-7ce2-47f2-9f71-f28eb56c7348', 'rag.source_type', 'PAPER', '论文', 30, false, true, '2026-10-27 14:39:23.532276+00', '2026-10-27 14:39:23.532276+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('f4f2932c-2e6e-4d52-a67d-06a90c2b7157', 'rag.source_type', 'OTHER', '其他', 90, false, true, '2026-10-27 14:39:23.532276+00', '2026-10-27 14:39:23.532276+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('197baf64-13eb-4289-8e19-437ec42debe9', 'rag.trust_tier', 'OFFICIAL_PUBLISHER', '教材出版社官方', 10, true, true, '2026-10-27 14:39:23.5324+00', '2026-10-27 14:39:23.5324+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('3b847f5e-cfc7-4ea9-88dc-10c411fd6514', 'rag.trust_tier', 'OFFICIAL_TUTORIAL', '教辅品牌正式出版', 20, true, true, '2026-10-27 14:39:23.5324+00', '2026-10-27 14:39:23.5324+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('9d0fc859-0ec7-4d22-b741-7190d2845690', 'rag.trust_tier', 'PEER_REVIEWED_PAPER', '同行评审论文', 30, true, true, '2026-10-27 14:39:23.5324+00', '2026-10-27 14:39:23.5324+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('a58b4f52-27d7-4f2c-a3c4-5c09dc8db0b1', 'rag.trust_tier', 'PREPRINT', '预印本 / 白皮书', 40, true, true, '2026-10-27 14:39:23.5324+00', '2026-10-27 14:39:23.5324+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('b1e0c850-b6e3-4ef8-b118-c71ddffcc1ea', 'rag.trust_tier', 'UNVERIFIED', '未校验', 50, true, true, '2026-10-27 14:39:23.5324+00', '2026-10-27 14:39:23.5324+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('552b8464-ebb9-4472-90ff-cf7efc275bb1', 'rag.verification_method', 'NONE', '无', 10, true, true, '2026-10-27 14:39:23.532542+00', '2026-10-27 14:39:23.532542+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('5c09549b-b0ba-42f6-803e-689abfa4d4ad', 'rag.verification_method', 'ISBN', 'ISBN 校验', 20, true, true, '2026-10-27 14:39:23.532542+00', '2026-10-27 14:39:23.532542+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('dccb625e-ea42-487d-87b2-477e6dabcaf2', 'rag.verification_method', 'DOI', 'DOI 校验', 30, true, true, '2026-10-27 14:39:23.532542+00', '2026-10-27 14:39:23.532542+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('7800cba7-0296-4894-90ca-6da6f24d1d54', 'rag.verification_method', 'MANUAL_REVIEW', '人工逐份比对', 40, true, true, '2026-10-27 14:39:23.532542+00', '2026-10-27 14:39:23.532542+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('6fc0c36e-a36f-44e3-ad51-feb8e9cf92c8', 'rag.verification_method', 'OFFICIAL_HASH', '官方哈希比对', 50, true, true, '2026-10-27 14:39:23.532542+00', '2026-10-27 14:39:23.532542+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('60fcb176-dddc-47b6-ba6b-8ebdf9013cbf', 'rag.license_status', 'LICENSED', '已获授权', 10, true, true, '2026-10-27 14:39:23.532689+00', '2026-10-27 14:39:23.532689+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('e88d4c55-8bfe-4472-a211-54cb6a9ec0da', 'rag.license_status', 'FAIR_USE_QUOTATION', '合理引用', 20, true, true, '2026-10-27 14:39:23.532689+00', '2026-10-27 14:39:23.532689+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('b483c6a2-cf89-4ce8-9749-54c615041b75', 'rag.license_status', 'PENDING', '未取得授权', 30, true, true, '2026-10-27 14:39:23.532689+00', '2026-10-27 14:39:23.532689+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('9c752ee7-1b96-449a-a120-d0d0eb6310e9', 'rag.license_status', 'RESTRICTED', '禁止使用', 40, true, true, '2026-10-27 14:39:23.532689+00', '2026-10-27 14:39:23.532689+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('76ca8286-6d99-4327-a85e-f9af6adbde69', 'rag.subject', 'MATH', '数学', 10, false, true, '2026-10-27 14:39:23.532829+00', '2026-10-27 14:39:23.532829+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('acbd2037-1f30-4854-a94b-f00ecdffe7d7', 'rag.subject', 'CHINESE', '语文', 20, false, true, '2026-10-27 14:39:23.532829+00', '2026-10-27 14:39:23.532829+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('e5305557-580f-47be-a9de-d288e543ced6', 'rag.subject', 'ENGLISH', '英语', 30, false, true, '2026-10-27 14:39:23.532829+00', '2026-10-27 14:39:23.532829+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('658d8c38-d6b2-4b75-8c7c-8779546b511b', 'rag.subject', 'PHYSICS', '物理', 40, false, true, '2026-10-27 14:39:23.532829+00', '2026-10-27 14:39:23.532829+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('44efce7c-e740-4dc9-9708-46d8df3038c3', 'rag.subject', 'CHEMISTRY', '化学', 50, false, true, '2026-10-27 14:39:23.532829+00', '2026-10-27 14:39:23.532829+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('cfdd3930-bf06-4c62-af9f-53fc162c4668', 'rag.subject', 'BIOLOGY', '生物', 60, false, true, '2026-10-27 14:39:23.532829+00', '2026-10-27 14:39:23.532829+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('baf3cdde-d4b7-4a57-a5ed-771cac6c2e4e', 'rag.subject', 'HISTORY', '历史', 70, false, true, '2026-10-27 14:39:23.532829+00', '2026-10-27 14:39:23.532829+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('f1cbe6e9-05a8-4ed3-a1b9-501e8deab7b2', 'rag.subject', 'GEOGRAPHY', '地理', 80, false, true, '2026-10-27 14:39:23.532829+00', '2026-10-27 14:39:23.532829+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('a46b52e4-6bd0-4977-8d02-25534ac341f4', 'rag.subject', 'POLITICS', '道德与法治', 90, false, true, '2026-10-27 14:39:23.532829+00', '2026-10-27 14:39:23.532829+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('c01c44bf-5dda-404a-b0a1-e7fae29feed4', 'rag.grade', 'G7', '七年级', 10, false, true, '2026-10-27 14:39:23.532986+00', '2026-10-27 14:39:23.532986+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('359f0b46-fbf2-4f45-9d41-69051053889b', 'rag.grade', 'G8', '八年级', 20, false, true, '2026-10-27 14:39:23.532986+00', '2026-10-27 14:39:23.532986+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('bbd54091-30c6-432b-a2ff-0e3c0c3a8e49', 'rag.grade', 'G9', '九年级', 30, false, true, '2026-10-27 14:39:23.532986+00', '2026-10-27 14:39:23.532986+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('4740cc7f-ee8d-461b-af5e-0567cea0e470', 'rag.grade', 'G1', '一年级', 40, false, true, '2026-10-27 14:39:23.532986+00', '2026-10-27 14:39:23.532986+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('56f712c2-3f5c-4413-9f4c-103765fe6fb0', 'rag.grade', 'G2', '二年级', 50, false, true, '2026-10-27 14:39:23.532986+00', '2026-10-27 14:39:23.532986+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('df74312e-ff15-4548-a62d-858f3d3ec666', 'rag.grade', 'G3', '三年级', 60, false, true, '2026-10-27 14:39:23.532986+00', '2026-10-27 14:39:23.532986+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('199a8bdf-3010-4367-9cf3-12e68f73a24f', 'rag.grade', 'G4', '四年级', 70, false, true, '2026-10-27 14:39:23.532986+00', '2026-10-27 14:39:23.532986+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('8c5284c2-3621-4bd9-88e9-cdceedf4d8b0', 'rag.grade', 'G5', '五年级', 80, false, true, '2026-10-27 14:39:23.532986+00', '2026-10-27 14:39:23.532986+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('eb5d2452-d435-46b8-8dc6-3e160928971d', 'rag.grade', 'G6', '六年级', 90, false, true, '2026-10-27 14:39:23.532986+00', '2026-10-27 14:39:23.532986+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('59f1e809-6a17-4ab4-9f24-b26b37c4f5e3', 'rag.term', 'FIRST', '上册', 10, false, true, '2026-10-27 14:39:23.533171+00', '2026-10-27 14:39:23.533171+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('46aa5a8a-0ddb-4064-a586-b452da5edcd2', 'rag.term', 'SECOND', '下册', 20, false, true, '2026-10-27 14:39:23.533171+00', '2026-10-27 14:39:23.533171+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('48fe5b4a-2987-4883-a474-0a13d5488c0a', 'rag.term', 'FULL', '全一册', 30, false, true, '2026-10-27 14:39:23.533171+00', '2026-10-27 14:39:23.533171+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('4215f215-6e43-4b66-918a-e5dcb7b11a29', 'rag.publisher', 'RJ', '人教版', 10, false, true, '2026-10-27 14:39:23.533329+00', '2026-10-27 14:39:23.533329+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('a713a142-f0df-4eef-80ef-7471ddbfd4c4', 'rag.publisher', 'BSD', '北师大版', 20, false, true, '2026-10-27 14:39:23.533329+00', '2026-10-27 14:39:23.533329+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('9c3abda0-4af2-4f41-b9ab-f55560b3d2d0', 'rag.publisher', 'SJ', '苏教版', 30, false, true, '2026-10-27 14:39:23.533329+00', '2026-10-27 14:39:23.533329+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('3f3c4a8f-73a1-4e0a-9b21-2323188b0495', 'rag.publisher', 'HJ', '沪教版', 40, false, true, '2026-10-27 14:39:23.533329+00', '2026-10-27 14:39:23.533329+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('a4f4bf6d-8c6d-4ba7-91bd-30acf5420d35', 'rag.publisher', 'ZJ', '浙教版', 50, false, true, '2026-10-27 14:39:23.533329+00', '2026-10-27 14:39:23.533329+00', 0);
INSERT INTO platform.sys_dictionary_item VALUES ('f43f3a45-366d-4396-9e79-b3098a66dc23', 'rag.publisher', 'WY', '外研版', 60, false, true, '2026-10-27 14:39:23.533329+00', '2026-10-27 14:39:23.533329+00', 0);


--
-- Data for Name: tenant; Type: TABLE DATA; Schema: platform; Owner: -
--

-- ⚠️ **本段是手写补录，不是 pg_dump 导出产物**：租户的**平台侧权威行 + 配额**。
--   运行期「这个租户能不能执行」有两处读点：票证闸门读 platform.tenant.lifecycle_state，
--   worker 探针读 public.tenant.status —— 后者是前者在 JdbcPlatformTenantRepository
--   provisionTenant(:150) / transitionLifecycle(:209) 里的**同事务镜像**。
--   本文件原先只灌 public.tenant（下一段），于是空库复放出来的种子租户
--   「业务侧 ACTIVE、平台侧无登记」：worker 认它，取执行票证得 404 TENANT_NOT_FOUND
--   （2026-10-29 实测，见 docs/RAG检索与端到端实测问题记录_20261028.md §9）。
--   这两行按 provisionTenant 的形状补齐；plan 留 NULL（不为演示数据编造计费套餐）。
--   ⚠️ 顺序：platform.tenant 必须在 public.tenant 之前（tenant_quota 外键指向它）。
INSERT INTO platform.tenant (id, name, slug, status, plan, lifecycle_state, created_at, updated_at) VALUES ('2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', '某某教育科技', 't-a', 'ACTIVE', NULL, 'ACTIVE', '2026-10-27 14:40:18.458157+00', '2026-10-27 14:40:18.458157+00');
INSERT INTO platform.tenant_quota (tenant_id, agent_limit, run_concurrency_limit) VALUES ('2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', 50, 10);


--
-- Data for Name: tenant; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.tenant VALUES ('2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', 't-a', '某某教育科技', 'ACTIVE', '2026-10-27 14:40:18.458157+00', '2026-10-27 14:40:18.458157+00');


--
-- Data for Name: agent; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.agent VALUES ('9730eadc-c27c-498d-b588-512545c57f84', '2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', 'math-tutor', '数学辅导 Agent：读取学生档案与课程安排，给出个性化练习建议', '2026-10-27 14:40:18.500564+00', '2026-10-27 14:40:18.500564+00');
INSERT INTO public.agent VALUES ('c8408ea9-5fb3-455a-933c-5c329f2ba0c1', '2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', 'profile-reviewer', '档案复核 Agent：复核并维护学生档案中的关键联系字段', '2026-10-27 14:40:18.500564+00', '2026-10-27 14:40:18.500564+00');
INSERT INTO public.agent VALUES ('26ad3fb1-c047-4382-b01b-b389c9356280', '2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', 'assignment-copilot', '作业协同 Agent：串行委派数学辅导与档案复核，聚合结果向家长通报', '2026-10-27 14:40:18.500564+00', '2026-10-27 14:40:18.500564+00');


--
-- Data for Name: agent_version; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.agent_version VALUES ('ff0b6749-5402-4092-9c14-8374442bf620', '2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', '9730eadc-c27c-498d-b588-512545c57f84', 1, '{"name": "math-tutor", "roles": ["student"], "agentId": "math-tutor", "overrides": {}, "description": "数学辅导 Agent：读取学生档案与课程安排，给出个性化练习建议", "skillBundles": [], "enabledSkills": ["skill-profile-view", "skill-report-brief"], "orchestration": {}, "capabilityTags": ["tutor", "math"]}', '2026-10-27 14:40:18.500564+00', '2026-10-27 14:40:18.500564+00');
INSERT INTO public.agent_version VALUES ('15c361da-d0c8-42fe-825d-11ad2a9f3e2e', '2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', 'c8408ea9-5fb3-455a-933c-5c329f2ba0c1', 1, '{"name": "profile-reviewer", "roles": ["teacher"], "agentId": "profile-reviewer", "overrides": {}, "description": "档案复核 Agent：复核并维护学生档案中的关键联系字段", "skillBundles": [], "enabledSkills": ["skill-profile-edit"], "orchestration": {}, "capabilityTags": ["reviewer", "profile"]}', '2026-10-27 14:40:18.500564+00', '2026-10-27 14:40:18.500564+00');
INSERT INTO public.agent_version VALUES ('09b9c5a1-d45b-4695-914a-15363e5b332c', '2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', '26ad3fb1-c047-4382-b01b-b389c9356280', 1, '{"name": "assignment-copilot", "roles": ["student", "teacher"], "agentId": "assignment-copilot", "overrides": {}, "description": "作业协同 Agent：串行委派数学辅导与档案复核，聚合结果向家长通报", "skillBundles": [], "enabledSkills": ["skill-profile-view"], "orchestration": {"mode": "SEQUENTIAL", "timeoutMs": 60000, "failurePolicy": "FAIL_FAST", "childAgentRefs": [{"id": "math-tutor", "version": 1, "required": true}, {"id": "profile-reviewer", "version": 1, "required": true}], "maxDelegationDepth": 2, "maxChildInvocations": 3}, "capabilityTags": ["copilot", "orchestrator"]}', '2026-10-27 14:40:18.500564+00', '2026-10-27 14:40:18.500564+00');


--
-- Data for Name: budget_account; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.budget_account VALUES ('40cd0917-c12a-46a3-a6af-a9c4e0233f1b', '2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', 'CNY', 100000.000000, 0.000000, 0.000000, '2026-10-27 14:40:18.45903+00', '2026-10-27 14:40:18.45903+00');


--
-- Data for Name: cap_agent_def; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.cap_agent_def VALUES ('2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', '9730eadc-c27c-498d-b588-512545c57f84', 1, 'math-tutor', '数学辅导 Agent：读取学生档案与课程安排，给出个性化练习建议', '["student"]', '["skill-profile-view", "skill-report-brief"]', '[]', '["tutor", "math"]', NULL, '{}', '543c7630542dd4de28d4d8c3c481596b0fdd1564c63260a1936685b4d0bb55e2', 1, '2026-10-27 14:40:18.500564+00', '2026-10-27 14:40:18.500564+00', '2026-10-27 14:40:18.500564+00', 'PUBLISHED', '{}');
INSERT INTO public.cap_agent_def VALUES ('2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', 'c8408ea9-5fb3-455a-933c-5c329f2ba0c1', 1, 'profile-reviewer', '档案复核 Agent：复核并维护学生档案中的关键联系字段', '["teacher"]', '["skill-profile-edit"]', '[]', '["reviewer", "profile"]', NULL, '{}', '39f9ab6484d481ed1366510baafd7cfcab6e90704e04eeec9163152a495ff044', 1, '2026-10-27 14:40:18.500564+00', '2026-10-27 14:40:18.500564+00', '2026-10-27 14:40:18.500564+00', 'PUBLISHED', '{}');
INSERT INTO public.cap_agent_def VALUES ('2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', '26ad3fb1-c047-4382-b01b-b389c9356280', 1, 'assignment-copilot', '作业协同 Agent：串行委派数学辅导与档案复核，聚合结果向家长通报', '["student", "teacher"]', '["skill-profile-view"]', '[]', '["copilot", "orchestrator"]', NULL, '{}', '76a427eec5a3932a5eea073d4f6c83b3aee2c3929a105e4370c12799b6fe37d0', 1, '2026-10-27 14:40:18.500564+00', '2026-10-27 14:40:18.500564+00', '2026-10-27 14:40:18.500564+00', 'PUBLISHED', '{"mode": "SEQUENTIAL", "timeoutMs": 60000, "failurePolicy": "FAIL_FAST", "childAgentRefs": [{"id": "math-tutor", "version": 1, "required": true}, {"id": "profile-reviewer", "version": 1, "required": true}], "maxDelegationDepth": 2, "maxChildInvocations": 3}');


--
-- Data for Name: cap_agent_child_ref; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.cap_agent_child_ref VALUES ('2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', '26ad3fb1-c047-4382-b01b-b389c9356280', 1, '9730eadc-c27c-498d-b588-512545c57f84', 1, 0, true, '2026-10-27 14:40:18.500564+00');
INSERT INTO public.cap_agent_child_ref VALUES ('2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', '26ad3fb1-c047-4382-b01b-b389c9356280', 1, 'c8408ea9-5fb3-455a-933c-5c329f2ba0c1', 1, 1, true, '2026-10-27 14:40:18.500564+00');


--
-- Data for Name: cap_mcp_server_def; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.cap_mcp_server_def VALUES ('mcp-student-profile', 1, 'http', 'https://mcp-profile.internal-studykid.cn/mcp', NULL, 'token', 'internal', '["lookupProfile", "lookupGuardian"]', 'READ', false, '2d7008948ae245ffbf55bec7369e587ab92e4c422de2957a01218123fcda22fb', 'vault://kv/platform/mcp-student-profile#api_token', 1, '2026-10-27 14:40:18.497452+00', '2026-10-27 14:40:18.497452+00', '2026-10-27 14:40:18.497452+00', 'PUBLISHED');
INSERT INTO public.cap_mcp_server_def VALUES ('mcp-course-calendar', 1, 'http', 'https://mcp-calendar.partner-studykid.cn/mcp', NULL, 'oauth', 'partner', '["getSchedule", "getEvents"]', 'READ', false, 'f09914a41a3f5d26e6c01a99a337db5a8b3adc48dbc14a0d0432e2bea03872c6', 'vault://kv/platform/mcp-course-calendar#oauth_client', 1, '2026-10-27 14:40:18.497452+00', '2026-10-27 14:40:18.497452+00', '2026-10-27 14:40:18.497452+00', 'PUBLISHED');


--
-- Data for Name: cap_result_contract; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: cap_routing_policy; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: cap_skill_def; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.cap_skill_def VALUES ('skill-profile-view', 1, '档案查看', '查看学生档案与监护人信息（只读）', '["tool-profile-read"]', '[]', 'READ', '[]', NULL, '[]', 'platform', 'a50797d8b7deeb715a8d2ec8dd87b563148432427b1d964b500ae7a216b47a8c', 1, '2026-10-27 14:40:18.498745+00', '2026-10-27 14:40:18.498745+00', '2026-10-27 14:40:18.498745+00', 'PUBLISHED', NULL, NULL, NULL);
INSERT INTO public.cap_skill_def VALUES ('skill-profile-edit', 1, '档案维护', '查看并更新学生档案（含写档案字段，需走审批链路）', '["tool-profile-read", "tool-profile-update"]', '[]', 'WRITE_PROFILE', '[]', NULL, '[]', 'platform', '78d9ca9d24303101ff296caa78c6bebb6344f26011024177d6adba0f0be5739f', 1, '2026-10-27 14:40:18.498745+00', '2026-10-27 14:40:18.498745+00', '2026-10-27 14:40:18.498745+00', 'PUBLISHED', NULL, NULL, NULL);
INSERT INTO public.cap_skill_def VALUES ('skill-report-brief', 1, '成绩简报', '拉取成绩报告并经 MCP 校对档案（含 MCP 绑定 Tool）', '["tool-grade-report-fetch", "tool-mcp-profile-lookup"]', '[]', 'READ', '[]', NULL, '[]', 'platform', 'e234f9dbcfb5a77b7c3664da9086036a382ef3bcaed06b156075f1c6a9e48529', 1, '2026-10-27 14:40:18.498745+00', '2026-10-27 14:40:18.498745+00', '2026-10-27 14:40:18.498745+00', 'PUBLISHED', NULL, NULL, NULL);
INSERT INTO public.cap_skill_def VALUES ('skill-include-guardian', 1, '家长协同', '查询课程安排并向家长发送提示（声明 MCP 直连 + WRITE_EXTERNAL）', '[]', '[{"serverId": "mcp-course-calendar"}]', 'WRITE_EXTERNAL', '[{"op": "lte", "param": "maxRecipients", "value": 50}]', NULL, '[]', 'platform', '63b483d3ba3b41b39518e8c07834357bea3d6289a2621586ffe44a46dc7d8667', 1, '2026-10-27 14:40:18.498745+00', '2026-10-27 14:40:18.498745+00', '2026-10-27 14:40:18.498745+00', 'PUBLISHED', NULL, NULL, NULL);


--
-- Data for Name: cap_tool_def; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.cap_tool_def VALUES ('tool-profile-read', 1, '档案读取', 'native', 'READ', NULL, NULL, '{"type": "object", "required": ["studentId"], "properties": {"studentId": {"type": "string", "description": "学生编码"}}}', '[]', 'platform', 'a988ed39b07ccb47fc8532129255f6a8bbaa87e7edcad72066ed7d64b815e4c7', 1, '2026-10-27 14:40:18.498277+00', '2026-10-27 14:40:18.498277+00', '2026-10-27 14:40:18.498277+00', 'PUBLISHED', NULL, NULL, NULL);
INSERT INTO public.cap_tool_def VALUES ('tool-profile-update', 1, '档案修改', 'native', 'WRITE_PROFILE', NULL, NULL, '{"type": "object", "required": ["studentId", "field", "value"], "properties": {"field": {"type": "string", "description": "允许更新的档案字段"}, "value": {"type": "string"}, "studentId": {"type": "string"}}}', '[{"op": "in_allowlist", "param": "field", "value": ["guardianPhone"]}]', 'platform', '538bd82cdf17ae34d68679ea5555b8840c702c9361fd6f3b0d3fc70f162b1f65', 1, '2026-10-27 14:40:18.498277+00', '2026-10-27 14:40:18.498277+00', '2026-10-27 14:40:18.498277+00', 'PUBLISHED', NULL, NULL, NULL);
INSERT INTO public.cap_tool_def VALUES ('tool-grade-report-fetch', 1, '成绩报告拉取', 'http', 'READ', NULL, NULL, '{"type": "object", "required": ["studentId"], "properties": {"studentId": {"type": "string"}}}', '[]', 'platform', 'ada565453ddee41b251aa857067570f28ab5373759b7317d5daa0b811b14f88c', 1, '2026-10-27 14:40:18.498277+00', '2026-10-27 14:40:18.498277+00', '2026-10-27 14:40:18.498277+00', 'PUBLISHED', 'GET', 'https://reports.internal-studykid.cn/api/v1/grade-reports', 3000);
INSERT INTO public.cap_tool_def VALUES ('tool-parent-notification', 1, '家长通知发送', 'http', 'WRITE_EXTERNAL', NULL, NULL, '{"type": "object", "required": ["studentId", "message"], "properties": {"message": {"type": "string", "maxLength": 500}, "studentId": {"type": "string"}}}', '[]', 'platform', '5a750e7f24334857104fce760b50913faeac42ea340ea0fb01083eaeddf563b8', 1, '2026-10-27 14:40:18.498277+00', '2026-10-27 14:40:18.498277+00', '2026-10-27 14:40:18.498277+00', 'PUBLISHED', 'POST', 'https://notify-gateway.internal-studykid.cn/api/v1/messages', 5000);
INSERT INTO public.cap_tool_def VALUES ('tool-mcp-profile-lookup', 1, '档案查询(MCP)', 'mcp', 'READ', 'mcp-student-profile', 'lookupProfile', '{"type": "object", "required": ["studentId"], "properties": {"studentId": {"type": "string"}}}', '[]', 'platform', '6818e52b18c0bb5e78ac7860a14ba9b6a15fac5cf9b283666f156bc147e574c4', 1, '2026-10-27 14:40:18.498277+00', '2026-10-27 14:40:18.498277+00', '2026-10-27 14:40:18.498277+00', 'PUBLISHED', NULL, NULL, NULL);
INSERT INTO public.cap_tool_def VALUES ('tool-mcp-schedule', 1, '课程表查询(MCP)', 'mcp', 'READ', 'mcp-course-calendar', 'getSchedule', '{"type": "object", "required": ["studentId"], "properties": {"date": {"type": "string", "description": "yyyy-MM-dd"}, "studentId": {"type": "string"}}}', '[]', 'platform', '14dac999e566a05b25ddd5f850c3f2e96699b291fd8c8ca5f6484774dc427c95', 1, '2026-10-27 14:40:18.498277+00', '2026-10-27 14:40:18.498277+00', '2026-10-27 14:40:18.498277+00', 'PUBLISHED', NULL, NULL, NULL);


--
-- PostgreSQL database dump complete
--

\unrestrict 41TLVhigOe5lQmRdbPsgLPeZN8wrjMog2DCAZIV1YKh5a6eYzhYlxffWYxZ5eyi


-- =====================================================================
-- 【附录 · 非 pg_dump 内容】能力目录的「发布头 + 当前指针」
--
-- 为什么放在种子里：定义表里的 lifecycle_state='PUBLISHED' **不等于运行期可用**。
-- 运行期只按 指针 → 发布头 → object_versions → id@精确版本 解析
-- （JdbcPublishedVersionPort.resolveFromPointer）。只灌定义的种子会留下
-- 「目录有 14 个已发布对象、发布头只有 2 个、TENANT 指针一条都没有」的库，
-- 于是除 e2e-minimax 之外的每个 Agent 在双写打开后都算不出闭包 →
-- 该 Run 没有能力快照 → 执行准入判 CONFIG_UNAVAILABLE（2026-10-29 现场）。
-- 这一段与 deploy/sql/platform/patch_cap_release_head.sql **同源**，
-- 差异只在头部注释；**两份必须一起改**（另见该文件的「指纹为什么在 SQL 里逐字拼」）。
-- 对本文件的影响：前面是 pg_dump 导出，重导会丢这一段 —— 复放后请核对
-- cap_config_pointer 有 PLATFORM 行且其发布头对象数 = 目录已发布对象数。
-- 执行身份同 patch：须为库 owner（platform_ops 缺序列 UPDATE，实测报
-- permission denied for sequence cap_config_publish_seq）。
-- 幂等：按 aggregate_fingerprint 判等，重复执行写 0 行。
-- =====================================================================

-- ── 0. 校正发布序号序列（本补丁的前置，实测必需）───────────────────
-- ⚠️ 现场：cap_config_release 已有 config_publish_seq=1 的行（release_id=4），
--    而序列 cap_config_publish_seq 的 last_value 也还是 1 ——
--    说明那条手写发布头**没有从序列取号**（§6.3 第 2 条要求序号由序列分配）。
--    不校正的话本补丁第一次 nextval 就撞 unique 约束（dry-run 实测报错）。
--    这里只把序列**追到已有行的最大值**，不改任何历史行、不回拨、不跳号复用。
SELECT setval('public.cap_config_publish_seq',
              (SELECT coalesce(max(config_publish_seq), 0) FROM public.cap_config_release),
              true);

-- ── 1. PLATFORM 发布头：覆盖目录里全部已发布平台对象 ──────────────────
INSERT INTO public.cap_config_release
    (config_publish_seq, scope, tenant_id, object_versions, aggregate_fingerprint,
     status, requester_id, approver_id, effective_at, created_at)
SELECT nextval('public.cap_config_publish_seq'), 'PLATFORM', NULL,
       d.json_text::jsonb,
       'cfg_agg_v1:' || encode(sha256(d.json_text::bytea), 'hex'),
       'PUBLISHED', 'seed-replay', NULL, now(), now()
FROM (
    SELECT '[' || string_agg(
               '{"type":"' || o.object_type || '","id":"' || o.id
                   || '","version":' || o.version || '}',
               ',' ORDER BY o.object_type, o.id, o.version) || ']' AS json_text
    FROM (
        SELECT 'mcp_server' AS object_type, id, max(version) AS version
          FROM public.cap_mcp_server_def
         WHERE lifecycle_state = 'PUBLISHED' GROUP BY id
        UNION ALL
        SELECT 'skill', id, max(version)
          FROM public.cap_skill_def
         WHERE lifecycle_state = 'PUBLISHED' GROUP BY id
        UNION ALL
        SELECT 'tool', id, max(version)
          FROM public.cap_tool_def
         WHERE lifecycle_state = 'PUBLISHED' GROUP BY id
    ) o
    WHERE o.id ~ '^[A-Za-z0-9_.:@-]+$'
) d
WHERE d.json_text IS NOT NULL
  AND NOT EXISTS (
        SELECT 1
        FROM public.cap_config_pointer p
        JOIN public.cap_config_release r ON r.release_id = p.current_release_id
        WHERE p.pointer_key = 'PLATFORM'
          AND r.aggregate_fingerprint =
              'cfg_agg_v1:' || encode(sha256(d.json_text::bytea), 'hex'));

-- ── 2. PLATFORM 指针：缺失则创建 ──────────────────────────────────────
-- ⚠️ 空库复放时这一步是**必需**的：deploy/sql 下没有任何脚本写过 cap_config_pointer，
--    开发库那条 PLATFORM 行是应用首次发布时 JdbcConfigPointerRepository
--    的 INSERT ... ON CONFLICT DO NOTHING 惰性建出来的。
--    只靠下一步的 UPDATE，在干净库里会「发布头写成功、指针 0 行」→
--    运行期照旧 RESOURCE_UNKNOWN。
INSERT INTO public.cap_config_pointer
    (pointer_key, scope, tenant_id, current_release_id, current_publish_seq, updated_at)
SELECT 'PLATFORM', 'PLATFORM', NULL, h.release_id, h.config_publish_seq, now()
FROM (
        SELECT r.release_id, r.config_publish_seq
        FROM public.cap_config_release r
        WHERE r.scope = 'PLATFORM' AND r.status = 'PUBLISHED'
        ORDER BY r.config_publish_seq DESC
        LIMIT 1
) h
WHERE NOT EXISTS (SELECT 1 FROM public.cap_config_pointer p
                   WHERE p.pointer_key = 'PLATFORM');

-- ── 3. PLATFORM 指针：CAS 到内容等于当前目录的那份发布头 ─────────────
UPDATE public.cap_config_pointer p
   SET current_release_id    = h.release_id,
       current_publish_seq   = h.config_publish_seq,
       updated_at            = now()
  FROM (
        SELECT r.release_id, r.config_publish_seq
        FROM public.cap_config_release r
        WHERE r.scope = 'PLATFORM'
          AND r.status = 'PUBLISHED'
          AND r.aggregate_fingerprint = (
              SELECT 'cfg_agg_v1:' || encode(sha256(
                       ('[' || string_agg(
                            '{"type":"' || o.object_type || '","id":"' || o.id
                                || '","version":' || o.version || '}',
                            ',' ORDER BY o.object_type, o.id, o.version) || ']')::bytea),
                       'hex')
              FROM (
                  SELECT 'mcp_server' AS object_type, id, max(version) AS version
                    FROM public.cap_mcp_server_def
                   WHERE lifecycle_state = 'PUBLISHED' GROUP BY id
                  UNION ALL
                  SELECT 'skill', id, max(version)
                    FROM public.cap_skill_def
                   WHERE lifecycle_state = 'PUBLISHED' GROUP BY id
                  UNION ALL
                  SELECT 'tool', id, max(version)
                    FROM public.cap_tool_def
                   WHERE lifecycle_state = 'PUBLISHED' GROUP BY id
              ) o
              WHERE o.id ~ '^[A-Za-z0-9_.:@-]+$')
        ORDER BY r.config_publish_seq DESC
        LIMIT 1
  ) h
 WHERE p.pointer_key = 'PLATFORM'
   AND p.current_release_id IS DISTINCT FROM h.release_id;

-- ── 4. TENANT 发布头：每个有已发布 Agent 的租户一份（按该租户全部已发布 Agent）──
INSERT INTO public.cap_config_release
    (config_publish_seq, scope, tenant_id, object_versions, aggregate_fingerprint,
     status, requester_id, approver_id, effective_at, created_at)
SELECT nextval('public.cap_config_publish_seq'), 'TENANT', a.tenant_id,
       a.json_text::jsonb,
       'cfg_agg_v1:' || encode(sha256(a.json_text::bytea), 'hex'),
       'PUBLISHED', 'seed-replay', NULL, now(), now()
FROM (
    SELECT g.tenant_id,
           '[' || string_agg(
                      '{"type":"agent","id":"' || g.id || '","version":' || g.version || '}',
                      ',' ORDER BY g.id, g.version) || ']' AS json_text
    FROM (
        SELECT tenant_id, agent_id::text AS id, max(version) AS version
          FROM public.cap_agent_def
         WHERE lifecycle_state = 'PUBLISHED'
         GROUP BY tenant_id, agent_id
    ) g
    WHERE g.id ~ '^[A-Za-z0-9_.:@-]+$'
    GROUP BY g.tenant_id
) a
WHERE NOT EXISTS (
        SELECT 1
        FROM public.cap_config_pointer p
        JOIN public.cap_config_release r ON r.release_id = p.current_release_id
        WHERE p.pointer_key = 'TENANT:' || a.tenant_id::text
          AND r.aggregate_fingerprint =
              'cfg_agg_v1:' || encode(sha256(a.json_text::bytea), 'hex'));

-- ── 5. 每个租户一行 TENANT 指针（缺失则插入，已存在则 CAS）──────────────
INSERT INTO public.cap_config_pointer
    (pointer_key, scope, tenant_id, current_release_id, current_publish_seq, updated_at)
SELECT 'TENANT:' || t.tenant_id::text, 'TENANT', t.tenant_id,
       h.release_id, h.config_publish_seq, now()
FROM (SELECT DISTINCT tenant_id FROM public.cap_agent_def
       WHERE lifecycle_state = 'PUBLISHED') t
JOIN LATERAL (
        SELECT r.release_id, r.config_publish_seq
        FROM public.cap_config_release r
        WHERE r.scope = 'TENANT' AND r.status = 'PUBLISHED' AND r.tenant_id = t.tenant_id
          AND r.aggregate_fingerprint = (
              SELECT 'cfg_agg_v1:' || encode(sha256(
                       ('[' || string_agg(
                            '{"type":"agent","id":"' || g.id || '","version":' || g.version || '}',
                            ',' ORDER BY g.id, g.version) || ']')::bytea), 'hex')
              FROM (SELECT agent_id::text AS id, max(version) AS version
                      FROM public.cap_agent_def
                     WHERE lifecycle_state = 'PUBLISHED' AND tenant_id = t.tenant_id
                     GROUP BY agent_id) g
              WHERE g.id ~ '^[A-Za-z0-9_.:@-]+$')
        ORDER BY r.config_publish_seq DESC
        LIMIT 1
) h ON true
WHERE NOT EXISTS (SELECT 1 FROM public.cap_config_pointer p
                   WHERE p.pointer_key = 'TENANT:' || t.tenant_id::text);

UPDATE public.cap_config_pointer p
   SET current_release_id  = h.release_id,
       current_publish_seq = h.config_publish_seq,
       updated_at          = now()
  FROM (
        SELECT r.tenant_id, r.release_id, r.config_publish_seq
        FROM public.cap_config_release r
        WHERE r.scope = 'TENANT' AND r.status = 'PUBLISHED'
          AND r.aggregate_fingerprint = (
              SELECT 'cfg_agg_v1:' || encode(sha256(
                       ('[' || string_agg(
                            '{"type":"agent","id":"' || g.id || '","version":' || g.version || '}',
                            ',' ORDER BY g.id, g.version) || ']')::bytea), 'hex')
              FROM (SELECT agent_id::text AS id, max(version) AS version
                      FROM public.cap_agent_def
                     WHERE lifecycle_state = 'PUBLISHED' AND tenant_id = r.tenant_id
                     GROUP BY agent_id) g
              WHERE g.id ~ '^[A-Za-z0-9_.:@-]+$')
          AND r.config_publish_seq = (
              SELECT max(r2.config_publish_seq) FROM public.cap_config_release r2
               WHERE r2.scope = 'TENANT' AND r2.status = 'PUBLISHED'
                 AND r2.tenant_id = r.tenant_id
                 AND r2.aggregate_fingerprint = r.aggregate_fingerprint)
  ) h
 WHERE p.pointer_key = 'TENANT:' || h.tenant_id::text
   AND p.current_release_id IS DISTINCT FROM h.release_id;

