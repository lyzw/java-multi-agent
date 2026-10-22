#!/usr/bin/env bash
# 运行 Demo 与测试（在 agent-permission-demo/ 目录下执行）
set -e
cd "$(dirname "$0")"
echo ">>> 调用链示例"
python examples/call_chain_demo.py
echo
echo ">>> 单元测试"
python -m tests.test_permission
