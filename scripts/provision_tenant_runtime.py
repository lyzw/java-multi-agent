#!/usr/bin/env python3
"""E2E / 运维用「租户运行前置」幂等补齐 + 前置自检（T2 · 2026-11-14）。

背景（E2E 报告 F7 的四层前置）：一次 Run 要真正跑起来，需要同时满足
  ① worker 灰度清单包含该租户 slug（AGENT_TENANT_SLUGS，漏配 ⇒ Run 永远 QUEUED、无任何报错）
  ② 治理发布的 Agent 有 modelRef（null ⇒ CONFIG_UNAVAILABLE）
  ③ cap_authz_version 有行（缺 ⇒ 能力快照双写跳过 ⇒ CONFIG_UNAVAILABLE）
  ④ budget_account 有账户（缺 ⇒ BUDGET_EXHAUSTED）
此前 ③④ 靠一次性 psycopg2 手工 INSERT、① 靠启动环境变量、② 靠重发治理 payload，
没有脚本化，换环境 / 换租户同一套 E2E 必然红，且失败是「201 之后静默 FAILED」。

本脚本做三件事：
  · provision：幂等补齐 ③④（含 worker 行与全部现存主体的授权版本行）；
  · preflight：逐项自检并明确报出缺哪一项，而不是等 Run FAILED 后倒查；
  · register-platform：补齐 platform.tenant / tenant_quota 治理侧登记（默认 DRY-RUN，
    必须显式 --apply 才写；只补缺失半边，绝不覆盖已有 lifecycle_state / status）。

⚠️ ①② 属运维/治理动作，脚本默认只报告不写；治理登记补齐也只有显式 --apply 才提交。

用法:
    # 单租户：幂等补齐 + 前置自检（E2E 门禁）
    python3 scripts/provision_tenant_runtime.py --slug t-e2e-fe
    python3 scripts/provision_tenant_runtime.py --slug t-e2e-fe --check-only --no-health

    # 存量批量补齐（默认实写；--dry-run 只预览、绝不写）
    python3 scripts/provision_tenant_runtime.py --all --dry-run
    python3 scripts/provision_tenant_runtime.py --all --slug-prefix t-
    python3 scripts/provision_tenant_runtime.py --all --check-only

    # 治理侧登记补齐（默认 DRY-RUN，必须 --apply 才写；让 --require-platform-registry 可达）
    python3 scripts/provision_tenant_runtime.py --register-platform --all --slug-prefix t-
    python3 scripts/provision_tenant_runtime.py --register-platform --slug t-e2e-fe --apply

⚠️ 对共享 / 远程 RDS 批量写入前必须先 --dry-run 并经运维确认；IT 夹具残留租户
   （it-*）通常应清理而非补齐，用 --slug-prefix 圈定范围，避免污染测试语义。

连接参数优先级: --dsn > PG_DSN / E2E_PG_DSN > PGHOST/PGUSER/... > 仓库根 platform.env 的
SPRING_DATASOURCE_URL/USERNAME/PASSWORD（只在本机读文件，不打印口令，不硬编码凭据）。
AGENT_TENANT_SLUGS 优先级: 当前 shell 环境变量 > 仓库根 platform.env（部署实际读取的文件）。
--check-only 有任何缺项时退出码为 1，可直接作为 E2E 硬门禁。
"""

from __future__ import annotations

import argparse
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

try:
    import psycopg2
except ImportError:  # pragma: no cover - 环境依赖
    print("❌ 缺少 psycopg2；请先 pip install psycopg2-binary", file=sys.stderr)
    raise SystemExit(2)

WORKER_PRINCIPAL = "worker"
DEFAULT_BUDGET_LIMIT = "1000.000000"
# 与 02_platform_seed.sql 的 provisionTenant 形状一致；不编造计费套餐（plan 留 NULL）。
DEFAULT_AGENT_LIMIT = 50
DEFAULT_RUN_CONCURRENCY_LIMIT = 10


def env_file() -> Path:
    return Path(__file__).resolve().parent.parent / "platform.env"


def read_env_values() -> dict[str, str]:
    """读取部署用 KEY=VALUE 文件（只用于本机，值绝不打印）。"""
    path = env_file()
    values: dict[str, str] = {}
    if not path.is_file():
        return values
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
            value = value[1:-1]
        values[key.strip()] = value
    return values


