package org.example.util;

import org.example.entity.datamodel.*;

//工具类，用来打印输出sql解析的结果
//SQL 解析结果展示器：树形文本 + JSON 双格式
public class SqlParseResultPrinter {

    // ============ ANSI 颜色 ============
    public static final String RESET = "\u001B[0m";
    public static final String RED = "\u001B[31m";
    public static final String GREEN = "\u001B[32m";
    public static final String YELLOW = "\u001B[33m";
    public static final String BLUE = "\u001B[34m";
    public static final String MAGENTA = "\u001B[35m";
    public static final String CYAN = "\u001B[36m";
    public static final String PURPLE = "\u001B[35m";
    public static final String WHITE = "\u001B[37m";

    // ★ 新增：亮色系（在 IDEA 深色主题下更清晰）
    public static final String BRIGHT_BLUE = "\u001B[94m";
    public static final String BRIGHT_CYAN = "\u001B[96m";

    private boolean useColor = true;

    public SqlParseResultPrinter() {}
    public SqlParseResultPrinter(boolean useColor) { this.useColor = useColor; }

    private String c(String color, String text) {
        return useColor ? color + text + RESET : text;
    }

    // ============ 主入口 ============
    public String print(SqlInfo sqlInfo) {
        StringBuilder sb = new StringBuilder();
        printSqlInfo(sqlInfo, sb, 0);
        return sb.toString();
    }

    //打印解析后的sql语句详细信息
    private void printSqlInfo(SqlInfo sql, StringBuilder sb, int depth) {
        if (sql == null) return;

        String indent = "  ".repeat(depth);

        // 1. SQL 头
        sb.append("\n").append(indent)
                .append(c(CYAN, "┌─ [SQL L" + sql.sqlLevel + "] "))
                .append(truncate(sql.originalSql, 100)).append("\n");

        // 2. 表列表
        sb.append(indent).append("│ ").append(c(BLUE, "📦 Tables(" + sql.tables.size() + "):")).append("\n");
        for (TableInfo t : sql.tables) {
            sb.append(indent).append("│   ├─ ").append(formatTable(t)).append("\n");
        }


        // 3. JOIN ON 条件
        if (sql.joinConditions != null && !sql.joinConditions.isEmpty()) {
            sb.append(indent).append("│ ").append(c(YELLOW, "🔗 JoinConditions(" + sql.joinConditions.size() + "):")).append("\n");
            for (FilterCondition jc : sql.joinConditions) {
                printFilterCondition(jc, sb, depth + 1,false);
            }
        }


        // 4. WHERE 条件
        if (sql.whereConditions != null && !sql.whereConditions.isEmpty()) {
            sb.append(indent).append("│ ").append(c(WHITE, "🔍 WhereConditions(" + sql.whereConditions.size() + "):")).append("\n");
            for (FilterCondition wc : sql.whereConditions) {
                printFilterCondition(wc, sb, depth + 1,true);
            }
        }


        // 5. 打印 GROUP BY
        if (sql.groupByColumns != null && !sql.groupByColumns.isEmpty()) {
            sb.append(indent).append("│ ").append(c(PURPLE, "📊 GroupBy(" + sql.groupByColumns.size() + "):")).append("\n");
            for (ColumnRef col : sql.groupByColumns) {
                sb.append(indent).append("│   ├─ ").append(formatColumnRef(col)).append("\n");
            }
        }

        // 6. 打印 HAVING
        if (sql.havingConditions != null && !sql.havingConditions.isEmpty()) {
            sb.append(indent).append("│ ").append(c(YELLOW, "🔍 HavingConditions(" + sql.havingConditions.size() + "):")).append("\n");
            for (FilterCondition hc : sql.havingConditions) {
                printFilterCondition(hc, sb, depth + 1, false); // 复用你之前的条件打印方法
            }
        }

        // 5. 查询列
        sb.append(indent).append("│ ").append(c(MAGENTA, "📋 SelectItems(" + sql.selectItems.size() + "):")).append("\n");
        for (int i = 0; i < sql.selectItems.size(); i++) {
            printSelectItem(sql.selectItems.get(i), sb, depth + 1, i + 1);
        }

        // 6. 子查询递归
        if (!sql.childSqls.isEmpty()) {
            sb.append(indent).append("│ ").append(c(CYAN, "🔍 SubQueries(" + sql.childSqls.size() + "):")).append("\n");
            for (SqlInfo child : sql.childSqls) {
                printSqlInfo(child, sb, depth + 1);
            }
        }

        sb.append(indent).append(c(CYAN, "└─")).append("\n");
    }



