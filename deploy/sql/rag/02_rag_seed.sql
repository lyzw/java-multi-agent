-- =====================================================================
-- rag 种子数据（pg_dump --data-only --inserts 导出）
-- 【来源】由 02_rag_seed / 04_rag_seed_global_dictionary 灌入基准库后导出，空库复放验证通过。
-- 【内容】全局字典 4 条 SOURCE_TYPE 项 + 演示语料（来源 3 / 载体 7 / 片段 8 / 版本 3）
-- 【租户】tenant_id 为基准库 t-a 的实际 UUID，与 platform 种子中的租户行一致
-- =====================================================================

--
-- PostgreSQL database dump
--

\restrict HIGJs39kH11ZqGM9QVPg3K7ztsfWXPIdRRrahRLmHG7pJdrFYiJSpkx8t3GO99m

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
-- Data for Name: rag_source; Type: TABLE DATA; Schema: rag; Owner: -
--

INSERT INTO rag.rag_source VALUES ('9a1c0000-0000-4000-8000-000000000001', '2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', 'TEXTBOOK', '演示教材·八年级数学上册（人教版）', '人教版', '978-7-107-88888-1', NULL, 'OFFICIAL_PUBLISHER', 'VERIFIED', 'ISBN', '演示用 ISBN 校验记录', '2026-10-27 14:41:03.073488+00', NULL, 365, 'LICENSED', NULL, 'dcf4ee8b7b2827cc45a6d11d4f5a2bf4dcf4ee8b7b2827cc45a6d11d4f5a2bf4', '2026-10-27 14:41:03.073488+00', '2026-10-27 14:41:03.073488+00', NULL, NULL, NULL, NULL, 'TENANT');
INSERT INTO rag.rag_source VALUES ('9a1c0000-0000-4000-8000-000000000002', '2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', 'TEXTBOOK', '演示教材·九年级数学下册（苏教版·待审核）', '苏教版', NULL, NULL, 'UNVERIFIED', 'PENDING', 'NONE', NULL, NULL, NULL, 365, 'PENDING', NULL, 'e5bb1efa03275cb3ea92ff42f18e1b4fe5bb1efa03275cb3ea92ff42f18e1b4f', '2026-10-27 14:41:03.073488+00', '2026-10-27 14:41:03.073488+00', NULL, NULL, NULL, NULL, 'TENANT');
INSERT INTO rag.rag_source VALUES ('9a1c0000-0000-4000-8000-000000000003', '2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', 'TEXTBOOK', '演示教材·七年级数学上册（来源已撤回）', '人教版', NULL, NULL, 'UNVERIFIED', 'REVOKED', 'MANUAL_REVIEW', NULL, NULL, NULL, 365, 'RESTRICTED', NULL, 'b7ffab4c4b726ead37af6a3028cba13eb7ffab4c4b726ead37af6a3028cba13e', '2026-10-27 14:41:03.073488+00', '2026-10-27 14:41:03.073488+00', NULL, NULL, '版权方撤回，停止对外提供', '2026-10-27 14:41:03.073488+00', 'TENANT');


--
-- Data for Name: rag_edition; Type: TABLE DATA; Schema: rag; Owner: -
--

INSERT INTO rag.rag_edition VALUES ('9a1c0000-0000-4000-9000-000000000001', '2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', '9a1c0000-0000-4000-8000-000000000001', '数学', '八年级', '上册', '人教版', '2024', '义务教育教科书·数学·八年级上册', '2024-08-01', NULL, '2026-10-27 14:41:03.073488+00', 'TENANT');
INSERT INTO rag.rag_edition VALUES ('9a1c0000-0000-4000-9000-000000000002', '2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', '9a1c0000-0000-4000-8000-000000000002', '数学', '九年级', '下册', '苏教版', '2024', '义务教育教科书·数学·九年级下册', '2024-08-01', NULL, '2026-10-27 14:41:03.073488+00', 'TENANT');
INSERT INTO rag.rag_edition VALUES ('9a1c0000-0000-4000-9000-000000000003', '2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', '9a1c0000-0000-4000-8000-000000000003', '数学', '七年级', '上册', '人教版', '2023', '义务教育教科书·数学·七年级上册', '2023-08-01', NULL, '2026-10-27 14:41:03.073488+00', 'TENANT');


