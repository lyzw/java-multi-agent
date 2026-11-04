// RAG 语料管理 · 共享外壳：侧边栏四治理域 + 顶栏
// 通过 <body data-page="..."> 标记当前高亮 nav。

function shell(breadcrumb, scope) {
  const nav = `
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-logo">R</div>
        <div>
          <div class="brand-name">RAG 语料管理</div>
          <div class="brand-sub">语料 · 检索 · 治理</div>
        </div>
      </div>

      <div class="nav-group">
        <div class="nav-group-title">总览</div>
        <a class="nav-item" data-page="dashboard" href="#"><span class="dot" style="background:var(--brand-500)"></span>Dashboard<span class="count">5</span></a>
      </div>

      <div class="nav-group">
        <div class="nav-group-title">录入与校对</div>
        <a class="nav-item" data-page="source-new"  href="#"><span class="dot"></span>来源登记</a>
        <a class="nav-item" data-page="source-list" href="#"><span class="dot"></span>来源列表</a>
        <a class="nav-item" data-page="doc-upload"  href="#"><span class="dot"></span>载体上传与解析预览<span class="count">3</span></a>
        <a class="nav-item" data-page="chunk-fix"   href="#"><span class="dot"></span>片段校对<span class="count">12</span></a>
      </div>

      <div class="nav-group">
        <div class="nav-group-title">审核</div>
        <a class="nav-item" data-page="verify"      href="#"><span class="dot"></span>校验决定<span class="count">6</span></a>
        <a class="nav-item" data-page="inspect"     href="#"><span class="dot"></span>抽检队列</a>
        <a class="nav-item" data-page="recall"      href="#"><span class="dot"></span>召回解释（质检）</a>
      </div>

      <div class="nav-group">
        <div class="nav-group-title">规则配置</div>
        <a class="nav-item" data-page="roster"      href="#"><span class="dot"></span>审核员名录</a>
        <a class="nav-item" data-page="kpalias"     href="#"><span class="dot"></span>知识点别名</a>
        <a class="nav-item" data-page="dict"        href="#"><span class="dot"></span>字典</a>
      </div>

      <div class="nav-group">
        <div class="nav-group-title">运维</div>
        <a class="nav-item" data-page="jobs"        href="#"><span class="dot"></span>摄取任务</a>
        <a class="nav-item" data-page="reindex"     href="#"><span class="dot"></span>重索引</a>
      </div>
    </aside>`;

  const top = `
    <div class="topbar">
      <span class="crumb">RAG 语料管理 <b>· ${breadcrumb}</b></span>
      <span class="spacer"></span>
      <span class="tag"><span class="key">租户</span>studykid_上海</span>
      <span class="tag"><span class="key">作用域</span>${scope || '租户档'}</span>
      <span class="tag"><span class="key">角色</span>审核员</span>
      <span class="tag" style="background:var(--info-soft);border-color:#c7d8fd;color:#1e40af">原型 · 交互示意</span>
    </div>`;

  document.body.innerHTML = `<div class="app">${nav}<div class="main">${top}<div class="content" id="content"></div></div></div>`;

  const page = document.body.dataset.page;
  document.querySelectorAll('.nav-item').forEach(a => {
    if (a.dataset.page === page) a.classList.add('active');
  });
}