    private void printFilterCondition(FilterCondition cond, StringBuilder sb, int depth, boolean isWhere) {
        String indent = "  ".repeat(depth);

        // 根据是 WHERE 还是 JOIN 选择主色调
        String mainColor = isWhere ? WHITE : YELLOW;

        // 如果是隐式 JOIN，增加高亮警告
        String implicitTag = "";
        if (cond.isImplicitJoin) {
            implicitTag = c(RED, " [⚠ 隐式JOIN]");
        }

        sb.append(indent).append("├─ ").append(c(mainColor, "[" + cond.operator + "] "))
                .append(truncate(cond.rawConditionText, 60))
                .append(implicitTag)
                .append("\n");

        // 打印左侧字段
        if (cond.leftColumns != null && !cond.leftColumns.isEmpty()) {
            sb.append(indent).append("│   ├─ Left:  ");
            for (int i = 0; i < cond.leftColumns.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(formatColumnRef(cond.leftColumns.get(i)));
            }
            sb.append("\n");
        }

        // 打印右侧字段
        if (cond.rightColumns != null && !cond.rightColumns.isEmpty()) {
            sb.append(indent).append("│   ├─ Right: ");
            for (int i = 0; i < cond.rightColumns.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(formatColumnRef(cond.rightColumns.get(i)));
            }
            sb.append("\n");
        }

        // 打印子查询（如果存在）
        if (cond.leftSubQueries != null && !cond.leftSubQueries.isEmpty()) {
            sb.append(indent).append("│   ├─ Left SubQuery: (see child SQLs below)\n");
        }
        if (cond.rightSubQueries != null && !cond.rightSubQueries.isEmpty()) {
            sb.append(indent).append("│   ├─ Right SubQuery: (see child SQLs below)\n");
        }
    }

    private void printSelectItem(SelectItem item, StringBuilder sb, int depth, int idx) {
        String indent = "  ".repeat(depth);

        String alias = item.selectItemAliasList.isEmpty() ? "(no alias)" : String.join(",", item.selectItemAliasList);
        String display = item.selectItemDisplayNameList.isEmpty() ? "(no display)" : String.join(",", item.selectItemDisplayNameList);

        sb.append(indent).append("├─ #").append(idx).append(" ")
                .append(c(MAGENTA, truncate(item.normalizedExpression, 60)))
                .append("  [alias=").append(alias).append(", display=").append(display).append("]\n");

        sb.append(indent).append("│   ├─ exprType=").append(item.exprType)
                .append(", transformType=").append(item.transformType)
                .append(", level=").append(item.selectItemLevel).append("\n");

        if (!item.sourceColumns.isEmpty()) {
            sb.append(indent).append("│   ├─ ").append(c(GREEN, "🩸 SourceColumns(" + item.sourceColumns.size() + "):")).append("\n");
            for (ColumnRef col : item.sourceColumns) {
                sb.append(indent).append("│   │   └─ ").append(formatColumnRef(col)).append("\n");
            }
        }

        if (!item.functionChain.isEmpty()) {
            sb.append(indent).append("│   ├─ ").append(c(MAGENTA, "🧩 FunctionChain(" + item.functionChain.size() + "):")).append("\n");
            for (FunctionInfo f : item.functionChain) {
                printFunction(f, sb, depth + 2);
            }
        }

        if (!item.selectItemSubQueries.isEmpty()) {
            sb.append(indent).append("│   └─ ").append(c(CYAN, "🔍 InlineSubQueries(" + item.selectItemSubQueries.size() + ")"))
                    .append(" (see child SQLs below)\n");
        }
    }

