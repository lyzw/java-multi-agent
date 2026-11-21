#!/usr/bin/env python3
"""
JdbcXxxRepository → MybatisXxxRepository 脚手架。

用法：
    python3 scripts/gen-migrate.py \
        --jdbc JdbcTenantAgentBindingRepository.java \
        --port TenantAgentBindingPort \
        --table tenant_agent_binding \
        --scope tenant           # tenant | platform | rag
        --columns tenant_id,agent_id,status,pinned_version,config,created_at,updated_at
"""
import argparse, re, os, sys, textwrap, itertools

def extract_sql_blocks(src):
    """Extract all triple-quoted Java string blocks."""
    blocks = []
    # Match """ ... """ possibly across newlines
    pat = re.compile(r'"""(.*?)"""', re.DOTALL)
    for m in pat.finditer(src):
        raw = m.group(1)
        dedented = textwrap.dedent(raw).strip('\n')
        blocks.append(dedented)
    return blocks

def extract_singleline_sql(src):
    """Extract SQL from single-line strings (may be append fragments)."""
    lines = []
    # Capture assignments like String sql = "..."
    for m in re.finditer(r'sql\s*(?:\+=|=)\s*"([^"]*?SELECT[^"]*|INSERT[^"]*|UPDATE[^"]*|DELETE[^"]*)"', src, re.IGNORECASE):
        lines.append(m.group(1))
    return lines

def find_port_methods(port_file):
    """Extract method signatures from Port interface."""
    if not port_file or not os.path.exists(port_file):
        return []
    text = open(port_file).read()
    methods = []
    for m in re.finditer(r'([\w<>?, @]+?)\s+(\w+)\s*\(([^)]*)\)', text):
        ret = m.group(1).strip()
        name = m.group(2)
        params = m.group(3).strip()
        if name in ("interface","record") or not ret: continue
        # skip nested types / package / class defs
        if '(' not in m.group(0): continue
        methods.append((ret, name, params))
    return methods

def snake2camel(s):
    parts = s.split('_')
    return parts[0] + ''.join(p[:1].upper()+p[1:] for p in parts[1:])

def gen_do(table, columns, pkg="cn.studykid.multiagent.infrastructure.persistence.model"):
    fields = []
    for c in columns.split(','):
        c = c.strip()
        camel = snake2camel(c)
        fields.append((c, camel))
    lines = [f"package {pkg};", "",
             "import com.baomidou.mybatisplus.annotation.TableField;",
             "import com.baomidou.mybatisplus.annotation.TableName;",
             "",
             f"@TableName(value = \"{table}\", autoResultMap = true)",
             "public class XxxDO {", ""]
    for col, prop in fields:
        lines.append(f"    @TableField(\"{col}\")")
        # type inference (very rough)
        if col.endswith('_at') or col == 'created_at' or col == 'updated_at':
            t = "java.time.Instant"
        elif col in ('tenant_id','agent_id','policy_id','cap_id','run_id','draft_id'):
            t = "String"
        elif col.endswith('_id') or col == 'id':
            t = "String"
        elif 'enabled' in col or col == 'built_in':
            t = "boolean"
        elif col in ('sort_order','pinned_version','version'):
            t = "int" if col == 'sort_order' else "Integer"
        elif col == 'config':
            t = "String"
        else:
            t = "String"
        lines.append(f"    private {t} {prop};")
        lines.append("")
    lines.append("}")
    return "\n".join(lines).replace("XxxDO","DO")

def gen_mapper(mapper_name, pkg, do_pkg, methods):
    lines = [f"package {pkg};", "",
             "import java.util.List;", "import java.util.Optional;",
             "import org.apache.ibatis.annotations.Param;",
             f"import {do_pkg};", "",
             f"public interface {mapper_name} {{", ""]
    for ret, name, params in methods:
        lines.append(f"    {ret} {name}({params});")
    lines.append("}")
    return "\n".join(lines)