def build_dsn(host: str, port: str | None, user: str, password: str, db: str) -> str:
    # ⚠️ 口令常含 @ / : / # 等 URL 保留字符；不编码会把口令截成 host 的一部分，
    #    表现为「密码认证失败」，而真实成因离得很远。
    auth = (f"{urllib.parse.quote(user, safe='')}:"
            f"{urllib.parse.quote(password, safe='')}@") if password \
        else f"{urllib.parse.quote(user, safe='')}@"
    return (f"postgresql://{auth}{host}:{port or '5432'}/"
            f"{urllib.parse.quote(db, safe='')}")


def dsn_from_env() -> str:
    explicit = os.environ.get("PG_DSN") or os.environ.get("E2E_PG_DSN")
    if explicit:
        return explicit

    values = read_env_values()
    host = os.environ.get("PGHOST") or os.environ.get("IT_HOST")
    port = os.environ.get("PGPORT") or os.environ.get("IT_PORT")
    user = os.environ.get("PGUSER") or os.environ.get("IT_ADMIN_USER")
    db = os.environ.get("PGDATABASE") or os.environ.get("IT_DB")
    password = os.environ.get("PGPASSWORD") or os.environ.get("AI_AGENT_PASSWORD") or ""

    if not (host and user):
        # 回落到部署配置：jdbc:postgresql://host:port/db[?params]
        jdbc = values.get("SPRING_DATASOURCE_URL", "")
        if jdbc.startswith("jdbc:postgresql://"):
            rest = jdbc[len("jdbc:postgresql://"):]
            host_port, _, dbpart = rest.partition("/")
            host = host_port.split(":")[0]
            port = host_port.split(":")[1] if ":" in host_port else None
            db = dbpart.split("?")[0]
            user = values.get("SPRING_DATASOURCE_USERNAME", "")
            password = values.get("SPRING_DATASOURCE_PASSWORD", "")
        else:
            print(
                "❌ 未配置数据库连接：请设置 PG_DSN、PGHOST/PGUSER/...，"
                "或确保 platform.env 含 SPRING_DATASOURCE_URL/USERNAME/PASSWORD",
                file=sys.stderr,
            )
            raise SystemExit(2)

    return build_dsn(host, port, user, password, db or "agent_dev")


def tenant_slugs_env() -> tuple[str | None, str]:
    """返回 (灰度清单值, 来源说明)。

    ⚠️ 部署实际读的是仓库根 platform.env；若只看当前 shell，
       运维明明配好了却会报 MISS —— 假失败会让门禁被关掉，比没有门禁更糟。
    """
    if os.environ.get("AGENT_TENANT_SLUGS") is not None:
        return os.environ["AGENT_TENANT_SLUGS"], "当前 shell 环境变量"
    value = read_env_values().get("AGENT_TENANT_SLUGS")
    if value is not None:
        return value, str(env_file())
    return None, "未配置"


def pilot_max_tenants() -> int:
    """与 application.yml 的 deployment.pilot.max-tenants 同源（初值 1）。

    ⚠️ 该上限在 WorkerPolicy.requireTenantSlugs 里是 fail-closed 的：清单超限
       ⇒ **服务根本起不来**，不是 Run FAILED。门禁必须在服务启动前就报出来。
    """
    raw = (os.environ.get("DEPLOYMENT_PILOT_MAX_TENANTS")
           or read_env_values().get("DEPLOYMENT_PILOT_MAX_TENANTS"))
    if raw is None:
        return 1
    try:
        value = int(raw)
        return value if value > 0 else 1
    except ValueError:
        return 1


def open_db(dsn: str):
    conn = psycopg2.connect(dsn)
    conn.autocommit = False
    return conn


def tenant_id(cur: Any, slug: str) -> str | None:
    cur.execute("SELECT tenant_id::text FROM tenant WHERE slug = %s", (slug,))
    row = cur.fetchone()
    return row[0] if row else None


def bind_tenant(cur: Any, tid: str) -> None:
    """⚠️ 铁律 1：FORCE RLS 表必须先绑租户 GUC，否则读/写静默 0 行＝假绿。"""
    cur.execute("SELECT set_config('app.tenant_id', %s, true)", (tid,))


