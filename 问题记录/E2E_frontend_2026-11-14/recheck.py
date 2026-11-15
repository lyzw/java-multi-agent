import subprocess, json, os, time

E2E_OUT = os.environ.get("E2E_OUT", ".")
TID = "8394ad1e-5cb2-4d51-a0bb-2ee2e38b5751"
BASE = "http://localhost:5173"


def ab(*args, timeout=60):
    p = subprocess.run(["agent-browser", *args], capture_output=True, text=True, timeout=timeout)
    return p.stdout.strip()


def eval_js(js, timeout=30):
    return ab("eval", js, timeout=timeout)


def set_value(sel, text):
    js = (
        "(() => {"
        "  const el = document.querySelector(%r);"
        "  if (!el) return 'NO_EL';"
        "  const proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;"
        "  const setter = Object.getOwnPropertyDescriptor(proto, 'value').set;"
        "  setter.call(el, %r);"
        "  el.dispatchEvent(new Event('input', { bubbles: true }));"
        "  el.dispatchEvent(new Event('change', { bubbles: true }));"
        "  return 'SET:' + el.value;"
        "})()" % (sel, text)
    )
    return eval_js(js)


def click_button_by_text(text):
    js = (
        "(() => {"
        "  const btn = Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === %r);"
        "  if (!btn) return 'NO_BTN';"
        "  btn.click();"
        "  return 'CLICKED';"
        "})()" % text
    )
    return eval_js(js)


def snapshot():
    return ab("snapshot")


def get_url():
    try:
        return ab("get", "url")
    except Exception:
        return "?"


def wait(sec):
    time.sleep(sec)


def save(name, content):
    d = os.path.join(E2E_OUT, "recheck")
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, name), "w") as f:
        f.write(content)


def login(login_name, pw):
    ab("open", BASE + "/login")
    wait(1.5)
    set_value("#tenantId", TID)
    set_value("#loginName", login_name)
    set_value("#password", pw)
    wait(0.3)
    click_button_by_text("登录")
    wait(2.5)
    return get_url()


out = {}

# ── Scenario 1: student hits /config → expect /no-permission (F2 fix) ──
out["student_login_url"] = login("e2estudent", "E2e-Student-2026")
ab("open", BASE + "/config")
wait(2.0)
snap = snapshot()
save("student_config.txt", snap)
out["student_config_url"] = get_url()
out["student_config_no_perm_page"] = ("无权限" in snap) or ("无权" in snap)
out["student_config_silent_dashboard"] = ("无权执行该操作" in snap)

# ── Scenario 2: admin RAG search → observe error/result surfacing (F4) ──
out["admin_login_url"] = login("e2eadmin", "E2e-Admin-2026")
ab("open", BASE + "/rag")
wait(2.0)
out["rag_set"] = set_value("#rag-query", "一元二次方程求根公式是什么")
wait(0.3)
out["rag_click"] = click_button_by_text("检索并生成")
states = []
for _ in range(8):
    wait(2)
    s = snapshot()
    states.append(s)
    if any(k in s for k in ["检索失败", "生成回答失败", "没有找到相关语料", "整理方式",
                            "没有可引用的教材依据", "能力未接入", "正在检索", "正在整理"]):
        break
last = states[-1]
save("admin_rag_final.txt", last)
out["rag_final_url"] = get_url()
out["rag_error_or_empty_shown"] = any(k in last for k in
                                      ["检索失败", "生成回答失败", "没有找到相关语料", "能力未接入"])
out["rag_answer_shown"] = "整理方式" in last
out["rag_loading_seen"] = any("正在检索" in s or "正在整理" in s for s in states)

# ── Scenario 3: admin chat → observe error/result surfacing (F4) ──
ab("open", BASE + "/chat")
wait(2.0)
out["chat_set"] = set_value('textarea[aria-label="问题输入"]', "用一句话解释条件概率")
wait(0.3)
out["chat_click"] = click_button_by_text("发送")
states = []
for _ in range(8):
    wait(2)
    s = snapshot()
    states.append(s)
    if "gchat__error" in s or "gchat__text" in s:
        break
last = states[-1]
save("admin_chat_final.txt", last)
out["chat_final_url"] = get_url()
out["chat_msg_shown"] = any("gchat__text" in s for s in states)
out["chat_error_shown"] = "gchat__error" in last
out["chat_still_empty"] = "还没有提问" in last

print(json.dumps(out, ensure_ascii=False, indent=2))
