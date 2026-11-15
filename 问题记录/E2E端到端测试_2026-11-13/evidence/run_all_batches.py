#!/usr/bin/env python3
"""串行执行 5 个交互批次；每批限时 12 分钟，失败不阻断后续批次。"""
import subprocess, sys, time, os
D = '/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/问题记录/E2E端到端测试_2026-11-13'
LOGS = D + '/logs'
# 从头开始，避免上一次中断产生的重复记录
open(LOGS + '/interactions.jsonl', 'w', encoding='utf-8').close()
batches = ['batch1_session.py', 'batch2_governance.py', 'batch3_catalog.py',
           'batch4_rag_lab.py', 'batch5_roles.py']
for b in batches:
    t = time.time()
    print('=== START', b, flush=True)
    try:
        p = subprocess.run([sys.executable, D + '/evidence/' + b],
                           capture_output=True, text=True, timeout=720)
        out = p.stdout + p.stderr
        with open(LOGS + '/' + b.replace('.py', '_run.log'), 'w', encoding='utf-8') as f:
            f.write(out)
        print(out[-4000:], flush=True)
        print('=== END', b, 'rc', p.returncode, 'sec', round(time.time() - t, 1), flush=True)
    except subprocess.TimeoutExpired as e:
        print('=== TIMEOUT', b, flush=True)
        with open(LOGS + '/' + b.replace('.py', '_run.log'), 'w', encoding='utf-8') as f:
            f.write((e.stdout or b'') if isinstance(e.stdout, bytes) else (e.stdout or ''))
print('ALL BATCHES FINISHED', flush=True)