def provision(cur: Any, tid: str) -> dict[str, int]:
    """幂等补齐租户级与主体级运行前置；返回各项新增行数。"""
    bind_tenant(cur, tid)
    cur.execute(
        """INSERT INTO budget_account
               (tenant_id, currency, total_limit, reserved_amount, settled_amount)
           VALUES (%s::uuid, 'CNY', %s, 0, 0)
           ON CONFLICT (tenant_id, currency) DO NOTHING""",
        (tid, DEFAULT_BUDGET_LIMIT),
    )
    budget_added = cur.rowcount
    cur.execute(
        """INSERT INTO cap_authz_version
               (tenant_id, principal_id, authz_version, version, updated_at)
           VALUES (%s::uuid, %s, 1, 0, now())
           ON CONFLICT (tenant_id, principal_id) DO NOTHING""",
        (tid, WORKER_PRINCIPAL),
    )
    worker_added = cur.rowcount
    # ⚠️ 主体授权版本行按 (tenant_id, principal_id) 组织，principal_id 是 uuid 列、
    #    cap_authz_version.principal_id 是 text，故需 ::text。
    #    ON CONFLICT DO NOTHING ⇒ 已撤权主体（版本 ≥ 2）绝不会被重置为 1。
    cur.execute(
        """INSERT INTO cap_authz_version
               (tenant_id, principal_id, authz_version, version, updated_at)
           SELECT p.tenant_id, p.principal_id::text, 1, 0, now()
             FROM principal p
            WHERE p.tenant_id = %s::uuid
           ON CONFLICT (tenant_id, principal_id) DO NOTHING""",
        (tid,),
    )
    principal_added = cur.rowcount
    return {
        "budget_account": budget_added,
        "authz_worker": worker_added,
        "authz_principals": principal_added,
    }


def all_tenants(cur: Any, prefix: str | None) -> list[tuple[str, str]]:
    """列出待批量处理的 (tenant_id, slug)；public.tenant 无 RLS，直接读全量。"""
    if prefix:
        cur.execute(
            "SELECT tenant_id::text, slug FROM tenant WHERE slug LIKE %s ORDER BY slug",
            (prefix + "%",),
        )
    else:
        cur.execute("SELECT tenant_id::text, slug FROM tenant ORDER BY slug")
    return cur.fetchall()


def gap_counts(cur: Any, tid: str) -> dict[str, int]:
    """只诊断不写入：返回补齐将产生的行数，供 --dry-run / --all --check-only 使用。"""
    bind_tenant(cur, tid)
    cur.execute(
        "SELECT count(*) FROM budget_account WHERE tenant_id = %s::uuid AND currency = 'CNY'",
        (tid,),
    )
    budget = 0 if cur.fetchone()[0] > 0 else 1
    cur.execute(
        "SELECT count(*) FROM cap_authz_version "
        "WHERE tenant_id = %s::uuid AND principal_id = %s",
        (tid, WORKER_PRINCIPAL),
    )
    worker = 0 if cur.fetchone()[0] > 0 else 1
    cur.execute(
        """SELECT count(*) FROM principal p
             WHERE p.tenant_id = %s::uuid
               AND NOT EXISTS (
                    SELECT 1 FROM cap_authz_version v
                     WHERE v.tenant_id = p.tenant_id
                       AND v.principal_id = p.principal_id::text)""",
        (tid,),
    )
    principals = cur.fetchone()[0]
    return {
        "budget_account": budget,
        "authz_worker": worker,
        "authz_principals": principals,
    }


