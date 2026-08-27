package org.example;

import org.example.entity.datamodel.*;
import org.example.hive.HiveParser;
import org.example.hive.HiveParserBaseListener;


import java.util.ArrayList;
import java.util.List;
import java.util.Objects;


public class MyHiveListener extends HiveParserBaseListener{

    public List<SqlInfo> sqlInfos =new ArrayList<>();  //临时储存sql，用于sql与sql之间进行关联,sql信息的完善

    public List<SelectItem> selectItems=new ArrayList<>();  //临时储存查询列，用于与表名进行关联,查询列信息的完善

    public List<FunctionInfo> functions=new ArrayList<>();  //临时存储函数，用于判断函数是否嵌套，关联父函数

    int tableLever = -1;  //表的层级(主查询表与子查询表的区别),初始值为-1,最外层的子sql层级为0,向内层级逐渐增大

    int selectItemLever = -1;  //查询列层级(主查询列与子查询列的区别),初始值为-1,最外层的子sql层级为0,向内层级逐渐增大

    int sqlLever = -1;  //sql层级(主查询与子查询的区别),初始值为-1,最外层的子sql层级为0,向内层级逐渐增大

    int sqlOk=0;   //判断当前是否已经构造sql类

    //获取并封装物理字段
    public void enterPrecedenceFieldExpression(HiveParser.PrecedenceFieldExpressionContext ctx){

        SelectItem selectItem=selectItems.getLast();   //获取最新查询列

        if(ctx.atomExpression().tableOrColumn() != null){  //查询列的物理字段

            if(Objects.equals(selectItem.selectItemDisplayName, "")){   //判断查询列展示名字是否仍为初始值
                selectItem.selectItemDisplayName=ctx.getText();   //物理字段名作为查询列展示名字
            }
            if(selectItem.selectItemAlias != null){
                selectItem.transformType= TransformType.DIRECT;   //确定物理字段与查询列关系为 直取
            }

            //关联物理字段与查询列
            selectItem.sourceColumns.add(ctx.getText());
        }
        else if(ctx.atomExpression().subQueryExpression() != null){  //查询列的子查询嵌套

            selectItem.transformType= TransformType.COMPLEX;   //确定物理字段与查询列关系为 复杂加工
            System.out.println("==访问子查询节点==");

        }
        else if(ctx.atomExpression().castExpression() != null){  //查询列的cast()类型转换

            selectItem.transformType= TransformType.SIMPLE;   //确定物理字段与查询列关系为 简单加工
            System.out.println("==访问expression节点==");

            FunctionInfo functionInfo=new FunctionInfo();   //封装cast()函数信息

            if(!functions.isEmpty() &&functions.getLast()!=null){ //判断该函数有没有父函数
                functionInfo.parentFunction=functions.getLast();  //关联父函数
                functionInfo.isNested=true;  //更新嵌套函数标记
            }

            functionInfo.functionName="CAST";  //封装cast()函数名和参数列表
            functionInfo.argument=ctx.getText().substring("CAST".length());

            functions.add(functionInfo);   //将函数添加进列表

        }
        else if(ctx.atomExpression().caseExpression()!=null){

        }
    }

    //获取并封装查询列的函数链
    public void exitPrecedenceFieldExpression(HiveParser.PrecedenceFieldExpressionContext ctx) {

        //遍历并记录最新查询列的函数调用链
        for (FunctionInfo function : functions) {
            selectItems.getLast().functionChain.add(function);
        }

        functions.clear();   //清空函数列表
    }


