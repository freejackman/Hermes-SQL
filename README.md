# Hermes-SQL

**SQL Semantic Analysis & Data Security Testing Framework for Multi-Engine (Hive/Spark/Hetu)**  
— 支持方言差异分析、字段级血缘追溯与安全等级推导

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-8%2B-orange)](https://adoptium.net/)
[![Maven](https://img.shields.io/badge/Maven-3.8%2B-red)](https://maven.apache.org/)

---

## 🚀 项目背景

在金融级数据治理场景中，SQL 需要频繁在 **Hive / Impala / Gauss** 与 **Spark / Hetu** 之间迁移。不同引擎在**数据类型、函数行为、隐式列名生成、NULL 语义**等方面的细微差异，常导致生产事故。

`Hermes-SQL` 应运而生——它是一套轻量级、可扩展的 SQL 解析与分析框架，专为 **大数据测试工程师** 和 **数据安全工程师** 设计，用于：

- 自动对比多引擎 SQL 方言差异
- 追溯字段级血缘关系并验证脱敏策略完整性
- 推导敏感字段的安全等级传递

---

## 🧩 模块设计（当前规划）

```
hermes-sql (父POM)
├── hermes-core          # 核心解析引擎（ANTLR4封装、Scope/SelectItem数据模型）
├── hermes-diff          # 方言差异分析器（项目一）
├── hermes-lineage       # 血缘图谱构建（项目二，支持Neo4j导出）
└── hermes-security      # 安全等级推导引擎（项目三）
```

> **当前进度**：核心解析器（`hermes-core`）正在开发中，后续模块将逐步迭代。

---

## 🛠️ 技术栈

- **Java 8+**（运行环境）
- **Maven**（构建管理）
- **ANTLR4**（SQL 语法解析，基于 Hive/Spark 官方 `.g4` 文件）
- **Neo4j**（血缘可视化，可选）
- **JUnit 5**（单元测试）

---

## 📦 快速开始（本地运行）

### 前置条件
- JDK 8 或 11
- Maven 3.8+
- Git

### 克隆与构建
```bash
git clone https://github.com/your-username/Hermes-SQL.git
cd Hermes-SQL
mvn clean install -DskipTests
```

### 运行第一个解析示例（待实现）
```java
// 示例代码（将在核心模块完成后提供）
SqlParser parser = new SqlParser("SELECT UPPER(name), 1+2 FROM user_table");
ParseResult result = parser.parse();
System.out.println(result.getSelectItems());
```

---

## 🧪 当前开发状态

- [x] 仓库初始化（Apache License 2.0）
- [x] 基础 Maven 多模块骨架
- [x] ANTLR4 依赖引入
- [ ] 核心 `Scope` 与 `SelectItem` 数据模型实现
- [ ] Hive/Spark `.g4` 文件集成与解析器生成
- [ ] 第一个完整 SQL 解析测试（含 JOIN 和嵌套函数）

> 详细开发路线图参见 [ROADMAP.md](ROADMAP.md)（后续补充）

---

## 📄 许可证

本项目采用 **Apache License 2.0**，详情见 [LICENSE](LICENSE) 文件。  
特别感谢 Apache Hive 和 Apache Spark 社区提供的语法文件参考。

---

## 👤 作者

- **freejackman**  
  测试工程师，技术爱好者。

---

## 🤝 贡献

欢迎提交 Issue 和 Pull Request。如果你有新的引擎适配需求（如 Presto、Doris），请开启讨论。

---
