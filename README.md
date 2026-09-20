# 企业工单与审批系统 —— 后端

Spring Boot 3 + MyBatis-Plus + MySQL 8 + JWT。纯接口服务，不渲染页面。

## 技术栈与选型理由

| 组件 | 版本 | 为什么选它 |
|---|---|---|
| JDK | 17 | Spring Boot 3 的最低要求；本机另有 JDK 23，但本项目固定用 17，见下文 |
| Spring Boot | 3.x | 3.x 起基线是 Jakarta EE 9+，包名从 `javax.*` 变 `jakarta.*`，不要按 2.x 的教程抄 |
| MyBatis-Plus | 3.5.x | 单表 CRUD 与分页不必手写 SQL；复杂查询仍可回到 XML |
| MySQL | 8.0 | 用了 `INSERT ... ON DUPLICATE KEY UPDATE` 做单号自增，5.7 也支持，但 8.0 的窗口函数与 CTE 以后有用 |
| JWT (jjwt) | 0.12.x | 无状态鉴权，服务端不存 session，多实例部署不用做会话共享 |
| Apache POI | 5.2.5 | 导出 Excel，见下文「导出」一节 |

## 运行

```bash
# 1) 建库建表 + 种子数据
mysql -uroot -p < sql/schema.sql

# 2) 配数据库密码（该文件已被 gitignore，不会进版本库）
#    src/main/resources/application-local.yml
#    spring:
#      datasource:
#        password: 你的密码

# 3) 启动（默认 8081）
mvn spring-boot:run
```

Swagger UI: http://localhost:8081/swagger-ui.html

### 关于 JDK 23

本机装了 JDK 23，但本项目编译与运行都用 JDK 17。原因是 Lombok 与部分注解处理器在
新 JDK 上需要额外开 `--add-opens`，而 JDK 21 之后 JDK 内置的类加载机制变动会让一些
老插件报 "module is not opened"。学习阶段没有必要一边学业务一边跟工具链搏斗，
所以 `pom.xml` 里显式声明了 `<java.version>17</java.version>`。
JDK 23 的存在不影响本项目 —— Maven 认的是 `JAVA_HOME`，不是机器上装了哪些 JDK。

## 已实现的接口

统一响应体 `{code, message, data}`，`code === 200` 才算成功。
注意 `spring.jackson.default-property-inclusion: non_null`，**值为 null 的字段不会出现在 JSON 里**，
前端不能用 `'字段' in obj` 判断字段是否存在。

```
POST   /api/auth/login             登录，返回 token
GET    /api/auth/me                当前登录用户

POST   /api/tickets                创建工单（草稿）→ 返回 Ticket 实体
PUT    /api/tickets/{id}           修改（仅草稿/已驳回，仅创建人）→ 返回 TicketVO
DELETE /api/tickets/{id}           删除（仅从未提交过的草稿）
POST   /api/tickets/{id}/submit    提交，进入审批流
POST   /api/tickets/{id}/approve   {action: APPROVE|REJECT, comment}  需 ADMIN/APPROVER
POST   /api/tickets/{id}/withdraw  撤回
POST   /api/tickets/{id}/cancel    {comment} 作废，comment 可省
GET    /api/tickets                分页查询 → IPage<TicketVO>
GET    /api/tickets/export         导出 Excel → xlsx 文件流
GET    /api/tickets/{id}           详情 → TicketVO
GET    /api/tickets/{id}/history   审批轨迹 → List<ApprovalRecord>

GET    /api/ticket-types           仅启用的类型
GET    /api/ticket-types/all       含停用（ADMIN）
POST/PUT/DELETE /api/ticket-types  增删改（ADMIN；被工单引用时返回 code 1001）
```

枚举取值：

- `TicketStatus`: DRAFT 草稿 / PENDING 审批中 / APPROVED 已通过 / REJECTED 已驳回 / WITHDRAWN 已撤回 / CLOSED 已作废
- `Priority`: LOW 低 / NORMAL 普通 / HIGH 高 / URGENT 紧急
- `ApprovalAction`: SUBMIT / APPROVE / REJECT / WITHDRAW / CANCEL

错误码：200 成功、400 参数错误、401 未登录、403 无权限、404 不存在、405 方法不允许、
415 媒体类型不支持、500 服务端异常、**1001 业务校验失败**。