def preflight(cur: Any, tid: str, slug: str, health_url: str | None,
              strict_registry: bool = False) -> list[tuple[str, bool, str, bool]]:
    """逐项自检；返回 (项目, 是否通过, 说明, 是否阻断 E2E)。

    ⚠️ 「平台租户登记」默认**只告警不阻断**：本项目存在两张注册表
       （public.tenant 供 RLS/执行链、platform.tenant 供平台治理 API），
       管理侧开通只写 public.tenant（见 AdminTenantController 对已下线 status
       通道的说明）。把这项做成硬门禁会在「Run 明明能跑」的租户上报假失败，
       守卫一旦误报就会被关掉 —— 那比没有守卫更糟。
       走平台治理 API / 执行票证的 E2E 可加 --require-platform-registry 收紧。
    """
    bind_tenant(cur, tid)
    checks: list[tuple[str, bool, str, bool]] = []

    cur.execute("SELECT count(*) FROM platform.tenant WHERE id = %s::uuid", (tid,))
    checks.append((
        "平台租户登记（治理侧）",
        cur.fetchone()[0] > 0,
        "只在 platform.tenant 登记；缺失不影响 Run 执行链，但平台治理列表/票证端点不认该租户。"
        "如需收紧加 --require-platform-registry",
        strict_registry,
    ))

    cur.execute("SELECT count(*) FROM budget_account WHERE tenant_id = %s::uuid", (tid,))
    checks.append(("预算账户", cur.fetchone()[0] > 0, "缺账户 ⇒ BUDGET_EXHAUSTED", True))

    cur.execute(
        "SELECT count(*) FROM cap_authz_version WHERE tenant_id = %s::uuid AND principal_id = %s",
        (tid, WORKER_PRINCIPAL),
    )
    checks.append((
        "Worker 授权版本行", cur.fetchone()[0] > 0,
        "缺行 ⇒ 执行链 AUTHZ_UNAVAILABLE", True,
    ))

    cur.execute(
        """SELECT count(*) FROM principal p
            WHERE p.tenant_id = %s::uuid
              AND NOT EXISTS (
                    SELECT 1 FROM cap_authz_version v
                     WHERE v.tenant_id = p.tenant_id
                       AND v.principal_id = p.principal_id::text)""",
        (tid,),
    )
    missing_principals = cur.fetchone()[0]
    checks.append((
        "主体授权版本行",
        missing_principals == 0,
        f"仍有 {missing_principals} 个主体缺行 ⇒ 快照双写跳过 ⇒ CONFIG_UNAVAILABLE",
        True,
    ))

    # ⚠️ Agent 已全局化（2026-11-19）：cap_agent_def 没有 tenant_id，租户可见性
    #    只看 tenant_agent_binding 的 ACTIVE 行；pinned_version 非空时只认该版本，
    #    不能回退到最新版。旧脚本按 tenant_id 直查会 UndefinedColumn，E2E 直接起不来。
    cur.execute(
        """SELECT count(*) FROM tenant_agent_binding b
            JOIN cap_agent_def d
              ON d.agent_id = b.agent_id
             AND (b.pinned_version IS NULL OR d.version = b.pinned_version)
            WHERE b.tenant_id = %s::uuid
              AND b.status = 'ACTIVE'
              AND d.lifecycle_state = 'PUBLISHED'::public.cap_lifecycle_state
              AND d.model_ref IS NOT NULL AND btrim(d.model_ref) <> ''""",
        (tid,),
    )
    published = cur.fetchone()[0]
    checks.append((
        "已发布 Agent（含 modelRef）",
        published > 0,
        "治理发布时 modelRef 必须有值；null ⇒ CONFIG_UNAVAILABLE（脚本不代改治理数据）",
        True,
    ))

    slugs, slugs_source = tenant_slugs_env()
    if slugs is None:
        checks.append((
            "worker 灰度清单（含目标 slug）",
            False,
            "未配置 AGENT_TENANT_SLUGS：把当前试点租户写进 platform.env 并重启 platform-bootstrap",
            True,
        ))
    else:
        items = [s.strip() for s in slugs.split(",") if s.strip()]
        checks.append((
            "worker 灰度清单（含目标 slug）",
            slug in items,
            f"AGENT_TENANT_SLUGS={slugs}（来源：{slugs_source}）；"
            "清单外租户的 outbox 永不消费（Run 静默 QUEUED）",
            True,
        ))
        max_tenants = pilot_max_tenants()
        checks.append((
            "灰度清单不超试点上限",
            0 < len(items) <= max_tenants and len(set(items)) == len(items),
            f"清单 {len(items)} 个 / 上限 {max_tenants}"
            "（deployment.pilot.max-tenants，application.yml 初值 1）；"
            "超限 / 重复 / 全空 ⇒ platform-bootstrap fail-closed 拒绝启动"
            "（是服务起不来，不是 Run 失败）",
            True,
        ))

    if health_url:
        try:
            with urllib.request.urlopen(health_url, timeout=4) as resp:
                ok = resp.status == 200
        except (urllib.error.URLError, OSError) as exc:
            ok = False
            health_url = f"{health_url}（{type(exc).__name__}）"
        checks.append((
            "平台服务存活", ok,
            f"{health_url}；未启动则先重启 platform-bootstrap（可用 --no-health 跳过本项）",
            True,
        ))

    return checks


