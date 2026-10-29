package org.example.hive;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.example.SqlParser;
import org.example.entity.datamodel.SqlInfo;
import org.example.util.SqlParseResultPrinter;

import java.util.HashMap;
import java.util.Map;

public class HiveSql implements SqlParser {

    //获取当前引擎的方言名称
    public String getDialectName() {
        return "Hive";
    }

    //用于封装 hive sql 解析过程，解析完成后，返回最外层的sql信息
    public SqlInfo parse(String sql) {

        //用于记录所有sql语句与节点的一对一关系，sql中包含对应的scope作用域信息
        Map<ParserRuleContext, SqlInfo> sqlInfoMap = new HashMap<>();


        // 将sql转成一个个字符流,将字符流传给词法分析器lexer,由词法分析器将字符组装成一个个token(可识别并处理的最小的单元)
        HiveLexer lexer = new HiveLexer(CharStreams.fromString(sql));

        //将词法分析器lexer中的token 转换成token流
        CommonTokenStream tokens = new CommonTokenStream(lexer);

        //将token流传给语法分析器parser
        HiveParser parser = new HiveParser(tokens);

        //语法分析器parser开始根据规则，解析传来的token流，最终返回对应的语法树AST
        ParseTree tree = parser.statement(); // 从入口规则开始解析

        //创建作用域构建器,并将作用域信息通过sqlInfoMap传递出来
        ScopeBuilderListener scopeBuilder = new ScopeBuilderListener(sqlInfoMap);

        //第一次遍历语法树
        ParseTreeWalker.DEFAULT.walk(scopeBuilder, tree);

        //获取到最外层的sql语句信息
        SqlInfo firstSqlInfo = scopeBuilder.firstSqlInfo;

        //创建sql分析器,用于填充sql的查询列
        SqlAnalyzerListener sqlAnalyzer = new SqlAnalyzerListener(sqlInfoMap);

        //第二次遍历语法树
        ParseTreeWalker.DEFAULT.walk(sqlAnalyzer, tree);


        return firstSqlInfo;
    }
}