    //获取查询列原始表达式和查询列别名
    public void enterSelectItem(HiveParser.SelectItemContext ctx) {

        SelectItem selectItem=new SelectItem();  //用于封装SelectItem查询列

        selectItem.selectItemLevel= selectItemLever +1;  //更新查询列层级

        if(ctx.expression()!=null) {  //查询列不是星号*

            //获取并记录查询列的原始表达式文本
            selectItem.normalizedExpression = ctx.expression().getText();

            if (ctx.id_() != null) {  //查询列的别名存在

                int selectItemAliasCount = ctx.id_().size();  //获取查询列的别名的个数(可能有多个别名)
                StringBuilder selectItemAlias = new StringBuilder();     //接收并拼接查询列的多个别名

                for (int i = 0; i < selectItemAliasCount; i++) {

                    //拼接查询列的别名
                    selectItemAlias.append(ctx.id_(i).getText());
                    if (i < selectItemAliasCount - 1) {
                        selectItemAlias.append(",");
                    }
                }

                //记录查询列的别名
                selectItem.selectItemAlias = selectItemAlias.toString();
                //展示名字与别名保持一致
                selectItem.selectItemDisplayName=selectItemAlias.toString();
            }
            else{
                selectItem.selectItemDisplayName="";   //查询列没有别名,就先把展示名字段恢复为初始值
            }

        }else if (ctx.tableAllColumns() != null) {
            //查询列是星号*
            System.out.println(ctx.tableAllColumns().getText());
        }

        //将查询列添加进列表,便于后续节点访问
        selectItems.add(selectItem);

    }

    //获取查询列的原始表达式
    public void exitSelectItem(HiveParser.SelectItemContext ctx){


    }

    //封装并记录函数信息
    public void enterFunction_(HiveParser.Function_Context ctx){

        FunctionInfo functionInfo=new FunctionInfo();   //封装函数信息

        if(!functions.isEmpty() &&functions.getLast()!=null){ //判断该函数有没有父函数
            functionInfo.parentFunction=functions.getLast();  //关联父函数
            functionInfo.isNested=true;  //更新嵌套函数标记
        }

        String functionName=ctx.functionName().getText();   //获取到函数名

        String functionArgument=ctx.getText().substring(functionName.length());  //获取函数参数列表

        //更新对应的函数信息
        functionInfo.functionName=functionName;
        functionInfo.argument=functionArgument;


        functions.add(functionInfo);   //将函数添加进列表

    }


    //获取并封装函数信息
    public void exitFunction_(HiveParser.Function_Context ctx){


    }

    //更新查询列层级和表层级
    public void enterJoinSource(HiveParser.JoinSourceContext ctx){

        //查询列层级更新
        selectItemLever +=1;

        //表层级更新
        tableLever= selectItemLever;

    }


    //判断并处理from 后的数据源类
    public void exitJoinSource(HiveParser.JoinSourceContext ctx){

        /*--------------------------------------------------------------------------------------------*/

        //第一个数据源

        TableInfo table= new TableInfo();   //存放表名与对应的表别名

        //最普通的物理表/视图
        if(ctx.atomjoinSource().tableSource()!=null){

            table.tableName=ctx.atomjoinSource().tableSource().tableName().getText(); // 记录当前表名

            //判断表有没有别名
            if(ctx.atomjoinSource().tableSource().alias!=null){
                table.tableNameAlias=ctx.atomjoinSource().tableSource().alias.getText();  //记录当前表别名
            }
            else{
                table.tableNameAlias="";  //记录默认表别名
            }
            table.tableLevel=tableLever;   //记录表的层级
        }
        else if(ctx.atomjoinSource().subQuerySource()!=null){  //子查询（嵌套的 SELECT）
            table.tableName=""; //记录默认表名

            //记录当前子查询对应的表别名
            table.tableNameAlias=ctx.atomjoinSource().subQuerySource().id_().getText();
            table.tableLevel=tableLever;    //记录表的层级
        }

        if(!sqlInfos.isEmpty()&&sqlInfos.getLast()!=null){
           SqlInfo sqlInfo = sqlInfos.getLast(); //获取到当前sql
            sqlInfo.tables.add(table);  //关联当前表与sql
        }




        /*--------------------------------------------------------------------------------------------*/

        //除第一个数据源以外的剩下数据源

        int joinCount=ctx.joinToken().size();  //获取除第一个数据源以外的数据源个数

        for(int i=0;i<joinCount;i++){

            table= new TableInfo();   //存放表名与对应的表别名

            //最普通的物理表/视图
            if(ctx.joinSourcePart(i).tableSource()!=null){

                table.tableName=ctx.joinSourcePart(i).tableSource().tableName().getText(); // 记录当前表名

                //判断表有没有别名
                if(ctx.joinSourcePart(i).tableSource().alias!=null){
                    table.tableNameAlias=ctx.atomjoinSource().tableSource().alias.getText();  //记录当前表别名
                }
                else{
                    table.tableNameAlias="";  //记录默认表别名
                }
                table.tableLevel=tableLever;    //记录表的层级

            }
            else if (ctx.joinSourcePart(i).subQuerySource()!=null){  //子查询（嵌套的 SELECT）
                table.tableName=""; //记录默认表名

                //记录当前子查询对应的表别名
                table.tableNameAlias=ctx.joinSourcePart(i).subQuerySource().id_().getText();

                table.tableLevel=tableLever;    //记录表的层级
            }

            if(!sqlInfos.isEmpty()&&sqlInfos.getLast()!=null){
                SqlInfo sqlInfo = sqlInfos.getLast(); //获取到当前sql
                sqlInfo.tables.add(table);  //关联当前表与sql
            }

        }

        tableLever-=1;  //表层级更新
    }


