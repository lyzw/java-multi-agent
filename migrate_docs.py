import os
import shutil
import json
import sys

# Configuration
DRY_RUN = True # Set to False to actually move files

def get_new_path(file_path):
    """
    Determine the new location of a file based on the project reorganization plan.
    """
    # 1. Handle Archive (Versions and specific old files)
    if "v3.0" in file_path or "v1.0-draft" in file_path or "v1.0-baseline" in file_path:
        # Implementation plan v1.1 is the new master, keep it out of archive
        if "Implementation_Plan" not in file_path:
            return f"docs/05_archive/{os.path.basename(file_path)}"

    # 2. Baseline (Core Project Files)
    # Requirements: P0-D01 to P0-D06
    if "docs/P0/P0/" in file_path:
        if any(pattern in file_path for pattern in ["P0-D01", "P0-D02", "P0-D03", "P0-D04", "P0-D05", "P0-D06"]):
            return f"docs/01_baseline/requirements/{os.path.basename(file_path)}"
    
    # Management: README and Catalog in P0
    if file_path == "docs/P0/README_P0文档包说明.md" or file_path == "docs/P0/文档清单.md":
        return f"docs/01_baseline/management/{os.path.basename(file_path)}"

    # Milestones: P1, P2, P3, P4
    if file_path.startswith("docs/P0/P1/") or \
       file_path.startswith("docs/P0/P2/") or \
       file_path.startswith("docs/P0/P3/") or \
       file_path.startswith("docs/P0/P4/"):
        # Keep the subdirectory structure inside milestones
        sub_path = os.path.relpath(file_path, "docs/P0")
        return f"docs/01_baseline/milestones/{sub_path}"

    # Architecture: v3.1
    if "架构设计方案 v3.1" in file_path:
        return f"docs/01_baseline/architecture/{os.path.basename(file_path)}"
    
    # Archive: v3.0
    if "架构设计方案 v3.0" in file_path:
        return f"docs/05_archive/{os.path.basename(file_path)}"

    # Implementation Plan (The master version v1.1)
    if "软件开发阶段实施计划与文档交付清单.md" in file_path:
        if "多级 Agent优化" in file_path:
            return f"docs/01_baseline/management/Implementation_Plan_v3.1.md"
        else:
            return f"docs/05_archive/{os.path.basename(file_path)}"

    # Capabilities
    if file_path.startswith("docs/能力维度深度报告"):
        return f"docs/01_baseline/capabilities/{os.path.relpath(file_path, 'docs/能力维度深度报告')}"
    
    # Evidence
    if file_path.startswith("docs/门禁证据") or file_path.startswith("docs/RAG-D09-G1"):
        if file_path.startswith("docs/门禁证据"):
            return f"docs/03_evidence/{os.path.relpath(file_path, 'docs/门禁证据')}"
        else:
            return f"docs/03_evidence/{os.path.basename(file_path)}"

    # Development (Feature Specific)
    if file_path.startswith("docs/管理后台开发计划"):
        return f"docs/02_development/admin-console/{os.path.relpath(file_path, 'docs/管理后台开发计划')}"

    if file_path.startswith("docs/统一配置与权限管控体系_开发计划"):
        return f"docs/02_development/permission-sys/{os.path.relpath(file_path, 'docs/统一配置与权限管控体系_开发计划')}"

    if file_path.startswith("docs/多级 Agent优化"):
        # Skip the implementation plan as it's handled above
        if "实施计划" not in file_path:
            return f"docs/02_development/multi-level-agent/{os.path.relpath(file_path, 'docs/多级 Agent优化')}"
    
    # Knowledge
    if file_path.startswith("docs/Java后端转型知识库"):
        return f"docs/04_knowledge/{os.path.relpath(file_path, 'docs/Java后端转型知识库')}"

    # Everything else in docs/ goes to archive to ensure cleanup
    if file_path.startswith("docs/"):
        # Avoid moving files that are already in the target structure or in top-level docs/
        # (but for a complete cleanup, anything not matched should be archived)
        return f"docs/05_archive/{os.path.relpath(file_path, 'docs')}"
    
    return file_path

def main():
    if not os.path.exists('all_docs_list.txt'):
        print("Error: all_docs_list.txt not found")
        sys.exit(1)

    with open('all_docs_list.txt', 'r') as f:
        files = [line.strip() for line in f.readlines() if line.strip()]

    plan = []
    for f in files:
        new_p = get_new_path(f)
        if new_p != f and not f.startswith("docs/0"): # Don't move things already in new structure
            plan.append({'old': f, 'new': new_p})

    if not plan:
        print("No files to move.")
        return

    print(f"--- {'DRY RUN' if DRY_RUN else 'ACTUAL MOVE'} ---")
    print(f"Plan contains {len(plan)} files.")

    for entry in plan:
        old = entry['old']
        new = entry['new']

        if DRY_RUN:
            print(f"PLAN: {old} -> {new}")
        else:
            # Ensure directory exists
            os.makedirs(os.path.dirname(new), exist_ok=True)
            try:
                if os.path.exists(old):
                    shutil.move(old, new)
                    print(f"MOVED: {old} -> {new}")
                else:
                    print(f"SKIP (not found): {old}")
            except Exception as e:
                print(f"ERROR moving {old}: {e}")

    if not DRY_RUN:
        print("\nChecking for empty directories in docs/ to clean up...")
        # A simple way to clean up: try to remove all directories in docs/ 
        # that do not start with '0' and are not in the target list.
        # This is risky, so let's just list them for user review and manually remove important ones.
        for root, dirs, files in os.walk("docs", topdown=False):
            for name in dirs:
                dir_path = os.path.join(root, name)
                if not os.path.exists(dir_path) or not os.listdir(dir_path):
                    # If it's an old-style directory, try to remove it
                    if not any(arg in dir_path for arg in ["01_baseline", "02_development", "03_evidence", "04_knowledge", "05_archive"]):
                        try:
                            os.rmdir(dir_path)
                            print(f"REMOVED EMPTY DIR: {dir_path}")
                        except Exception as e:
                            print(f"COULD NOT REMOVE DIR {dir_path}: {e}")

if __name__ == "__main__":
    # Read command line arg for dry run if provided
    if len(sys.argv) > 1 and sys.argv[1] == "--run":
        DRY_RUN = False
    
    main()