## 导出 Excel

`GET /api/tickets/export`，筛选参数（`scope/status/typeId/keyword`）与列表接口完全一致，
但**导出的是全量，不是当前页**。

### 为什么放在后端

前端用 SheetJS 只能拿到已经加载进页面的那几十行；要导全量就得循环调分页接口，
而分页接口有 `pageSize` 上限 200 —— 导 5 万行要发 250 个请求，每个请求都是一次完整的
鉴权、查询、序列化，数据还得全留在浏览器内存里。
后端导出从数据库一批批取、直接写进 HTTP 响应流，内存占用与总行数无关。

### 三个关键设计

1. **SXSSF 而非 XSSF**：XSSF 把整张表留在堆内存，行数一多就 OOM。
   SXSSF 只在内存里保留 200 行，更早的行刷到磁盘临时文件。
   代价是已刷走的行不能回头改，所以只能一路追加 —— 这也是截断提示必须在建表时就传入的原因。

2. **`flushTo(out)` 与 `close()` 分开**：参数校验失败、没有权限、数据库断开这些异常都发生在
   `flushTo` 之前，此时响应流还没被碰过，全局异常处理器能把错误正常转成 JSON 返回。
   若在创建 sheet 时就把 `response.getOutputStream()` 传进去，一有异常就只能返回一个半截 zip，
   前端拿到的是一句「请求失败」，真正的原因丢在服务端日志里。
   `close()` 里的 `workbook.dispose()` 不能省 —— 否则每次导出都会在系统临时目录留下垃圾文件。

3. **游标翻页用 `id` 而不是 `(created_at, id)`**：`created_at` 在批量导入的数据里大量重复
   （实测 54 行只对应 17 个不同时间戳）且没有索引，用它做游标会退化成 O(n²) 扫描。
   `id` 是自增主键，唯一且单调，天然适合做翻页游标。

### 导出上限

配置项 `workorder.export.max-rows`（默认 100000）。超过后只导出最新的这么多行，
并在文件首行写一句提示。设上限不是为了保护数据库，而是不让一次误操作生成一个
几百 MB 的文件把用户浏览器卡死。

做成配置项而不是常量的理由：这段截断提示代码只在数据真的超限时才执行，
写死在代码里就等于本机永远验证不到 —— 而没被执行过的代码就是没被验证过的代码。
测试用 `-Dspring-boot.run.arguments=--workorder.export.max-rows=2` 把它真实跑了一遍。

## 测试

```bash
mvn test                        # 单元测试（状态机、审批流解析、生命周期）
node e2e/e2e-smoke.mjs          # 接口端到端冒烟（需服务已启动）
node e2e/e2e-export.mjs         # 导出接口端到端
node e2e/e2e-export-truncate.mjs # 截断分支（需以 max-rows=2 启动服务）
```

`e2e/xlsx-read.py` 用标准库 `zipfile` 读 xlsx 供断言使用，不依赖 openpyxl。

> 注意：e2e 脚本会真的往数据库写数据。跑完记得清理产生的工单与工单类型，
> 否则种子数据和测试数据会混在一起，界面上很难分辨。

## 目录结构

```
src/main/java/com/enterprise/workorder/
├── common/      统一响应体、错误码、业务异常、全局异常处理
├── config/      Spring/MyBatis-Plus/OpenAPI/Security 配置
├── controller/  接口层，只做参数接收与响应包装
├── dto/         请求与响应对象
├── entity/      数据库实体
├── enums/       状态、优先级、角色等枚举
├── excel/       Excel 写出（SXSSF 流式）
├── mapper/      MyBatis-Plus Mapper
├── security/    JWT 生成校验、过滤器、登录用户上下文
└── service/     业务逻辑，审批流与状态机都在这里
```

## 已知遗留

- 冒烟脚本 `e2e/e2e-smoke.mjs` 创建的工单只能靠 SQL 清理：流程走完的单是「已通过」终态，
  界面按审计要求不允许删除。这是设计如此，不是缺陷，但脚本应打印出 id 方便清理。
- `application.yml` 里开了 MyBatis 的 SQL 控制台日志（`log-impl: StdOutImpl`），
  学习阶段便于观察，生产环境必须关掉。