    private void printFunction(FunctionInfo f, StringBuilder sb, int depth) {
        String indent = "  ".repeat(depth);

        // 如果是窗口函数，加个特殊标识
        String prefix = f.isNested ? "└─[nested] " : "├─ ";
        String funcTag = f.isWindowFunction ? c(PURPLE, "[窗口函数] ") : "";

        sb.append(indent).append(prefix).append(funcTag).append(c(MAGENTA, f.functionName))
                .append("(").append(f.arguments.size()).append(" args)\n");

        // 打印常规参数
        for (FunctionArgument arg : f.arguments) {
            sb.append(indent).append("│   └─ ").append(formatArg(arg)).append("\n");
        }

        // ★ 深度打印窗口函数特有属性
        if (f.isWindowFunction) {
            String winIndent = "  ".repeat(depth + 1);
            sb.append(winIndent).append(c(PURPLE, "└─ OVER() 子句:\n"));
            String detailIndent = "  ".repeat(depth + 2);

            // 1. PARTITION BY
            if (!f.partitionByColumns.isEmpty()) {
                sb.append(detailIndent).append("├─ PARTITION BY: ");
                for (int i = 0; i < f.partitionByColumns.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(formatColumnRef(f.partitionByColumns.get(i)));
                }
                sb.append("\n");
            }

            // 2. ORDER BY
            if (!f.orderByColumns.isEmpty()) {
                sb.append(detailIndent).append("├─ ORDER BY: ");
                for (int i = 0; i < f.orderByColumns.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(formatColumnRef(f.orderByColumns.get(i)));
                }
                sb.append("\n");
            }

            // 3. 窗口边界 (ROWS / RANGE)
            if (f.windowFrame != null && !f.windowFrame.isEmpty()) {
                sb.append(detailIndent).append("├─ FRAME: ").append(c(YELLOW, f.windowFrame)).append("\n");
            }

            // 4. NULL 处理 (IGNORE NULLS / RESPECT NULLS)
            if (f.nullTreatment != null && !f.nullTreatment.isEmpty()) {
                sb.append(detailIndent).append("├─ NULLS: ").append(c(YELLOW, f.nullTreatment)).append("\n");
            }
        }
    }


    private String formatTable(TableInfo t) {
        // ★ 安全判空：防止 t.dataBaseName 或 t.tableName 为 null 导致 NPE
        String dbName = t.dataBaseName == null ? "" : t.dataBaseName;
        String tblName = t.tableName == null ? "" : t.tableName;

        String name = dbName.isEmpty() ? tblName : dbName + "." + tblName;
        String aliasPart = "";

        if (name.isEmpty()) {
            name = (t.tableNameAlias == null || t.tableNameAlias.isEmpty()) ? "(unnamed)" : t.tableNameAlias;
        } else if (t.tableNameAlias != null && !t.tableNameAlias.isEmpty()) {
            aliasPart = " AS " + t.tableNameAlias;
        }

        String type = t.tableType == null ? "?" : t.tableType.name();
        return String.format("%s%s [%s, L%d]", name, aliasPart, type, t.tableLevel);
    }

    private String formatColumnRef(ColumnRef col) {
        StringBuilder sb = new StringBuilder();
        String tableName = "(unknown)";
        if (col.originTable != null) {
            String tName = col.originTable.tableName == null ? "" : col.originTable.tableName;
            String aName = col.originTable.tableNameAlias == null ? "" : col.originTable.tableNameAlias;
            tableName = tName.isEmpty() ? aName : tName;
        }

        sb.append(c(GREEN, tableName + "." + col.columnName));
        if (col.tableAlias != null && !col.tableAlias.isEmpty()) {
            sb.append(" (from=").append(col.tableAlias).append(")");
        }
        if (col.isWildcard) sb.append(c(YELLOW, " [*]"));
        if (col.isAmbiguous) sb.append(c(RED, " [⚠ ambiguous]"));
        return sb.toString();
    }

    private String formatArg(FunctionArgument arg) {
        String type = arg.type == null ? "?" : arg.type.name();
        String text = arg.rawText.isEmpty() ? "" : " raw='" + truncate(arg.rawText, 40) + "'";
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(type).append("]");
        if (arg.columnRef != null) sb.append(" → ").append(formatColumnRef(arg.columnRef));
        if (arg.nestedFunction != null) sb.append(" → fn:").append(arg.nestedFunction.functionName);
        if (arg.subQueries != null) sb.append(" → subquery(L").append(arg.subQueries.sqlLevel).append(")");
        if (arg.constantValue != null) sb.append(" → const:").append(arg.constantValue);
        sb.append(text);
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}