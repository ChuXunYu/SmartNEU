/**
 * 课表 / 成绩差异计算与卡片渲染（纯函数，便于单独测试）。
 *
 * 浏览器：window.NEUDiff
 * Node：  require("./diff.js")
 */
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  if (root) root.NEUDiff = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  const DAYS = { 1: "周一", 2: "周二", 3: "周三", 4: "周四", 5: "周五", 6: "周六", 7: "周日" };
  const MAX_ROWS = 6;

  function text(v) {
    return v == null ? "" : String(v).trim();
  }

  function sections(c) {
    const b = Number(c.begin_section) || 0;
    const e = Number(c.end_section) || 0;
    if (!b) return "";
    return e && e !== b ? `第${b}-${e}节` : `第${b}节`;
  }

  function slot(c) {
    return [DAYS[Number(c.day)] || "", sections(c)].filter(Boolean).join(" ");
  }

  function weeksText(c) {
    if (text(c.weeks_text)) return text(c.weeks_text);
    if (Array.isArray(c.weeks) && c.weeks.length) return `${c.weeks.join("、")}周`;
    return "";
  }

  function keyOf(c) {
    return [text(c.course), Number(c.day) || 0, Number(c.begin_section) || 0,
      Number(c.end_section) || 0].join("|");
  }

  function group(list) {
    const map = new Map();
    (list || []).forEach((c) => {
      if (!c || c.arranged === false) return;
      const k = keyOf(c);
      if (!map.has(k)) map.set(k, []);
      map.get(k).push(c);
    });
    map.forEach((arr) => arr.sort((a, b) => weeksText(a).localeCompare(weeksText(b))));
    return map;
  }

  function brief(c) {
    return {
      course: text(c.course),
      slot: slot(c),
      place: text(c.place),
      teacher: text(c.teacher),
      weeks: weeksText(c),
    };
  }

  function schedule(prevCourses, nextCourses) {
    const out = { added: [], removed: [], changed: [] };
    if (!Array.isArray(prevCourses) || !Array.isArray(nextCourses)) return out;
    const before = group(prevCourses);
    const after = group(nextCourses);
    const keys = new Set([...before.keys(), ...after.keys()]);
    keys.forEach((k) => {
      const a = before.get(k) || [];
      const b = after.get(k) || [];
      const n = Math.max(a.length, b.length);
      for (let i = 0; i < n; i++) {
        const oldOne = a[i];
        const newOne = b[i];
        if (!oldOne) { out.added.push(brief(newOne)); continue; }
        if (!newOne) { out.removed.push(brief(oldOne)); continue; }
        const fields = [];
        const pairs = [
          ["地点", text(oldOne.place), text(newOne.place)],
          ["教师", text(oldOne.teacher), text(newOne.teacher)],
          ["周次", weeksText(oldOne), weeksText(newOne)],
          ["时间", text(oldOne.begin) + (text(oldOne.end) ? "-" + text(oldOne.end) : ""),
            text(newOne.begin) + (text(newOne.end) ? "-" + text(newOne.end) : "")],
        ];
        pairs.forEach(([label, from, to]) => {
          if (from !== to) fields.push({ label, from, to });
        });
        if (fields.length) out.changed.push({ course: text(newOne.course), slot: slot(newOne), fields });
      }
    });
    return out;
  }

  function gradeKey(x) {
    return [text(x.term), text(x.course)].join("|");
  }

  function gradeBrief(x) {
    return {
      course: text(x.course),
      term: text(x.term),
      score: text(x.score),
      gpa: text(x.gpa),
      credit: text(x.credit),
    };
  }

  function grades(prev, next) {
    const out = { gpa: null, rank: null, added: [], changed: [] };
    if (!prev || !next) return out;
    const before = new Map();
    (prev.items || []).forEach((x) => { if (x && x.course) before.set(gradeKey(x), x); });
    const after = new Map();
    (next.items || []).forEach((x) => { if (x && x.course) after.set(gradeKey(x), x); });

    after.forEach((x, k) => {
      const old = before.get(k);
      if (!old) { out.added.push(gradeBrief(x)); return; }
      const fields = [];
      if (text(old.score) !== text(x.score)) fields.push({ label: "成绩", from: text(old.score), to: text(x.score) });
      if (text(old.gpa) !== text(x.gpa)) fields.push({ label: "绩点", from: text(old.gpa), to: text(x.gpa) });
      if (text(old.credit) !== text(x.credit)) fields.push({ label: "学分", from: text(old.credit), to: text(x.credit) });
      if (fields.length) out.changed.push(Object.assign(gradeBrief(x), { fields }));
    });

    const pr = (prev.rank || {});
    const nr = (next.rank || {});
    if (text(pr.gpa) && text(nr.gpa) && text(pr.gpa) !== text(nr.gpa)) {
      out.gpa = { from: text(pr.gpa), to: text(nr.gpa) };
    }
    const fromRank = text(pr.major_rank) || text(pr.rank);
    const toRank = text(nr.major_rank) || text(nr.rank);
    if (fromRank && toRank && fromRank !== toRank) {
      out.rank = { from: fromRank, to: toRank };
    }
    return out;
  }

  function isEmpty(diff) {
    if (!diff) return true;
    const s = diff.schedule || {};
    const g = diff.grades || {};
    return !(s.added || []).length && !(s.removed || []).length && !(s.changed || []).length &&
      !(g.added || []).length && !(g.changed || []).length && !g.gpa && !g.rank;
  }

  function more(n) {
    return n > MAX_ROWS ? `<p class="diffmore">还有 ${n - MAX_ROWS} 项未展开</p>` : "";
  }

  /**
   * 渲染卡片正文。esc 由调用方传入，保证与页面其他文案同一套转义。
   */
  function render(diff, esc) {
    const e = typeof esc === "function" ? esc : (s) => String(s == null ? "" : s)
      .replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
    const s = (diff && diff.schedule) || {};
    const g = (diff && diff.grades) || {};
    const parts = [];

    const hasSchedule = (s.added || []).length || (s.removed || []).length || (s.changed || []).length;
    if (hasSchedule) {
      const rows = [];
      (s.added || []).slice(0, MAX_ROWS).forEach((c) => {
        rows.push(`<div class="diffrow add"><i>新增</i><b>${e(c.course)}</b><span>${e(c.slot)}${c.place ? " · " + e(c.place) : ""}${c.weeks ? " · " + e(c.weeks) : ""}</span></div>`);
      });
      (s.removed || []).slice(0, MAX_ROWS).forEach((c) => {
        rows.push(`<div class="diffrow del"><i>删除</i><b>${e(c.course)}</b><span>${e(c.slot)}${c.place ? " · " + e(c.place) : ""}${c.weeks ? " · " + e(c.weeks) : ""}</span></div>`);
      });
      (s.changed || []).slice(0, MAX_ROWS).forEach((c) => {
        const fields = c.fields.map((f) => `${e(f.label)} ${e(f.from) || "空"} → ${e(f.to) || "空"}`).join("；");
        rows.push(`<div class="diffrow mod"><i>修改</i><b>${e(c.course)}</b><span>${e(c.slot)}</span><span class="diffmeta">${fields}</span></div>`);
      });
      const total = (s.added || []).length + (s.removed || []).length + (s.changed || []).length;
      parts.push(`<section class="diffsec"><div class="diffhead"><h4>课表</h4><span>${total} 处变化</span></div>${rows.join("")}${more(total)}</section>`);
    }

    const hasGrades = g.gpa || g.rank || (g.added || []).length || (g.changed || []).length;
    if (hasGrades) {
      const rows = [];
      if (g.gpa) {
        rows.push(`<div class="diffrow key"><i>绩点</i><b>${e(g.gpa.from)} → ${e(g.gpa.to)}</b><span>平均绩点 GPA</span></div>`);
      }
      if (g.rank) {
        rows.push(`<div class="diffrow key"><i>排名</i><b>${e(g.rank.from)} → ${e(g.rank.to)}</b><span>专业排名</span></div>`);
      }
      (g.added || []).slice(0, MAX_ROWS).forEach((x) => {
        rows.push(`<div class="diffrow add"><i>新成绩</i><b>${e(x.course)}</b><span>${e(x.score)} 分${x.gpa ? " · 绩点 " + e(x.gpa) : ""}${x.credit ? " · " + e(x.credit) + " 学分" : ""}</span>${x.term ? `<span class="diffmeta">${e(x.term)}</span>` : ""}</div>`);
      });
      (g.changed || []).slice(0, MAX_ROWS).forEach((x) => {
        const fields = x.fields.map((f) => `${e(f.label)} ${e(f.from) || "空"} → ${e(f.to) || "空"}`).join("；");
        rows.push(`<div class="diffrow mod"><i>变动</i><b>${e(x.course)}</b><span class="diffmeta">${fields}</span></div>`);
      });
      const total = (g.added || []).length + (g.changed || []).length + (g.gpa ? 1 : 0) + (g.rank ? 1 : 0);
      parts.push(`<section class="diffsec"><div class="diffhead"><h4>成绩</h4><span>${total} 处变化</span></div>${rows.join("")}${more(total)}</section>`);
    }

    return parts.join("");
  }

  return { schedule, grades, isEmpty, render, slot, sections };
});
