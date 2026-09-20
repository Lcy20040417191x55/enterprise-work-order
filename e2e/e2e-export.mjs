// =====================================================================
//  导出接口端到端验证
//  用法: node e2e\e2e-export.mjs        (服务需已启动在 8081)
//
//  断言全部在本脚本内完成，只把"通过/失败"和样本写进 e2e\export-report.txt。
//  原因：Windows 控制台在 PowerShell 重定向与 Node 之间会做一次非 UTF-8 转码，
//  中文直接打印出来是问号。断言不依赖控制台，才不会被编码问题误报。
//
//  验证点：
//    1. 返回真正的 xlsx（zip 魔数），不是 JSON 错误
//    2. 中文文件名用 RFC 5987 编码，可被正确解码
//    3. 表头、列顺序、单元格内容正确
//    4. 导出内容与列表接口的筛选条件一致
//    5. 权限生效：越权拿不到数据
//    6. 未登录被拦下
//    7. 非法查询条件返回 JSON 错误而不是坏文件
//    8. 空结果仍返回可打开的 xlsx
// =====================================================================
import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";

const BASE = process.env.BASE || "http://127.0.0.1:8081";
const PWD = "123456";
const OUT_DIR = path.join(process.cwd(), "e2e", "export-out");
const READER = path.join(process.cwd(), "e2e", "xlsx-read.py");
const REPORT = path.join(process.cwd(), "e2e", "export-report.txt");

let pass = 0, fail = 0;
const failures = [];
const reportLines = [];

function log(line) { console.log(line); reportLines.push(line); }

function check(name, cond, extra = "") {
  if (cond) { pass++; log("  [PASS] " + name); }
  else {
    fail++; failures.push(name + (extra ? " -> " + extra : ""));
    log("  [FAIL] " + name + (extra ? " -> " + extra : ""));
  }
}

async function login(username) {
  const r = await fetch(BASE + "/api/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password: PWD }),
  });
  const b = await r.json();
  if (b.code !== 200) throw new Error("login failed: " + JSON.stringify(b));
  return b.data.token;
}

async function getPage(token, params) {
  const qs = new URLSearchParams(params).toString();
  const r = await fetch(BASE + "/api/tickets?" + qs, {
    headers: { Authorization: "Bearer " + token },
  });
  return (await r.json()).data;
}

async function getRaw(token, params) {
  const qs = new URLSearchParams(params).toString();
  const r = await fetch(BASE + "/api/tickets/export?" + qs, {
    headers: token ? { Authorization: "Bearer " + token } : {},
  });
  const buf = Buffer.from(await r.arrayBuffer());
  return { status: r.status, headers: r.headers, buf };
}

const isZip = (buf) => buf.length > 2 && buf[0] === 0x50 && buf[1] === 0x4b;

/** 把 xlsx 交给 Python 读成 JSON。必须显式 utf8，否则 Windows 默认 GBK 会乱码 */
function readXlsx(file) {
  const out = execFileSync("python", ["-X", "utf8", READER, file], { encoding: "utf8" });
  return JSON.parse(out);
}

function save(file, buf) {
  const p = path.join(OUT_DIR, file);
  fs.writeFileSync(p, buf);
  return p;
}

/** 直接读 sheet1.xml 原文，用来断言 POI 写出的表格设置 */
function rawSheetXml(file) {
  const tmp = path.join(OUT_DIR, "_probe.py");
  fs.writeFileSync(tmp, "import sys,zipfile\nprint(zipfile.ZipFile(sys.argv[1]).read('xl/worksheets/sheet1.xml').decode('utf-8'))", "utf8");
  const out = execFileSync("python", ["-X", "utf8", tmp, file], { encoding: "utf8" });
  fs.rmSync(tmp);
  return out;
}

fs.mkdirSync(OUT_DIR, { recursive: true });

const admin = await login("admin");
const zhangsan = await login("zhangsan");
const wangwu = await login("wangwu");
const lisi = await login("lisi");

const EXPECTED_HEADER = ["工单编号", "标题", "类型", "优先级", "状态", "发起部门",
  "创建人", "当前处理人", "当前进度", "提交时间", "办结时间", "创建时间", "内容"];

// ---------------------------------------------------------------- 1
log("\n=== 1. 管理员导出全部 ===");
const r1 = await getRaw(admin, { scope: "all" });
check("HTTP 200", r1.status === 200, "got " + r1.status);
check("是 xlsx 而非 JSON（zip 魔数 PK）", isZip(r1.buf),
  "首字节 " + [...r1.buf.slice(0, 4)].map(b => b.toString(16)).join(" "));
check("Content-Type 正确",
  (r1.headers.get("content-type") || "").includes("spreadsheetml.sheet"),
  r1.headers.get("content-type"));