def platform_registration_gap(cur: Any, tid: str, slug: str) -> dict[str, Any] | None:
    """诊断治理侧登记缺口；不改任何数据。

    ⚠️ 三列状态必须相等：public.tenant.status = platform.tenant.status
       = platform.tenant.lifecycle_state（停用租户靠这条生效）。
       不相等属于数据冲突，只报告、不自动改（沿用历史补登记纪律）。
    """
    cur.execute("SELECT name, status FROM tenant WHERE tenant_id = %s::uuid", (tid,))
    row = cur.fetchone()
    if row is None:
        return None
    name, status = row
    cur.execute("SELECT count(*) FROM platform.tenant WHERE id = %s::uuid", (tid,))
    has_row = cur.fetchone()[0] > 0
    cur.execute(
        "SELECT count(*) FROM platform.tenant WHERE slug = %s AND id <> %s::uuid",
        (slug, tid),
    )
    conflict = cur.fetchone()[0] > 0
    cur.execute(
        "SELECT count(*) FROM platform.tenant_quota WHERE tenant_id = %s::uuid", (tid,)
    )
    has_quota = cur.fetchone()[0] > 0
    mismatch = False
    if has_row:
        cur.execute(
            "SELECT status, lifecycle_state FROM platform.tenant WHERE id = %s::uuid", (tid,)
        )
        plat_status, lifecycle = cur.fetchone()
        mismatch = (plat_status != status or lifecycle != status)
    return {
        "name": name, "status": status, "has_row": has_row,
        "has_quota": has_quota, "conflict": conflict, "mismatch": mismatch,
    }


def register_platform_tenant(cur: Any, tid: str, slug: str,
                             gap: dict[str, Any]) -> tuple[int, int]:
    """只补缺失半边；已有行绝不覆盖 lifecycle_state / status。返回 (tenant, quota) 新增行数。"""
    added_t = added_q = 0
    if not gap["has_row"] and not gap["conflict"]:
        cur.execute(
            """INSERT INTO platform.tenant
                   (id, name, slug, status, plan, lifecycle_state,
                    effective_policy_version, tenant_authz_version, created_at, updated_at)
               VALUES (%s::uuid, %s, %s, %s, NULL, %s, 1, 1, now(), now())
               ON CONFLICT (id) DO NOTHING""",
            (tid, gap["name"], slug, gap["status"], gap["status"]),
        )
        added_t = cur.rowcount
    # tenant_quota 的外键指向 platform.tenant；缺行时一并补，不覆盖已有额度。
    if added_t or (gap["has_row"] and not gap["has_quota"]):
        cur.execute(
            """INSERT INTO platform.tenant_quota
                   (tenant_id, agent_limit, run_concurrency_limit)
               VALUES (%s::uuid, %s, %s)
               ON CONFLICT (tenant_id) DO NOTHING""",
            (tid, DEFAULT_AGENT_LIMIT, DEFAULT_RUN_CONCURRENCY_LIMIT),
        )
        added_q = cur.rowcount
    return added_t, added_q


