// =====================================================================
//  导出截断提示验证（需要服务以低上限启动）
//  用法:
//   1) 用 -Dworkorder.export.max-rows=2 启动服务
//   2) node e2e\e2e-export-truncate.mjs
//
//  为什么要把上限做成可配置：这段"结果被截断"的代码只在数据超过上限时才执行。
//  如果上限是写死的 100000，那么在本机永远跑不到这个分支 —— 而没被执行过的代码
//  就是没被验证过的代码。把它做成配置项，才能真实地把这条路径跑一遍。
// =====================================================================
import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";

const BASE = process.env.BASE || "http://127.0.0.1:8081";
const OUT_DIR = path.join(process.cwd(), "e2e", "export-out");
const READER = path.join(process.cwd(), "e2e", "xlsx-read.py");

let pass = 0, fail = 0;
const lines = [];
function log(l) { console.log(l); lines.push(l); }
function check(name, cond, extra = "") {
  if (cond) { pass++; log("  [PASS] " + name); }
  else { fail++; log("  [FAIL] " + name + (extra ? " -> " + extra : "")); }
}

async function login(u) {
  const r = await fetch(BASE + "/api/auth/login", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username: u, password: "123456" }),
  });
  return (await r.json()).data.token;
}

const admin = await login("admin");
const qs = new URLSearchParams({ scope: "all" }).toString();
const r = await fetch(BASE + "/api/tickets/export?" + qs, {
  headers: { Authorization: "Bearer " + admin },
});
const buf = Buffer.from(await r.arrayBuffer());
const file = path.join(OUT_DIR, "truncated.xlsx");
fs.mkdirSync(OUT_DIR, { recursive: true });
fs.writeFileSync(file, buf);
const sheet = JSON.parse(execFileSync("python", ["-X", "utf8", READER, file], { encoding: "utf8" })).sheet;

const total = (await (await fetch(BASE + "/api/tickets?" + qs, {
  headers: { Authorization: "Bearer " + admin },
})).json()).data.total;

log("\n=== 截断分支验证（服务端上限设为 2，实际符合条件的共 " + total + " 条） ===");
log("  xlsx 实际行数: " + sheet.length + "（含表头）");
log("  第 1 行内容: " + JSON.stringify(sheet[0].cells.slice(0, 1)));
log("  第 2 行内容: " + JSON.stringify(sheet[1].cells.slice(0, 1)));

check("第 1 行仍是表头",
  sheet[0].cells[0] === "工单编号" && sheet[0].cells[1] === "标题");
check("第 2 行是提示行，不是数据",
  /^提示/.test(sheet[1].cells[0]) && !sheet[1].cells[0].startsWith("TK"),
  JSON.stringify(sheet[1].cells.slice(0, 2)));
check("提示里写明了符合条件的总条数",
  sheet[1].cells[0].includes(String(total)),
  sheet[1].cells[0]);
check("提示里写明了本次实际导出的条数",
  sheet[1].cells[0].includes("2"), sheet[1].cells[0]);
check("提示行之后紧接着就是数据（表头下方无空行）",
  sheet[2].cells[0].startsWith("TK"), JSON.stringify(sheet[2].cells.slice(0, 2)));
check("数据行数正好等于上限 2（按上限截断）",
  sheet.length - 2 === 2, "数据行=" + (sheet.length - 2));
check("表头没有被提示行挤掉任何列",
  sheet[0].cells.length === 13 && sheet[2].cells.length === 13);

log("\n================================");
log("通过 " + pass + " / 失败 " + fail);
fs.writeFileSync(path.join(process.cwd(), "e2e", "export-truncate-report.txt"), lines.join("\n"), "utf8");
process.exit(fail === 0 ? 0 : 1);