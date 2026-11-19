const assert = require("assert");
const D = require("../web/diff.js");

const course = (o) => Object.assign({
  course: "课程", day: 1, begin_section: 1, end_section: 2, begin: "08:30", end: "10:10",
  place: "A101", teacher: "张", weeks_text: "1-8周", weeks: [1, 2, 3, 4, 5, 6, 7, 8], arranged: true,
}, o);

const prevCourses = [
  course({ course: "操作系统", day: 1 }),
  course({ course: "数据库概论", day: 2, place: "B201" }),
  course({ course: "旧课程", day: 3 }),
];
const nextCourses = [
  course({ course: "操作系统", day: 1 }),
  course({ course: "数据库概论", day: 2, place: "B305" }),
  course({ course: "新课程", day: 4 }),
];

const sd = D.schedule(prevCourses, nextCourses);
assert.strictEqual(sd.added.length, 1, "新增 1 门");
assert.strictEqual(sd.added[0].course, "新课程");
assert.strictEqual(sd.removed.length, 1, "删除 1 门");
assert.strictEqual(sd.removed[0].course, "旧课程");
assert.strictEqual(sd.changed.length, 1, "修改 1 门");
assert.strictEqual(sd.changed[0].course, "数据库概论");
assert.strictEqual(sd.changed[0].fields[0].label, "地点");
assert.strictEqual(sd.changed[0].fields[0].from, "B201");
assert.strictEqual(sd.changed[0].fields[0].to, "B305");

const prevGrades = {
  rank: { gpa: "4.2423", major_rank: "4" },
  items: [
    { course: "操作系统", term: "2025-2026春", score: "90", gpa: "4.8", credit: "2.25" },
    { course: "数据库概论", term: "2025-2026春", score: "88", gpa: "4.5", credit: "2.5" },
  ],
};
const nextGrades = {
  rank: { gpa: "4.2501", major_rank: "3" },
  items: [
    { course: "操作系统", term: "2025-2026春", score: "90", gpa: "4.8", credit: "2.25" },
    { course: "数据库概论", term: "2025-2026春", score: "92", gpa: "4.7", credit: "2.5" },
    { course: "计算机网络", term: "2025-2026春", score: "85", gpa: "3.5", credit: "2.5" },
  ],
};
const gd = D.grades(prevGrades, nextGrades);
assert.deepStrictEqual(gd.gpa, { from: "4.2423", to: "4.2501" });
assert.deepStrictEqual(gd.rank, { from: "4", to: "3" });
assert.strictEqual(gd.added.length, 1);
assert.strictEqual(gd.added[0].course, "计算机网络");
assert.strictEqual(gd.added[0].score, "85");
assert.strictEqual(gd.changed.length, 1);
assert.strictEqual(gd.changed[0].course, "数据库概论");

// 空差异
assert.strictEqual(D.isEmpty({}), true);
assert.strictEqual(D.isEmpty({ schedule: { added: [], removed: [], changed: [] }, grades: {} }), true);
assert.strictEqual(D.isEmpty({ schedule: sd }), false);
assert.strictEqual(D.isEmpty({ grades: gd }), false);

// 首次获取 / 缺少旧数据
assert.strictEqual(D.isEmpty({ schedule: D.schedule(undefined, nextCourses) }), true);
assert.strictEqual(D.isEmpty({ grades: D.grades(null, nextGrades) }), true);

// 渲染
const esc = (s) => String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const html = D.render({ schedule: sd, grades: gd }, esc);
assert.ok(html.includes("课表"), "包含课表分区");
assert.ok(html.includes("新增") && html.includes("删除") && html.includes("修改"), "包含三类课表变化");
assert.ok(html.includes("绩点") && html.includes("4.2423") && html.includes("4.2501"), "包含绩点变化");
assert.ok(html.includes("排名") && html.includes("新成绩") && html.includes("计算机网络"), "包含排名与新成绩");
assert.ok(html.includes("85"), "新成绩包含分数");
assert.ok(!html.includes("<script>"), "无脚本注入");

// 长列表截断提示
const many = Array.from({ length: 9 }, (_, i) => course({ course: "课程" + i, day: 1 }));
const manyHtml = D.render({ schedule: { added: many.map((c) => ({ course: c.course, slot: "", place: "", teacher: "", weeks: "" })), removed: [], changed: [] } }, esc);
assert.ok(manyHtml.includes("还有 3 项未展开"), "超过 6 项给出折叠提示");

console.log("diff tests passed");