def run_register(args: argparse.Namespace) -> int:
    """治理登记补齐：默认 DRY-RUN，只有 --apply 才提交（显式运维动作）。"""
    conn = open_db(args.dsn or dsn_from_env())
    try:
        with conn.cursor() as cur:
            # 权限预检：FORCE RLS / 少授权会让 INSERT 静默失败，必须先问清楚。
            cur.execute(
                "SELECT has_table_privilege(current_user, 'platform.tenant', 'INSERT'), "
                "       has_table_privilege(current_user, 'platform.tenant_quota', 'INSERT')"
            )
            can_t, can_q = cur.fetchone()
            if not (can_t and can_q):
                print("❌ 当前 DB 角色缺少 platform.tenant / tenant_quota 的 INSERT 权限；"
                      "请用库 owner 的 DSN 重试（--dsn）", file=sys.stderr)
                conn.rollback()
                return 2

            if args.slug:
                tid = tenant_id(cur, args.slug)
                targets = [] if tid is None else [(tid, args.slug)]
            else:
                targets = all_tenants(cur, args.slug_prefix)
            scope = (args.slug_prefix + "*") if args.slug_prefix else (
                args.slug or "全部")
            print(f"▶ 治理登记补齐：命中 {len(targets)} 个租户（范围 {scope}）"
                  f"，模式 {'APPLY 实写' if args.apply else 'DRY-RUN 预览'}")
            if not targets:
                print("❌ 未命中租户 —— 守卫不得空转，请检查 --slug / --slug-prefix / DSN")
                conn.rollback()
                return 1

            planned_t = planned_q = 0
            conflicts = mismatches = 0
            for tid, slug in targets:
                gap = platform_registration_gap(cur, tid, slug)
                if gap is None:
                    continue
                if gap["conflict"]:
                    print(f"  ⚠️ {slug:<26} platform.tenant 已有同 slug 不同 id —— 跳过，需人工裁决")
                    conflicts += 1
                    continue
                if gap["mismatch"]:
                    print(f"  ⚠️ {slug:<26} 三列状态不相等 —— 只报告，不自动改（停用语义需人工确认）")
                    mismatches += 1
                if args.apply:
                    at, aq = register_platform_tenant(cur, tid, slug, gap)
                else:
                    at = 0 if gap["has_row"] else 1
                    aq = 0 if gap["has_quota"] else 1
                if at or aq:
                    planned_t += at
                    planned_q += aq
                    print(f"  · {slug:<26} {'已补' if args.apply else '待补'} "
                          f"platform.tenant {at} / quota {aq}")
            if args.apply:
                conn.commit()
            else:
                conn.rollback()
    finally:
        conn.close()

    mode = "治理登记补齐（已提交）" if args.apply else "治理登记 DRY-RUN（未写入）"
    print(f"\n=== {mode} ===")
    print(f"  platform.tenant {planned_t} 行 / platform.tenant_quota {planned_q} 行；"
          f"slug 冲突 {conflicts}、状态冲突 {mismatches}（均不自动处理）")
    if not args.apply:
        print("确认范围后加 --apply 执行；建议先 --slug-prefix 圈定，避免动到 IT 夹具残留租户。")
    else:
        print("再跑一次 ./scripts/check-tenant-registry.sh，① ④ 应归零。")
    return 0


def run_all(args: argparse.Namespace) -> int:
    """存量批量补齐 / 预览 / 批量自检；单个事务，失败整体回滚。"""
    conn = open_db(args.dsn or dsn_from_env())
    try:
        with conn.cursor() as cur:
            tenants = all_tenants(cur, args.slug_prefix)
            scope = args.slug_prefix + "*" if args.slug_prefix else "全部"
            print(f"▶ 批量模式：public.tenant 命中 {len(tenants)} 个租户（范围 {scope}）")
            if not tenants:
                print("❌ 一个租户都没命中 —— 守卫不得空转，请检查 --slug-prefix / DSN")
                conn.rollback()
                return 1

            totals = {"budget_account": 0, "authz_worker": 0, "authz_principals": 0}
            affected = 0
            for tid, slug in tenants:
                counts = (gap_counts(cur, tid) if (args.check_only or args.dry_run)
                          else provision(cur, tid))
                for key in totals:
                    totals[key] += counts[key]
                missing = sum(counts.values())
                if missing:
                    affected += 1
                    print(
                        f"  · {slug:<26} 待补 {missing:>3} 行"
                        f"（budget {counts['budget_account']}，worker {counts['authz_worker']}，"
                        f"主体 {counts['authz_principals']}）"
                    )

            # dry-run / check-only 绝不提交；实写用一个事务整体提交
            if args.check_only or args.dry_run:
                conn.rollback()
            else:
                conn.commit()
    finally:
        conn.close()

    total = sum(totals.values())
    mode = "DRY-RUN 预览" if args.dry_run else (
        "批量自检" if args.check_only else "批量幂等补齐")
    print(f"\n=== {mode} 汇总：{len(tenants)} 租户 / {affected} 个有缺口 / {total} 行 ===")
    print(f"  budget_account +{totals['budget_account']}，"
          f"worker 授权版本 +{totals['authz_worker']}，"
          f"主体授权版本 +{totals['authz_principals']}")

    if args.dry_run:
        print("未写入任何数据；确认范围后去掉 --dry-run 即执行（建议先 --slug-prefix 圈定）。")
        return 0
    if args.check_only:
        if total:
            print(f"❌ 仍有 {total} 行缺口；用 --all 补齐后再开始 E2E。")
            return 1
        print("✅ 全部租户运行前置齐备。")
        return 0
    print("✅ 已补齐；再跑一次 --all --check-only 应报 0 行（幂等自证）。")
    return 0


