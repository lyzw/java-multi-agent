-- ╔═══════════════════════════════════════════════════════════════════════════╗
-- ║ 统一模型目录 · 种子默认模型（用于存量库 / 已建库后补种）                    ║
-- ║                                                                           ║
-- ║ 幂等：基于 (scenario, model_name) 唯一索引冲突即跳过，可安全重复执行。      ║
-- ║ 全新库请用 01_platform_ddl.sql（已含相同种子，无需再跑本文件）。            ║
-- ║                                                                           ║
-- ║ ⚠️ 本表不含任何 API Key：密钥按 provider 由环境变量                          ║
-- ║    agent.model.<provider>.api-key 解析（遵循「凭据不入库」铁律）。          ║
-- ╚═══════════════════════════════════════════════════════════════════════════╝

INSERT INTO public.model_catalog
    (provider, scenario, model_name, display_name, base_url,
     is_default, supports_tools, supports_streaming, supports_vision,
     dimension, is_enabled, sort_order, description)
VALUES
    ('minimax', 'agent-chat', 'MiniMax-M2.5', 'MiniMax M2.5',
     'https://api.minimax.cn/v1',
     true, true, true, false, NULL, true, 0,
     '主 Agent 对话默认模型（MiniMax M2.5，思考型）。base_url/key 仍走环境变量。')
ON CONFLICT (scenario, model_name) DO NOTHING;

INSERT INTO public.model_catalog
    (provider, scenario, model_name, display_name, base_url,
     is_default, supports_tools, supports_streaming, supports_vision,
     dimension, is_enabled, sort_order, description)
VALUES
    ('minimax', 'console-chat', 'MiniMax-M2.5', 'MiniMax M2.5（控制台）',
     'https://api.minimax.cn/v1',
     true, true, true, false, NULL, true, 0,
     '管理后台控制台直接对话默认模型。')
ON CONFLICT (scenario, model_name) DO NOTHING;

INSERT INTO public.model_catalog
    (provider, scenario, model_name, display_name, base_url,
     is_default, supports_tools, supports_streaming, supports_vision,
     dimension, is_enabled, sort_order, description)
VALUES
    ('bge', 'embedding', 'bge-m3', 'BGE-M3 向量化',
     NULL,
     true, false, false, false, 1024, true, 0,
     'RAG 嵌入默认模型（BGE-M3，1024 维）。base_url 由 yml 提供，维度与向量列一致。')
ON CONFLICT (scenario, model_name) DO NOTHING;

INSERT INTO public.model_catalog
    (provider, scenario, model_name, display_name, base_url,
     is_default, supports_tools, supports_streaming, supports_vision,
     dimension, is_enabled, sort_order, description)
VALUES
    ('minimax', 'default', 'MiniMax-M2.5', 'MiniMax M2.5（兜底）',
     'https://api.minimax.cn/v1',
     true, true, true, false, NULL, true, 0,
     '全局兜底默认模型：具体场景无命中且无自身默认时回落到此。')
ON CONFLICT (scenario, model_name) DO NOTHING;
