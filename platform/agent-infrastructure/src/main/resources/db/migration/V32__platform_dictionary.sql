-- =====================================================================
-- V32 · 平台字典配置表（P0-1 收编：deploy/sql/platform/07_platform_dictionary_ddl.sql）
-- 评审 §4.1：与 V31 同批收编；种子 ON CONFLICT DO NOTHING，人工调整不被覆盖。
-- =====================================================================
-- =====================================================================
-- 平台字典配置表 DDL
-- =====================================================================
--
-- 【要解决的问题】前端下拉枚举散落在各表单的 TS 常量里（HTTP 方法、
--    工具副作用等级、学科 / 年级 / 出版社 …）。改一个中文标签要改代码、
--    发版、重新构建；新增一个取值更是要动多处。字典表把这些取值收敛为
--    **数据**，平台侧可自行新增 / 改名 / 停用，不必改前端代码。
--
-- 【模型】两张表，与后端枚举保持同构：
--    sys_dictionary       字典（类型）      —— 「这是一组什么取值」
--    sys_dictionary_item  字典项（取值）    —— 「具体的 code → label」
--
-- 【built_in 的语义（关键纪律）】
--    built_in = TRUE   该字典与**代码枚举强绑定**（如 http.method、
--                      tool.side_effect、rag.trust_tier）。后端在写业务表时
--                      会按 Java 枚举校验取值，因此：
--                        · 禁止删除（否则历史行的取值变成悬空引用）
--                        · 禁止改 item_code（同理）
--                        · **可以**改中文标签、排序、停用
--                      ⚠️ 对内置字典新增一项，若后端枚举未同步，
--                         写入业务表时仍会被后端拒绝——字典页只负责字典，
--                         不替后端背书。页面会显式提示这一点。
--    built_in = FALSE  纯业务数据字典（如 rag.subject / rag.grade /
--                      rag.publisher）。可自由增删改停。
--
-- 【为何不物理删除】平台侧一贯的 fail-closed 纪律：字典项被业务行引用后，
--    物理删除会让历史数据出现「取值不在字典里」的悬空状态，
--    排查成本远高于一条停用标记。故一律 `enabled = FALSE` 停用。
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS platform;

-- ── 字典（类型）─────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS platform.sys_dictionary (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- ⚠️ 字典编码是前后端契约（如 'http.method'），创建后不可改
    dict_code   VARCHAR(64)  NOT NULL UNIQUE,
    dict_name   VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    -- 内置字典：与代码枚举强绑定，禁止删除；非内置：可自由维护
    built_in    BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0
);

COMMENT ON TABLE  platform.sys_dictionary IS '字典类型：一组取值的命名容器（如 http.method）';
COMMENT ON COLUMN platform.sys_dictionary.built_in IS 'TRUE=与代码枚举强绑定，禁止删除与改 code；FALSE=纯业务数据，可自由维护';

-- ── 字典项（取值）───────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS platform.sys_dictionary_item (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- ⚠️ ON DELETE RESTRICT：字典下还有取值时不允许删字典
    dict_code  VARCHAR(64)  NOT NULL
               REFERENCES platform.sys_dictionary(dict_code) ON DELETE RESTRICT,
    item_code  VARCHAR(128) NOT NULL,
    item_label VARCHAR(256) NOT NULL,
    sort_order INTEGER      NOT NULL DEFAULT 0,
    built_in   BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version    BIGINT       NOT NULL DEFAULT 0,
    -- 同一字典内取值唯一（跨字典允许同名，如 PENDING 出现在多个字典里）
    CONSTRAINT sys_dictionary_item_code_unique UNIQUE (dict_code, item_code)
);

CREATE INDEX IF NOT EXISTS idx_sys_dict_item_dict
    ON platform.sys_dictionary_item (dict_code, sort_order, item_code);

COMMENT ON COLUMN platform.sys_dictionary_item.item_code  IS '写入业务表的实际取值（后端枚举校验的就是它）';
COMMENT ON COLUMN platform.sys_dictionary_item.item_label IS '展示给人的中文标签（可改，不影响已落库数据）';

-- =====================================================================
-- 种子数据
-- =====================================================================
-- ⚠️ ON CONFLICT DO NOTHING：本文件可重复执行；已存在的人工调整不会被覆盖。
--    （字典是「可维护的数据」，重复初始化把人工改动冲掉是事故，不是幂等。）

