(function(){
  'use strict';
  var SITE = window.KB_SITE || {pages:[]};
  var pageId = document.body.getAttribute('data-page');
  var toc = document.getElementById('toc');
  var links = [], lastGroup = '';
  var out = '';
  SITE.pages.forEach(function(p){
    if(p.group !== lastGroup){ out += '<div class="toc-part">'+p.group+'</div>'; lastGroup = p.group; }
    out += '<ul><li><a class="lv1'+(p.id===pageId?' current':'')+'" href="'+p.file+'">'+p.title+'</a></li>';
    if(p.id===pageId){
      out += '<ul>';
      p.chapters.forEach(function(c){
        out += '<li><a class="lv2" href="#'+c.id+'">'+c.title+'</a></li>';
        c.subs.forEach(function(s){
          out += '<li><a class="lv3" href="#'+s.id+'">'+s.title+'</a></li>';
        });
      });
      out += '</ul>';
    }
    out += '</ul>';
  });
  toc.innerHTML = out;
  links = Array.prototype.slice.call(toc.querySelectorAll('a[href^="#"]'));

  /* 滚动高亮（仅当前页内锚点） */
  var targets = [];
  links.forEach(function(a){
    var el = document.getElementById(a.getAttribute('href').slice(1));
    if(el) targets.push({el:el,a:a});
  });
  function spy(){
    var y = window.scrollY + 110, best = null;
    targets.forEach(function(t){ if(t.el.getBoundingClientRect().top + window.scrollY <= y) best = t; });
    links.forEach(function(a){ a.classList.remove('active'); });
    if(best) best.a.classList.add('active');
    document.getElementById('backtop').style.display = window.scrollY > 400 ? 'block' : 'none';
  }
  window.addEventListener('scroll', spy, {passive:true}); spy();

  /* 全站检索：页面标题 + 章节/小节标题 + 关键词索引 */
  var input = document.getElementById('search'), box = document.getElementById('search-results');
  function esc(s){ return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }
  function doSearch(){
    var q = input.value.trim().toLowerCase();
    if(!q){ box.style.display='none'; return; }
    var out = [], n = 0;
    SITE.pages.forEach(function(p){
      if(n < 26 && (p.title+' '+p.sub).toLowerCase().indexOf(q) >= 0){
        out.push('<a href="'+p.file+'"><span class="sr-page">分册</span>'+esc(p.title)+'</a>'); n++;
      }
      p.chapters.forEach(function(c){
        if(n < 26 && (c.title+' '+(c.keywords||'')).toLowerCase().indexOf(q) >= 0){
          out.push('<a href="'+p.file+'#'+c.id+'"><span class="sr-page">'+esc(p.title)+' · 章</span>'+esc(c.title)+'</a>'); n++;
        }
        c.subs.forEach(function(s){
          if(n < 26 && s.title.toLowerCase().indexOf(q) >= 0){
            out.push('<a href="'+p.file+'#'+s.id+'"><span class="sr-page">'+esc(p.title)+' · 节</span>　└ '+esc(s.title)+'</a>'); n++;
          }
        });
      });
    });
    box.innerHTML = out.length ? out.join('') :
      '<div class="sr-empty">没有匹配项。可试配置键（AGENT_MESSAGING_MODE）、类名（TenantSessionBinder）、决策编号（ADR-013）或问题编号（TST-01）。</div>';
    box.style.display = 'block';
  }
  input.addEventListener('input', doSearch);
  input.addEventListener('focus', doSearch);
  document.addEventListener('click', function(e){ if(!box.contains(e.target) && e.target !== input) box.style.display='none'; });
  box.addEventListener('click', function(e){ if(e.target.closest('a')) box.style.display='none'; });
  document.addEventListener('keydown', function(e){
    if(e.key==='Escape'){ box.style.display='none'; input.blur(); }
    if((e.metaKey||e.ctrlKey) && e.key==='k'){ e.preventDefault(); input.focus(); input.select(); }
  });

  /* 代码块复制 */
  document.querySelectorAll('.code-block').forEach(function(b){
    var btn = b.querySelector('button'); if(!btn) return;
    btn.addEventListener('click', function(){
      var pre = b.querySelector('pre'); if(!pre) return;
      var txt = pre.innerText;
      if(navigator.clipboard && navigator.clipboard.writeText){
        navigator.clipboard.writeText(txt).then(function(){ btn.textContent='已复制'; setTimeout(function(){btn.textContent='复制';},1400); });
      }
    });
  });

  var pb = document.getElementById('print-btn'); if(pb) pb.addEventListener('click', function(){ window.print(); });
  document.getElementById('backtop').addEventListener('click', function(){ window.scrollTo({top:0,behavior:'smooth'}); });
  var sb = document.getElementById('sidebar');
  document.getElementById('menu-toggle').addEventListener('click', function(){ sb.classList.toggle('open'); });
  document.addEventListener('click', function(e){
    if(window.innerWidth<=1050 && sb.classList.contains('open') && !sb.contains(e.target) && e.target.id!=='menu-toggle') sb.classList.remove('open');
  });
})();