--
-- Data for Name: rag_document; Type: TABLE DATA; Schema: rag; Owner: -
--

INSERT INTO rag.rag_document VALUES ('9a1c0000-0000-4000-a000-000000000001', '2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', '9a1c0000-0000-4000-8000-000000000001', '9a1c0000-0000-4000-9000-000000000001', 'EPUB', '八年级上册·第一章 官方电子版', 42, 's3://demo/8a-1.epub', 'CHUNKED', 'demo-parser-1', 'ee1f2ac0237e69e6b3c25d1090dbe2e9ee1f2ac0237e69e6b3c25d1090dbe2e9', '2026-10-27 14:41:03.073488+00', '2026-10-27 14:41:03.073488+00', 'VERIFIED', true, NULL, NULL, 'LICENSED', 'TENANT');
INSERT INTO rag.rag_document VALUES ('9a1c0000-0000-4000-a000-000000000002', '2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', '9a1c0000-0000-4000-8000-000000000001', '9a1c0000-0000-4000-9000-000000000001', 'SCAN', '八年级上册·第一章 扫描版', 42, 's3://demo/8a-1-scan.pdf', 'CHUNKED', 'demo-parser-1', 'be22c1a412cfdfd65cad0a8776e826f5be22c1a412cfdfd65cad0a8776e826f5', '2026-10-27 14:41:03.073488+00', '2026-10-27 14:41:03.073488+00', 'IN_REVIEW', false, NULL, NULL, 'PENDING', 'TENANT');
INSERT INTO rag.rag_document VALUES ('9a1c0000-0000-4000-a000-000000000003', '2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', '9a1c0000-0000-4000-8000-000000000001', '9a1c0000-0000-4000-9000-000000000001', 'PDF', '八年级上册·第二章 官方电子版（缺向量待补）', 38, 's3://demo/8a-2.pdf', 'CHUNKED', 'demo-parser-1', '3ab249502d832b03d6290d22d7c0ed913ab249502d832b03d6290d22d7c0ed91', '2026-10-27 14:41:03.073488+00', '2026-10-27 14:41:03.073488+00', 'VERIFIED', true, NULL, NULL, 'LICENSED', 'TENANT');
INSERT INTO rag.rag_document VALUES ('9a1c0000-0000-4000-a000-000000000004', '2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', '9a1c0000-0000-4000-8000-000000000001', '9a1c0000-0000-4000-9000-000000000001', 'PDF', '八年级上册·附录 解析失败', 6, 's3://demo/8a-appendix.pdf', 'FAILED', 'demo-parser-1', '39faf1b063dfc90a0840db739e1df89339faf1b063dfc90a0840db739e1df893', '2026-10-27 14:41:03.073488+00', '2026-10-27 14:41:03.073488+00', 'PENDING', false, NULL, NULL, 'PENDING', 'TENANT');
INSERT INTO rag.rag_document VALUES ('9a1c0000-0000-4000-a000-000000000005', '2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', '9a1c0000-0000-4000-8000-000000000001', '9a1c0000-0000-4000-9000-000000000001', 'PDF', '八年级上册·封面 刚入库待审', 2, 's3://demo/8a-cover.pdf', 'PENDING', NULL, '3e61d9db60b50f3632dfb259a99ca21b3e61d9db60b50f3632dfb259a99ca21b', '2026-10-27 14:41:03.073488+00', '2026-10-27 14:41:03.073488+00', 'PENDING', false, NULL, NULL, 'PENDING', 'TENANT');
INSERT INTO rag.rag_document VALUES ('9a1c0000-0000-4000-a000-000000000006', '2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', '9a1c0000-0000-4000-8000-000000000002', '9a1c0000-0000-4000-9000-000000000002', 'EPUB', '九年级下册·第一章 官方电子版（来源待审）', 40, 's3://demo/9b-1.epub', 'CHUNKED', 'demo-parser-1', '83c7bae7edcb81e002c36fd495e6375b83c7bae7edcb81e002c36fd495e6375b', '2026-10-27 14:41:03.073488+00', '2026-10-27 14:41:03.073488+00', 'PENDING', false, NULL, NULL, 'PENDING', 'TENANT');
INSERT INTO rag.rag_document VALUES ('9a1c0000-0000-4000-a000-000000000007', '2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', '9a1c0000-0000-4000-8000-000000000003', '9a1c0000-0000-4000-9000-000000000003', 'EPUB', '七年级上册·第一章 官方电子版（已撤回）', 36, 's3://demo/7a-1.epub', 'CHUNKED', 'demo-parser-1', '4266cad992cf68f25b09297130e7102e4266cad992cf68f25b09297130e7102e', '2026-10-27 14:41:03.073488+00', '2026-10-27 14:41:03.073488+00', 'REVOKED', false, '2026-10-27 14:41:03.073488+00', '演示：载体级撤回（仅置 retrievable=false，检索期未按该字段过滤）', 'RESTRICTED', 'TENANT');


