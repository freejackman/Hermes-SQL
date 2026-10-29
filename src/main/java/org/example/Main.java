package org.example;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.example.entity.datamodel.SqlInfo;
import org.example.hive.HiveLexer;   // 导入 Hive
import org.example.hive.HiveParser;
import org.example.spark.SparkSql;
import org.example.util.SqlParseResultPrinter;
import org.example.hive.*;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;
import org.example.spark.SqlBaseLexer;
import org.example.spark.SqlBaseParser;


import java.util.HashMap;
import java.util.Map;

public class Main {
    public static void main(String[] args) {

        String sql= """
                SELECT\s
                    t1.dept_id,\s
                    t1.emp_name,
                    ROW_NUMBER() OVER (PARTITION BY t1.dept_id ORDER BY t1.salary DESC) as rn
                FROM employee t1
                WHERE t1.status = 'ACTIVE'
                GROUP BY t1.dept_id, t1.emp_name, t1.salary
                HAVING SUM(t1.salary) > 10000;
                """ ;

        SqlParseResultPrinter printer = new SqlParseResultPrinter(true);

        //用 Hive 引擎解析并打印
        System.out.println("================ [Hive 引擎解析] ================");
        SqlParser hiveEngine = new HiveSql();
        SqlInfo hiveResult = hiveEngine.parse(sql);
        System.out.println(printer.print(hiveResult));

        //用 Spark 引擎解析并打印
        System.out.println("================ [Spark 引擎解析] ================");
        SqlParser sparkEngine = new SparkSql();
        SqlInfo sparkResult = sparkEngine.parse(sql);
        System.out.println(printer.print(sparkResult));


        //输出sql元数据提取结果


//        // 1. 准备一句最简单的 Spark SQL
//        String sql = "SELECT * FROM test_table WHERE id = 1";
//
//        // 2. 词法分析 (Lexer)
//        SqlBaseLexer lexer = new SqlBaseLexer(CharStreams.fromString(sql));
//        CommonTokenStream tokens = new CommonTokenStream(lexer);
//
//        // 3. 语法分析 (Parser)
//        SqlBaseParser parser = new SqlBaseParser(tokens);
//
//        // 这里的关键是找到 Parser 中的入口规则名
//        // Spark 的入口通常是 singleStatement, singleExpression 或 query 等
//        // 请打开 SqlBaseParser.g4 查看第一个非 fragment 的规则名
//        ParseTree tree = parser.singleStatement(); // 假设入口规则叫 singleStatement
//
//        // 4. 打印语法树
//        System.out.println("解析结果: " + tree.toStringTree(parser));
//
//        // 5. 检查是否有语法错误
//        if (parser.getNumberOfSyntaxErrors() > 0) {
//            System.out.println("SQL 解析存在语法错误！");
//        } else {
//            System.out.println("Spark SQL 解析成功！");
//        }
//
//
//



   }
}