INSERT INTO platform.sys_dictionary (dict_code, dict_name, description, built_in, sort_order) VALUES
    ('http.method',              'HTTP 方法',       'Tool kind=HTTP 时允许的请求方法（后端按枚举校验）', TRUE,  10),
    ('tool.risk_level',          '工具风险等级',    'READ 只读 / WRITE_PROFILE 内部写 / WRITE_EXTERNAL 外部写', TRUE,  20),
    ('agent.orchestration_mode', 'Agent 编排模式',  'LEAF 叶子 / ROUTER 路由 / SEQUENTIAL 串行', TRUE,  30),
    ('agent.failure_policy',     '失败策略',        'FAIL_FAST / CONTINUE / COMPENSATE', TRUE,  40),
    ('rag.source_type',          '语料来源类型',    '教材 / 教辅 / 论文 / 其他', FALSE, 50),
    ('rag.trust_tier',           '语料权威等级',    '准入门槛按此分级（后端 TrustTier 枚举）', TRUE,  60),
    ('rag.verification_method',  '校验手段',        'ISBN / DOI / 人工比对 / 官方哈希', TRUE,  70),
    ('rag.license_status',       '版权状态',        '已获授权 / 合理引用 / 未取得授权 / 禁止使用', TRUE,  80),
    ('rag.subject',              '学科',            '教辅版本坐标系 · 学科轴', FALSE, 90),
    ('rag.grade',                '年级',            '教辅版本坐标系 · 年级轴', FALSE, 100),
    ('rag.term',                 '学期',            '教辅版本坐标系 · 学期轴', FALSE, 110),
    ('rag.publisher',            '出版社',          '教辅版本坐标系 · 出版社轴', FALSE, 120)
ON CONFLICT (dict_code) DO NOTHING;

-- HTTP 方法（内置）
INSERT INTO platform.sys_dictionary_item (dict_code, item_code, item_label, sort_order, built_in) VALUES
    ('http.method', 'GET',     'GET',     10, TRUE),
    ('http.method', 'POST',    'POST',    20, TRUE),
    ('http.method', 'PUT',     'PUT',     30, TRUE),
    ('http.method', 'PATCH',   'PATCH',   40, TRUE),
    ('http.method', 'DELETE',  'DELETE',  50, TRUE),
    ('http.method', 'HEAD',    'HEAD',    60, TRUE),
    ('http.method', 'OPTIONS', 'OPTIONS', 70, TRUE)
ON CONFLICT (dict_code, item_code) DO NOTHING;

-- 工具风险等级（内置）
INSERT INTO platform.sys_dictionary_item (dict_code, item_code, item_label, sort_order, built_in) VALUES
    ('tool.risk_level', 'READ',           'READ（只读）',           10, TRUE),
    ('tool.risk_level', 'WRITE_PROFILE',  'WRITE_PROFILE（内部写）',   20, TRUE),
    ('tool.risk_level', 'WRITE_EXTERNAL', 'WRITE_EXTERNAL（外部写）',  30, TRUE)
ON CONFLICT (dict_code, item_code) DO NOTHING;

-- Agent 编排模式（内置）
INSERT INTO platform.sys_dictionary_item (dict_code, item_code, item_label, sort_order, built_in) VALUES
    ('agent.orchestration_mode', 'LEAF',       'LEAF（叶子，不再委派）', 10, TRUE),
    ('agent.orchestration_mode', 'ROUTER',     'ROUTER（按条件路由）',   20, TRUE),
    ('agent.orchestration_mode', 'SEQUENTIAL', 'SEQUENTIAL（串行执行）', 30, TRUE)
ON CONFLICT (dict_code, item_code) DO NOTHING;

-- 失败策略（内置）
INSERT INTO platform.sys_dictionary_item (dict_code, item_code, item_label, sort_order, built_in) VALUES
    ('agent.failure_policy', 'FAIL_FAST',  'FAIL_FAST（立即失败）',   10, TRUE),
    ('agent.failure_policy', 'CONTINUE',   'CONTINUE（继续）',       20, TRUE),
    ('agent.failure_policy', 'COMPENSATE', 'COMPENSATE（补偿）',     30, TRUE)
ON CONFLICT (dict_code, item_code) DO NOTHING;

-- 语料来源类型（非内置，可自由维护）
INSERT INTO platform.sys_dictionary_item (dict_code, item_code, item_label, sort_order, built_in) VALUES
    ('rag.source_type', 'TEXTBOOK', '教材', 10, FALSE),
    ('rag.source_type', 'WORKBOOK', '教辅', 20, FALSE),
    ('rag.source_type', 'PAPER',    '论文', 30, FALSE),
    ('rag.source_type', 'OTHER',    '其他', 90, FALSE)
ON CONFLICT (dict_code, item_code) DO NOTHING;

