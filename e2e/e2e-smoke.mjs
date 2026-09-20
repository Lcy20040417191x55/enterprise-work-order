// =====================================================================
//  企业工单与审批系统 —— 端到端联调脚本
//  用法: node e2e\e2e-smoke.mjs        (服务需已启动在 8081)
//
//  错误返回规则（断言时按此分类）：
//   1) 安全层错误（未登录 / 角色不符）—— 在过滤器链内被拦截
//        -> 真实 HTTP 状态码 401 / 403
//   2) 业务层错误且业务码可映射到 HTTP 语义（400/401/403/404/500）—— GlobalExceptionHandler
//        -> HTTP 写回真实状态码，同时响应体 code 与之相同（两处一致，前端只需看 HTTP）
//   3) 纯业务错误 1001（状态机不允许等）—— 没有对应 HTTP 语义
//        -> HTTP 200 + body.code=1001（"请求被正常处理，但业务规则不允许"）
//  无论哪种，响应体始终是 Result 结构 { code, message, data }。
// =====================================================================
const BASE = process.env.BASE || "http://127.0.0.1:8081";
const PWD = "123456";
let pass = 0, fail = 0;
const failures = [];
const L = (...a) => console.log(a.join(" "));

async function call(method, path, { token, body } = {}) {
  const headers = { "Content-Type": "application/json; charset=utf-8" };
  if (token) headers.Authorization = "Bearer " + token;
  const res = await fetch(BASE + path, {
    method, headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { }
  return { http: res.status, code: json && json.code, msg: json && json.message, data: json && json.data, text };
}

function ok(cond, label) {
  if (cond) { pass++; L("  PASS  " + label); }
  else { fail++; failures.push(label); L("  FAIL  " + label); }
}
/** 断言标准错误码：HTTP 状态码与 body.code 必须同时等于期望值 */
const apiCode = (r, expect) => r.http === expect && r.code === expect;
/** 断言纯业务码 1001：HTTP 仍为 200，只有 body.code 表达错误 */
const bizCode = (r, expect) => r.http === 200 && r.code === expect;
/** 断言安全层错误：真实 HTTP 状态码 */
const httpCode = (r, expect) => r.http === expect;
const isOk = (r) => r.http === 200 && r.code === 200;

async function login(u) {
  const r = await call("POST", "/api/auth/login", { body: { username: u, password: PWD } });
  if (!isOk(r)) throw new Error("登录失败 " + u + " -> " + r.text);
  return r.data.token;
}

const createdTicketIds = [];
// ---------------- 1 ----------------
L("========== 1. 健康检查与安全层拦截（真实 HTTP 状态码）==========");
{
  const h = await call("GET", "/api/health");
  ok(isOk(h) && h.data.status === "UP", "GET /api/health -> 200 UP");
  const a = await call("GET", "/api/tickets");
  ok(httpCode(a, 401), "无令牌 GET /api/tickets -> HTTP 401（未认证拦截）");
  const b = await call("GET", "/api/tickets", { token: "eyJhbGciOiJIUzI1NiJ9.fake.sig" });
  ok(httpCode(b, 401), "伪造令牌 -> HTTP 401");
}

// ---------------- 2 ----------------
L("");
L("========== 2. 登录与身份回读（中文编码）==========");
const T = {};
for (const u of ["admin", "zhangsan", "lisi", "wangwu"]) T[u] = await login(u);
ok(Object.keys(T).length === 4, "四个账号登录成功");
const expect = { admin: ["系统管理员", "ADMIN"], zhangsan: ["张三", "EMPLOYEE"], lisi: ["李四", "APPROVER"], wangwu: ["王五", "APPROVER"] };
for (const u of Object.keys(T)) {
  const r = await call("GET", "/api/auth/me", { token: T[u] });
  const d = r.data;
  ok(isOk(r) && d.username === u && d.realName === expect[u][0] && d.roleCode === expect[u][1],
    "me(" + u + ") -> " + d.realName + " / " + d.roleCode);
}
{
  const r = await call("GET", "/api/ticket-types", { token: T.zhangsan });
  // 断言只看"四个内置类型是否齐全且启用"，不锁死总条数。
  // 原先写的是 r.data.length === 4，它假设库里只有内置类型——
  // 但其他用例（本文件第 12 节）会新建类型，一旦它没清干净，
  // 这条断言就变成与被测行为无关的假失败。断言应该只关心它真正要验证的东西。
  const codes = isOk(r) ? r.data.map(t => t.code) : [];
  const builtin = ["LEAVE", "EXPENSE", "IT_REPAIR", "PURCHASE"];
  const missing = builtin.filter(c => !codes.includes(c));
  ok(builtin.every(c => codes.includes(c)), "内置工单类型齐全 -> " + (missing.length ? "缺 " + missing.join(",") : builtin.join(", ")));
}
// ---------------- 3 ----------------
L("");
L("========== 3. 工单全流程（请假 = 两级审批 DEPT_LEADER -> ADMIN）==========");
const c = await call("POST", "/api/tickets", { token: T.zhangsan, body: { title: "端到端-请假申请", content: "联调：年假 3 天", typeId: 1, priority: "NORMAL" } });
const tk = c.data;
createdTicketIds.push(tk.id);
ok(isOk(c) && tk.status === "DRAFT" && tk.totalStep === 0, "创建草稿 -> " + tk.ticketNo + " status=DRAFT totalStep=0");
{
  const r = await call("POST", "/api/tickets/" + tk.id + "/approve", { token: T.lisi, body: { action: "APPROVE", comment: "草稿也想批" } });
  ok(bizCode(r, 1001), "草稿状态审批被拒 -> code=1001 \"" + r.msg + "\"");
}
{
  const r = await call("POST", "/api/tickets/" + tk.id + "/submit", { token: T.zhangsan });
  ok(isOk(r), "提交 -> " + r.msg);
}
{
  const d = await call("GET", "/api/tickets/" + tk.id, { token: T.zhangsan });
  const t = d.data;
  ok(t.status === "PENDING" && t.currentStep === 1 && t.totalStep === 2 && Number(t.currentApproverId) === 3,
    "提交后 -> PENDING 第" + t.currentStep + "/" + t.totalStep + "步 审批人=" + t.currentApproverName);
}

// ---------------- 4 ----------------
L("");
L("========== 4. 可见性权限（回归 bug#1 / bug#2）==========");
{
  const w = await call("GET", "/api/tickets/" + tk.id, { token: T.wangwu });
  ok(apiCode(w, 403), "王五（无关人员）看详情 -> HTTP 403 / code=403 \"" + w.msg + "\"");
  const wh = await call("GET", "/api/tickets/" + tk.id + "/history", { token: T.wangwu });
  ok(apiCode(wh, 403), "[bug#2] 王五（无关人员）看轨迹 -> HTTP 403 / code=403 \"" + wh.msg + "\"");

  const l = await call("GET", "/api/tickets/" + tk.id, { token: T.lisi });
  ok(isOk(l), "[bug#1] 李四（当前审批人）看详情 -> 200 可正常打开");
  const lh = await call("GET", "/api/tickets/" + tk.id + "/history", { token: T.lisi });
  ok(isOk(lh), "[bug#2] 李四 看轨迹 -> 200（已校验权限且放行审批人）");
}

// ---------------- 5 ----------------
L("");
L("========== 5. 角色授权与业务鉴权（回归 bug#4）==========");
{
  const r = await call("POST", "/api/tickets/" + tk.id + "/approve", { token: T.zhangsan, body: { action: "APPROVE", comment: "自己批自己" } });
  ok(httpCode(r, 403), "[bug#4] 张三（EMPLOYEE）审批 -> HTTP 403 \"" + r.msg + "\"（安全层拦下）");
  const r2 = await call("POST", "/api/tickets/" + tk.id + "/approve", { token: T.wangwu, body: { action: "APPROVE", comment: "不是我的单" } });
  ok(apiCode(r2, 403), "王五（APPROVER 非本单待办人）审批 -> HTTP 403 / code=403 \"" + r2.msg + "\"（业务层拦下）");
}
// ---------------- 6 ----------------
L("");
L("========== 6. 两级流转与办结（回归 bug#3）==========");
{
  const a1 = await call("POST", "/api/tickets/" + tk.id + "/approve", { token: T.lisi, body: { action: "APPROVE", comment: "同意，部门已核" } });
  ok(isOk(a1), "李四（第1级）通过");
  const d = await call("GET", "/api/tickets/" + tk.id, { token: T.lisi });
  const t = d.data;
  ok(t.status === "PENDING" && t.currentStep === 2 && Number(t.currentApproverId) === 1,
    "流转到第2级 -> step=" + t.currentStep + "/" + t.totalStep + " 审批人=" + t.currentApproverName);

  const a2 = await call("POST", "/api/tickets/" + tk.id + "/approve", { token: T.admin, body: { action: "APPROVE", comment: "批准" } });
  ok(isOk(a2), "管理员（第2级）通过");
  const d3 = await call("GET", "/api/tickets/" + tk.id, { token: T.admin });
  const t3 = d3.data;
  ok(t3.status === "APPROVED" && t3.currentApproverId == null && t3.finishedAt != null,
    "[bug#3] 办结后 -> APPROVED 且 approverId 已清空（字段在 JSON 中被省略 = null）");
}
{
  const h = await call("GET", "/api/tickets/" + tk.id + "/history", { token: T.zhangsan });
  const acts = h.data.map(x => x.action).join(",");
  ok(isOk(h) && acts === "SUBMIT,APPROVE,APPROVE", "审批轨迹 -> " + acts);
  L("        明细: " + h.data.map(x => "第" + x.step + "步/" + x.action + "/审批人" + x.approverId + "/\"" + (x.comment ?? "") + "\"").join("  |  "));
}

// ---------------- 7 ----------------
L("");
L("========== 7. 列表范围过滤（scope）==========");
{
  const mine = await call("GET", "/api/tickets?scope=mine&pageSize=100", { token: T.zhangsan });
  ok(mine.data.records.some(r => r.id === tk.id), "scope=mine（张三）-> 含本单");
  const mine2 = await call("GET", "/api/tickets?scope=mine&pageSize=100", { token: T.wangwu });
  ok(!mine2.data.records.some(r => r.id === tk.id), "scope=mine（王五）-> 不含他人单据");
  const todo = await call("GET", "/api/tickets?scope=todo&pageSize=100", { token: T.admin });
  ok(!todo.data.records.some(r => r.id === tk.id), "scope=todo（管理员）-> 已办结不再出现");
  const done = await call("GET", "/api/tickets?scope=done&pageSize=100", { token: T.lisi });
  ok(done.data.records.some(r => r.id === tk.id), "scope=done（李四）-> 含已审批的单");
  const all = await call("GET", "/api/tickets?scope=all&pageSize=200", { token: T.admin });
  ok(all.data.records.some(r => r.id === tk.id), "scope=all（管理员）-> 含全部单");
  const allEmp = await call("GET", "/api/tickets?scope=all&pageSize=200", { token: T.wangwu });
  ok(!allEmp.data.records.some(r => r.id === tk.id), "scope=all（王五，非管理员）-> 看不到无关单据");
  const noScope = await call("GET", "/api/tickets?pageSize=100", { token: T.wangwu });
  ok(!noScope.data.records.some(r => r.id === tk.id), "scope 缺省（王五）-> 等价 mine");
}
// ---------------- 8 ----------------
L("");
L("========== 8. 撤回 / 重新提交 / 单级审批 ==========");
{
  const c2 = await call("POST", "/api/tickets", { token: T.zhangsan, body: { title: "端到端-IT报修", content: "打印机卡纸", typeId: 3, priority: "HIGH" } });
  const tk2 = c2.data;
  createdTicketIds.push(tk2.id);
  ok(isOk(c2) && tk2.priority === "HIGH", "创建 IT报修（单级审批）-> " + tk2.ticketNo);
  await call("POST", "/api/tickets/" + tk2.id + "/submit", { token: T.zhangsan });
  {
    const d = await call("GET", "/api/tickets/" + tk2.id, { token: T.zhangsan });
    ok(d.data.totalStep === 1 && Number(d.data.currentApproverId) === 3, "IT报修 -> totalStep=1 审批人=" + d.data.currentApproverName);
  }
  const byOther = await call("POST", "/api/tickets/" + tk2.id + "/withdraw", { token: T.lisi });
  ok(apiCode(byOther, 403), "非创建人撤回被拒 -> HTTP 403 / code=403 \"" + byOther.msg + "\"");
  const wd = await call("POST", "/api/tickets/" + tk2.id + "/withdraw", { token: T.zhangsan });
  ok(isOk(wd), "创建人撤回 -> " + wd.msg);
  {
    const d = await call("GET", "/api/tickets/" + tk2.id, { token: T.zhangsan });
    ok(d.data.status === "WITHDRAWN" && d.data.currentApproverId == null, "撤回后 -> WITHDRAWN 且 approverId 清空");
  }
  // 已撤回必须能重新提交：撤回的本意就是"我要改改再交"。
  // 早期版本把 WITHDRAWN 当终态，结果是申请人既改不了也交不了，
  // 单据永远卡住，只能另建新单，历史记录白白断掉。
  {
    const d = await call("GET", "/api/tickets/" + tk2.id, { token: T.zhangsan });
    ok(d.data.editable === true && d.data.submittable === true && d.data.deletable === false,
      "已撤回 -> editable/submittable=true, deletable=false（可改可交，但不可删）");
  }
  const re = await call("POST", "/api/tickets/" + tk2.id + "/submit", { token: T.zhangsan });
  ok(isOk(re), "已撤回可重新提交 -> " + re.msg);
  {
    const d = await call("GET", "/api/tickets/" + tk2.id, { token: T.zhangsan });
    ok(d.data.status === "PENDING" && d.data.currentStep === 1 && d.data.totalStep === 1
      && Number(d.data.currentApproverId) === 3 && d.data.finishedAt == null,
      "重新提交后 -> PENDING 1/1 待办人=" + d.data.currentApproverName + " 且 finishedAt 清空");
  }

  // 另建一单验证单级审批直接办结
  const c3 = await call("POST", "/api/tickets", { token: T.zhangsan, body: { title: "端到端-IT报修2", content: "键盘失灵", typeId: 3, priority: "NORMAL" } });
  const tk3 = c3.data;
  createdTicketIds.push(tk3.id);
  await call("POST", "/api/tickets/" + tk3.id + "/submit", { token: T.zhangsan });
  const a = await call("POST", "/api/tickets/" + tk3.id + "/approve", { token: T.lisi, body: { action: "APPROVE", comment: "已更换" } });
  ok(isOk(a), "单级审批通过");
  {
    const d = await call("GET", "/api/tickets/" + tk3.id, { token: T.zhangsan });
    ok(d.data.status === "APPROVED" && d.data.currentApproverId == null && d.data.finishedAt != null,
      "单级审批后直接办结 -> APPROVED 且 approverId 清空");
    const h = await call("GET", "/api/tickets/" + tk3.id + "/history", { token: T.zhangsan });
    ok(h.data.map(x => x.action).join(",") === "SUBMIT,APPROVE", "轨迹 -> " + h.data.map(x => x.step + ":" + x.action).join(" -> "));
  }
}

// ---------------- 9 ----------------
L("");
L("========== 9. 驳回与重新提交 ==========");
{
  const c4 = await call("POST", "/api/tickets", { token: T.zhangsan, body: { title: "端到端-驳回用例", content: "费用报销", typeId: 2, priority: "LOW" } });
  const tk4 = c4.data;
  createdTicketIds.push(tk4.id);
  await call("POST", "/api/tickets/" + tk4.id + "/submit", { token: T.zhangsan });
  const rj = await call("POST", "/api/tickets/" + tk4.id + "/approve", { token: T.lisi, body: { action: "REJECT", comment: "发票不全" } });
  ok(isOk(rj), "李四驳回 -> " + rj.msg);
  {
    const d = await call("GET", "/api/tickets/" + tk4.id, { token: T.zhangsan });
    const t = d.data;
    ok(t.status === "REJECTED" && t.currentApproverId == null && t.finishedAt != null, "驳回后 -> REJECTED 且 approverId 清空");
  }
  const todo = await call("GET", "/api/tickets?scope=todo&pageSize=100", { token: T.lisi });
  ok(!todo.data.records.some(r => r.id === tk4.id), "驳回后不再出现在李四待办");

  const re = await call("POST", "/api/tickets/" + tk4.id + "/submit", { token: T.zhangsan });
  ok(isOk(re), "驳回后可重新提交 -> " + re.msg);
  {
    const d = await call("GET", "/api/tickets/" + tk4.id, { token: T.zhangsan });
    ok(d.data.status === "PENDING" && d.data.currentStep === 1 && d.data.totalStep === 2 && d.data.finishedAt == null,
      "重新提交后 -> PENDING 级次重置为 " + d.data.currentStep + "/" + d.data.totalStep + " 且 finishedAt 清空");
  }
}

// ---------------- 10 ----------------
L("");
L("========== 10. 参数校验与边界 ==========");
{
  const e1 = await call("POST", "/api/tickets", { token: T.zhangsan, body: { title: "", content: "x", typeId: 1, priority: "NORMAL" } });
  ok(apiCode(e1, 400), "标题为空 -> HTTP 400 / code=400 \"" + e1.msg + "\"（@Valid 校验）");
  const e2 = await call("POST", "/api/tickets", { token: T.zhangsan, body: { title: "够长的标题", content: "x", typeId: 1, priority: "WRONG" } });
  ok(bizCode(e2, 1001), "非法优先级 -> code=1001 \"" + e2.msg + "\"");
  const e3 = await call("POST", "/api/tickets", { token: T.zhangsan, body: { title: "够长的标题", content: "x", typeId: 9999, priority: "NORMAL" } });
  ok(e3.code !== 200, "不存在的类型 -> code=" + e3.code + " \"" + e3.msg + "\"");
  const e4 = await call("GET", "/api/tickets/99999999", { token: T.zhangsan });
  ok(apiCode(e4, 404), "不存在的工单 -> HTTP 404 / code=404 \"" + e4.msg + "\"");
  // 注意：必须用仍在 PENDING 的单据，否则会先被"状态不允许审批"拦下，
  // 测不到动作解析这一段（第一版脚本就在这里写错了断言对象）。
  const c5 = await call("POST", "/api/tickets", { token: T.zhangsan, body: { title: "端到端-动作校验", content: "x", typeId: 3, priority: "NORMAL" } });
  const tk5 = c5.data;
  createdTicketIds.push(tk5.id);
  await call("POST", "/api/tickets/" + tk5.id + "/submit", { token: T.zhangsan });
  const e5 = await call("POST", "/api/tickets/" + tk5.id + "/approve", { token: T.lisi, body: { action: "WRONG", comment: "x" } });
  ok(e5.code !== 200 && e5.msg.includes("不支持的审批动作"), "非法审批动作（PENDING 单）-> code=" + e5.code + " \"" + e5.msg + "\"");

  const e6 = await call("POST", "/api/tickets/" + tk5.id + "/approve", { token: T.lisi, body: { action: "SUBMIT", comment: "x" } });
  ok(e6.code !== 200 && e6.msg.includes("APPROVE 或 REJECT"), "校验动作白名单（SUBMIT 不可作为审批动作）-> \"" + e6.msg + "\"");
}

// ---------------- 11 ----------------
L("");
L("========== 11. 审批轨迹顺序（回归：驳回后重新提交）==========");
{
  const c = await call("POST", "/api/tickets", { token: T.zhangsan, body: { title: "端到端-轨迹顺序", content: "x", typeId: 2, priority: "NORMAL" } });
  const tk = c.data;
  createdTicketIds.push(tk.id);
  await call("POST", "/api/tickets/" + tk.id + "/submit", { token: T.zhangsan });
  await call("POST", "/api/tickets/" + tk.id + "/approve", { token: T.lisi, body: { action: "REJECT", comment: "发票不全" } });
  await call("POST", "/api/tickets/" + tk.id + "/submit", { token: T.zhangsan });

  const h = await call("GET", "/api/tickets/" + tk.id + "/history", { token: T.zhangsan });
  const seq = h.data.map(x => x.action).join(",");
  // 真实发生顺序是 提交 -> 驳回 -> 重新提交。若按 step 排序，两条 step=0 会被排到一起，
  // 显示成 提交/提交/驳回 —— 与实际顺序不符，复盘时会误判。
  ok(seq === "SUBMIT,REJECT,SUBMIT", "轨迹顺序 -> " + h.data.map(x => x.step + ":" + x.action).join(" -> "));
  ok(h.data.map(x => x.id).every((v, i, a) => i === 0 || a[i - 1] < v), "轨迹主键严格递增（顺序的底层保证）");
}

// ---------------- 12 ----------------
L("");
L("========== 12. 工单类型配置（ADMIN 专属）==========");
{
  // 用小写编码验证"服务端归一化为大写"：DTO 的 @Pattern 只校验形状，
  // 允许小写通过，转大写的职责在 Service —— 两处若都管，小写就会被 400 拒掉。
  const code = "e2e_tmp_" + Date.now().toString().slice(-6);
  const save = {
    code: code.toLowerCase(),          // 故意传小写，验证服务端归一化为大写
    name: "端到端-临时类型",
    description: "由 e2e 脚本创建",
    approvalFlow: " dept_leader , admin ",
    sort: 88,
    enabled: 1,
  };

  // 非管理员不得增删改：授权写在 SecurityConfig 里，进入业务代码之前就被拦下
  const denyCreate = await call("POST", "/api/ticket-types", { token: T.lisi, body: save });
  ok(httpCode(denyCreate, 403), "APPROVER 新建类型 -> HTTP 403（配置层授权）");
  const denyAll = await call("GET", "/api/ticket-types/all", { token: T.zhangsan });
  ok(httpCode(denyAll, 403), "EMPLOYEE 查含停用列表 -> HTTP 403");

  const created = await call("POST", "/api/ticket-types", { token: T.admin, body: save });
  ok(isOk(created), "ADMIN 新建类型 -> " + created.msg);
  const typeId = created.data.id;
  ok(created.data.code === code.toUpperCase(), "编码归一化 -> " + created.data.code);
  ok(created.data.approvalFlow === "DEPT_LEADER,ADMIN", "审批链归一化 -> " + created.data.approvalFlow);

  const dup = await call("POST", "/api/ticket-types", { token: T.admin, body: save });
  ok(bizCode(dup, 1001) && dup.msg.includes("已存在"), "编码重复 -> code=1001 \"" + dup.msg + "\"");

  const badRole = await call("POST", "/api/ticket-types", { token: T.admin, body: { ...save, code: code + "_X", approvalFlow: "DEPT_LEADER,BOSS" } });
  ok(bizCode(badRole, 1001) && badRole.msg.includes("第 2 级"), "非法角色 -> code=1001 \"" + badRole.msg + "\"");

  const badCode = await call("POST", "/api/ticket-types", { token: T.admin, body: { ...save, code: "1bad code" } });
  ok(apiCode(badCode, 400), "非法编码格式 -> HTTP 400（@Pattern）");

  const renamed = await call("PUT", "/api/ticket-types/" + typeId, { token: T.admin, body: { ...save, name: "端到端-改名后" } });
  ok(isOk(renamed) && renamed.data.name === "端到端-改名后", "只改名称不改编码 -> 不报重复");

  // 已被工单引用的类型不能删（类型 1 = LEAVE 有工单在用）
  const delUsed = await call("DELETE", "/api/ticket-types/1", { token: T.admin });
  ok(bizCode(delUsed, 1001) && delUsed.msg.includes("停用"), "删除被引用的类型 -> code=1001 \"" + delUsed.msg + "\"");

  const del = await call("DELETE", "/api/ticket-types/" + typeId, { token: T.admin });
  ok(isOk(del), "删除未被引用的类型 -> " + del.msg);
  const delGone = await call("PUT", "/api/ticket-types/" + typeId, { token: T.admin, body: save });
  ok(apiCode(delGone, 404), "再改已删除的类型 -> HTTP 404 / code=404");
}

// ---------------- 13 ----------------
L("");
L("========== 13. 列表动作标记与 N+1 修复 ==========");
{
  const c = await call("POST", "/api/tickets", { token: T.zhangsan, body: { title: "端到端-动作标记", content: "x", typeId: 1, priority: "NORMAL" } });
  const tk = c.data;
  createdTicketIds.push(tk.id);

  const row = (await call("GET", "/api/tickets?scope=mine&pageSize=200", { token: T.zhangsan }))
    .data.records.find(r => r.id === tk.id);
  ok(!!row, "列表能查到刚建的草稿");
  ok(row.editable === true && row.deletable === true && row.submittable === true
    && row.cancelable === true && row.approvable === false && row.withdrawable === false,
    "草稿行动作标记 -> 可改可删可交可废，不可审不可撤");
  ok(row.typeName === "请假申请" && row.departmentName === "技术部" && row.creatorName === "张三",
    "列表关联名称已填充 -> " + [row.typeName, row.departmentName, row.creatorName].join(" / "));
  ok(row.statusLabel === "草稿" && row.priorityLabel === "普通",
    "枚举展示名 -> " + row.statusLabel + " / " + row.priorityLabel);

  await call("POST", "/api/tickets/" + tk.id + "/submit", { token: T.zhangsan });
  const row2 = (await call("GET", "/api/tickets?scope=mine&pageSize=200", { token: T.zhangsan }))
    .data.records.find(r => r.id === tk.id);
  ok(row2.approvable === true && row2.withdrawable === true && row2.editable === false,
    "审批中行动作标记 -> 可审可撤，不可改");
  ok(row2.currentApprover === false, "currentApprover 按登录人判定（当前登录人是张三，不是待办人）");
  const row3 = (await call("GET", "/api/tickets?scope=todo&pageSize=200", { token: T.lisi }))
    .data.records.find(r => r.id === tk.id);
  ok(!!row3 && row3.currentApprover === true, "待办人视角 -> currentApprover=true");
}

// ---------------- 14 ----------------
L("");
L("========== 14. 并发创建工单（回归：单号序列）==========");
{
  // 回归的缺陷：单号原本是 SELECT MAX(ticket_no)+1 算出来的。
  // 并发下多个请求读到同一份快照、算出同一个序号，
  // 第二个插入撞 uk_ticket_no，接口直接 500。当时实测 20 并发只有 3 个成功。
  // 现在改为 ticket_no_seq 表原子自增，应当 20/20 全成功且单号无重复。
  const N = 20;
  const rs = await Promise.all(Array.from({ length: N }, (_, i) =>
    call("POST", "/api/tickets", { token: T.zhangsan, body: { title: "并发回归-" + i, content: "x", typeId: 1, priority: "NORMAL" } })));
  const good = rs.filter(isOk);
  const bad = rs.filter(r => !isOk(r));
  good.forEach(r => createdTicketIds.push(r.data.id));
  ok(good.length === N, "并发 " + N + " 个创建全部成功（实际 " + good.length + "）");
  if (bad.length) L("      失败样本 -> HTTP " + bad[0].http + " code=" + bad[0].code + " msg=" + bad[0].msg);
  const nos = good.map(r => r.data.ticketNo);
  ok(new Set(nos).size === nos.length, "并发产生的单号无重复（" + new Set(nos).size + " 个不同）");
  // 单号形如 TK20260920-0041：同日内序号应连续、无空缺
  const seqs = nos.map(n => Number(n.split("-")[1])).sort((a, b) => a - b);
  const contiguous = seqs.every((v, i) => i === 0 || v === seqs[i - 1] + 1);
  ok(contiguous, "当日序号连续无空缺 -> " + seqs[0] + " ~ " + seqs[seqs.length - 1]);
}

// ---------------- 15 ----------------
L("");
L("========== 15. 编码删除后可复用（回归：唯一索引）==========");
{
  // 回归的缺陷：ticket_type 原本是 UNIQUE KEY uk_code(code)，
  // 它约束的是"历史上出现过的所有编码"；被逻辑删除的行仍申请着编码。
  // 于是管理员重建同名编码会 500。
  // 现改为函数索引 uk_code_active，只约束未删除的行（deleted=1 时取 NULL，
  // MySQL 的唯一索引不约束 NULL）。
  const code = "E2E_REUSE_" + Math.floor(Math.random() * 900000 + 100000);
  const save = { code, name: "回归-编码复用", description: "e2e", approvalFlow: "DEPT_LEADER", sort: 98, enabled: 1 };
  const c1 = await call("POST", "/api/ticket-types", { token: T.admin, body: save });
  ok(isOk(c1), "1) 新建类型 -> " + (isOk(c1) ? "id=" + c1.data.id : "HTTP " + c1.http + " " + c1.msg));
  const id1 = c1.data && c1.data.id;

  const d = await call("DELETE", "/api/ticket-types/" + id1, { token: T.admin });
  ok(isOk(d), "2) 逻辑删除 -> " + d.msg);

  const c2 = await call("POST", "/api/ticket-types", { token: T.admin, body: save });
  ok(isOk(c2), "3) 用同一编码重建（修复前这里 500）-> " + (isOk(c2) ? "可复用" : "HTTP " + c2.http + " " + c2.msg));
  // 收尾：把重建的那个也删掉，避免污染后续运行与第 2 节断言
  if (c2.data && c2.data.id) await call("DELETE", "/api/ticket-types/" + c2.data.id, { token: T.admin });
}

L("=============================================");
L("结果: " + pass + " 通过 / " + fail + " 失败 / 共 " + (pass + fail) + " 项");
if (fail) { L("失败项:"); failures.forEach(f => L("  - " + f)); }
L("本次创建的工单 id: " + createdTicketIds.join(", "));
L("=============================================");
process.exit(fail === 0 ? 0 : 1);