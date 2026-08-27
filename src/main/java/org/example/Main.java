package org.example;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.example.hive.HiveLexer;   // 导入 Hive
import org.example.hive.HiveParser;

public class Main {
    public static void main(String[] args) {

        String sql="select id as t,count(min(max(concat(hive.id_card,phone)))) as test from (select ko from xxx)hive,(select ts,ss from spark)impala";

        // 将sql转成一个个字符流,将字符流传给词法分析器lexer,由词法分析器将字符组装成一个个token(可识别并处理的最小的单元)
        HiveLexer lexer = new HiveLexer(CharStreams.fromString(sql));

        //将词法分析器lexer中的token 转换成token流
        CommonTokenStream tokens = new CommonTokenStream(lexer);

        //将token流传给语法分析器parser
        HiveParser parser = new HiveParser(tokens);

        //语法分析器parser开始根据规则，解析传来的token流，最终返回对应的语法树AST
        ParseTree tree = parser.statement(); // 从入口规则开始解析

        // 创建监听器并遍历
        MyHiveListener extractor = new MyHiveListener();
        ParseTreeWalker.DEFAULT.walk(extractor, tree);

        //输出sql元数据提取结果
       // System.out.println(extractor.returnSqlInfo());
    }
}
