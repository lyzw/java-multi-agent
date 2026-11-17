import re
import os

# Paths
TRACKER_PATH = "docs/01_baseline/management/PROJECT_STATUS_TRACKER.md"
GREP_FILE = "grep_output.txt"

def parse_line(line):
    # format: path:line_content
    if ":" not in line:
        return None
    
    parts = line.split(":", 1)
    file_path = parts[0].strip()
    content = parts[1].strip()
    
    if "[待确认]" not in content:
        return None
    
    # Clean up content
    # Sometimes it's in a table format: | Role | [待确认] |
    # Sometimes it's a bullet: - Item: [待确认]
    
    # Basic extraction logic
    # Try to see if it's a table line
    if "|" in content:
        cells = [c.strip() for c in content.split("|") if c.strip()]
        if len(cells) >= 2:
            # If it's like: | Role | [待确认] |
            if "[待确认]" in cells[1]:
                return {
                    "file": file_path,
                    "item": cells[0],
                    "detail": cells[1],
                    "person": "", # Hard to tell without more logic, maybe cells[0] is the person?
                    "context": ""
                }
            elif len(cells) >= 3:
                # | Role | Description | [待确认] |
                return {
                    "file": file_path,
                    "item": cells[0],
                    "detail": cells[1],
                    "person": "",
                    "context": cells[2]
                }
    
    # Try to handle standard bullets: "- Item: [待确认]"
    bullet_match = re.match(r"[-*]\s*(.*?):\s*\[待确认\]", content)
    if bullet_match:
        return {
            "file": file_path,
            "item": bullet_match.group(1),
            "detail": "[待确认]",
            "person": "",
            "context": ""
        }
    
    # Fallback
    return {
        "file": file_path,
        "item": content.split("[待确认]")[0].strip(),
        "detail": "[待确认]",
        "person": "",
        "context": ""
    }

def main():
    if not os.path.exists(GREP_FILE):
        print(f"Error: {GREP_FILE} not found")
        return

    items = []
    with open(GREP_FILE, 'r', encoding='utf-8') as f:
        for line in f:
            parsed = parse_line(line.strip())
            if parsed:
                items.append(parsed)

    # Group items by file to make it readable
    files_map = {}
    for item in items:
        f = item['file']
        if f not in files_map:
            files_map[f] = []
        files_map[f].append(item)

    with open(TRACKER_PATH, 'w', encoding='utf-8') as f:
        f.write("# Project Status Tracker - 已知待确认事项汇总\n\n")
        f.write("> **说明**：本文件汇总了项目中所有文档中标记为 `[待确认]` 的项。用于集中追踪项目风险、决策项及未明确的需求细节。\n\n")
        f.write("| 源文件 | 类别 / 事项 | 详细描述 / 补充信息 | 责任人 | 状态 |\n")
        f.write("| :--- | :--- | :--- | :--- | :--- |\n")

        for file_path, file_items in files_map.items():
            # Get the filename for display
            display_name = os.path.basename(file_path)
            
            # We'll print one header row per file to group them
            # However, to keep table integrity, we must provide empty cells for the other columns
            # Or we can just list everything. Let's list everything but prefix with file name.
            
            for item in file_items:
                f_name = display_name
                item_name = item['item']
                item_detail = item['detail']
                item_person = item['person']
                item_context = item['context']
                
                # Clean up details if they are just [待确认]
                if item_detail == "[待确认]" and not item_context:
                    item_detail = "-"
                
                # Combine detail and context if needed
                if item_context:
                    item_detail = f"{item_detail} ({item_context})"
                
                f.write(f"| `{f_name}` | {item_name} | {item_detail} | {item_person} | 🔴 PENDING |\n")

    print(f"Successfully created {TRACKER_PATH} with {len(items)} items.")

if __name__ == "__main__":
    main()