def gen_xml(mapper_ns, sql_blocks):
    body = []
    for i, block in enumerate(sql_blocks):
        # Heuristic: figure out what this SQL does
        head = block.split('\n')[0].strip()
        if head.startswith('SELECT'): tag='select'; result='resultType="..."'
        elif head.startswith('INSERT'): tag='insert'; result=''
        elif head.startswith('UPDATE'): tag='update'; result=''
        elif head.startswith('DELETE'): tag='delete'; result=''
        else: continue
        body.append(f'    <{tag} id="sql{i}" {result}>')
        body.append(f"        {block}")
        body.append(f"    </{tag}>")
        body.append("")
    return textwrap.dedent(f'''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="{mapper_ns}">

{chr(10).join(body)}
</mapper>
''')

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jdbc", required=True)
    ap.add_argument("--port", required=True)
    ap.add_argument("--table", required=True)
    ap.add_argument("--scope", choices=["tenant","platform","rag"], default="tenant")
    ap.add_argument("--columns", required=True)
    ap.add_argument("--out", default="platform/agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence")
    args = ap.parse_args()

    src_path = args.jdbc
    src = open(src_path).read()

    sql_blocks = extract_sql_blocks(src)
    print(f"[SQL] found {len(sql_blocks)} triple-quoted blocks:")
    for i, s in enumerate(sql_blocks):
        head = s.split('\n')[0][:80]
        print(f"  {i}: {head}")

    port_methods = find_port_methods(src_path.replace('Jdbc','').replace('.java',''))
    print(f"[Port] extracted {len(port_methods)} method signatures")
    for ret, name, params in port_methods[:10]:
        print(f"  {ret} {name}({params})")

    # Generate DO
    do_name = args.port.replace("Port","") + "DO"
    do_pkg = args.out + ".model"
    mapper_pkg = args.out + ".mapper"
    mapper_name = args.port.replace("Port","") + "Mapper"

    do_code = gen_do(args.table, args.columns, do_pkg)
    os.makedirs(args.out + "/model", exist_ok=True)
    os.makedirs(args.out + "/mapper", exist_ok=True)
    do_path = args.out + "/model/" + do_name
    # fix DO: need proper name, not "DO"
    do_code = do_code.replace("public class DO {", f"public class {do_name} {{")
    with open(do_path, "w") as f:
        f.write(do_code)
    print(f"[DO]   → {do_path}")

    mapper_code = gen_mapper(mapper_name, mapper_pkg, do_pkg + "." + do_name, [])
    mapper_path = args.out + "/mapper/" + mapper_name + ".java"
    with open(mapper_path, "w") as f:
        f.write(mapper_code)
    print(f"[Map]  → {mapper_path}")

    # XML
    mapper_ns = mapper_pkg + "." + mapper_name
    xml_dir = os.path.join(os.path.dirname(args.out), "..", "..", "..", "..", "resources", "mapper", "tenant" if args.scope=="tenant" else "ops", "")
    # resolve platform resources dir properly
    if "cap/" in args.jdbc:
        xml_subdir = "cap"
    elif "/agent/" in args.out:
        xml_subdir = "agent"
    else:
        xml_subdir = "config"
    xml_dir = f"platform/agent-persistence/src/main/resources/mapper/{'tenant' if args.scope=='tenant' else 'ops'}/{xml_subdir}"
    os.makedirs(xml_dir, exist_ok=True)
    xml_path = f"{xml_dir}/{mapper_name}.xml"
    with open(xml_path, "w") as f:
        f.write(gen_xml(mapper_ns, sql_blocks))
    print(f"[XML]  → {xml_path}")
    print("\n⚠️ 脚手架生成骨架，仍需人工：参数绑定 ? → #{}/@Param、结果映射、Repository 适配层、ApplicationConfiguration 装配。")

if __name__ == "__main__":
    main()
