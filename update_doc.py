import sys

file_path = sys.argv[1]

with open(file_path, 'r', encoding='utf-8') as f:
    lines = f.readlines()

def update_table(lines, marker, new_rows):
    header_idx = -1
    for i, line in enumerate(lines):
        if marker in line:
            header_idx = i
            break
    
    if header_idx == -1:
        return False
    
    # Find the start of the table (the header)
    # The header line should be a line with | and the marker
    # But wait, the marker is in the section header.
    # The table follows.
    
    table_start = -1
    for j in range(header_idx + 1, len(lines)):
        # Look for the header line (e.g. | 列 | 类型 | 说明 |)
        if lines[j].strip().startswith('|') and '列' in lines[j]:
            table_start = j
            break
            
    if table_start == -1:
        return False
    
    # Find end of table
    table_end = -1
    for k in range(table_start, len(lines)):
        if k > table_start and not lines[k].strip().startswith('|'):
            table_end = k
            break
    if table_end == -1:
        table_end = len(lines)
    
    new_table = [
        "| 列 | 类型 | 说明 |\n",
        "|---|---|---|\n"
    ] + [r + "\n" for r in new_rows]
    
    lines[table_start:table_end] = new_table
    return True

tool_rows = [
    '| id | varchar PK |',
    '| name | varchar |',
    '| kind | varchar |',
    '| risk_level | varchar |',
    '| mcp_server_id | varchar? |',
    '| mcp_tool_name | varchar? |',
    '| httpConfig | jsonb? |',
    '| input_schema | jsonb |',
    '| description | varchar? |',
    '| config_fingerprint | varchar |',
    '| schemaVersion | int |',
    '| version | int |',
    '| created_at | ts |',
    '| publishedAt | ts? |'
]

skill_rows = [
    '| id | varchar PK |',
    '| name / description | varchar |',
    '| declared_tools | jsonb | Tool id 列表（引用，非复制）',
    '| declared_mcp | jsonb | `[{serverId, toolAllowlist?}]`',
    '| risk_ceiling | varchar | 上限',
    '| param_policies | jsonb | V1 仅 `in_allowlist`/`lte`（其他 op 写入即拒）',
    '| inherits | jsonb | **V1 写入即拒**',
    '| required_caps | jsonb | V1 忽略语义（预留）',
    '| config_fingerprint | varchar |',
    '| schemaVersion | int |',
    '| version | int |',
    '| created_at | ts |',
    '| publishedAt | ts? |'
]

success_tool = update_table(lines, "cap_tool_def", tool_rows)
success_skill = update_table(lines, "cap_skill_def", skill_rows)

if success_tool or success_skill:
    with open(file_path, 'w', encoding='utf-8') as f:
        f.writelines(lines)
    print(f"Updated: tool={success_tool}, skill={success_skill}")
else:
    print("Failed to update any table.")
