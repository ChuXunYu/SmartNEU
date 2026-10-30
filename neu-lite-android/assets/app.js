const $ = (s) => document.querySelector(s);
const $$ = (s) => Array.from(document.querySelectorAll(s));

const state = {
  session: null,
  data: null,
  weekStart: null,
  selectedDay: null,
  schedule: null,
  tab: "schedule",
  mailDetails: {},
  mailPrefetching: new Set(),
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
      } else if (Date.now() - started > 20000) {
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
    case "/api/card": return nativeCallAsync("card");
    case "/api/card/qr": return nativeCallAsync("cardQr");
    case "/api/mail": return nativeCallAsync("mail");
    case "/api/mail/detail": return nativeCallAsync("mailDetail", { id: params.get("id") || "" });
    case "/api/mail/send": {
      const b = JSON.parse(opts.body || "{}");
      return nativeCallAsync("sendMail", b);
    }
    case "/api/pay/card": return nativeCallAsync("payCard", body);
    case "/api/pay/net": return nativeCallAsync("payNet", body);
    case "/api/pay/order": return nativeCallAsync("payOrder", { order_no: params.get("order_no") || "" });
    case "/api/jwxt": return nativeCallAsync("jwxt", { probe: params.get("probe") === "1" });
    case "/api/login": return nativeCallAsync(body.quick ? "quickLogin" : "login", body);
    case "/api/sms": return nativeCallAsync("sms", body);
    case "/api/logout": return nativeCallAsync("logout");
    default: throw new Error("unsupported: " + p);
  }
}
const api = window.NEU ? nativeApi : httpApi;

/* ---------------- 启动与缓存 ---------------- */
const CACHE_KEY = "neu.overview.v2";
let refreshing = false;
let refreshGuard = null;
let refreshId = 0;

function withTimeout(promise, ms, message) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(message)), ms);
    promise.then(
      (v) => { clearTimeout(timer); resolve(v); },
      (e) => { clearTimeout(timer); reject(e); }
    );
  });
}

const CACHE_BUDGET = 2 * 1024 * 1024;
const MAIL_DETAIL_MAX = 30;
const MAIL_DETAIL_BUDGET = 1 * 1024 * 1024;

function cacheTextSize(s) { return (s || "").length * 2; }

function pruneMailDetails(items) {
  const valid = new Set((items || []).map((x) => x.id));
  const rows = [];
  Object.keys(state.mailDetails || {}).forEach((id) => {
    if (valid.size && !valid.has(id)) {
      delete state.mailDetails[id];
      return;
    }
    const one = state.mailDetails[id];
    if (!one) return;
    rows.push([id, one, cacheTextSize(JSON.stringify(one))]);
  });
  rows.sort((a, b) => (Number(b[1]._cachedAt) || 0) - (Number(a[1]._cachedAt) || 0));
  const kept = {};
  let total = 0;
  rows.forEach(([id, one, size], i) => {
    if (i >= MAIL_DETAIL_MAX || total + size > MAIL_DETAIL_BUDGET) return;
    kept[id] = one;
    total += size;
  });
  state.mailDetails = kept;
}

function updateCacheUsage() {
  const el = $("#cacheUsage");
  if (!el) return;
  let chars = 0;
  try { chars = (localStorage.getItem(CACHE_KEY) || "").length; } catch {}
  el.textContent = `本地缓存 ${Math.round(chars * 2 / 1024)} KB / 上限 2 MB`;
}

function loadCache() {
  try {
    const c = JSON.parse(localStorage.getItem(CACHE_KEY) || "null");
    return c && c.data && c.data.profile ? c : null;
  } catch { return null; }
}
function saveCache(d) {
  try {
    pruneMailDetails(d && d.mail && d.mail.items);
    const copy = Object.assign({}, d);
    if (copy.mail) {
      copy.mail = Object.assign({}, copy.mail);
      delete copy.mail.web_url;
    }
    const details = {};
    Object.keys(state.mailDetails || {}).forEach((id) => {
      const one = Object.assign({}, state.mailDetails[id]);
      delete one.web_url;
      details[id] = one;
    });
    copy.mail_details = details;
    let str = JSON.stringify({ at: Date.now(), data: copy });
    if (cacheTextSize(str) > CACHE_BUDGET) {
      delete copy.mail_details;
      str = JSON.stringify({ at: Date.now(), data: copy });
    }
    if (cacheTextSize(str) > CACHE_BUDGET && copy.mail && copy.mail.items) {
      copy.mail.items = copy.mail.items.slice(0, 10);
      str = JSON.stringify({ at: Date.now(), data: copy });
    }
    try {
      localStorage.setItem(CACHE_KEY, str);
    } catch (e) {
      delete copy.mail_details;
      localStorage.setItem(CACHE_KEY, JSON.stringify({ at: Date.now(), data: copy }));
    }
  } catch {}
  updateCacheUsage();
}
function clearCache() {
  try { localStorage.removeItem(CACHE_KEY); } catch {}
  clearCardCache();
  state.mailDetails = {};
  state.mailPrefetching = new Set();
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
  renderMailBadge(d.mail ? (d.mail.unread ?? (d.mail.items || []).filter((x) => x.unread).length) : 0);
  if (d.mail && !$("#mailView").classList.contains("hidden")) renderMail(d.mail);
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
  // 硬截止：无论底层请求发生什么，22 秒后一定收掉动画
  clearTimeout(refreshGuard);
  refreshGuard = setTimeout(() => {
    refreshId++;
    refreshing = false;
    refreshGuard = null;
    document.body.classList.remove("updating");
    $("#progress").classList.add("hidden");
    $("#updateChip").classList.add("hidden");
    toast("更新超时，请稍后重试");
  }, 22000);
}
function finishRefresh(ok) {
  if (refreshGuard) { clearTimeout(refreshGuard); refreshGuard = null; }
  // 数据一到就立刻收掉动画，不再做“已更新”停留
  document.body.classList.remove("updating");
  $("#progress").classList.add("hidden");
  const chip = $("#updateChip");
  chip.classList.remove("done");
  chip.classList.add("hidden");
}