    //创建sql信息
    public void enterQueryStatementExpression(HiveParser.QueryStatementExpressionContext ctx){

        sqlOk=1;   //标记，已经构造sql
        SqlInfo sqlInfo=new SqlInfo();   //封装sql查询语句信息

        //获取原始sql文本
        sqlInfo.originalSql=ctx.getText();  //记录原始sql
        if (!sqlInfos.isEmpty() && sqlInfos.getLast() != null) {  //判断有无父级sql语句
            sqlInfo.parentSql=sqlInfos.getLast();  //关联父级sql语句
            sqlInfos.getLast().childSqls.add(sqlInfo);  //关联孩子sql语句
        }

        sqlInfos.add(sqlInfo);   //将sql加入sql列表

    }


    public void exitQueryStatementExpression(HiveParser.QueryStatementExpressionContext ctx){

        for (SelectItem selectItem : selectItems) {
            System.out.println("======" + selectItem);
        }

    }




    //创建或更新sql信息
    public void enterSelectStatement(HiveParser.SelectStatementContext ctx){

        SqlInfo sqlInfo;
        if(sqlOk==1 && !sqlInfos.isEmpty()&&sqlInfos.getLast()!=null){  //已有构造号的sql信息

            sqlInfo=sqlInfos.getLast(); //获取已经构造好的sql
            sqlOk=0;  //标识重置
        }
        else{        //无构造号的sql信息

            sqlInfo=new SqlInfo();   //封装sql查询语句信息
            //记录原始sql文本
            sqlInfos.getLast().originalSql=ctx.getText();

            if (!sqlInfos.isEmpty() && sqlInfos.getLast() != null) {  //判断有无父级sql语句
                sqlInfo.parentSql=sqlInfos.getLast();  //关联父级sql语句
                sqlInfos.getLast().childSqls.add(sqlInfo);  //关联孩子sql语句
            }
            sqlInfos.add(sqlInfo);   //将sql加入sql列表
        }
        sqlInfo.sqlType="DQL";    //记录sql语句类型为查询语句
        sqlLever+=1;  //更新sql层级
        sqlInfo.sqlLevel=sqlLever;  //记录sql层级
    }

    //更新sql层级，封装sql与对应selectItem查询列
    public void exitSelectStatement(HiveParser.SelectStatementContext ctx){
        sqlLever-=1; // 更新sql层级

        SqlInfo sqlInfo=sqlInfos.getLast();  //获取当前的查询sql

        for (SelectItem selectItem : selectItems) {   //遍历并记录该sql的selectItem查询列
            if (selectItem.selectItemLevel == sqlInfo.sqlLevel) {
                sqlInfo.selectItems.add(selectItem);
            }
        }

        //sql不是初始sql语句的话,就移除它
        if(sqlInfos.size()>1){
            sqlInfos.removeLast();  //移除当前sql，当前sql已解析结束
        }

    }
}
