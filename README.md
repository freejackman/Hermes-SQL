# Hermes-SQL

**SQL Semantic Analysis & Data Security Testing Framework for Multi-Engine (Hive/Spark/Hetu)**  
— 支持方言差异分析、字段级血缘追溯与安全等级推导

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-8%2B-orange)](https://adoptium.net/)
[![Maven](https://img.shields.io/badge/Maven-3.8%2B-red)](https://maven.apache.org/)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](https://github.com/freejackman/Hermes-SQL/pulls)

---

## 🚀 项目背景

在金融级数据治理场景中，SQL 需要频繁在 **Hive / Impala / Gauss** 与 **Spark / Hetu** 之间迁移。不同引擎在**数据类型、函数行为、隐式列名生成、NULL 语义**等方面的细微差异，常导致生产事故。

`Hermes-SQL` 应运而生——它是一套轻量级、可扩展的 SQL 解析与分析框架，专为 **大数据测试工程师** 和 **数据安全工程师** 设计，用于：

- **自动对比**多引擎 SQL 方言差异（隐式命名、类型截断等）
- **精准追溯**字段级血缘关系，穿透复杂子查询与 UDTF 虚拟表
- **推导**敏感字段的安全等级传递，验证脱敏策略完整性

---

## ✨ 核心特性 (Features)

- **自研轻量级血缘解析引擎**：基于 ANTLR4，无重型框架（如 Calcite）依赖，高度可定制。
- **双遍历架构 (Dual-pass)**：第一阶段（`ScopeBuilderListener`）专职构建作用域与表映射；第二阶段（`SqlAnalyzerListener`）专职提取字段血缘，彻底解耦 `SELECT` 与 `FROM` 的解析顺序依赖。
- **多栈协同管理 (Multi-stack)**：采用 SQL 嵌套栈、作用域栈、查询列栈、函数栈联动，完美处理深层嵌套。
- **复杂 SQL 场景全覆盖**：支持多表 JOIN、子查询（FROM 级/列级）、CASE WHEN 多源表达式、LATERAL VIEW (UDTF 虚拟列穿透)、分区表函数 (PTF)。
- **高阶子句深度穿透**：不仅解析表层字段，更能精准捕获 `GROUP BY`、`HAVING` 的聚合血缘依赖。
- **窗口函数 (Window Functions) 无损解析**：深入 `OVER()` 子句内部，精准提取 `PARTITION BY` 与 `ORDER BY` 的底层物理字段依赖，解决脱敏策略在 Shuffle 阶段失效的安全盲区。
- **统一条件模型 (FilterCondition)**：完美统一 `JOIN ON` 与 `WHERE` 子句。支持提取 `AND/OR` 嵌套、`IN / BETWEEN / LIKE`，自动标记 `WHERE` 中的隐式 JOIN。
---

## 🧩 模块设计
```
hermes-sql (父POM)
├── hermes-core # 核心解析引擎（双遍历架构、Scope作用域管理、血缘提取）【已完成】
├── hermes-diff # 方言差异分析器（Hive vs Spark 差异对比）【开发中】
├── hermes-lineage # 血缘图谱构建（支持导出为 Neo4j 图数据库）【规划中】
└── hermes-security # 安全等级推导引擎（敏感字段脱敏验证）【规划中】
```

---

## 🛠️ 技术栈

- **Java 11+** (开发环境)
- **ANTLR4** (基于 Hive/Spark 官方 `.g4` 语法文件定制)
- **Maven** (多模块构建)
- **SnakeYAML** (计划中：用于本地元数据模拟)

---

## 📦 快速开始

### 1. 克隆与构建
```bash
git clone https://github.com/freejackman/Hermes-SQL.git
cd Hermes-SQL
mvn clean install -DskipTests
```
### 2.解析你的第一条 SQL
```java
String sql = "SELECT t1.dept_id, ROW_NUMBER() OVER (PARTITION BY t1.dept_id ORDER BY t1.salary DESC) as rn FROM employee t1 WHERE t1.status = 'ACTIVE' GROUP BY t1.dept_id, t1.salary";

// 1. 词法 & 语法解析
HiveLexer lexer = new HiveLexer(CharStreams.fromString(sql));
HiveParser parser = new HiveParser(new CommonTokenStream(lexer));
ParseTree tree = parser.statement();

// 2. 第一阶段：构建作用域 (Double-pass Pass 1)
Map<ParserRuleContext, SqlInfo> sqlInfoMap = new IdentityHashMap<>();
ScopeBuilderListener scopeBuilder = new ScopeBuilderListener(sqlInfoMap);
ParseTreeWalker.DEFAULT.walk(scopeBuilder, tree);

// 3. 第二阶段：字段血缘提取 (Double-pass Pass 2)
SqlAnalyzerListener sqlAnalyzer = new SqlAnalyzerListener(sqlInfoMap);
ParseTreeWalker.DEFAULT.walk(sqlAnalyzer, tree);

// 4. 树形输出展示 (注意 print 返回的是 String，需要输出到控制台)
SqlParseResultPrinter printer = new SqlParseResultPrinter(true);
System.out.println(printer.print(scopeBuilder.firstSqlInfo));
```
### 3.解析效果展示

对于包含 JOIN、WHERE、GROUP BY、HAVING 以及窗口函数 的复杂 SQL，引擎能精准输出各层级依赖如下：
```
┌─ [SQL L1] SELECT t1.dept_id, ROW_NUMBER() OVER (PARTITION BY t1.dept_id...
│ 📦 Tables(1):
│   ├─ employee AS t1 [PHYSICAL, L1]
│ 🔍 WhereConditions(1):
│   ├─ [=] t1.status = 'ACTIVE'
│   │   ├─ Left:  employee.status (from=t1)
│ 📊 GroupBy(2):
│   ├─ employee.dept_id (from=t1)
│   ├─ employee.salary (from=t1)
│ 🔍 HavingConditions(1):
│   ├─ [>] SUM(t1.salary) > 10000
│   │   ├─ Left:  employee.salary (from=t1)
│ 📋 SelectItems(2):
│   ├─ #1 t1.dept_id
│   │   ├─ exprType=DIRECT, transformType=DIRECT
│   │   ├─ 🩸 SourceColumns(1):
│   │   │   └─ employee.dept_id (from=t1)
│   ├─ #2 ROW_NUMBER() OVER...
│   │   ├─ exprType=FUNCTION, transformType=SIMPLE
│   │   ├─ 🩸 SourceColumns(2):
│   │   │   ├─ employee.dept_id (from=t1)
│   │   │   ├─ employee.salary (from=t1)
│   │   ├─ 🧩 FunctionChain(1):
│   │   │   ├─ [窗口函数] ROW_NUMBER(0 args)
│   │   │   │   └─ OVER() 子句:
│   │   │   │       ├─ PARTITION BY: employee.dept_id (from=t1)
│   │   │   │       ├─ ORDER BY: employee.salary (from=t1)
```
## 🗺️ 开发路线图 (Roadmap)
☑ v0.1.0 基础骨架与数据模型设计 (2026-08)

☑ v0.1.0 第一阶段：ScopeBuilderListener 作用域构建器 (2026-09-11)

☑ v0.2.0 第二阶段：SqlAnalyzerListener 字段血缘提取 (2026-09-22)

☑ v0.3.0 复杂场景补全：CASE WHEN、列级子查询合并、UDTF 虚拟列穿透 (2026-10-19)

☑ v0.4.0 条件解析大满贯：JOIN ON 深度解析、WHERE 过滤条件、隐式 JOIN 识别 (2026-10-24)

☑ v0.4.1 可视化展示升级：树形结构高亮区分、SqlParseResultPrinter 完善 (2026-10-26)

☑ v0.5.0 高阶子句解析：GROUP BY、HAVING 与窗口函数（OVER）依赖提取 (2026-10-29)

□ v0.6.0 元数据服务接入（解析无元数据的裸字段）

□ v0.7.0 方言差异分析模块 (hermes-diff) 与 SQL 隐式命名检测

□ v1.0.0 血缘可视化与安全等级推导引擎闭环


## 📄 许可证
本项目采用 Apache License 2.0，详情见 LICENSE 文件。
特别感谢 Apache Hive 和 Apache Spark 社区提供的语法文件参考。

## 👤 作者
freejackman
测试开发工程师 | 技术爱好者,极客梦想家,工程落地派,交付守夜人


## 🤝 贡献
欢迎提交 Issue 和 Pull Request。如果你有新的引擎适配需求（如 Presto、Doris），请开启讨论。