async function refresh() {
  if (refreshing) return;
  refreshing = true;
  const myId = ++refreshId;
  startRefresh();
  try {
    const d = await withTimeout(api("/api/overview"), 20000, "更新超时（20 秒）");
    if (myId !== refreshId) return;
    const prevMail = state.data && state.data.mail;
    if (prevMail && !d.mail) {
      d.mail = prevMail;
      d.mail_unread = prevMail.portal_unread ?? prevMail.unread;
    }
    renderAll(d);
    renderStats(state.tab, false);
    saveCache(d);
    setUpdated(Date.now());
    finishRefresh(true);
    setTimeout(loadIpwg, 500);
    // 邮箱比总览/网费慢，延后到低优先级执行，避免拖慢更新动画
    setTimeout(() => {
      if (myId !== refreshId || !state.session || !state.session.logged_in) return;
      withTimeout(api("/api/mail"), 15000, "邮箱更新超时")
        .then((mail) => { if (mail && myId === refreshId) attachMail(mail, true); })
        .catch(() => {});
    }, 1200);
  } catch (e) {
    if (myId !== refreshId) return;
    toast("更新失败：" + e.message);
    finishRefresh(false);
  } finally {
    if (myId === refreshId) refreshing = false;
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
  if (!logged) { renderLoginMode(state.session); return; }
  if (state.session.demo) toast("演示模式：示例数据");

  const cache = loadCache();
  if (cache) {
    // 秒开：先用上次成功刷新的数据渲染，不发起网络请求
    state.data = cache.data;
    state.mailDetails = cache.data.mail_details || {};
    pruneMailDetails(cache.data.mail && cache.data.mail.items);
    updateCacheUsage();
    renderAll(cache.data);
    renderStats(state.tab, false);
    setUpdated(cache.at);
    setTimeout(warmCard, 500);
    setTimeout(restoreComposeDraft, 900);
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
  setTimeout(warmCard, 300);
  setTimeout(restoreComposeDraft, 900);
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
  $("#btnImport").classList.toggle("hidden", !window.NEU);
  renderStats(state.tab, false);
}

/* ---------------- 顶部概览（随标签变化） ---------------- */
function examStatSummary() {
  const items = examItems();
  const now = new Date();
  const today = ymd(now);
  const sunday = new Date(now);
  sunday.setDate(now.getDate() - now.getDay());
  const ws = ymd(sunday);
  const we = ymd(new Date(sunday.getTime() + 6 * 86400000));
  const week = items.filter((e) => e.date >= ws && e.date <= we).length;
  const up = items.filter((e) => e.date >= today).sort((a, b) => (a.start || "").localeCompare(b.start || ""));
  if (!up.length) return { week, value: "—", label: "暂无考试" };
  const days = Math.round((new Date(up[0].date + "T00:00:00") - new Date(today + "T00:00:00")) / 86400000);
  if (days <= 0) return { week, value: "今天", label: "有考试" };
  return { week, value: `${days}天`, label: "距下考" };
}

function statsFor(tab) {
  const d = state.data || {};
  const today = ymd(new Date());
  if (tab === "schedule") {
    const ev = (d.schedule || {}).events || [];
    const ex = examStatSummary();
    return [
      { v: ev.filter((e) => (e.start || "").slice(0, 10) === today).length, l: "今日课程" },
      { v: ex.week, l: "本周考试" },
      { v: ex.value, l: ex.label },
    ];
  }
  if (tab === "exams") {
    const items = (d.exams || {}).items || [];
    const up = items.filter((x) => !isExamPast(x)).length;
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
  if (tab !== "card") { led.classList.add("hidden"); return; }
  led.classList.remove("hidden");
  const paint = () => {
    const cells = statsFor("schedule");
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

/* ---------------- 课表：课程 + 考试 ---------------- */
function examItems() {
  const raw = (state.data && state.data.exams && state.data.exams.items) || [];
  return raw.map((x, i) => {
    const m = String(x.time || "").match(/(\d{4}-\d{2}-\d{2})\s+(\d{1,2}:\d{2})\s*[-–]\s*(\d{1,2}:\d{2})/);
    if (!m) return null;
    return {
      kind: "exam", date: m[1], hm: m[2], endHm: m[3],
      start: `${m[1]}T${m[2]}`, end: `${m[1]}T${m[3]}`,
      title: x.course || x.title || "考试",
      place: x.place || "", seat: x.seat || "",
      key: "exam-" + i,
    };
  }).filter(Boolean);
}

function examsForDate(date) {
  return examItems().filter((e) => e.date === date);
}

function renderExamUpNext() {
  const box = $("#examUpNext");
  if (!box) return;
  const ws = state.weekStart ? ymd(state.weekStart) : "";
  const we = ws ? ymd(new Date(state.weekStart.getTime() + 6 * 86400000)) : "";
  const today = ymd(new Date());
  const list = examItems()
    .filter((e) => e.date >= today && (e.date < ws || e.date > we))
    .sort((a, b) => (a.start || "").localeCompare(b.start || ""))
    .slice(0, 3);
  if (!list.length) {
    box.classList.add("hidden");
    box.innerHTML = "";
    return;
  }
  box.classList.remove("hidden");
  const e = list[0];
  const dt = new Date(e.date + "T00:00:00");
  const wd = isNaN(dt.getTime()) ? "" : WEEK_NAMES[dt.getDay()];
  const more = list.length > 1 ? `<i class="eu-more">+${list.length - 1}</i>` : "";
  box.innerHTML = `<button class="examup-line" type="button">
    <span class="eu-label">近期考试</span>
    <span class="eu-date">${e.date.slice(5).replace("-", ".")}<i>${wd}</i></span>
    <span class="eu-main">${esc(e.title)}</span>
    <span class="eu-meta">${esc(e.hm)} · ${esc(e.place || "待定")}</span>
    ${more}<span class="eu-arrow">›</span>
  </button>`;
  box.querySelector(".examup-line").addEventListener("click", () => {
    activateTab("exams");
    history.replaceState(null, "", "#exams");
  });
}

function renderSchedule(schedule) {
  if (!schedule || schedule.error) {
    $("#dayStrip").innerHTML = "";
    $("#timeline").innerHTML = `<div class="empty">${esc(schedule?.error || "暂无课表")}</div>`;
    $("#examUpNext").classList.add("hidden");
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
    const date = ymd(d);
    days.push({ date, label: WEEK_NAMES[i], dayNum: d.getDate(), examCount: examsForDate(date).length });
  }
  if (state.selectedDay == null) {
    const todayIndex = days.findIndex((x) => x.date === today);
    state.selectedDay = todayIndex >= 0 ? todayIndex : 0;
  }

  $("#dayStrip").innerHTML = days.map((d, i) => `
    <button class="day ${i === state.selectedDay ? "active" : ""} ${d.date === today ? "today" : ""} ${d.examCount ? "has-exam" : ""}" data-i="${i}">
      <b>${d.dayNum}</b><span>${d.label}${d.examCount ? '<i class="dexam"></i>' : ""}</span>
    </button>`).join("");
  $$("#dayStrip .day").forEach((b) => b.addEventListener("click", () => {
    state.selectedDay = Number(b.dataset.i);
    renderSchedule(state.schedule);
  }));
  if (state.view === "week") {
    $("#dayTitle").parentElement.classList.add("hidden");
    renderWeek(days);
  } else {
    $("#dayTitle").parentElement.classList.remove("hidden");
    renderTimeline(days[state.selectedDay]);
  }
  renderExamUpNext();
}

const WEEK_ORDER = [0, 1, 2, 3, 4, 5, 6]; // 沿用现有周日为首日，日期与后端周区间一致
const PERIOD_COUNT = 12;
const MAX_PERIODS = 14;
const PERIOD_START = ["08:00", "08:55", "10:00", "10:55", "13:30", "14:25",
  "15:30", "16:25", "18:30", "19:25", "20:20", "21:15"];

function sectionFromClock(hm) {
  const m = String(hm || "").match(/(\d{1,2}):(\d{2})/);
  if (!m) return null;
  const t = Number(m[1]) * 60 + Number(m[2]);
  const pairs = [
    [1, 2, 0, 9 * 60 + 59],
    [3, 4, 10 * 60, 11 * 60 + 59],
    [5, 6, 13 * 60, 15 * 60 + 19],
    [7, 8, 15 * 60 + 20, 17 * 60 + 19],
    [9, 10, 18 * 60, 20 * 60 + 19],
    [11, 12, 20 * 60 + 20, 24 * 60],
  ];
  const p = pairs.find((x) => t >= x[2] && t <= x[3]) || pairs[pairs.length - 1];
  return { start: p[0], end: p[1] };
}

function sectionBounds(e) {
  const bs = Number(e.begin_section || 0);
  const es = Number(e.end_section || 0);
  if (bs >= 1 && bs <= MAX_PERIODS) {
    return { start: bs, end: Math.min(MAX_PERIODS, Math.max(bs, es || bs)) };
  }
  const m = String(e.sections || "").match(/(\d+)\s*[-–—]\s*(\d+)/);
  if (m) {
    const a = Math.max(1, Math.min(MAX_PERIODS, Number(m[1])));
    const b = Math.max(a, Math.min(MAX_PERIODS, Number(m[2])));
    return { start: a, end: b };
  }
  const startHm = (e.start || "").slice(11, 16) || e.hm;
  const endHm = (e.end || "").slice(11, 16) || e.endHm;
  const b = sectionFromClock(startHm) || { start: 1, end: 2 };
  const en = sectionFromClock(endHm);
  return { start: b.start, end: Math.max(b.start, en ? en.end : b.end) };
}

const COURSE_BG = ["#e7a69a", "#edc68b", "#b4d0a6", "#b3c7e6", "#ddb6d0",
  "#ddc18e", "#a8d0c8", "#efb5a8", "#c7c0e5", "#d4dfa1", "#e5bfa5", "#bad4df"];

function courseColor(e, colors) {
  const key = String(e.title || "课程");
  if (colors.has(key)) return colors.get(key);
  const color = COURSE_BG[colors.size % COURSE_BG.length];
  colors.set(key, color);
  return color;
}

function fitTimetable(rows) {
  const grid = $("#timeline .timetable");
  const tab = $(".tabbar");
  if (!grid || !tab || !rows) return;
  const top = grid.getBoundingClientRect().top;
  const bottom = tab.getBoundingClientRect().top;
  const available = bottom - top - 6;
  if (available < 120) return;
  const header = 42;
  const height = Math.max(28, Math.min(46, Math.floor((available - header) / rows)));
  grid.style.gridTemplateRows = `${header}px repeat(${rows}, ${height}px)`;
}

function renderWeek(days) {
  const events = state.schedule?.events || [];
  const today = ymd(new Date());
  const weekDays = WEEK_ORDER.map((i) => days[i]);
  const colOf = new Map(weekDays.map((d, i) => [d.date, i + 2]));
  const usedEnds = events.map((e) => sectionBounds(e).end);
  const periodCount = events.length
    ? Math.min(MAX_PERIODS, Math.max(PERIOD_COUNT, ...usedEnds))
    : PERIOD_COUNT;
  // 有真实课程时，行时间完全按接口返回的上课/下课时间；没有课程时才用默认节次时间占位。
  const defaultTimes = Array.from({ length: periodCount }, (_, i) => PERIOD_START[i] || "");
  const slotTimes = events.length ? Array(periodCount).fill("") : defaultTimes;
  // 先放上课时间；同一节有多门课时取最早的上课时间。
  events.forEach((e) => {
    const b = sectionBounds(e);
    const t1 = (e.start || "").slice(11, 16);
    if (t1 && b.start >= 1 && b.start <= slotTimes.length && (!slotTimes[b.start - 1] || t1 < slotTimes[b.start - 1])) {
      slotTimes[b.start - 1] = t1;
    }
  });
  // 只有该节没有上课时间的课程时，才用下课时间补空行。
  events.forEach((e) => {
    const b = sectionBounds(e);
    const t2 = (e.end || "").slice(11, 16);
    if (t2 && b.end >= 1 && b.end <= slotTimes.length && !slotTimes[b.end - 1]) {
      slotTimes[b.end - 1] = t2;
    }
  });

  const head = weekDays.map((d, i) => `
    <button class="tg-day ${d.date === today ? "today" : ""}"
      style="grid-column:${i + 2};grid-row:1" data-date="${d.date}" type="button">
      <b>${d.label}</b><span>${d.date.slice(5).replace("-", ".")}</span>
    </button>`).join("");

  const slots = Array.from({ length: periodCount }, (_, i) => `
    <div class="tg-slot" style="grid-column:1;grid-row:${i + 2}">
      <b>${i + 1}</b><span>${slotTimes[i]}</span>
    </div>`).join("");

  const cells = weekDays.map((d, i) =>
    Array.from({ length: periodCount }, (_, r) => `
      <div class="tg-cell ${d.date === today ? "today" : ""}" style="grid-column:${i + 2};grid-row:${r + 2}"></div>`
    ).join("")).join("");

  const cards = [];
  const colors = new Map();
  weekDays.forEach((d, di) => {
    const list = [];
    events.filter((e) => (e.start || "").slice(0, 10) === d.date).forEach((e) => {
      const b = sectionBounds(e);
      const t1 = (e.start || "").slice(11, 16);
      const t2 = (e.end || "").slice(11, 16);
      const meta = [e.location, e.teacher].filter(Boolean).join(" · ");
      list.push({
        b,
        html: `<div class="tg-course" style="--c:${courseColor(e, colors)}"
          data-date="${d.date}" title="${esc(t1 + (t2 ? "–" + t2 : "") + " · " + (e.title || "课程"))}">
          <i class="tg-time">${esc(t1)}</i>
          <b>${esc(e.title || "未命名课程")}</b>
          <small>${esc(meta)}</small>
        </div>`
      });
    });
    list.sort((a, b) => a.b.start - b.b.start || a.b.end - b.b.end);
    const clusters = [];
    list.forEach((it) => {
      const last = clusters[clusters.length - 1];
      if (last && it.b.start <= last.end) {
        last.end = Math.max(last.end, it.b.end);
        last.items.push(it);
      } else {
        clusters.push({ start: it.b.start, end: it.b.end, items: [it] });
      }
    });
    clusters.forEach((g) => {
      const style = `grid-column:${di + 2};grid-row:${g.start + 1}/${g.end + 2}`;
      const inner = g.items.map((x) => x.html).join("");
      cards.push(g.items.length === 1
        ? `<div class="tg-one" style="${style}">${inner}</div>`
        : `<div class="tg-stack" style="${style}" title="${g.items.length} 门课程时间重叠">${inner}</div>`);
    });
  });

  $("#timeline").innerHTML = `<div class="timetable" style="grid-template-rows:46px repeat(${periodCount},44px)">
    <div class="tg-corner" style="grid-column:1;grid-row:1"><b>节</b><span>时间</span></div>
    ${head}${slots}${cells}${cards.join("")}
  </div>`;

  requestAnimationFrame(() => fitTimetable(periodCount));

  $$("#timeline .tg-day, #timeline .tg-course, #timeline .tg-exam").forEach((el) => {
    el.addEventListener("click", () => {
      const idx = days.findIndex((d) => d.date === el.dataset.date);
      if (idx < 0) return;
      state.selectedDay = idx;
      state.view = "day";
      try { localStorage.setItem("neu.schedule.view", "day"); } catch {}
      applyViewUI();
      renderSchedule(state.schedule);
    });
  });
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
  const exams = examsForDate(day.date);
  $("#dayTitle").textContent = `${day.label} · ${day.date.slice(5)}`;
  const all = [
    ...events.map((e) => ({ kind: "course", start: e.start || "", data: e })),
    ...exams.map((e) => ({ kind: "exam", start: e.start || "", data: e })),
  ].sort((a, b) => a.start.localeCompare(b.start));

  if (!all.length) {
    const now = new Date();
    const nowDate = ymd(now);
    const nowHM = `${pad(now.getHours())}:${pad(now.getMinutes())}`;
    const cutoff = day.date > nowDate ? day.date : nowDate;
    const weekEnd = state.weekStart ? ymd(new Date(state.weekStart.getTime() + 6 * 86400000)) : day.date;
    const weekCourses = (state.schedule?.events || []).filter((e) => {
      const d = (e.start || "").slice(0, 10);
      if (d < cutoff || d > weekEnd) return false;
      if (d === cutoff && d === nowDate) return (e.start || "").slice(11, 16) > nowHM;
      return true;
    }).map((e) => ({ kind: "course", start: e.start || "", data: e }));
    const weekExams = examItems().filter((e) => e.date >= cutoff && e.date <= weekEnd)
      .map((e) => ({ kind: "exam", start: e.start || "", data: e }));
    const week = [...weekCourses, ...weekExams].sort((a, b) => a.start.localeCompare(b.start));
    const quick = week.slice(0, 4).map((it) => {
      const e = it.data;
      const date = (e.start || "").slice(0, 10);
      const dt = new Date(date + "T00:00:00");
      const label = isNaN(dt.getTime()) ? "" : WEEK_NAMES[dt.getDay()];
      const isExam = it.kind === "exam";
      return `<button class="chipbtn quickday ${isExam ? "quickexam" : ""}" data-date="${date}" data-kind="${it.kind}">
        ${isExam ? '<i>考</i>' : ""}${label} · ${esc(e.title)}</button>`;
    }).join("");
    $("#timeline").innerHTML = `<div class="empty">${day.label}没有课，休息一下 ☕
      ${week.length ? `<div class="quickwrap"><span class="sub">本周还有：</span><div class="quick">${quick}</div></div>` : ""}
    </div>`;
    $$("#timeline .quickday").forEach((b) => b.addEventListener("click", () => {
      const days = [];
      for (let i = 0; i < 7; i++) days.push(ymd(new Date(state.weekStart.getTime() + i * 86400000)));
      const idx = days.indexOf(b.dataset.date);
      if (idx >= 0) { state.selectedDay = idx; renderSchedule(state.schedule); }
      else { activateTab("exams"); history.replaceState(null, "", "#exams"); }
    }));
    return;
  }

  $("#timeline").innerHTML = all.map((it) => {
    const e = it.data;
    if (it.kind === "exam") {
      const meta = [e.place, e.seat ? `座位 ${e.seat}` : ""].filter(Boolean).map(esc).join(" / ");
      return `<div class="slot examslot">
        <div class="time"><b>${esc(e.hm || "—")}</b><span>${esc(e.endHm || "")}</span></div>
        <div class="body"><div class="name"><i class="tag">考试</i>${esc(e.title || "考试")}</div>
          <div class="meta">${meta}</div></div>
      </div>`;
    }
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
async function goThisWeek() {
  if (weekBusy) return;
  weekBusy = true;
  $("#prevWeek").disabled = true;
  $("#nextWeek").disabled = true;
  $("#btnThisWeek").disabled = true;
  try {
    const today = ymd(new Date());
    let local = state.data && state.data.schedule;
    // 优先回到上次刷新得到的“本周”数据；跨周时再基于整学期课表计算
    if (!local || local.start > today || local.end < today) {
      local = computeWeek(new Date());
    }
    if (!local) {
      toast("本地还没有整学期课表，请点「更多 → 刷新数据」更新一次");
      return;
    }
    const start = new Date(local.start + "T00:00:00");
    const idx = Math.round((new Date(today + "T00:00:00") - start) / 86400000);
    state.selectedDay = idx >= 0 && idx < 7 ? idx : new Date().getDay();
    renderSchedule(local);
  } catch (e) {
    toast(e.message);
  } finally {
    weekBusy = false;
    $("#prevWeek").disabled = false;
    $("#nextWeek").disabled = false;
    $("#btnThisWeek").disabled = false;
  }
}

$("#prevWeek").addEventListener("click", () => loadWeek(-1));
$("#nextWeek").addEventListener("click", () => loadWeek(1));
$("#btnThisWeek").addEventListener("click", goThisWeek);

/* 周课表左右滑动切换上一周 / 下一周 */
(() => {
  const panel = $("#panel-schedule");
  if (!panel) return;
  let start = null;
  let lastTouch = 0;
  let blockClickUntil = 0;
  const allowed = (target) => !(target && target.closest &&
    target.closest("button,a,input,textarea,select,.sheet,.tabbar"));

  const begin = (x, y) => {
    if (state.view !== "week") return;
    start = { x, y, at: Date.now() };
  };
  const end = (x, y) => {
    if (!start) return;
    const s = start;
    start = null;
    const dx = x - s.x;
    const dy = y - s.y;
    if (Date.now() - s.at > 900) return;
    if (Math.abs(dx) < 48 || Math.abs(dx) < Math.abs(dy) * 1.4) return;
    if (s.x < 24 || x < 24) return; // 避开系统边缘返回手势
    if (weekBusy) return;
    blockClickUntil = Date.now() + 420;
    $("#timeline").classList.add(dx < 0 ? "swipe-left" : "swipe-right");
    setTimeout(() => $("#timeline").classList.remove("swipe-left", "swipe-right"), 260);
    loadWeek(dx < 0 ? 1 : -1);
  };

  panel.addEventListener("touchstart", (e) => {
    if (e.touches.length !== 1) return;
    lastTouch = Date.now();
    if (!allowed(e.target)) return;
    begin(e.touches[0].clientX, e.touches[0].clientY);
  }, { passive: true });
  panel.addEventListener("touchend", (e) => {
    const t = e.changedTouches[0];
    if (!t) return;
    lastTouch = Date.now();
    end(t.clientX, t.clientY);
  }, { passive: true });

  panel.addEventListener("mousedown", (e) => {
    if (Date.now() - lastTouch < 800 || e.button !== 0 || !allowed(e.target)) return;
    begin(e.clientX, e.clientY);
  });
  window.addEventListener("mouseup", (e) => {
    if (Date.now() - lastTouch < 800) return;
    end(e.clientX, e.clientY);
  });

  // 滑动后浏览器可能补发一次 click，阻止它误触课程/日期。
  panel.addEventListener("click", (e) => {
    if (Date.now() < blockClickUntil) {
      e.preventDefault();
      e.stopPropagation();
    }
  }, true);
})();

/* ---------------- 考试 ---------------- */
function examParts(x) {
  const t = String(x.time || "");
  const date = t.slice(0, 10);
  const hm = t.slice(11);
  const m = hm.match(/(\d{1,2}:\d{2})\s*[-–~]\s*(\d{1,2}:\d{2})/);
  return { date, start: m ? m[1] : "", end: m ? m[2] : "", text: hm };
}

function hmMinutes(s) {
  const m = String(s || "").match(/^(\d{1,2}):(\d{2})$/);
  return m ? Number(m[1]) * 60 + Number(m[2]) : null;
}

function isExamPast(x) {
  const p = examParts(x);
  if (!p.date) return false;
  const now = new Date();
  const today = ymd(now);
  if (p.date < today) return true;
  if (p.date > today) return false;
  const end = hmMinutes(p.end);
  if (end == null) return false;
  return now.getHours() * 60 + now.getMinutes() > end;
}

function examDayText(x) {
  const p = examParts(x);
  const d = new Date(p.date + "T00:00:00");
  const wd = isNaN(d.getTime()) ? "" : WEEK_NAMES[d.getDay()];
  return `${p.date.slice(5).replace("-", ".")} ${wd}`.trim();
}

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
  const upcoming = items.filter((x) => !isExamPast(x))
    .sort((a, b) => String(a.time).localeCompare(String(b.time)));
  const past = items.filter((x) => isExamPast(x))
    .sort((a, b) => String(b.time).localeCompare(String(a.time)));
  const now = new Date();
  const nowMin = now.getHours() * 60 + now.getMinutes();
  const today = ymd(now);

  const card = (x, isPast) => {
    const p = examParts(x);
    const d = new Date(p.date + "T00:00:00");
    const wd = isNaN(d.getTime()) ? "" : WEEK_NAMES[d.getDay()];
    const diff = Math.round((d - new Date(today + "T00:00:00")) / 86400000);
    let status = `<span class="status soon">还有 ${diff} 天</span>`;
    if (isPast) status = `<span class="status done">已结束</span>`;
    else if (diff === 0) {
      const start = hmMinutes(p.start), end = hmMinutes(p.end);
      if (start != null && end != null && nowMin >= start && nowMin <= end) status = `<span class="status now">进行中</span>`;
      else status = `<span class="status now">今天</span>`;
    }
    return `<div class="exam ${isPast ? "past" : ""}">
      <div class="datebox"><b>${p.date.slice(8) || "--"}</b><span>${p.date.slice(5, 7)}月</span><i>${wd}</i></div>
      <div class="info">
        <div class="co">${esc(x.course || "")}</div>
        <div class="meta">
          <span class="pill">${esc(p.text || "")}</span>
          ${x.place ? `<span class="pill loc">${esc(x.place)}</span>` : ""}
          ${x.seat ? `<span class="pill">座位 ${esc(x.seat)}</span>` : ""}
          ${x.type ? `<span class="pill">${esc(x.type)}</span>` : ""}
        </div>
      </div>
      ${status}
    </div>`;
  };

  let html = "";
  if (upcoming.length) {
    html += `<div class="examgroup">
      <div class="examgroup-head"><h4>待考</h4><span>${upcoming.length} 场</span></div>
      <div class="examlist">${upcoming.map((x) => card(x, false)).join("")}</div>
    </div>`;
  } else {
    html += `<div class="examgroup">
      <div class="examgroup-head"><h4>待考</h4><span>0 场</span></div>
      <div class="examnone">暂无待考</div>
    </div>`;
  }
  if (past.length) {
    html += `<details class="examgroup pastgroup" ${upcoming.length ? "" : "open"}>
      <summary><span>已结束</span><i>${past.length} 场</i><b>展开</b></summary>
      <div class="examlist">${past.map((x) => card(x, true)).join("")}</div>
    </details>`;
  }
  box.innerHTML = html;
}

/* ---------------- 成绩 ---------------- */
function rankText(value) {
  const s = String(value || "").trim();
  if (!s) return "—";
  if (s.includes("/")) return s.split("/").map((x) => x.trim()).filter(Boolean).join(" / ");
  return `第 ${s} 名`;
}

function gradeKind(x) {
  const raw = [x.exam_type, x.assess_type, x.type].filter(Boolean).join(" ");
  if (/考查/.test(raw)) return "考查课";
  if (/考试/.test(raw)) return "考试课";
  return "";
}

function convertedGrade(raw) {
  const s = String(raw ?? "").trim();
  if (!/^[-+]?\d+(?:\.\d+)?$/.test(s)) return s || "—";
  return (Number(s) / 10 - 5).toFixed(1);
}

function renderGrades(grades) {
  const hero = $("#gpaHero");
  const list = $("#gradeList");
  if (!grades) { hero.innerHTML = ""; list.innerHTML = `<div class="empty">暂无成绩</div>`; return; }
  const r = grades.rank || {};
  const items = grades.items || [];
  const gpa = parseFloat(r.gpa);
  const major = rankText(r.major_rank || r.rank);
  const overall = r.rank && r.rank !== r.major_rank ? rankText(r.rank) : "";

  hero.innerHTML = `<div class="gpahero">
    <div class="gpa-num"><b>${isNaN(gpa) ? "—" : gpa.toFixed(2)}</b><span>平均绩点 GPA</span></div>
    <div class="gpa-meta">
      <h4>${r.major_rank ? "专业排名" : "排名"}</h4>
      <div class="rank">${major}</div>
      ${overall ? `<div class="rank-sub">年级 ${overall}</div>` : ""}
    </div>
  </div>`;

  $("#gradeCount").textContent = `${items.length} 门`;
  if (!items.length) { list.innerHTML = `<div class="empty">暂无成绩</div>`; return; }

  const row = (x) => {
    const raw = String(x.score ?? "").trim();
    const num = parseFloat(raw);
    const numeric = /^[-+]?\d+(?:\.\d+)?$/.test(raw);
    let cls = "txt";
    if (numeric && !isNaN(num)) cls = num >= 90 ? "s90" : num >= 80 ? "s80" : num >= 60 ? "s60" : "s0";
    const kind = gradeKind(x);
    const type = String(x.type || "");
    const metaParts = [
      x.term,
      type && !/考查|考试/.test(type) ? type : "",
      x.credit ? `${x.credit} 学分` : "",
    ].filter(Boolean);
    const meta = metaParts.map(esc).join(" · ");
    const pct = numeric && !isNaN(num) ? Math.max(0, Math.min(100, num)) : null;
    return `<div class="grade">
      <div class="g-name">
        <b>${esc(x.course || "")}</b>
        <span>${meta}${kind ? ` <i class="g-kind ${kind === "考试课" ? "is-exam" : "is-check"}">${kind}</i>` : ""}</span>
      </div>
      <div class="score ${cls}" title="原始成绩 ${esc(raw || "—")}">${esc(convertedGrade(raw))}</div>
      ${pct != null ? `<div class="g-bar"><i style="width:${pct}%"></i></div>` : ""}
    </div>`;
  };

  list.innerHTML = items.map(row).join("");
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

function fmtGB(n) {
  if (n == null || isNaN(Number(n))) return "—";
  return (Math.round(Number(n) * 10) / 10).toFixed(1);
}

function renderNetPlan(net, usage) {
  const box = $("#netPlan");
  if (!box) return;
  const traffic = (net.cards || []).find((c) => /流量/.test(c.name || ""));
  const auto = net.usage_gb != null && !isNaN(Number(net.usage_gb));
  const fixedFee = auto && typeof net.fee === "number" ? Number(net.fee) : null;
  if (usage == null) {
    usage = auto ? Number(net.usage_gb) : (traffic ? parseGB(traffic.value, traffic.unit) : null);
  }
  if (usage == null || isNaN(Number(usage))) usage = null;

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
    <div class="plan-summary">
      <div class="plan-fee"><span>本月消费</span><b id="netFee">—</b></div>
      <div class="plan-free" id="freeText">每月 10G 免费额度</div>
    </div>
    ${auto ? "" : `<label class="plan-manual">手动填写本月流量
      <input id="netUsage" type="number" min="0" step="0.1" inputmode="decimal" placeholder="—"
        value="${usage != null ? usage : ""}"><span>GB</span></label>`}
    <div class="freebar"><i id="freeBar"></i></div>
    <div class="ruler">${ruler}<b id="netMark" class="mark"></b></div>
    <div class="ticks">${ticks}</div>
    <div class="tiertable">${rows}</div>
    <p class="plan-note">${source ? source + "<br>" : ""}仅对 IPv4 下行流量计费；IPv6、校内资源、IPv4 上行免费。
      每日 0:00–6:00 外网流量免费；一个账号最多 5 台设备同时在线。
      依据《东北大学学生上网资费政策》，实际账单以 ipgw 为准。</p>
  </div>`;

  let current = usage;
  const sync = () => {
    const input = $("#netUsage");
    if (input) {
      const v = parseFloat(input.value);
      current = isNaN(v) ? null : Math.max(0, v);
      if (current != null) {
        try { localStorage.setItem("neu.net.usage", String(current)); } catch {}
      }
    }
    const bar = $("#freeBar");
    const mark = $("#netMark");
    if (current == null) {
      if (bar) bar.style.width = "0%";
      if (mark) mark.style.left = "0%";
      const feeEl = $("#netFee"); if (feeEl) feeEl.textContent = "—";
      const freeEl = $("#freeText"); if (freeEl) freeEl.textContent = "每月 10G 免费额度";
      $$(".tierrow").forEach((r) => r.classList.remove("on"));
      return;
    }
    const f = netFee(current);
    const fee = fixedFee != null ? fixedFee : f.fee;
    if (bar) bar.style.width = Math.min(1, current / NET_FREE_GB) * 100 + "%";
    if (mark) mark.style.left = Math.min(100, current / 105 * 100) + "%";
    const feeEl = $("#netFee"); if (feeEl) feeEl.textContent = `¥ ${fee.toFixed(2)}`;
    const diff = NET_FREE_GB - current;
    const freeEl = $("#freeText");
    if (freeEl) freeEl.textContent = diff >= 0.05
      ? `10G 免费额度内，还剩 ${diff.toFixed(1)}G`
      : diff >= -0.05 ? "10G 免费额度已用完" : `已超出免费额度 ${(current - NET_FREE_GB).toFixed(1)}G`;
    $$(".tierrow").forEach((r, i) => r.classList.toggle("on", i === f.tier));
  };
  const input = $("#netUsage");
  if (input) input.addEventListener("input", sync);
  sync();
}

function renderNet(net) {
  const cards = $("#netCards");
  const links = $("#netLinks");
  if (!net) { cards.innerHTML = ""; return; }
  const traffic = (net.cards || []).find((c) => /流量/.test(c.name || ""));
  const balance = (net.cards || []).find((c) => c !== traffic && /余额/.test(c.name || ""))
    || (net.cards || []).find((c) => c !== traffic);
  const auto = net.usage_gb != null && !isNaN(Number(net.usage_gb));
  const usage = auto ? Number(net.usage_gb) : (traffic ? parseGB(traffic.value, traffic.unit) : null);
  cards.innerHTML = `<div class="netcard">
      <div class="k">网费余额</div>
      <div class="v">${esc(String(balance?.value ?? "—"))}<small>${esc(balance?.unit || "元")}</small></div>
      <div class="t">账户可用余额</div>
    </div>
    <div class="netcard">
      <div class="k">本月已用</div>
      <div class="v">${fmtGB(usage)}<small>GB</small></div>
      <div class="t">IPv4 下行流量</div>
    </div>`;
  renderNetPlan(net, usage);
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
function maskUsername(value) {
  const s = String(value || "");
  if (s.length <= 6) return s;
  return s.slice(0, 4) + "****" + s.slice(-2);
}

function renderLoginMode(session) {
  const saved = session && session.saved_user && session.password_saved !== false;
  $("#smsBox").classList.add("hidden");
  $("#loginMsg").textContent = "";
  $("#savedLogin").classList.toggle("hidden", !saved);
  $("#manualLogin").classList.toggle("hidden", !!saved);
  $("#loginHint").textContent = saved
    ? "账号密码只保存在本机，不会上传；换设备请导入 state"
    : "登录一次后，导出 state.json 带到新设备可免二次验证";
  if (!saved) return;
  $("#username").value = saved;
  $("#savedName").textContent = (session.saved_name || "已保存账号").trim();
  $("#savedId").textContent = "学号 " + maskUsername(session.saved_xgh || saved);
}

$("#btnSwitchAccount").addEventListener("click", () => {
  $("#savedLogin").classList.add("hidden");
  $("#manualLogin").classList.remove("hidden");
  $("#password").value = "";
  $("#password").focus();
});

$("#btnQuickLogin").addEventListener("click", async () => {
  const btn = $("#btnQuickLogin");
  btn.disabled = true;
  btn.textContent = "正在登录";
  $("#loginMsg").textContent = "";
  try {
    const r = await api("/api/login", {
      method: "POST",
      body: JSON.stringify({ quick: true, remember: true }),
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
  } finally {
    btn.disabled = false;
    btn.textContent = "一键登录";
  }
});
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

$("#btnLogout2").addEventListener("click", doLogout);
async function doLogout() {
  closeSheet();
  clearCache();
  await api("/api/logout", { method: "POST", body: "{}" });
  location.reload();
}

/* ---------------- 更多面板 ---------------- */
function closeSheet() { $("#sheetMask").classList.add("hidden"); }
$("#btnMore").addEventListener("click", () => { updateCacheUsage(); $("#sheetMask").classList.remove("hidden"); });
$("#sheetMask").addEventListener("click", (e) => { if (e.target === $("#sheetMask")) closeSheet(); });
$("#linkJwxt").addEventListener("click", () => closeSheet());
function getCampusRunUrl() {
  try {
    if (window.NEU && window.NEU.call) {
      return JSON.parse(window.NEU.call("campusRunUrl", "{}") || "{}").url || "";
    }
  } catch {}
  try { return localStorage.getItem("neu.run.url") || ""; } catch { return ""; }
}

function saveCampusRunUrl(url) {
  try {
    if (window.NEU && window.NEU.call) {
      window.NEU.call("saveCampusRunUrl", JSON.stringify({ url }));
      return;
    }
  } catch {}
  try { localStorage.setItem("neu.run.url", url); } catch {}
}

function clearCampusRunUrl() {
  try {
    if (window.NEU && window.NEU.call) {
      window.NEU.call("clearCampusRunUrl", "{}");
      return;
    }
  } catch {}
  try { localStorage.removeItem("neu.run.url"); } catch {}
}

function updateRunPanel() {
  const el = $("#runTabState");
  if (!el) return;
  const url = getCampusRunUrl();
  el.textContent = url ? "已绑定，可直接打开；链接失效时点「从门户绑定 / 更换」。" : "尚未绑定；登录后会自动后台绑定，也可点「更换」重试。";
}

function showRunSheet() {
  closeSheet();
  $("#runUrl").value = getCampusRunUrl();
  $("#runMsg").textContent = "";
  $("#runMask").classList.remove("hidden");
  setTimeout(() => $("#runUrl").focus(), 80);
}

function openCampusRun() {
  const url = getCampusRunUrl();
  if (!url) { showRunSheet(); return; }
  openUrlSafe(url, "校园乐跑");
}

function closeRunSheet() {
  $("#runMask").classList.add("hidden");
}

$("#btnRunOpenTab").addEventListener("click", openCampusRun);
$("#btnRunBindTab").addEventListener("click", showRunSheet);
window.addEventListener("focus", updateRunPanel);

function startRunAutoBind() {
  closeRunSheet();
  if (!(window.NEU && window.NEU.call)) {
    openSsoSafe("https://personal.neu.edu.cn/portal", "自动绑定校园乐跑", true);
    return;
  }
  window.NEU.call("autoBindRun", "{}");
  $("#runTabState").textContent = "正在后台绑定校园乐跑…";
  toast("正在后台绑定校园乐跑…");
  clearInterval(window.__runBindTimer);
  let tries = 0;
  window.__runBindTimer = setInterval(() => {
    tries++;
    if (getCampusRunUrl()) {
      clearInterval(window.__runBindTimer);
      updateRunPanel();
      toast("校园乐跑已绑定");
    } else if (tries > 30) {
      clearInterval(window.__runBindTimer);
      updateRunPanel();
      toast("自动绑定超时，可重试或粘贴链接");
    }
  }, 1000);
}

$("#runPortal").addEventListener("click", startRunAutoBind);
$("#runOpen").addEventListener("click", () => {
  const url = $("#runUrl").value.trim().replace(/\s+/g, "");
  if (!/^https:\/\/tybzhtypt\.neu\.edu\.cn\//.test(url)) {
    $("#runMsg").textContent = "请粘贴学校官方校园乐跑链接";
    return;
  }
  saveCampusRunUrl(url);
  closeRunSheet();
  openUrlSafe(url, "校园乐跑");
});
$("#runClear").addEventListener("click", () => {
  clearCampusRunUrl();
  $("#runUrl").value = "";
  $("#runMsg").textContent = "已清除本机链接";
});
$("#runCancel").addEventListener("click", closeRunSheet);
$("#runMask").addEventListener("click", (e) => { if (e.target === $("#runMask")) closeRunSheet(); });

/* ---------------- 导出 / 状态 ---------------- */
let passResolve = null;
let passAllowEmpty = false;

function askPassword(title, hint, allowEmpty = false) {
  return new Promise((resolve) => {
    passResolve = resolve;
    passAllowEmpty = allowEmpty;
    $("#passTitle").textContent = title;
    $("#passHint").textContent = hint;
    $("#passInput").value = "";
    $("#passMask").classList.remove("hidden");
    setTimeout(() => $("#passInput").focus(), 60);
  });
}
function finishPass(value) {
  if (!passResolve) return;
  const fn = passResolve;
  passResolve = null;
  $("#passMask").classList.add("hidden");
  $("#passInput").value = "";
  fn(value);
}
$("#passOk").addEventListener("click", () => {
  const v = $("#passInput").value;
  if (!v && !passAllowEmpty) return toast("请输入密码");
  finishPass(v);
});
$("#passCancel").addEventListener("click", () => finishPass(null));
$("#passMask").addEventListener("click", (e) => { if (e.target === $("#passMask")) finishPass(null); });
$("#passInput").addEventListener("keydown", (e) => { if (e.key === "Enter") $("#passOk").click(); });

async function encryptedExport(kind) {
  if (!window.NEU) {
    toast("桌面端导出为明文浏览器下载");
    location.href = kind === "exams" ? "/api/export/exams.csv" : "/api/export/timetable.csv";
    return;
  }
  const pass = await askPassword("设置导出密码", "文件会用 AES-256 加密；密码只用于本次导出，不会保存。");
  if (pass == null) return;
  const r = String(kind === "state"
    ? window.NEU.exportState(pass)
    : window.NEU.exportEncrypted(kind, pass));
  toast(r.startsWith("ERR") ? r : "已加密导出：" + r);
}

function exportPlainCsvAll() {
  if (!window.NEU) { toast("仅 Android 客户端支持"); return; }
  const r = String(window.NEU.exportPlainCsvAll() || "");
  toast(r.startsWith("ERR") ? r : "已导出明文：" + r);
}

$("#btnExportKb").addEventListener("click", () => encryptedExport("timetable"));
$("#btnExport").addEventListener("click", () => { closeSheet(); encryptedExport("state"); });
$("#btnImport").addEventListener("click", async () => {
  closeSheet();
  if (!window.NEU) return;
  const pass = await askPassword("导入密码", "输入导出时设置的密码；旧版明文备份可留空。", true);
  if (pass == null) return;
  window.NEU.importState(pass);
});
if (window.NEU) {
  $("#fileBlock").classList.remove("hidden");
  $("#btnOpenExports").addEventListener("click", () => {
    closeSheet();
    if (window.NEU.call) window.NEU.call("openExports", "{}");
  });
  $("#btnClearCache").addEventListener("click", () => {
    closeSheet();
    if (window.NEU.call) window.NEU.call("clearWebCache", "{}");
  });
}
$("#btnRefresh").addEventListener("click", () => { closeSheet(); refresh(); });

/* ---------------- 校园卡 / 邮箱 / 支付 ---------------- */
function openUrlSafe(url, title = "邮箱") {
  if (!url) return;
  if (window.NEU) {
    if (window.NEU.call) window.NEU.call("openWeb", JSON.stringify({ url, title }));
    else window.NEU.openUrl(url);
  } else {
    window.open(url, "_blank", "noopener");
  }
}

function openSsoSafe(url, title = "网页", autoBind = false) {
  if (!url) return;
  if (window.NEU && window.NEU.call) {
    window.NEU.call("openSsoWeb", JSON.stringify({ url, title, autoBind }));
  } else {
    openUrlSafe(url, title);
  }
}

async function warmCard() {
  if (!state.session || !state.session.logged_in) return;
  if (cardWarmPromise) return cardWarmPromise;
  cardWarmPromise = (async () => {
    try {
      const d = await api("/api/card");
      state.card = d;
      cardFetchedAt = Date.now();
      saveCardCache(d);
      if (state.tab === "card") renderCard(d);
    } catch (e) {
      // 后台预热失败不打扰，页面打开时再重试
    } finally {
      cardWarmPromise = null;
    }
  })();
  return cardWarmPromise;
}

function renderMailBadge(n) {
  const badge = $("#mailBadge");
  if (!badge) return;
  const v = parseInt(n, 10);
  if (!isNaN(v) && v > 0) {
    badge.textContent = v > 99 ? "99+" : String(v);
    badge.classList.remove("hidden");
  } else {
    badge.classList.add("hidden");
  }
}

const CARD_CACHE_KEY = "neu.card.v1";
let qrTimer = null;
let qrExpires = 0;
let qrRefreshing = false;
let pendingQr = null;
let cardLoading = false;
let cardFetchedAt = 0;
let cardWarmPromise = null;
let mailLoading = false;
let mailWebUrl = "";
let mailDetailCurrentId = "";
let payTarget = "card";
let payPollTimer = null;

function loadCardCache() {
  try {
    const c = JSON.parse(localStorage.getItem(CARD_CACHE_KEY) || "null");
    return c && c.data ? c.data : null;
  } catch { return null; }
}
function saveCardCache(d) {
  try {
    // 只缓存余额等静态字段；二维码按校方 10 秒有效期管理，绝不落盘复用
    const copy = Object.assign({}, d);
    delete copy.qr;
    localStorage.setItem(CARD_CACHE_KEY, JSON.stringify({ at: Date.now(), data: copy }));
  } catch {}
}
function clearCardCache() {
  try { localStorage.removeItem(CARD_CACHE_KEY); } catch {}
}

function stopQr() {
  if (qrTimer) { clearInterval(qrTimer); qrTimer = null; }
  qrRefreshing = false;
  pendingQr = null;
}

function drawQr(code) {
  const box = $("#cardQr");
  if (!box || !code || !window.qrcode) return false;
  try {
    const g = qrcode(0, "M");
    g.addData(code);
    g.make();
    box.innerHTML = g.createSvgTag({ cellSize: 4, margin: 0 });
    return true;
  } catch (e) {
    return false;
  }
}

function ensureQrShell(d) {
  if (document.querySelector("#cardQr")) return;
  $("#cardQrPanel").innerHTML = `<div class="qrlabel">校园码 · 通行</div>
    <div id="cardQr"></div>
    <div class="qrtime" id="qrTime">—</div>
    <div class="qrvalid" id="qrValid"></div>
    <div class="qrgate" id="qrGate"></div>`;
  $("#qrValid").textContent = d.valid_until ? "虚拟卡有效期至 " + d.valid_until : "e码通虚拟卡";
}

function applyQr(qr, d) {
  if (!qr || !qr.code) return false;
  ensureQrShell(d || {});
  if (!drawQr(qr.code)) return false;
  qrExpires = Number(qr.expires_at) || (Date.now() + 10000);
  $("#qrValid").textContent = d && d.valid_until ? "虚拟卡有效期至 " + d.valid_until : "e码通虚拟卡";
  const gate = (d && d.gate) || {};
  $("#qrGate").textContent = gate.name || "";
  startQrTimer();
  return true;
}

function startQrTimer() {
  if (qrTimer) { clearInterval(qrTimer); qrTimer = null; }
  const tick = () => {
    const left = Math.max(0, Math.ceil((qrExpires - Date.now()) / 1000));
    const el = $("#qrTime");
    if (el) el.textContent = pendingQr ? "新码已就绪" : (qrRefreshing ? "正在刷新…" : (left > 0 ? `${left} 秒后刷新` : "正在刷新…"));
    if (left <= 0) {
      if (pendingQr) {
        const q = pendingQr;
        pendingQr = null;
        try { applyFetchedQr(q); } catch {}
      } else if (!qrRefreshing) {
        refreshQr();
      }
    } else if (left <= 1 && !pendingQr && !qrRefreshing) {
      refreshQr();
    }
  };
  tick();
  qrTimer = setInterval(tick, 250);
}

function applyFetchedQr(q) {
  state.card = Object.assign({}, state.card || {}, q);
  saveCardCache(state.card);
  if (!applyQr(q.qr, state.card)) throw new Error("二维码生成失败");
}

async function refreshQr() {
  if (qrRefreshing) return;
  qrRefreshing = true;
  try {
    const q = await api("/api/card/qr");
    if (q.status !== "ok" || !q.qr || !q.qr.code) throw new Error(q.error || "二维码刷新失败");
    // 预取成功后不立刻换码：先保留旧码，等旧码到期再原子替换
    if (qrExpires > Date.now() + 300) {
      pendingQr = q;
    } else {
      applyFetchedQr(q);
    }
  } catch (e) {
    // 保留旧二维码，稍后重试，不出现白屏/空白
    qrExpires = Date.now() + 1500;
    const el = $("#qrTime");
    if (el) el.textContent = "刷新失败，正在重试";
  } finally {
    qrRefreshing = false;
  }
}

function renderCard(d, cached = false) {
  const bal = d.balance != null && d.balance !== "" ? esc(d.balance) : "—";
  const unit = esc(d.unit || "元");
  const name = esc(d.name || "同学");
  const sid = esc(d.student_id || "");
  $("#cardStatus").textContent = d.status === "ok" ? "正常卡" : "部分数据";
  $("#cardHero").innerHTML = `<div class="cardwho"><b>${name}</b><span>${sid ? "学号 " + sid : ""}</span></div>
    <div class="cardbal"><b>${bal}</b><span>${unit}</span></div>
    <div class="cardsub"><span>校园卡余额</span><span>${d.valid_until ? "有效期至 " + esc(d.valid_until) : ""}</span></div>`;
  if (d.qr && d.qr.code) {
    ensureQrShell(d);
    applyQr(d.qr, d);
  } else if (!document.querySelector("#cardQr svg")) {
    ensureQrShell(d);
    $("#cardQr").innerHTML = `<div class="qrloading"><i></i>正在获取通行码…</div>`;
    $("#qrValid").textContent = d.valid_until ? "虚拟卡有效期至 " + d.valid_until : "e码通虚拟卡";
  }
}

async function loadCard(force = false) {
  if (force) cardFetchedAt = 0;
  let cached = state.card || loadCardCache();
  if (cached && cached.qr && Number(cached.qr.expires_at || 0) <= Date.now() + 8000) {
    delete cached.qr;
    if (state.card) delete state.card.qr;
  }
  if (!cached && cardWarmPromise) {
    try { await cardWarmPromise; } catch {}
    cached = state.card;
  }
  if (cached) {
    state.card = cached;
    renderCard(cached, true);
  } else if (state.data && state.data.card_balance != null) {
    $("#cardStatus").textContent = "正在更新";
    $("#cardHero").innerHTML = `<div class="cardwho"><b>校园卡</b><span></span></div>
      <div class="cardbal"><b>${esc(state.data.card_balance)}</b><span>${esc(state.data.card_unit || "元")}</span></div>
      <div class="cardsub"><span>校园卡余额</span><span></span></div>`;
    $("#cardQrPanel").innerHTML = `<div class="qrlabel">校园码 · 通行</div><div class="qrfail">正在获取通行码…</div>`;
  } else {
    $("#cardHero").innerHTML = `<div class="cardwho"><b>正在读取校园卡</b><span></span></div>
      <div class="cardbal"><b>—</b><span>元</span></div>`;
    $("#cardQrPanel").innerHTML = `<div class="qrlabel">校园码 · 通行</div><div class="qrfail">正在获取通行码…</div>`;
  }
  if (cached && Date.now() - cardFetchedAt < 15000) return;
  if (cardLoading) return;
  cardLoading = true;
  try {
    const d = await api("/api/card");
    state.card = d;
    cardFetchedAt = Date.now();
    saveCardCache(d);
    renderCard(d);
    if (d.status !== "ok") toast(d.qr_error || "校园卡部分数据读取失败");
  } catch (e) {
    if (!cached) {
      $("#cardQrPanel").innerHTML = `<div class="qrlabel">校园码 · 通行</div><div class="qrfail">${esc(e.message || "读取失败")}</div>`;
      toast("校园卡读取失败：" + e.message);
    }
  } finally {
    cardLoading = false;
  }
}

function renderMail(data) {
  const list = $("#mailList");
  const items = data.items || [];
  const unreadItems = items.filter((x) => x.unread);
  const readItems = items.filter((x) => !x.unread);
  const unread = unreadItems.length;
  data.unread = unread;
  renderMailBadge(unread);
  mailWebUrl = data.web_url || "";
  $("#mailMeta").textContent = data.status === "partial"
    ? "邮箱部分数据读取失败"
    : `收件箱 ${items.length} 封 · 未读 ${unread} 封`;
  if (!items.length) {
    list.innerHTML = `<div class="empty">${data.error ? esc(data.error) : "收件箱为空"}</div>`;
    return;
  }
  const row = (x) => `<div class="mailitem ${x.unread ? "unread" : ""}" data-url="${esc(mailWebUrl)}" data-id="${esc(x.id)}">
    <div class="from">${esc(x.from || "")}</div>
    <div class="date">${esc((x.date || "").slice(5, 16))}</div>
    <div class="subject">${esc(x.subject || "(无主题)")}</div>
    <div class="flags">${x.attached ? "附件 · " : ""}${x.unread ? "未读" : "已读"}</div>
  </div>`;
  list.innerHTML = `<div class="mailgroup-head"><span>未读</span><b>${unread}</b></div>
    ${unreadItems.length ? unreadItems.map(row).join("") : `<div class="mailnone">没有未读邮件</div>`}
    <div class="mailgroup-head read"><span>已读</span><b>${readItems.length}</b></div>
    ${readItems.length ? readItems.map(row).join("") : `<div class="mailnone">没有已读邮件</div>`}`;
  $$("#mailList .mailitem").forEach((el) => el.addEventListener("click", () => {
    if (el.dataset.id) openMailDetail(el.dataset.id);
    else if (el.dataset.url) openUrlSafe(el.dataset.url);
  }));
}
function attachMail(mail, deferDetails = false) {
  const items = mail.items || [];
  // 未读邮件一律不预取正文，避免服务端“读取正文”把未读变成已读
  Object.keys(state.mailDetails || {}).forEach((id) => {
    const it = items.find((x) => x.id === id);
    if (it && it.unread) delete state.mailDetails[id];
  });
  mail.unread = items.filter((x) => x.unread).length;
  pruneMailDetails(items);
  state.data = state.data || {};
  mail.self = mail.self || (state.data.mail && state.data.mail.self) || "";
  state.data.mail = mail;
  state.data.mail_unread = mail.unread;
  saveCache(state.data);
  renderMailBadge(state.data.mail_unread);
  if (!$("#mailView").classList.contains("hidden")) renderMail(mail);
  if (deferDetails) setTimeout(() => prefetchMailDetails(mail.items || []), 800);
  else prefetchMailDetails(mail.items || []);
}

async function prefetchMailDetails(items) {
  const queue = (items || []).filter((x) => x.id && !x.unread
    && !(state.mailDetails[x.id] && state.mailDetails[x.id].status === "ok")
    && !state.mailPrefetching.has(x.id));
  if (!queue.length) return;
  queue.forEach((x) => state.mailPrefetching.add(x.id));
  const worker = async () => {
    while (queue.length) {
      const it = queue.shift();
      try {
        const d = await api(`/api/mail/detail?id=${encodeURIComponent(it.id)}`);
        if (d && d.status === "ok") {
          d._cachedAt = Date.now();
          state.mailDetails[it.id] = d;
          pruneMailDetails(state.data && state.data.mail && state.data.mail.items);
          saveCache(state.data);
          if (mailDetailCurrentId === it.id) renderMailDetail(d);
        }
      } catch (e) {
        // 单封邮件失败不影响其他邮件，点开时再重试
      } finally {
        state.mailPrefetching.delete(it.id);
      }
    }
  };
  const n = Math.min(1, queue.length);
  await Promise.all(Array.from({ length: n }, () => worker()));
}

function renderMailDetail(d) {
  $("#mailDetailHead").innerHTML = `<div class="subject">${esc(d.subject || "(无主题)")}</div>
    <div class="meta">${esc(d.from || "")}<br>${esc(d.date || "")}</div>`;
  $("#mailBody").textContent = d.text || "（正文为空）";
  const atts = d.attachments || [];
  $("#mailAttach").textContent = atts.length ? "附件：" + atts.join(" · ") : "";
  if (d.web_url) mailWebUrl = d.web_url;
}

async function loadMail(force = false) {
  const cached = state.data && state.data.mail;
  if (cached && !force) {
    renderMail(cached);
    prefetchMailDetails(cached.items || []);
    return;
  }
  if (mailLoading) return;
  mailLoading = true;
  if (!cached) {
    $("#mailList").innerHTML = `<div class="empty">正在读取邮箱…</div>`;
    $("#mailMeta").textContent = "";
  } else {
    $("#mailMeta").textContent = "正在刷新邮箱…";
  }
  try {
    const d = await api("/api/mail");
    attachMail(d);
    if (d.status === "partial") toast(d.error || "邮箱读取失败");
  } catch (e) {
    if (!cached) $("#mailList").innerHTML = `<div class="empty">${esc(e.message || "邮箱读取失败")}</div>`;
    toast("邮箱读取失败：" + e.message);
  } finally {
    mailLoading = false;
  }
}

function markMailRead(id) {
  const mail = state.data && state.data.mail;
  if (!mail || !mail.items) return;
  const it = mail.items.find((x) => x.id === id);
  if (!it || !it.unread) return;
  it.unread = false;
  mail.unread = mail.items.filter((x) => x.unread).length;
  state.data.mail_unread = mail.unread;
  saveCache(state.data);
  renderMailBadge(mail.unread);
  if (!$("#mailView").classList.contains("hidden")) renderMail(mail);
}

async function openMailDetail(id) {
  mailDetailCurrentId = id;
  $("#mailDetailView").classList.remove("hidden");
  const mail = state.data && state.data.mail;
  const item = mail && mail.items ? mail.items.find((x) => x.id === id) : null;
  const wasUnread = !!(item && item.unread);
  const cached = state.mailDetails[id];
  if (cached && cached.status === "ok" && !wasUnread) {
    renderMailDetail(cached);
    return;
  }
  $("#mailDetailHead").innerHTML = `<div class="subject">正在读取邮件…</div>`;
  $("#mailBody").textContent = "";
  $("#mailAttach").textContent = "";
  try {
    const d = await api(`/api/mail/detail?id=${encodeURIComponent(id)}`);
    if (d.status !== "ok") throw new Error(d.error || "邮件读取失败");
    d._cachedAt = Date.now();
    state.mailDetails[id] = d;
    pruneMailDetails(state.data && state.data.mail && state.data.mail.items);
    saveCache(state.data);
    renderMailDetail(d);
    if (wasUnread) markMailRead(id);
  } catch (e) {
    $("#mailDetailHead").innerHTML = `<div class="subject">邮件读取失败</div>`;
    $("#mailBody").textContent = e.message;
  }
}

function closeMailDetail() {
  $("#mailDetailView").classList.add("hidden");
  mailDetailCurrentId = "";
}

function openMail() {
  $("#mailView").classList.remove("hidden");
  loadMail();
}

async function openWebCompose() {
  let url = mailWebUrl;
  if (!url) {
    try {
      const d = await api("/api/mail");
      attachMail(d);
      url = d.web_url || "";
    } catch (e) {
      toast("邮箱会话读取失败：" + e.message);
      return;
    }
  }
  if (!url) {
    toast("邮箱会话已失效，请刷新邮箱后重试");
    return;
  }
  const base = url.split("#")[0];
  openUrlSafe(base + "#mail.compose", "写信");
}

let composePasswordSaved = false;

function mailSelf() {
  const known = (state.data && state.data.mail && state.data.mail.self) || "";
  if (known) return known;
  const xgh = (state.session && state.session.profile && state.session.profile.xgh)
    || (state.data && state.data.profile && state.data.profile.xgh) || "";
  return xgh ? `${xgh}@stu.neu.edu.cn` : "";
}

function readSavedPassword() {
  try {
    const r = window.NEU && window.NEU.call ? window.NEU.call("hasMailPassword", "{}") : "{}";
    return !!(JSON.parse(r || "{}").saved);
  } catch { return false; }
}

function setComposePassSaved(saved) {
  composePasswordSaved = !!saved;
  $("#composePasswordWrap").classList.toggle("hidden", composePasswordSaved);
  $("#composePassSaved").classList.toggle("hidden", !composePasswordSaved);
  if (composePasswordSaved) $("#composePassword").value = "";
  updateComposeReady();
}

function updateComposeReady() {
  const from = $("#composeFrom").value.trim();
  const password = composePasswordSaved || $("#composePassword").value;
  const to = $("#composeTo").value.trim();
  $("#composeSend").disabled = !(from.includes("@") && password && to.includes("@"));
}

function fmtSize(n) {
  n = Number(n) || 0;
  if (n >= 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + " MB";
  if (n >= 1024) return Math.round(n / 1024) + " KB";
  return n + " B";
}

function refreshAttachments() {
  const list = $("#composeAttachList");
  if (!list || !window.NEU) return;
  let arr = [];
  try { arr = JSON.parse(window.NEU.call("attachments", "{}") || "[]"); } catch {}
  if (!arr.length) { list.innerHTML = ""; return; }
  list.innerHTML = arr.map((a) => `<div class="attachitem"><b>${esc(a.name || "附件")}</b><span>${fmtSize(a.size)}</span></div>`).join("")
    + `<div class="attachitem"><button id="composeAttachClear" class="textbtn" type="button">清空附件</button></div>`;
}

function pickAttachments() {
  if (!window.NEU) return;
  saveComposeDraft();
  window.NEU.call("pickAttachments", "{}");
  let tries = 0;
  const timer = setInterval(() => {
    refreshAttachments();
    if (++tries > 40) clearInterval(timer);
  }, 500);
}

function clearAttachments() {
  if (!window.NEU) return;
  window.NEU.call("clearAttachments", "{}");
  refreshAttachments();
}

function saveComposeDraft() {
  try {
    localStorage.setItem("neu.mail.draft", JSON.stringify({
      open: true,
      from: $("#composeFrom").value,
      to: $("#composeTo").value,
      subject: $("#composeSubject").value,
      body: $("#composeBody").value,
    }));
  } catch {}
}

function clearComposeDraft() {
  try { localStorage.removeItem("neu.mail.draft"); } catch {}
}

async function openCompose() {
  if (!window.NEU) {
    toast("桌面端请使用网页邮箱写信");
    return;
  }
  clearComposeDraft();
  let from = mailSelf();
  $("#composeFromText").textContent = "正在获取…";
  $("#composeFrom").value = "";
  $("#composePassword").value = "";
  $("#composeTo").value = "";
  $("#composeSubject").value = "";
  $("#composeBody").value = "";
  $("#composeMsg").textContent = "";
  $("#composeWebFallback").classList.add("hidden");
  clearAttachments();
  $("#composeMask").classList.remove("hidden");
  if (!from) {
    try {
      const d = await api("/api/mail");
      attachMail(d);
      from = d.self || mailSelf();
    } catch (e) {
      $("#composeMsg").textContent = "发件邮箱读取失败：" + e.message;
    }
  }
  $("#composeFrom").value = from || "";
  $("#composeFromText").textContent = from || "未获取到，请刷新邮箱";
  setComposePassSaved(readSavedPassword());
  setTimeout(() => {
    const el = composePasswordSaved ? $("#composeTo") : $("#composePassword");
    if (el) el.focus();
  }, 180);
}

function closeCompose() {
  $("#composeMask").classList.add("hidden");
  if (!composePasswordSaved) $("#composePassword").value = "";
  clearAttachments();
  clearComposeDraft();
}

async function sendCompose() {
  const from = $("#composeFrom").value.trim();
  const password = composePasswordSaved ? "" : $("#composePassword").value;
  const to = $("#composeTo").value.trim();
  const subject = $("#composeSubject").value.trim();
  const body = $("#composeBody").value;
  if (!from || !from.includes("@")) return $("#composeMsg").textContent = "未获取到发件邮箱，请刷新邮箱";
  if (!composePasswordSaved && !password) return $("#composeMsg").textContent = "请填写客户端专用密码";
  if (!to || !to.includes("@")) return $("#composeMsg").textContent = "请填写收件人邮箱";
  const btn = $("#composeSend");
  btn.disabled = true;
  $("#composeMsg").textContent = "正在通过 SMTP 发送…";
  try {
    const payload = { from, to, subject, body };
    if (password) payload.password = password;
    await api("/api/mail/send", { method: "POST", body: JSON.stringify(payload) });
    if (password) setComposePassSaved(true);
    toast("邮件已发送");
    closeCompose();
  } catch (e) {
    if (password) setComposePassSaved(readSavedPassword());
    $("#composeMsg").textContent = "发送失败：" + e.message;
    if (/535|authentication failed/i.test(e.message)) {
      $("#composeWebFallback").classList.remove("hidden");
    }
  } finally {
    updateComposeReady();
  }
}

function restoreComposeDraft() {
  if (!window.NEU || !$("#composeMask").classList.contains("hidden")) return;
  let d = null;
  try { d = JSON.parse(localStorage.getItem("neu.mail.draft") || "null"); } catch {}
  if (!d || !d.open) return;
  const from = d.from || mailSelf();
  $("#composeFrom").value = from || "";
  $("#composeFromText").textContent = from || "未获取到，请刷新邮箱";
  $("#composeTo").value = d.to || "";
  $("#composeSubject").value = d.subject || "";
  $("#composeBody").value = d.body || "";
  $("#composeMsg").textContent = "";
  $("#composeWebFallback").classList.add("hidden");
  setComposePassSaved(readSavedPassword());
  refreshAttachments();
  $("#composeMask").classList.remove("hidden");
}

function handleBack() {
  if (!$("#composeMask").classList.contains("hidden")) { closeCompose(); return true; }
  if (!$("#passMask").classList.contains("hidden")) { finishPass(null); return true; }
  if (!$("#payMask").classList.contains("hidden")) { closePaySheet(); return true; }
  if (!$("#sheetMask").classList.contains("hidden")) { closeSheet(); return true; }
  if (!$("#mailDetailView").classList.contains("hidden")) { closeMailDetail(); return true; }
  if (!$("#mailView").classList.contains("hidden")) { closeMail(); return true; }
  if (!$("#smsBox").classList.contains("hidden")) { $("#smsBox").classList.add("hidden"); return true; }
  return false;
}
window.handleBack = handleBack;

function closeMail() {
  closeMailDetail();
  $("#mailView").classList.add("hidden");
}

function openPaySheet(target) {
  payTarget = target;
  $("#payMask").classList.remove("hidden");
  $("#payTitle").textContent = target === "card" ? "校园卡充值" : "网费充值";
  const bal = target === "card"
    ? (state.card?.balance != null ? `当前余额 ¥ ${state.card.balance}` : "校园卡")
    : (() => {
        const c = (state.data?.net?.cards || []).find((x) => /网费/.test(x.name || ""));
        return c ? `当前余额 ¥ ${c.value}${c.unit || ""}` : "网费";
      })();
  $("#payBalance").textContent = bal;
  $("#payAmount").value = "";
  $("#payMsg").textContent = "";
  $$("#payPresets button").forEach((b) => b.classList.remove("on"));
}

function closePaySheet() {
  $("#payMask").classList.add("hidden");
  if (payPollTimer) { clearTimeout(payPollTimer); payPollTimer = null; }
}

async function pollPay(orderNo, tries = 0) {
  if (!orderNo) return;
  if (tries > 45) { toast("支付结果查询超时，请稍后在缴费平台查看"); return; }
  try {
    const r = await api(`/api/pay/order?order_no=${encodeURIComponent(orderNo)}`);
    if (r.payflag === "2") {
      toast("充值成功");
      if (payTarget === "card") loadCard(true);
      else refresh();
      return;
    }
    if (r.payflag === "4") { toast("订单已过期"); return; }
  } catch (e) { /* 继续轮询 */ }
  payPollTimer = setTimeout(() => pollPay(orderNo, tries + 1), 2000);
}

async function confirmPay() {
  const amt = Math.max(0, Math.min(1000, parseFloat($("#payAmount").value)));
  if (isNaN(amt) || amt < 0.01) return toast("请输入 0.01–1000 元");
  const btn = $("#btnPayConfirm");
  btn.disabled = true;
  const payWin = window.NEU ? null : window.open("about:blank", "_blank", "noopener");
  $("#payMsg").textContent = "正在创建订单…";
  try {
    const r = await api(payTarget === "card" ? "/api/pay/card" : "/api/pay/net", {
      method: "POST", body: JSON.stringify({ amount: amt }),
    });
    if (!r.url) throw new Error("未返回支付地址");
    closePaySheet();
    if (window.NEU) window.NEU.call("openPay", JSON.stringify({ url: r.url }));
    else if (payWin) payWin.location.href = r.url;
    else window.open(r.url, "_blank", "noopener");
    toast("支付完成后会自动刷新");
    pollPay(r.order_no);
  } catch (e) {
    if (payWin) { try { payWin.close(); } catch {} }
    $("#payMsg").textContent = e.message;
  } finally {
    btn.disabled = false;
  }
}

$("#btnCardPay").addEventListener("click", () => openPaySheet("card"));
$("#btnNetPay").addEventListener("click", () => openPaySheet("net"));
$("#btnPayCancel").addEventListener("click", closePaySheet);
$("#btnPayConfirm").addEventListener("click", confirmPay);
$$("#payPresets button").forEach((b) => b.addEventListener("click", () => {
  $("#payAmount").value = b.dataset.amt;
  $$("#payPresets button").forEach((x) => x.classList.toggle("on", x === b));
}));
$("#payMask").addEventListener("click", (e) => { if (e.target === $("#payMask")) closePaySheet(); });

$("#btnMail").addEventListener("click", openMail);
$("#btnCardRefresh").addEventListener("click", () => {
  if (cardLoading) return;
  loadCard(true);
});
$("#mailBack").addEventListener("click", closeMail);
$("#mailDetailBack").addEventListener("click", closeMailDetail);
$("#mailDetailWeb").addEventListener("click", () => openUrlSafe(mailWebUrl));
$("#mailRefresh").addEventListener("click", () => loadMail(true));
$("#mailCompose").addEventListener("click", openCompose);
$("#composeCancel").addEventListener("click", closeCompose);
$("#composeAttach").addEventListener("click", pickAttachments);
$("#composeWebFallback").addEventListener("click", () => { closeCompose(); openWebCompose(); });
$("#composeSend").addEventListener("click", sendCompose);
$("#composeFrom").addEventListener("input", updateComposeReady);
$("#composePassword").addEventListener("input", updateComposeReady);
$("#composeTo").addEventListener("input", updateComposeReady);
$("#composePassChange").addEventListener("click", () => {
  try { window.NEU.call("clearMailPassword", "{}"); } catch {}
  setComposePassSaved(false);
  setTimeout(() => $("#composePassword").focus(), 80);
});
$("#composeAttachList").addEventListener("click", (e) => {
  if (e.target && e.target.id === "composeAttachClear") clearAttachments();
});
$("#composeMask").addEventListener("focusin", (e) => {
  setTimeout(() => e.target.scrollIntoView({ block: "center", behavior: "smooth" }), 180);
});
$("#composePwdHelp").addEventListener("click", (e) => {
  e.preventDefault();
  openUrlSafe("https://mails.neu.edu.cn/", "申请专用密码");
});
$("#composeMask").addEventListener("click", (e) => { if (e.target === $("#composeMask")) closeCompose(); });

/* ---------------- 可选底部 Tab ---------------- */
const TAB_DEFS = [
  { id: "schedule", label: "课表", note: "课程与周历" },
  { id: "exams", label: "考试", note: "考试安排" },
  { id: "card", label: "校园卡", note: "余额与校园码" },
  { id: "grades", label: "成绩", note: "成绩与排名" },
  { id: "net", label: "网费", note: "余额与流量" },
  { id: "run", label: "乐跑", note: "校园乐跑 H5" },
];
const TABS_KEY = "neu.tabs.v1";
let tabSettings = {};
function loadTabSettings() {
  try { tabSettings = JSON.parse(localStorage.getItem(TABS_KEY) || "{}") || {}; } catch { tabSettings = {}; }
  TAB_DEFS.forEach((t) => { if (typeof tabSettings[t.id] !== "boolean") tabSettings[t.id] = true; });
}
function saveTabSettings() {
  try { localStorage.setItem(TABS_KEY, JSON.stringify(tabSettings)); } catch {}
}
function visibleTabs() {
  return TAB_DEFS.filter((t) => tabSettings[t.id] !== false);
}
function renderTabs() {
  const enabled = visibleTabs();
  $$(".tab").forEach((b) => b.classList.toggle("hidden", !tabSettings[b.dataset.tab]));
  const bar = $(".tabbar");
  if (bar) bar.style.gridTemplateColumns = `repeat(${Math.max(1, enabled.length)},1fr)`;
  if (!enabled.some((t) => t.id === state.tab) && enabled.length) activateTab(enabled[0].id);
}
function renderTabSettings() {
  const box = $("#tabsList");
  if (!box) return;
  box.innerHTML = TAB_DEFS.map((t) => `<label class="tabrow">
    <span>${t.label}<small>${t.note}</small></span>
    <span class="switch"><input type="checkbox" data-tabid="${t.id}" ${tabSettings[t.id] !== false ? "checked" : ""}><i></i></span>
  </label>`).join("");
  $$("#tabsList input").forEach((input) => input.addEventListener("change", () => {
    const id = input.dataset.tabid;
    const on = input.checked;
    if (!on && visibleTabs().length <= 1) {
      input.checked = true;
      toast("至少保留一个 Tab");
      return;
    }
    tabSettings[id] = on;
    saveTabSettings();
    renderTabs();
    renderTabSettings();
  }));
}

/* ---------------- 标签页 ---------------- */
function activateTab(name) {
  const btn = $(`.tab[data-tab="${name}"]`);
  if (!btn || btn.classList.contains("hidden")) {
    const first = $$(".tab").find((b) => !b.classList.contains("hidden"));
    if (first && first.dataset.tab !== name) activateTab(first.dataset.tab);
    return;
  }
  state.tab = name;
  document.body.classList.toggle("card-mode", name === "card");
  document.body.classList.toggle("roomy-top", name === "schedule" || name === "exams");
  $$(".tab").forEach((b) => b.classList.toggle("active", b === btn));
  $$(".panel").forEach((p) => p.classList.add("hidden"));
  $(`#panel-${name}`).classList.remove("hidden");
  renderStats(name, true);
  if (name === "run") updateRunPanel();
  if (name === "card") loadCard();
  else stopQr();
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
else document.body.classList.add("roomy-top");
$("#btnTabs").addEventListener("click", () => {
  closeSheet();
  renderTabSettings();
  $("#tabsMask").classList.remove("hidden");
});
$("#tabsDone").addEventListener("click", () => $("#tabsMask").classList.add("hidden"));
$("#tabsMask").addEventListener("click", (e) => { if (e.target === $("#tabsMask")) $("#tabsMask").classList.add("hidden"); });
loadTabSettings();
renderTabSettings();
renderTabs();
boot();




