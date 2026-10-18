package org.example.util;

import org.example.entity.datamodel.*;

import java.util.List;

//工具类，用来打印输出sql解析的结果

//SQL 解析结果展示器：树形文本 + JSON 双格式


public class SqlParseResultPrinter {

    // ============ ANSI 颜色（可选，方便控制台区分） ============
    public static final String RESET = "\u001B[0m";
    public static final String RED = "\u001B[31m";    // 错误/歧义（红色）
    public static final String GREEN = "\u001B[32m";  // 血缘（绿色）
    public static final String YELLOW = "\u001B[33m"; // 警告（黄色）
    public static final String BLUE = "\u001B[34m";   // 表（蓝色）
    public static final String MAGENTA = "\u001B[35m";// 函数(紫红色)
    public static final String CYAN = "\u001B[36m";   // 层级/元信息（青色）

    private boolean useColor = true;

    public SqlParseResultPrinter() {}
    public SqlParseResultPrinter(boolean useColor) { this.useColor = useColor; }

    private String c(String color, String text) {
        return useColor ? color + text + RESET : text;
    }

    // ============ 主入口 ============

    public String print(SqlInfo sqlInfo) {

        //StringBuilder 是一个可变的字符序列类，创建对象后，对象可以继续操作
        StringBuilder sb = new StringBuilder();

        //打印sql解析后的详细信息
        printSqlInfo(sqlInfo, sb, 0);

        return sb.toString();
    }



    //打印解析后的sql语句详细信息
    private void printSqlInfo(SqlInfo sql, StringBuilder sb, int depth) {

        if (sql == null) return;

        String indent = "  ".repeat(depth);   //根据深度，来确定每一行开头的间隔

        // 1. SQL 头
        sb.append("\n").append(indent)
                .append(c(CYAN, "┌─ [SQL L" + sql.sqlLevel + "] "))
                .append(truncate(sql.originalSql, 100)).append("\n");    //拼接原始sql语句文本


        // 2. 表列表
        sb.append(indent).append("│ ").append(c(BLUE, "📦 Tables(" + sql.tables.size() + "):")).append("\n");

        for (TableInfo t : sql.tables) {
            sb.append(indent).append("│   ├─ ").append(formatTable(t)).append("\n");
        }



        // 3. 查询列
        sb.append(indent).append("│ ").append(c(MAGENTA, "📋 SelectItems(" + sql.selectItems.size() + "):")).append("\n");
        for (int i = 0; i < sql.selectItems.size(); i++) {
            printSelectItem(sql.selectItems.get(i), sb, depth + 1, i + 1);
        }

        // 4. 子查询递归
        if (!sql.childSqls.isEmpty()) {
            sb.append(indent).append("│ ").append(c(CYAN, "🔍 SubQueries(" + sql.childSqls.size() + "):")).append("\n");
            for (SqlInfo child : sql.childSqls) {
                printSqlInfo(child, sb, depth + 1);
            }
        }

        sb.append(indent).append(c(CYAN, "└─") ).append("\n");
    }

    private void printSelectItem(SelectItem item, StringBuilder sb, int depth, int idx) {
        String indent = "  ".repeat(depth);

        // 别名 / 展示名
        String alias = item.selectItemAliasList.isEmpty()
                ? "(no alias)"
                : String.join(",", item.selectItemAliasList);
        String display = item.selectItemDisplayNameList.isEmpty()
                ? "(no display)"
                : String.join(",", item.selectItemDisplayNameList);

        sb.append(indent).append("├─ #").append(idx).append(" ")
                .append(c(MAGENTA, truncate(item.normalizedExpression, 60)))
                .append("  [alias=").append(alias).append(", display=").append(display).append("]\n");

        // 表达式元信息
        sb.append(indent).append("│   ├─ exprType=").append(item.exprType)
                .append(", transformType=").append(item.transformType)
                .append(", level=").append(item.selectItemLevel).append("\n");

        // 血缘字段
        if (!item.sourceColumns.isEmpty()) {
            sb.append(indent).append("│   ├─ ").append(c(GREEN, "🩸 SourceColumns(" + item.sourceColumns.size() + "):")).append("\n");
            for (ColumnRef col : item.sourceColumns) {
                sb.append(indent).append("│   │   └─ ").append(formatColumnRef(col)).append("\n");
            }
        }

        // 函数链
        if (!item.functionChain.isEmpty()) {
            sb.append(indent).append("│   ├─ ").append(c(MAGENTA, "🧩 FunctionChain(" + item.functionChain.size() + "):")).append("\n");
            for (FunctionInfo f : item.functionChain) {
                printFunction(f, sb, depth + 2);
            }
        }

        // 查询列子查询
        if (!item.selectItemSubQueries.isEmpty()) {
            sb.append(indent).append("│   └─ ").append(c(CYAN, "🔍 InlineSubQueries(" + item.selectItemSubQueries.size() + ")"))
                    .append(" (see child SQLs below)\n");
        }
    }

    private void printFunction(FunctionInfo f, StringBuilder sb, int depth) {
        String indent = "  ".repeat(depth);
        String prefix = f.isNested ? "└─[nested] " : "├─ ";
        sb.append(indent).append(prefix).append(c(MAGENTA, f.functionName))
                .append("(").append(f.arguments.size()).append(" args)\n");

        for (FunctionArgument arg : f.arguments) {
            sb.append(indent).append("│   └─ ").append(formatArg(arg)).append("\n");
        }
    }


    // ============ 格式化工具 ============

    private String formatTable(TableInfo t) {
        String name = t.dataBaseName.isEmpty()
                ? t.tableName
                : t.dataBaseName + "." + t.tableName;

        String aliasPart = "";

        if (name.isEmpty()) {
            // 没有真实表名 → 用别名当显示名，不加 AS
            name = t.tableNameAlias.isEmpty() ? "(unnamed)" : t.tableNameAlias;
        } else if (!t.tableNameAlias.isEmpty()) {
            // 有真实表名 + 有别名 → 显示 "name AS alias"
            aliasPart = " AS " + t.tableNameAlias;
        }

        String type = t.tableType == null ? "?" : t.tableType.name();
        return String.format("%s%s [%s, L%d]", name, aliasPart, type, t.tableLevel);
    }


    private String formatColumnRef(ColumnRef col) {
        StringBuilder sb = new StringBuilder();
        String tableName = col.originTable == null ? "(unknown)"
                : (col.originTable.tableName.isEmpty() ? col.originTable.tableNameAlias : col.originTable.tableName);
        sb.append(c(GREEN, tableName + "." + col.columnName));
        if (!col.tableAlias.isEmpty()) sb.append(" (from=").append(col.tableAlias).append(")");
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