--
-- Data for Name: rag_chunk; Type: TABLE DATA; Schema: rag; Owner: -
--

INSERT INTO rag.rag_chunk VALUES ('9a1c0000-0000-4000-b000-000000000001', '2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', '9a1c0000-0000-4000-a000-000000000001', '9a1c0000-0000-4000-9000-000000000001', 'EXPLANATION', '一元一次方程的概念：只含一个未知数，并且未知数的次数是 1 的整式方程。', 12, '第一章/1.1 一元一次方程', '例1', 0, 41, '{}', NULL, 'd315e3cf44de105be6be1b2cbe4e5082d315e3cf44de105be6be1b2cbe4e5082', NULL, 1, '2026-10-27 14:41:03.073488+00', '', 'TENANT');
INSERT INTO rag.rag_chunk VALUES ('9a1c0000-0000-4000-b000-000000000002', '2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', '9a1c0000-0000-4000-a000-000000000001', '9a1c0000-0000-4000-9000-000000000001', 'EXERCISE', '练习：解下列一元一次方程 3x + 5 = 20，写出解方程的三个步骤。', 13, '第一章/1.1 一元一次方程', '练习1', 0, 38, '{}', NULL, '38255d93d9a5997af75c2f2ba1c2afef38255d93d9a5997af75c2f2ba1c2afef', NULL, 1, '2026-10-27 14:41:03.073488+00', '', 'TENANT');
INSERT INTO rag.rag_chunk VALUES ('9a1c0000-0000-4000-b000-000000000003', '2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', '9a1c0000-0000-4000-a000-000000000001', '9a1c0000-0000-4000-9000-000000000001', 'PAPER_BODY', '第一章 一元一次方程：本章学习用字母表示数，列出一元一次方程并求解。', 11, '第一章/章前语', NULL, 0, 35, '{}', NULL, '7a24f9dc16d2fc6b999fe32d0ad05ff17a24f9dc16d2fc6b999fe32d0ad05ff1', NULL, 1, '2026-10-27 14:41:03.073488+00', '', 'TENANT');
INSERT INTO rag.rag_chunk VALUES ('9a1c0000-0000-4000-b000-000000000004', '2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', '9a1c0000-0000-4000-a000-000000000002', '9a1c0000-0000-4000-9000-000000000001', 'EXPLANATION', '扫描版说明：等式两边同时加上同一个数，等式仍然成立。', 12, '第一章/1.2 等式的性质', '例2', 0, 28, '{}', NULL, 'c1c8a88df40ea1931a514df8d3e1db66c1c8a88df40ea1931a514df8d3e1db66', NULL, 1, '2026-10-27 14:41:03.073488+00', '', 'TENANT');
INSERT INTO rag.rag_chunk VALUES ('9a1c0000-0000-4000-b000-000000000005', '2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', '9a1c0000-0000-4000-a000-000000000003', '9a1c0000-0000-4000-9000-000000000001', 'EXPLANATION', '去括号法则：括号前是负号时，去掉括号后各项都要变号。', 20, '第二章/2.1 去括号', '例3', 0, 30, '{}', NULL, 'd15aaf6983c15e7a470c459cd936516ed15aaf6983c15e7a470c459cd936516e', NULL, 1, '2026-10-27 14:41:03.073488+00', '', 'TENANT');
INSERT INTO rag.rag_chunk VALUES ('9a1c0000-0000-4000-b000-000000000006', '2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', '9a1c0000-0000-4000-a000-000000000003', '9a1c0000-0000-4000-9000-000000000001', 'EXERCISE', '练习：解方程 2(x - 3) = 10，注意去括号时变号。', 21, '第二章/2.1 去括号', '练习2', 0, 29, '{}', NULL, '4a3507ebe0cd5f6fb981d59f908a44e94a3507ebe0cd5f6fb981d59f908a44e9', NULL, 1, '2026-10-27 14:41:03.073488+00', '', 'TENANT');
INSERT INTO rag.rag_chunk VALUES ('9a1c0000-0000-4000-b000-000000000007', '2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', '9a1c0000-0000-4000-a000-000000000006', '9a1c0000-0000-4000-9000-000000000002', 'EXPLANATION', '二次函数的图像是抛物线，顶点坐标决定开口方向与对称轴。', 15, '第二章/2.2 二次函数', '例4', 0, 31, '{}', NULL, '4cbe3e7be37e20aaf7c73ba88ed2ef5d4cbe3e7be37e20aaf7c73ba88ed2ef5d', NULL, 1, '2026-10-27 14:41:03.073488+00', '', 'TENANT');
INSERT INTO rag.rag_chunk VALUES ('9a1c0000-0000-4000-b000-000000000008', '2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', '9a1c0000-0000-4000-a000-000000000007', '9a1c0000-0000-4000-9000-000000000003', 'EXPLANATION', '有理数的乘方：负数的奇次幂是负数，偶次幂是正数。', 10, '第一章/1.3 有理数的乘方', '例5', 0, 28, '{}', NULL, '6df654a3653ee1bad4d28edf7c9ae43e6df654a3653ee1bad4d28edf7c9ae43e', NULL, 1, '2026-10-27 14:41:03.073488+00', '', 'TENANT');


