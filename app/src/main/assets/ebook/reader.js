/* 2026-11-26 — Solar E-Book reader paging.
   Takes the chapter's own HTML, moves it into a column container sized from the live
   viewport (so density needs no special casing), and turns pages with scrollLeft —
   no re-flow while reading. window.__solarInit carries the start spread and labels. */
(function () {
  var doc = document;
  var init = window.__solarInit || { start: 0, title: '', chapter: '' };
  var HEAD = 22, FOOT = 18, COLS = 2, GAP = 10, COLW = 160;
  var started = false, total = 1, pageStep = 0;

  function build() {
    /* This script is injected in <head>, so body may not exist yet — resolve it now. */
    var html = doc.documentElement;
    var body = doc.body || html;
    var W = html.clientWidth || window.innerWidth;
    COLS = W >= 300 ? 2 : 1;
    GAP = Math.max(6, Math.round(W * 0.025));
    COLW = Math.floor((W - (COLS - 1) * GAP) / COLS);

    var hd = doc.createElement('div');
    hd.id = 'solar-hd';
    body.appendChild(hd);
    var ft = doc.createElement('div');
    ft.id = 'solar-ft';
    body.appendChild(ft);

    var view = doc.createElement('div');
    view.id = 'solar-view';
    view.style.top = HEAD + 'px';
    view.style.bottom = FOOT + 'px';

    var flow = doc.createElement('div');
    flow.id = 'solar-flow';
    while (body.firstChild && body.firstChild.id !== 'solar-hd'
           && body.firstChild.id !== 'solar-ft') {
      flow.appendChild(body.firstChild);
    }
    flow.style.columnWidth = COLW + 'px';
    flow.style.webkitColumnWidth = COLW + 'px';
    flow.style.columnGap = GAP + 'px';
    flow.style.webkitColumnGap = GAP + 'px';

    view.appendChild(flow);
    body.appendChild(view);
    hd.textContent = init.title;
  }

  function measure() {
    var flow = doc.getElementById('solar-flow');
    if (!flow) return 0;
    void flow.offsetHeight; /* force layout before reading the column extent */
    var cols = Math.max(1, Math.floor((flow.scrollWidth + GAP + 1) / (COLW + GAP)));
    total = Math.max(1, Math.ceil(cols / COLS));
    /* Use the real column extent: the browser rounds column widths, so a stride of
       COLS*(COLW+GAP) would drift a few pixels every turn. */
    var maxScroll = Math.max(0, flow.scrollWidth - flow.clientWidth);
    pageStep = total > 1 ? maxScroll / (total - 1) : 0;
    if (window.SolarReader && window.SolarReader.onReady) {
      window.SolarReader.onReady(total);
    }
    return total;
  }

  function label(i) {
    var ft = doc.getElementById('solar-ft');
    if (!ft) return;
    var a = i * COLS + 1;
    var b = Math.min(total * COLS, i * COLS + COLS);
    ft.textContent = (b > a ? a + '\u2013' + b : '' + a) + ' / ' + (total * COLS)
      + '  \u00b7  ' + init.chapter;
  }

  window.solarGo = function (i) {
    var flow = doc.getElementById('solar-flow');
    if (!flow) return;
    if (i < 0) i = 0;
    if (i > total - 1) i = total - 1;
    flow.scrollLeft = Math.round(i * pageStep);
    label(i);
    if (window.SolarReader && window.SolarReader.onPage) {
      window.SolarReader.onPage(i, total);
    }
  };

  window.solarRemeasure = measure;

  function kick() {
    if (started) return;
    var W = doc.documentElement.clientWidth || window.innerWidth;
    if (!W) { setTimeout(kick, 50); return; }
    started = true;
    build();
    measure();
    window.solarGo(init.start);
  }

  if (doc.readyState === 'complete' || doc.readyState === 'interactive') kick();
  else doc.addEventListener('DOMContentLoaded', kick, false);
  window.addEventListener('load', function () { measure(); }, false);
})();
