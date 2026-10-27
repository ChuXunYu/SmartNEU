const $ = (s) => document.querySelector(s);
const $$ = (s) => Array.from(document.querySelectorAll(s));

const state = {
  session: null,
  data: null,
  weekStart: null,
  selectedDay: null,
  schedule: null,
  tab: "schedule",
  view: (() => { try { return localStorage.getItem("neu.schedule.view") || "week"; } catch { return "week"; } })(),
};

/* ---------------- 工具 ---------------- */
const pad = (n) => String(n).padStart(2, "0");
const ymd = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const WEEK_NAMES = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"];

function sundayOf(d) {
  const x = new Date(d);
  x.setDate(x.getDate() - x.getDay());
  x.setHours(0, 0, 0, 0);
  return x;
}
function toast(msg) {
  const t = $("#toast");
  t.textContent = msg;
  t.classList.add("show");
  clearTimeout(t._t);
  t._t = setTimeout(() => t.classList.remove("show"), 2600);
}
function esc(s) {
  return String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

/* ---------------- 请求（Web / Android 桥） ---------------- */
async function httpApi(path, opts = {}) {
  const r = await fetch(path, { headers: { "Content-Type": "application/json" }, ...opts });
  const j = await r.json().catch(() => ({}));
  if (!r.ok && !j.error) throw new Error(`HTTP ${r.status}`);
  if (j.error) throw new Error(j.error);
  return j;
}
function nativeCall(action, payload) {
  const raw = window.NEU.call(action, JSON.stringify(payload || {}));
  const j = JSON.parse(raw || "{}");
  if (j.error) { const e = new Error(j.error); e.code = j.code; throw e; }
  return j;
}

const neuPending = new Map();
let neuSeq = 0;
function nativeCallAsync(action, payload) {
  if (!window.NEU.callAsync || !window.NEU.poll) {
    return Promise.resolve(nativeCall(action, payload));
  }
  return new Promise((resolve, reject) => {
    const id = "r" + (++neuSeq);
    try {
      window.NEU.callAsync(action, JSON.stringify(payload || {}), id);
    } catch (e) {
      reject(e);
      return;
    }
    const started = Date.now();
    const timer = setInterval(() => {
      let raw = "";
      try { raw = window.NEU.poll(id); } catch (e) {
        clearInterval(timer);
        reject(e);
        return;
      }
      if (raw) {
        clearInterval(timer);
        try {
          const j = JSON.parse(raw);
          if (j.error) { const e = new Error(j.error); e.code = j.code; reject(e); }
          else resolve(j);
        } catch (e) { reject(e); }
      } else if (Date.now() - started > 120000) {
        clearInterval(timer);
        reject(new Error("请求超时"));
      }
    }, 60);
  });
}
async function nativeApi(path, opts = {}) {
  const [p, qs] = path.split("?");
  const params = new URLSearchParams(qs || "");
  const body = opts.body ? JSON.parse(opts.body) : {};
  switch (p) {
    case "/api/session": return nativeCall("session");
    case "/api/overview": return nativeCallAsync("overview", { probe: params.get("probe") === "1" });
    case "/api/schedule": return nativeCallAsync("schedule", { start: params.get("start") || "" });
    case "/api/net": return nativeCallAsync("net");
    case "/api/ipgw": return nativeCallAsync("ipgw");
    case "/api/jwxt": return nativeCallAsync("jwxt", { probe: params.get("probe") === "1" });
    case "/api/login": return nativeCallAsync("login", body);
    case "/api/sms": return nativeCallAsync("sms", body);
    case "/api/logout": return nativeCallAsync("logout");
    default: throw new Error("unsupported: " + p);
  }
}
const api = window.NEU ? nativeApi : httpApi;

/* ---------------- 启动与缓存 ---------------- */
const CACHE_KEY = "neu.overview.v2";
let refreshing = false;

function loadCache() {
  try {
    const c = JSON.parse(localStorage.getItem(CACHE_KEY) || "null");
    return c && c.data && c.data.profile ? c : null;
  } catch { return null; }
}
function saveCache(d) {
  try { localStorage.setItem(CACHE_KEY, JSON.stringify({ at: Date.now(), data: d })); } catch {}
}
function clearCache() {
  try { localStorage.removeItem(CACHE_KEY); } catch {}
}
function setUpdated(ts) {
  const el = $("#updatedAt");
  if (!el) return;
  if (!ts) { el.textContent = ""; return; }
  const d = new Date(ts);
  el.textContent = `数据更新于 ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function renderAll(d) {
  state.data = d;
  renderHeader(d);
  renderSchedule(d.schedule);
  renderExams(d.exams);
  renderGrades(d.grades);
  renderNet(d.net);
  if (d.session?.imei) $("#imei").textContent = `设备 ${d.session.imei}`;
}

let refreshStartedAt = 0;
function startRefresh() {
  refreshStartedAt = performance.now();
  document.body.classList.add("updating");
  $("#progress").classList.remove("hidden");
  const chip = $("#updateChip");
  chip.classList.remove("hidden", "done");
  $("#updateText").textContent = "正在更新";
}
function finishRefresh(ok) {
  // 至少让“更新中”停留 600ms，避免一闪而过
  const delay = Math.max(0, 600 - (performance.now() - refreshStartedAt));
  setTimeout(() => {
    document.body.classList.remove("updating");
    $("#progress").classList.add("hidden");
    const chip = $("#updateChip");
    if (!ok) { chip.classList.add("hidden"); return; }
    chip.classList.add("done");
    $("#updateText").textContent = "已更新";
    setTimeout(() => chip.classList.add("hidden"), 900);
  }, delay);
}

async function refresh() {
  if (refreshing) return;
  refreshing = true;
  startRefresh();
  try {
    const d = await api("/api/overview");
    renderAll(d);
    renderStats(state.tab, false);
    saveCache(d);
    setUpdated(Date.now());
    finishRefresh(true);
    setTimeout(loadIpwg, 500);
  } catch (e) {
    toast("更新失败：" + e.message);
    finishRefresh(false);
  } finally {
    refreshing = false;
  }
}

let ipgwLoading = false;
async function loadIpwg() {
  if (ipgwLoading || !state.data || !state.data.net) return;
  ipgwLoading = true;
  try {
    const u = await api("/api/ipgw");
    if (u && u.ok) {
      Object.assign(state.data.net, u);
      renderNet(state.data.net);
      saveCache(state.data);
    }
  } catch (e) {
    // 静默失败，保留上一次/手动测算
  } finally {
    ipgwLoading = false;
  }
}

let silentLoading = false;

async function boot() {
  try {
    state.session = await api("/api/session");
  } catch (e) {
    toast("无法连接后端");
    return;
  }
  const logged = !!state.session.logged_in;
  $("#loginView").classList.toggle("hidden", logged);
  $("#appView").classList.toggle("hidden", !logged);
  if (!logged) return;
  if (state.session.demo) toast("演示模式：示例数据");

  const cache = loadCache();
  if (cache) {
    // 秒开：先用上次成功刷新的数据渲染，不发起网络请求
    state.data = cache.data;
    renderAll(cache.data);
    renderStats(state.tab, false);
    setUpdated(cache.at);
    return;
  }
  // 首次安装：先用本地资料占位，再拉一次
  renderHeader({
    profile: state.session.profile || {},
    schedule: { events: [] },
    exams: { items: [], status: "loading" },
    grades: { items: [], rank: {} },
    net: { cards: [], links: [] },
  });
  showSkeleton();
  await refresh();
}

function greeting() {
  const h = new Date().getHours();
  if (h < 6) return "夜深了";
  if (h < 11) return "早上好";
  if (h < 14) return "中午好";
  if (h < 18) return "下午好";
  return "晚上好";
}

function showSkeleton() {
  $("#timeline").innerHTML = `<div class="skeleton"></div><div class="skeleton"></div>`;
  $("#examList").innerHTML = `<div class="skeleton"></div><div class="skeleton"></div>`;
}

function renderHeader(d) {
  const p = d.profile || {};
  const now = new Date();
  const wd = ["星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六"][now.getDay()];
  $("#todayLabel").textContent = `${now.getFullYear()} / ${pad(now.getMonth() + 1)} / ${pad(now.getDate())} · ${wd}`;
  $("#greet").textContent = `${greeting()}，${p.name || "同学"}`;
  $("#who").textContent = [p.depart, p.xgh || p.username, p.role].filter(Boolean).join(" · ");
  $("#btnLogout").textContent = (p.name || "我").slice(0, 1);
  $("#btnImport").classList.toggle("hidden", !window.NEU);
  renderStats(state.tab, false);
}

/* ---------------- 顶部概览（随标签变化） ---------------- */
function statsFor(tab) {
  const d = state.data || {};
  const today = ymd(new Date());
  if (tab === "schedule") {
    const ev = (d.schedule || {}).events || [];
    const days = new Set(ev.map((e) => (e.start || "").slice(0, 10)));
    return [
      { v: ev.length, l: "本周课程" },
      { v: ev.filter((e) => (e.start || "").slice(0, 10) === today).length, l: "今日课程" },
      { v: days.size, l: "有课天数" },
    ];
  }
  if (tab === "exams") {
    const items = (d.exams || {}).items || [];
    const up = items.filter((x) => String(x.time || "").slice(0, 10) >= today).length;
    return [
      { v: up, l: "待考" },
      { v: items.length - up, l: "已结束" },
      { v: items.length, l: "总场次" },
    ];
  }
  if (tab === "grades") {
    const items = (d.grades || {}).items || [];
    const credits = items.reduce((s, x) => s + (parseFloat(x.credit) || 0), 0);
    return [
      { v: items.length, l: "课程" },
      { v: credits.toFixed(1), l: "学分" },
      { v: items.filter((x) => x.passed !== "否").length, l: "通过" },
    ];
  }
  return [];
}

function renderStats(tab = state.tab, animate = true) {
  const led = $("#ledger");
  if (tab === "net") { led.classList.add("hidden"); return; }
  led.classList.remove("hidden");
  const paint = () => {
    const cells = statsFor(tab);
    for (let i = 0; i < 3; i++) {
      const c = cells[i] || { v: "—", l: "" };
      $("#st" + (i + 1)).textContent = c.v;
      $("#sl" + (i + 1)).textContent = c.l;
    }
  };
  if (animate && state.data) {
    led.classList.add("swap");
    setTimeout(() => { paint(); led.classList.remove("swap"); }, 110);
  } else {
    paint();
  }
}

/* ---------------- 课表 ---------------- */
function renderSchedule(schedule) {
  if (!schedule || schedule.error) {
    $("#dayStrip").innerHTML = "";
    $("#timeline").innerHTML = `<div class="empty">${esc(schedule?.error || "暂无课表")}</div>`;
    return;
  }
  state.schedule = schedule;
  const start = new Date((schedule.start || ymd(sundayOf(new Date()))) + "T00:00:00");
  state.weekStart = start;
  $("#weekTitle").textContent = schedule.week ? `第 ${schedule.week} 周` : "课程表";
  $("#weekRange").textContent = `${schedule.start?.slice(5)} ~ ${schedule.end?.slice(5)}`;

  const today = ymd(new Date());
  const days = [];
  for (let i = 0; i < 7; i++) {
    const d = new Date(start.getTime() + i * 86400000);
    days.push({ date: ymd(d), label: WEEK_NAMES[i], dayNum: d.getDate() });
  }
  if (state.selectedDay == null) {
    const todayIndex = days.findIndex((x) => x.date === today);
    state.selectedDay = todayIndex >= 0 ? todayIndex : 0;
  }

  $("#dayStrip").innerHTML = days.map((d, i) => `
    <button class="day ${i === state.selectedDay ? "active" : ""} ${d.date === today ? "today" : ""}" data-i="${i}">
      <b>${d.dayNum}</b><span>${d.label}</span>
    </button>`).join("");
  $$("#dayStrip .day").forEach((b) => b.addEventListener("click", () => {
    state.selectedDay = Number(b.dataset.i);
    renderSchedule(state.schedule);
  }));
  if (state.view === "week") {
    $("#dayTitle").textContent = "整周课程";
    renderWeek(days);
  } else {
    renderTimeline(days[state.selectedDay]);
  }
}

function renderWeek(days) {
  const events = state.schedule?.events || [];
  const today = ymd(new Date());
  const blocks = days.map((d, i) => {
    const evs = events.filter((e) => (e.start || "").slice(0, 10) === d.date)
      .sort((a, b) => (a.start || "").localeCompare(b.start || ""));
    const rows = evs.length
      ? evs.map((e) => {
          const t = (e.start || "").slice(11, 16);
          const meta = [e.location, e.teacher].filter(Boolean).map(esc).join(" · ");
          return `<div class="wdev"><span class="t">${t}</span><span class="n">${esc(e.title || "课程")}</span><span class="m">${meta}</span></div>`;
        }).join("")
      : `<div class="wdempty">无课</div>`;
    return `<div class="weekday ${d.date === today ? "today" : ""}">
      <button class="wdhead" data-i="${i}"><b>${d.label}</b><span>${d.date.slice(5)}</span></button>
      <div class="wdbody">${rows}</div>
    </div>`;
  }).join("");
  $("#timeline").innerHTML = `<div class="weekview">${blocks}</div>`;
  $$("#timeline .wdhead").forEach((b) => b.addEventListener("click", () => {
    state.selectedDay = Number(b.dataset.i);
    state.view = "day";
    try { localStorage.setItem("neu.schedule.view", "day"); } catch {}
    applyViewUI();
    renderSchedule(state.schedule);
  }));
}

function applyViewUI() {
  $$("#viewToggle button").forEach((b) => b.classList.toggle("on", b.dataset.view === state.view));
  $("#dayStrip").classList.toggle("hidden", state.view === "week");
}

$$("#viewToggle button").forEach((b) => b.addEventListener("click", () => {
  if (state.view === b.dataset.view) return;
  state.view = b.dataset.view;
  try { localStorage.setItem("neu.schedule.view", state.view); } catch {}
  applyViewUI();
  if (state.schedule) renderSchedule(state.schedule);
}));

function renderTimeline(day) {
  const events = (state.schedule?.events || []).filter((e) => (e.start || "").slice(0, 10) === day.date);
  $("#dayTitle").textContent = `${day.label} · ${day.date.slice(5)}`;
  if (!events.length) {
    const now = new Date();
    const nowDate = ymd(now);
    const nowHM = `${pad(now.getHours())}:${pad(now.getMinutes())}`;
    const cutoff = day.date > nowDate ? day.date : nowDate;
    const week = (state.schedule?.events || []).filter((e) => {
      const d = (e.start || "").slice(0, 10);
      if (d > cutoff) return true;
      if (d === cutoff && d === nowDate) return (e.start || "").slice(11, 16) > nowHM;
      return false;
    }).sort((a, b) => (a.start || "").localeCompare(b.start || ""));
    const quick = week.slice(0, 3).map((e) => {
      const d = new Date((e.start || "").slice(0, 10) + "T00:00:00");
      const label = isNaN(d.getTime()) ? "" : WEEK_NAMES[d.getDay()];
      return `<button class="chipbtn quickday" data-date="${(e.start || "").slice(0, 10)}">${label} · ${esc(e.title)}</button>`;
    }).join("");
    $("#timeline").innerHTML = `<div class="empty">${day.label}没有课，休息一下 ☕
      ${week.length ? `<div class="quickwrap"><span class="sub">本周还有：</span><div class="quick">${quick}</div></div>` : ""}
    </div>`;
    $$("#timeline .quickday").forEach((b) => b.addEventListener("click", () => {
      const days = [];
      for (let i = 0; i < 7; i++) days.push(ymd(new Date(state.weekStart.getTime() + i * 86400000)));
      const idx = days.indexOf(b.dataset.date);
      if (idx >= 0) { state.selectedDay = idx; renderSchedule(state.schedule); }
    }));
    return;
  }
  events.sort((a, b) => (a.start || "").localeCompare(b.start || ""));
  $("#timeline").innerHTML = events.map((e) => {
    const t1 = (e.start || "").slice(11, 16);
    const t2 = (e.end || "").slice(11, 16);
    const meta = [e.location, e.teacher, e.sections ? `第${e.sections}节` : ""].filter(Boolean)
      .map((x, i) => i === 0 ? `<em>${esc(x)}</em>` : esc(x)).join(" / ");
    return `<div class="slot">
      <div class="time"><b>${t1 || "—"}</b><span>${t2 || ""}</span></div>
      <div class="body"><div class="name">${esc(e.title || "未命名课程")}</div>
        <div class="meta">${meta}</div></div>
    </div>`;
  }).join("");
}

function mondayOf(d) {
  const x = new Date(d);
  x.setDate(x.getDate() - ((x.getDay() + 6) % 7));
  x.setHours(0, 0, 0, 0);
  return x;
}
function addDays(d, n) {
  const x = new Date(d);
  x.setDate(x.getDate() + n);
  return x;
}

function computeWeek(anchor) {
  const base = state.schedule || {};
  const courses = base.courses;
  if (!Array.isArray(courses) || !courses.length) return null;
  const cur = Number(base.current_week || base.week || 1);
  const start = sundayOf(anchor);
  const baseWeek = Number(base.week || 1);
  const baseStart = new Date((base.start || ymd(sundayOf(new Date()))) + "T00:00:00");
  const first = addDays(mondayOf(addDays(baseStart, 1)), -(baseWeek - 1) * 7);
  const days = [];
  for (let i = 0; i < 7; i++) days.push(addDays(start, i));
  const events = [];
  days.forEach((d, i) => {
    const colDay = i === 0 ? 7 : i;
    const weekNo = Math.round((mondayOf(d) - first) / 86400000 / 7) + 1;
    for (const c of courses) {
      if (!c.arranged || c.day !== colDay) continue;
      if (!Array.isArray(c.weeks) || !c.weeks.includes(weekNo)) continue;
      const t1 = c.begin || "00:00", t2 = c.end || t1;
      events.push({
        title: c.course || "",
        start: `${ymd(d)}T${t1}:00`,
        end: `${ymd(d)}T${t2}:00`,
        location: c.place || "",
        desc: c.weeks_text || "",
        teacher: c.teacher || "",
        sections: c.begin_section ? `${c.begin_section}-${c.end_section}` : "",
        color: c.color || "",
      });
    }
  });
  events.sort((a, b) => (a.start || "").localeCompare(b.start || ""));
  const week = Math.round((mondayOf(days[1]) - first) / 86400000 / 7) + 1;
  return { start: ymd(start), end: ymd(addDays(start, 6)), week,
           current_week: cur, events, courses };
}

let weekBusy = false;
async function loadWeek(delta) {
  if (weekBusy) return;
  weekBusy = true;
  $("#prevWeek").disabled = true;
  $("#nextWeek").disabled = true;
  const anchor = new Date((state.weekStart || sundayOf(new Date())).getTime() + delta * 7 * 86400000);
  try {
    const local = computeWeek(anchor);
    if (!local) {
      toast("本地还没有整学期课表，请点「更多 → 刷新数据」更新一次");
      return;
    }
    state.selectedDay = null;
    renderSchedule(local);
  } catch (e) { toast(e.message); }
  finally {
    weekBusy = false;
    $("#prevWeek").disabled = false;
    $("#nextWeek").disabled = false;
  }
}
$("#prevWeek").addEventListener("click", () => loadWeek(-1));
$("#nextWeek").addEventListener("click", () => loadWeek(1));

/* ---------------- 考试 ---------------- */
function renderExams(exams) {
  const box = $("#examList");
  if (!exams) { box.innerHTML = `<div class="empty">暂无考试数据</div>`; return; }
  $("#examNote").textContent = exams.note || (exams.status === "ok" ? "来自新教务系统" : "");
  const items = (exams.items || []).slice();
  if (!items.length) {
    box.innerHTML = exams.cards?.length
      ? exams.cards.map((c) => `<div class="grade"><div class="g-name"><b>${esc(c.name)}</b></div>
          <div class="score txt">${esc(c.value)}${esc(c.unit || "")}</div></div>`).join("")
      : `<div class="empty">暂无考试安排</div>`;
    return;
  }
  const todayStr = ymd(new Date());
  const byTime = (x) => String(x.time || "").slice(0, 10);
  const ordered = [
    ...items.filter((x) => byTime(x) >= todayStr).sort((a, b) => String(a.time).localeCompare(String(b.time))),
    ...items.filter((x) => byTime(x) < todayStr).sort((a, b) => String(b.time).localeCompare(String(a.time))),
  ];
  const today = new Date(todayStr + "T00:00:00");
  box.innerHTML = ordered.map((x) => {
    const t = String(x.time || "");
    const date = t.slice(0, 10);
    const d = new Date(date + "T00:00:00");
    const diff = Math.round((d - today) / 86400000);
    let status = `<span class="status soon">还有 ${diff} 天</span>`;
    if (diff < 0) status = `<span class="status done">已结束</span>`;
    else if (diff === 0) status = `<span class="status now">今天</span>`;
    const wd = isNaN(d.getTime()) ? "" : WEEK_NAMES[d.getDay()];
    return `<div class="exam">
      <div class="datebox"><b>${date.slice(8) || "--"}</b><span>${date.slice(5, 7)}月</span><i>${wd}</i></div>
      <div class="info">
        <div class="co">${esc(x.course || "")}</div>
        <div class="meta">
          <span class="pill">${esc(t.slice(11) || "")}</span>
          ${x.place ? `<span class="pill loc">${esc(x.place)}</span>` : ""}
          ${x.seat ? `<span class="pill">座位 ${esc(x.seat)}</span>` : ""}
          ${x.type ? `<span class="pill">${esc(x.type)}</span>` : ""}
        </div>
      </div>
      ${status}
    </div>`;
  }).join("");
}

async function probeJwxt() {
  toast("正在检查教务…");
  try {
    const j = await api("/api/jwxt?probe=1");
    toast(j.status === "ok" ? `教务已连接：考试 ${j.exams ?? 0} · 成绩 ${j.grades ?? 0}` : (j.note || "教务未连接"));
    await refresh();
  } catch (e) { toast(e.message); }
}

/* ---------------- 成绩 ---------------- */
function renderGrades(grades) {
  const hero = $("#gpaHero");
  const list = $("#gradeList");
  if (!grades) { hero.innerHTML = ""; list.innerHTML = `<div class="empty">暂无成绩</div>`; return; }
  const r = grades.rank || {};
  const items = grades.items || [];
  const gpa = parseFloat(r.gpa);
  hero.innerHTML = `<div class="gpahero">
    <div class="gpa-num"><b>${isNaN(gpa) ? "—" : gpa.toFixed(2)}</b><span>平均绩点 GPA</span></div>
    <div class="gpa-meta">
      <h4>专业排名</h4>
      <div class="rank">${r.major_rank ? `第 ${esc(r.major_rank)} 名` : "—"}</div>
    </div>
  </div>`;
  $("#gradeCount").textContent = "";
  if (!items.length) { list.innerHTML = `<div class="empty">暂无成绩</div>`; return; }
  list.innerHTML = items.map((x) => {
    const s = String(x.score ?? "");
    const num = parseFloat(s);
    let cls = "txt";
    if (!isNaN(num)) cls = num >= 90 ? "s90" : num >= 80 ? "s80" : num >= 60 ? "s60" : "s0";
    return `<div class="grade">
      <div class="g-name"><b>${esc(x.course)}</b><span>${esc(x.term || "")}${x.type ? " · " + esc(x.type) : ""}</span></div>
      <div class="score ${cls}">${esc(s || "—")}</div>
    </div>`;
  }).join("");
}

/* ---------------- 网费 ---------------- */
const NET_FREE_GB = 10;
const NET_TIERS = [
  { no: "一", range: "0–10G", rule: "免费", max: 10 },
  { no: "二", range: "10–25G", rule: "超出 10G 部分 1 元/G", max: 25 },
  { no: "三", range: "25–40G", rule: "15 元", max: 40 },
  { no: "四", range: "40–45G", rule: "15 元 + 超出 40G 部分 1 元/G", max: 45 },
  { no: "五", range: "45–80G", rule: "20 元", max: 80 },
  { no: "六", range: "80G 以上", rule: "20 元 + 超出 80G 部分 1 元/G", max: 999 },
];

function netFee(gb) {
  if (gb <= 10) return { fee: 0, tier: 0 };
  if (gb <= 25) return { fee: gb - 10, tier: 1 };
  if (gb <= 40) return { fee: 15, tier: 2 };
  if (gb <= 45) return { fee: 15 + (gb - 40), tier: 3 };
  if (gb <= 80) return { fee: 20, tier: 4 };
  return { fee: 20 + (gb - 80), tier: 5 };
}

function parseGB(value, unit) {
  const n = parseFloat(String(value ?? "").replace(/[^\d.]/g, ""));
  if (isNaN(n)) return null;
  const u = String(unit || "").toUpperCase();
  if (u.includes("TB")) return n * 1024;
  if (u.includes("MB")) return n / 1024;
  return n;
}

function renderNetPlan(net) {
  const box = $("#netPlan");
  if (!box) return;
  const traffic = (net.cards || []).find((c) => /流量/.test(c.name || ""));
  let stored = null;
  try { stored = localStorage.getItem("neu.net.usage"); } catch {}
  const autoUsage = net.usage_gb != null && !isNaN(Number(net.usage_gb)) ? Number(net.usage_gb) : null;
  const auto = autoUsage != null;
  const fixedFee = auto && typeof net.fee === "number" ? Number(net.fee) : null;
  let usage = auto ? autoUsage : (traffic ? parseGB(traffic.value, traffic.unit) : (stored !== null ? parseFloat(stored) : null));
  if (isNaN(usage)) usage = null;

  const ruler = NET_TIERS.map((t, i) => {
    const span = i === 0 ? 10 : t.max === 999 ? 25 : t.max - NET_TIERS[i - 1].max;
    return `<span class="seg" style="flex:${span}"></span>`;
  }).join("");
  const marks = [["0", 0], ["10", 10], ["25", 25], ["40", 40], ["45", 45], ["80", 80], ["100G+", 100]];
  const ticks = marks.map(([label, g]) => {
    const p = Math.min(100, (g / 105) * 100);
    const shift = p === 0 ? "0" : p >= 95 ? "-100%" : "-50%";
    return `<span style="left:${p}%;transform:translateX(${shift})">${label}</span>`;
  }).join("");
  const rows = NET_TIERS.map((t, i) => `<div class="tierrow ${i === (usage != null ? netFee(usage).tier : -1) ? "on" : ""}">
      <i>${t.no}</i><span class="r">${t.range}</span><span class="rule">${t.rule}</span>
    </div>`).join("");
  const source = auto
    ? `数据来自 ipgw${net.via === "webvpn" ? "（WebVPN）" : "（校内直连）"}${net.duration ? " · 已用时长 " + esc(net.duration) : ""}`
    : "";

  box.innerHTML = `<div class="plan">
    <div class="plan-head">
      <div>
        <div class="plan-k">本月 IPv4 下行流量${auto ? " · 自动读取" : ""}</div>
        <div class="plan-usage"><input id="netUsage" type="number" min="0" step="0.1" inputmode="decimal"
          placeholder="—" value="${usage != null ? usage : ""}" ${auto ? "readonly" : ""}><span>GB</span></div>
      </div>
      <div class="plan-fee"><span>${auto ? "本月消费" : "预计网费"}</span><b id="netFee">—</b></div>
    </div>
    <div class="freebar"><i id="freeBar"></i></div>
    <div class="plan-free" id="freeText">每月 10G 免费额度</div>
    <div class="ruler">${ruler}<b id="netMark" class="mark"></b></div>
    <div class="ticks">${ticks}</div>
    <div class="tiertable">${rows}</div>
    <p class="plan-note">${source ? source + "<br>" : ""}仅对 IPv4 下行流量计费；IPv6、校内资源、IPv4 上行免费。
      每日 0:00–6:00 外网流量免费；一个账号最多 5 台设备同时在线。
      依据《东北大学学生上网资费政策》，实际账单以 ipgw 为准。</p>
  </div>`;

  const sync = () => {
    const v = parseFloat($("#netUsage").value);
    const gb = isNaN(v) ? null : Math.max(0, v);
    if (!auto) {
      try { localStorage.setItem("neu.net.usage", gb == null ? "" : String(gb)); } catch {}
    }
    const bar = $("#freeBar");
    const mark = $("#netMark");
    if (gb == null) {
      bar.style.width = "0%";
      mark.style.left = "0%";
      $("#netFee").textContent = "—";
      $("#freeText").textContent = "每月 10G 免费额度";
      $$(".tierrow").forEach((r) => r.classList.remove("on"));
      return;
    }
    const f = netFee(gb);
    const fee = fixedFee != null ? fixedFee : f.fee;
    bar.style.width = Math.min(1, gb / NET_FREE_GB) * 100 + "%";
    mark.style.left = Math.min(100, gb / 105 * 100) + "%";
    $("#netFee").textContent = `¥ ${fee.toFixed(2)}`;
    const diff = NET_FREE_GB - gb;
    $("#freeText").textContent = diff >= 0.05
      ? `10G 免费额度内，还剩 ${diff.toFixed(1)}G`
      : diff >= -0.05 ? "10G 免费额度已用完" : `已超出免费额度 ${(gb - NET_FREE_GB).toFixed(1)}G`;
    $$(".tierrow").forEach((r, i) => r.classList.toggle("on", i === f.tier));
  };
  $("#netUsage").addEventListener("input", sync);
  sync();
}

function renderNet(net) {
  const cards = $("#netCards");
  const links = $("#netLinks");
  if (!net) { cards.innerHTML = ""; return; }
  const traffic = (net.cards || []).find((c) => /流量/.test(c.name || ""));
  const list = (net.cards || []).filter((c) => c !== traffic);
  cards.innerHTML = list.length
    ? list.map((c, i) => `<div class="netcard full">
        <div class="k">${esc(c.name)}</div>
        <div class="v">${esc(String(c.value ?? "—"))}<small>${esc(c.unit || "")}</small></div>
      </div>`).join("")
    : "";
  renderNetPlan(net);
  links.innerHTML = (net.links || []).slice(0, 6)
    .map((l) => `<a class="link" href="${esc(ssoHref(l.url))}" target="_blank" rel="noreferrer">${esc(l.name || l.url)}</a>`)
    .join("");
  $("#netLinksHead").classList.toggle("hidden", !(net.links || []).length);
  $("#netNote").textContent = net.note || "";
}

function ssoHref(url) {
  try {
    const u = new URL(url, location.origin);
    if (u.protocol === "https:" && (u.hostname === "neu.edu.cn" || u.hostname.endsWith(".neu.edu.cn"))) {
      return `/sso?url=${encodeURIComponent(u.href)}`;
    }
    return u.href;
  } catch { return "#"; }
}

/* ---------------- 登录 ---------------- */
$("#togglePwd").addEventListener("click", () => {
  const p = $("#password");
  const show = p.type === "password";
  p.type = show ? "text" : "password";
  $("#togglePwd").textContent = show ? "隐藏" : "显示";
  $("#togglePwd").classList.toggle("on", show);
});

$("#btnLogin").addEventListener("click", async () => {
  const username = $("#username").value.trim();
  const password = $("#password").value;
  if (!username || !password) return toast("请输入学号和密码");
  $("#btnLogin").disabled = true;
  $("#loginMsg").textContent = "";
  try {
    const r = await api("/api/login", {
      method: "POST",
      body: JSON.stringify({ username, password, remember: $("#remember").checked }),
    });
    if (r.status === "sms") {
      $("#smsBox").classList.remove("hidden");
      toast("需要短信验证");
      return;
    }
    clearCache();
    await boot();
  } catch (e) {
    $("#loginMsg").textContent = e.message;
  } finally { $("#btnLogin").disabled = false; }
});

$("#btnSendSms").addEventListener("click", async () => {
  try {
    await api("/api/sms", { method: "POST", body: JSON.stringify({ send: true }) });
    toast("验证码已发送");
  } catch (e) { toast(e.message); }
});

$("#btnVerify").addEventListener("click", async () => {
  const code = $("#smsCode").value.trim();
  if (!code) return toast("请输入验证码");
  try {
    await api("/api/sms", { method: "POST", body: JSON.stringify({ code, trust: $("#trustDevice").checked }) });
    $("#smsBox").classList.add("hidden");
    toast("验证成功");
    clearCache();
    await boot();
  } catch (e) { toast(e.message); }
});

$("#btnLogout").addEventListener("click", doLogout);
$("#btnLogout2").addEventListener("click", doLogout);
async function doLogout() {
  closeSheet();
  clearCache();
  await api("/api/logout", { method: "POST", body: "{}" });
  location.reload();
}

/* ---------------- 更多面板 ---------------- */
function closeSheet() { $("#sheetMask").classList.add("hidden"); }
$("#btnMore").addEventListener("click", () => $("#sheetMask").classList.remove("hidden"));
$("#sheetMask").addEventListener("click", (e) => { if (e.target === $("#sheetMask")) closeSheet(); });
$("#linkJwxt").addEventListener("click", () => closeSheet());

/* ---------------- 导出 / 状态 ---------------- */
$("#btnExportKb").addEventListener("click", () => exportCsv("timetable"));
$("#btnExportExams").addEventListener("click", () => exportCsv("exams"));
$("#btnExport").addEventListener("click", () => {
  closeSheet();
  if (window.NEU) {
    const p = window.NEU.exportState();
    toast(String(p).startsWith("ERR:") ? p : "已导出：" + p);
    return;
  }
  location.href = "/api/export";
});
$("#btnImport").addEventListener("click", () => { closeSheet(); clearCache(); window.NEU && window.NEU.importState(); });
$("#btnRefresh").addEventListener("click", () => { closeSheet(); refresh(); });
$("#probeJwxt").addEventListener("click", probeJwxt);

function exportCsv(kind) {
  if (window.NEU) {
    const r = String(window.NEU.exportCsv(kind) || "");
    toast(r.startsWith("ERR") ? r : "已导出：" + r);
    return;
  }
  location.href = kind === "exams" ? "/api/export/exams.csv" : "/api/export/timetable.csv";
}

/* ---------------- 标签页 ---------------- */
function activateTab(name) {
  const btn = $(`.tab[data-tab="${name}"]`);
  if (!btn) return;
  state.tab = name;
  $$(".tab").forEach((b) => b.classList.toggle("active", b === btn));
  $$(".panel").forEach((p) => p.classList.add("hidden"));
  $(`#panel-${name}`).classList.remove("hidden");
  renderStats(name, true);
  window.scrollTo(0, 0);
}
$$(".tab").forEach((btn) => btn.addEventListener("click", () => {
  activateTab(btn.dataset.tab);
  history.replaceState(null, "", "#" + btn.dataset.tab);
}));
window.addEventListener("hashchange", () => activateTab(location.hash.slice(1)));

/* ---------------- 免登链接拦截 ---------------- */
document.addEventListener("click", (e) => {
  const a = e.target.closest && e.target.closest("a[href]");
  if (!a || !window.NEU) return;
  const href = a.getAttribute("href") || "";
  if (!href.startsWith("/sso")) return;
  e.preventDefault();
  const u = new URL(href, location.href);
  window.NEU.openSso(u.searchParams.get("url") || "https://jwxt.neu.edu.cn/jwapp/sys/homeapp/index.do");
});

applyViewUI();
if (location.hash) activateTab(location.hash.slice(1));
boot();