-- 权威等级（内置）
INSERT INTO platform.sys_dictionary_item (dict_code, item_code, item_label, sort_order, built_in) VALUES
    ('rag.trust_tier', 'OFFICIAL_PUBLISHER',   '教材出版社官方',   10, TRUE),
    ('rag.trust_tier', 'OFFICIAL_TUTORIAL',    '教辅品牌正式出版', 20, TRUE),
    ('rag.trust_tier', 'PEER_REVIEWED_PAPER',  '同行评审论文',     30, TRUE),
    ('rag.trust_tier', 'PREPRINT',             '预印本 / 白皮书',  40, TRUE),
    ('rag.trust_tier', 'UNVERIFIED',           '未校验',           50, TRUE)
ON CONFLICT (dict_code, item_code) DO NOTHING;

-- 校验手段（内置）
INSERT INTO platform.sys_dictionary_item (dict_code, item_code, item_label, sort_order, built_in) VALUES
    ('rag.verification_method', 'NONE',          '无',               10, TRUE),
    ('rag.verification_method', 'ISBN',          'ISBN 校验',        20, TRUE),
    ('rag.verification_method', 'DOI',           'DOI 校验',         30, TRUE),
    ('rag.verification_method', 'MANUAL_REVIEW', '人工逐份比对',     40, TRUE),
    ('rag.verification_method', 'OFFICIAL_HASH', '官方哈希比对',     50, TRUE)
ON CONFLICT (dict_code, item_code) DO NOTHING;

-- 版权状态（内置）
INSERT INTO platform.sys_dictionary_item (dict_code, item_code, item_label, sort_order, built_in) VALUES
    ('rag.license_status', 'LICENSED',           '已获授权',   10, TRUE),
    ('rag.license_status', 'FAIR_USE_QUOTATION', '合理引用',   20, TRUE),
    ('rag.license_status', 'PENDING',            '未取得授权', 30, TRUE),
    ('rag.license_status', 'RESTRICTED',         '禁止使用',   40, TRUE)
ON CONFLICT (dict_code, item_code) DO NOTHING;

-- 学科（非内置）
INSERT INTO platform.sys_dictionary_item (dict_code, item_code, item_label, sort_order, built_in) VALUES
    ('rag.subject', 'MATH',    '数学', 10, FALSE),
    ('rag.subject', 'CHINESE', '语文', 20, FALSE),
    ('rag.subject', 'ENGLISH', '英语', 30, FALSE),
    ('rag.subject', 'PHYSICS', '物理', 40, FALSE),
    ('rag.subject', 'CHEMISTRY','化学', 50, FALSE),
    ('rag.subject', 'BIOLOGY', '生物', 60, FALSE),
    ('rag.subject', 'HISTORY', '历史', 70, FALSE),
    ('rag.subject', 'GEOGRAPHY','地理', 80, FALSE),
    ('rag.subject', 'POLITICS','道德与法治', 90, FALSE)
ON CONFLICT (dict_code, item_code) DO NOTHING;

-- 年级（非内置）
INSERT INTO platform.sys_dictionary_item (dict_code, item_code, item_label, sort_order, built_in) VALUES
    ('rag.grade', 'G7', '七年级', 10, FALSE),
    ('rag.grade', 'G8', '八年级', 20, FALSE),
    ('rag.grade', 'G9', '九年级', 30, FALSE),
    ('rag.grade', 'G1', '一年级', 40, FALSE),
    ('rag.grade', 'G2', '二年级', 50, FALSE),
    ('rag.grade', 'G3', '三年级', 60, FALSE),
    ('rag.grade', 'G4', '四年级', 70, FALSE),
    ('rag.grade', 'G5', '五年级', 80, FALSE),
    ('rag.grade', 'G6', '六年级', 90, FALSE)
ON CONFLICT (dict_code, item_code) DO NOTHING;

-- 学期（非内置）
INSERT INTO platform.sys_dictionary_item (dict_code, item_code, item_label, sort_order, built_in) VALUES
    ('rag.term', 'FIRST',  '上册',   10, FALSE),
    ('rag.term', 'SECOND', '下册',   20, FALSE),
    ('rag.term', 'FULL',   '全一册', 30, FALSE)
ON CONFLICT (dict_code, item_code) DO NOTHING;

-- 出版社（非内置）
INSERT INTO platform.sys_dictionary_item (dict_code, item_code, item_label, sort_order, built_in) VALUES
    ('rag.publisher', 'RJ',  '人教版',   10, FALSE),
    ('rag.publisher', 'BSD', '北师大版', 20, FALSE),
    ('rag.publisher', 'SJ',  '苏教版',   30, FALSE),
    ('rag.publisher', 'HJ',  '沪教版',   40, FALSE),
    ('rag.publisher', 'ZJ',  '浙教版',   50, FALSE),
    ('rag.publisher', 'WY',  '外研版',   60, FALSE)
ON CONFLICT (dict_code, item_code) DO NOTHING;