def run_one(args: argparse.Namespace) -> int:
    """单租户：幂等补齐 + 逐项前置自检（E2E 硬门禁）。"""
    conn = open_db(args.dsn or dsn_from_env())
    try:
        with conn.cursor() as cur:
            tid = tenant_id(cur, args.slug)
            if tid is None:
                print(f"❌ 租户不存在：slug={args.slug}（请先用开通 API 创建租户）")
                return 1
            print(f"▶ 租户 {args.slug} → {tid}")

            if not args.check_only:
                added = provision(cur, tid)
                conn.commit()
                print(
                    "  · 幂等补齐：budget_account +{budget_account}，"
                    "worker 授权版本 +{authz_worker}，主体授权版本 +{authz_principals}".format(**added)
                )
            else:
                conn.rollback()

            with conn.cursor() as check_cur:
                results = preflight(
                    check_cur, tid, args.slug,
                    None if args.no_health else args.health_url,
                    args.require_platform_registry,
                )
            conn.commit()
    finally:
        conn.close()

    print("\n=== E2E 前置自检 ===")
    failed = 0
    warned = 0
    for name, ok, note, blocking in results:
        if ok:
            print(f"  ✅ PASS  {name}")
        elif blocking:
            print(f"  ❌ MISS  {name} — {note}")
            failed += 1
        else:
            print(f"  ⚠️ WARN  {name} — {note}")
            warned += 1

    if warned:
        print(f"\n（{warned} 项告警不阻断 Run 执行链；如需收紧见上文说明。）")
    if failed:
        print(f"\n❌ 有 {failed} 项前置未满足；此时发起 Run 会静默 FAILED，禁止开始 E2E。")
        return 1
    print("\n✅ 阻断项全部齐备，可以开始 E2E。")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="租户运行前置幂等补齐 + E2E 前置自检")
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument("--slug", help="单个租户 slug，如 t-e2e-fe")
    target.add_argument("--all", action="store_true", help="批量处理存量租户")
    parser.add_argument("--slug-prefix", default=None,
                        help="--all 时只处理该前缀的 slug，如 t-；默认全量")
    parser.add_argument("--dsn", default=None, help="完整 PostgreSQL DSN（默认读环境变量）")
    parser.add_argument("--dry-run", action="store_true",
                        help="--all 时只预览将补齐的项，绝不写入")
    parser.add_argument("--check-only", action="store_true", help="只自检不写入；有缺项则退出码 1")
    parser.add_argument("--health-url", default="http://localhost:8080/actuator/health")
    parser.add_argument("--no-health", action="store_true", help="跳过平台服务存活检查")
    parser.add_argument(
        "--require-platform-registry", action="store_true",
        help="把 platform.tenant 登记升级为硬门禁（仅平台治理/执行票证类 E2E 需要）",
    )
    parser.add_argument(
        "--register-platform", action="store_true",
        help="补齐 platform.tenant / tenant_quota 治理侧登记（默认 DRY-RUN）",
    )
    parser.add_argument(
        "--apply", action="store_true",
        help="--register-platform 时真正提交；不给则只预览",
    )
    args = parser.parse_args()
    if args.dry_run and not args.all:
        parser.error("--dry-run 仅用于 --all 批量模式")
    if args.slug_prefix and not (args.all or args.register_platform):
        parser.error("--slug-prefix 仅用于 --all / --register-platform 批量模式")
    if args.apply and not args.register_platform:
        parser.error("--apply 仅用于 --register-platform（避免误写治理数据）")
    if args.register_platform:
        return run_register(args)
    return run_all(args) if args.all else run_one(args)


if __name__ == "__main__":
    raise SystemExit(main())