const cd = r1.headers.get("content-disposition") || "";
check("含 Content-Disposition", cd.includes("attachment"), cd);
const star = /filename\*=UTF-8''([^;]+)/.exec(cd);
check("文件名用 RFC 5987 编码", !!star, cd);
let decodedName = null;
if (star) {
  decodedName = decodeURIComponent(star[1]);
  check("中文文件名可正确解码",
    decodedName.startsWith("工单导出_") && decodedName.endsWith(".xlsx"), decodedName);
}

const f1 = save("all.xlsx", r1.buf);
const sheet1 = readXlsx(f1).sheet;

// ---------------------------------------------------------------- 2
log("\n=== 2. 文件内容与表格设置 ===");
check("表头文字与顺序正确",
  JSON.stringify(sheet1[0].cells) === JSON.stringify(EXPECTED_HEADER),
  JSON.stringify(sheet1[0].cells));
const xml1 = rawSheetXml(f1);
check("含冻结首行（滚动时表头不消失）", /<pane[^>]*ySplit="1/.test(xml1));
check("列宽已设置", /<cols>/.test(xml1) && /width="22/.test(xml1));
check("13 列宽度全部自定义", (xml1.match(/customWidth="true"/g) || []).length === 13,
  "customWidth 个数=" + (xml1.match(/customWidth="true"/g) || []).length);

// ---------------------------------------------------------------- 3
log("\n=== 3. 导出内容与列表筛选一致 ===");
const pageAll = await getPage(admin, { scope: "all", pageNum: 1, pageSize: 200 });
check("无截断时：数据行数 == 列表 total",
  sheet1.length - 1 === pageAll.total,
  "xlsx 数据行=" + (sheet1.length - 1) + " 列表 total=" + pageAll.total);

const pageLeave = await getPage(admin, { scope: "all", typeId: 1, pageNum: 1, pageSize: 200 });
const sheet2 = readXlsx(save("type1.xlsx", (await getRaw(admin, { scope: "all", typeId: 1 })).buf)).sheet;
check("按类型筛选后导出行数 == 列表 total",
  sheet2.length - 1 === pageLeave.total,
  "xlsx=" + (sheet2.length - 1) + " 列表=" + pageLeave.total);
check("按类型筛选后每行的类型列都正确",
  sheet2.slice(1).every(r => r.cells[2] === "请假申请"),
  JSON.stringify(sheet2.slice(1).map(r => r.cells[2])));

const pageKw = await getPage(admin, { scope: "all", keyword: "TK20260919", pageNum: 1, pageSize: 200 });
const sheetKw = readXlsx(save("keyword.xlsx", (await getRaw(admin, { scope: "all", keyword: "TK20260919" })).buf)).sheet;
check("按关键词筛选后导出行数 == 列表 total",
  sheetKw.length - 1 === pageKw.total,
  "xlsx=" + (sheetKw.length - 1) + " 列表=" + pageKw.total);
check("关键词命中的行确实都含该关键词",
  sheetKw.slice(1).every(r => r.cells[0].includes("TK20260919") || r.cells[1].includes("TK20260919")));

const pageLisiMine = await getPage(lisi, { scope: "done", pageNum: 1, pageSize: 200 });
const sheetLisiDone = readXlsx(save("lisi-done.xlsx", (await getRaw(lisi, { scope: "done" })).buf)).sheet;
check("scope=done 导出与列表一致（含子查询过滤）",
  sheetLisiDone.length - 1 === pageLisiMine.total,
  "xlsx=" + (sheetLisiDone.length - 1) + " 列表=" + pageLisiMine.total);

// ---------------------------------------------------------------- 4
log("\n=== 4. 单元格取值 ===");
const allRows = sheet1.slice(1);
const STATUSES = ["草稿", "审批中", "已通过", "已驳回", "已撤回", "已作废"];
const PRIORITIES = ["低", "普通", "高", "紧急"];
check("状态列都是中文标签，没有出现英文枚举名",
  allRows.every(r => STATUSES.includes(r.cells[4])),
  JSON.stringify([...new Set(allRows.map(r => r.cells[4]))]));
check("优先级列都是中文标签",
  allRows.every(r => PRIORITIES.includes(r.cells[3])),
  JSON.stringify([...new Set(allRows.map(r => r.cells[3]))]));
check("进度列格式为 x/y 或 -",
  allRows.every(r => r.cells[8] === "-" || /^\d+\/\d+$/.test(r.cells[8])),
  JSON.stringify([...new Set(allRows.map(r => r.cells[8]))]));
check("时间列为 yyyy-MM-dd HH:mm:ss 或空",
  allRows.every(r => [9, 10, 11].every(i => r.cells[i] === "" || /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(r.cells[i]))),
  JSON.stringify(allRows[0].cells.slice(9, 12)));
check("未提交的工单：提交时间/办结时间为空而不是 null 字样",
  allRows.filter(r => r.cells[4] === "草稿" || r.cells[4] === "已撤回")
    .every(r => !/null/i.test(r.cells[9]) && !/null/i.test(r.cells[10])));
check("内容列非空（有内容的单不能导出成空）",
  allRows.some(r => (r.cells[12] || "").length > 0));
check("每行都是 13 列（没有串列）",
  allRows.every(r => r.cells.length === 13),
  JSON.stringify([...new Set(allRows.map(r => r.cells.length))]));

// ---------------------------------------------------------------- 5
log("\n=== 5. 权限：导出不能越权拿到别人的单 ===");
const pageWangwu = await getPage(wangwu, { scope: "all", pageNum: 1, pageSize: 200 });
const sheetW = readXlsx(save("wangwu-all.xlsx", (await getRaw(wangwu, { scope: "all" })).buf)).sheet;
check("wangwu（与全部 5 张单无关）导出行数 == 他自己的列表 total",
  sheetW.length - 1 === pageWangwu.total,
  "xlsx=" + (sheetW.length - 1) + " 列表=" + pageWangwu.total);
check("wangwu 导出的数据行数为 0（证明权限收窄生效）",
  sheetW.length - 1 === 0, "xlsx 数据行=" + (sheetW.length - 1));
check("wangwu 的导出仍含完整表头（不是空文件）",
  JSON.stringify(sheetW[0].cells) === JSON.stringify(EXPECTED_HEADER),
  JSON.stringify(sheetW[0].cells));

const pageLisi = await getPage(lisi, { scope: "all", pageNum: 1, pageSize: 200 });
const sheetL = readXlsx(save("lisi-all.xlsx", (await getRaw(lisi, { scope: "all" })).buf)).sheet;
check("lisi（只与部分单相关）导出行数 == 自己的列表 total",
  sheetL.length - 1 === pageLisi.total,
  "xlsx=" + (sheetL.length - 1) + " 列表=" + pageLisi.total);
check("lisi 能看到的比管理员少",
  pageLisi.total < pageAll.total,
  "lisi=" + pageLisi.total + " 管理员=" + pageAll.total);

// ---------------------------------------------------------------- 6
log("\n=== 6. 未登录被拦下 ===");
const r3 = await getRaw(null, { scope: "all" });
check("未带 token -> 401", r3.status === 401, "got " + r3.status);
check("返回 JSON 错误而不是文件", !isZip(r3.buf), "首字节是 PK");
let json3 = null;
try { json3 = JSON.parse(r3.buf.toString("utf8")); } catch { /* ignore */ }
check("401 响应体是 Result 结构", json3 !== null && json3.code === 401,
  r3.buf.toString("utf8").slice(0, 200));

// ---------------------------------------------------------------- 7
log("\n=== 7. 非法查询条件返回 JSON 错误（不是坏文件） ===");
const r4 = await getRaw(admin, { scope: "not-a-scope" });
check("scope 非法时不返回 zip", !isZip(r4.buf),
  "首字节 " + [...r4.buf.slice(0, 2)].map(b => b.toString(16)).join(" "));
let json4 = null;
try { json4 = JSON.parse(r4.buf.toString("utf8")); } catch { /* ignore */ }
check("scope 非法时返回可解析的 JSON", json4 !== null, r4.buf.toString("utf8").slice(0, 300));
if (json4) check("错误提示里点名了 scope", /scope/i.test(json4.message || ""), json4.message);

let json4b = null;
try { json4b = JSON.parse((await getRaw(admin, { pageNum: "", scope: "all" })).buf.toString("utf8")); } catch { /* ignore */ }
check("pageNum 为空串时返回 400 而不是 500",
  json4b !== null && json4b.code === 400, JSON.stringify(json4b));

let json4c = null;
try { json4c = JSON.parse((await getRaw(admin, { scope: "all", typeId: "abc" })).buf.toString("utf8")); } catch { /* ignore */ }
check("typeId 非数字时返回 JSON 错误而不是 500",
  json4c !== null && json4c.code !== 500, JSON.stringify(json4c));

// ---------------------------------------------------------------- 8
log("\n=== 8. 空结果仍可打开 ===");
const r5 = await getRaw(admin, { scope: "all", keyword: "zzz-never-exists-zzz" });
check("空结果仍返回合法 xlsx", isZip(r5.buf));
const sheet5 = readXlsx(save("empty.xlsx", r5.buf)).sheet;
check("空结果只有表头", sheet5.length === 1, "行数=" + sheet5.length);
check("空结果表头完整", JSON.stringify(sheet5[0].cells) === JSON.stringify(EXPECTED_HEADER),
  JSON.stringify(sheet5[0].cells));

// ---------------------------------------------------------------- 汇总
log("\n================================");
log("通过 " + pass + " / 失败 " + fail);
if (failures.length) {
  log("\n失败项:");
  failures.forEach(f => log("  - " + f));
}
log("\n导出文件目录: " + OUT_DIR);
if (decodedName) log("示例文件名: " + decodedName);

fs.writeFileSync(REPORT, reportLines.join("\n"), "utf8");
process.exit(fail === 0 ? 0 : 1);