--
-- Data for Name: rag_dictionary; Type: TABLE DATA; Schema: rag; Owner: -
--

INSERT INTO rag.rag_dictionary VALUES ('0b12a01b-70c4-4267-81e9-527884ac92e3', '00000000-0000-0000-0000-000000000000', 'SOURCE_TYPE', 'TEXTBOOK', '教材', 10, true, '2026-10-27 14:40:55.957053+00', 'PLATFORM');
INSERT INTO rag.rag_dictionary VALUES ('2a33eed9-cdec-44e2-8906-267d2a10f236', '00000000-0000-0000-0000-000000000000', 'SOURCE_TYPE', 'WORKBOOK', '教辅', 20, true, '2026-10-27 14:40:55.957053+00', 'PLATFORM');
INSERT INTO rag.rag_dictionary VALUES ('5ddc9709-be1a-4eb2-9e37-256e34d588db', '00000000-0000-0000-0000-000000000000', 'SOURCE_TYPE', 'PAPER', '论文', 30, true, '2026-10-27 14:40:55.957053+00', 'PLATFORM');
INSERT INTO rag.rag_dictionary VALUES ('1e961c09-7e02-4780-bcd5-96206ffd8454', '00000000-0000-0000-0000-000000000000', 'SOURCE_TYPE', 'OTHER', '其他', 90, true, '2026-10-27 14:40:55.957053+00', 'PLATFORM');


--
-- PostgreSQL database dump complete
--

\unrestrict HIGJs39kH11ZqGM9QVPg3K7ztsfWXPIdRRrahRLmHG7pJdrFYiJSpkx8t3GO99m

