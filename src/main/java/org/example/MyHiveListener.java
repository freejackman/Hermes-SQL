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

    int enterFrom=0;  //判断sql解析是否进入from xxx阶段,0---未进入,1---已进入，通过该标识来执行不同的子查询逻辑

    int sqlOk=0;   //判断当前是否已经构造sql类,0---未构造,1---已构造

    int isTableFunctionSub =0;  //判断是否为分区表函数内部的子查询,0--否,1---是

    int isTableFunction =0;   //是否为表函数,0---否,1---是

    //分类型处理查询列
    public void enterPrecedenceFieldExpression(HiveParser.PrecedenceFieldExpressionContext ctx){

        SelectItem selectItem=selectItems.getLast();   //获取最新查询列

        if(ctx.atomExpression().tableOrColumn() != null){  //查询列的物理字段

            if(Objects.equals(selectItem.selectItemDisplayName, "")){   //判断查询列展示名字是否仍为初始值
                selectItem.selectItemDisplayName=ctx.getText();   //物理字段名作为查询列展示名字
            }
            if(selectItem.selectItemDisplayName != null){
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

            System.out.println("==访问expression节点==");

            FunctionInfo functionInfo=new FunctionInfo();   //封装cast()函数信息

            if(!functions.isEmpty() &&functions.getLast()!=null
                    &&functions.getLast().functionLever==selectItemLever+1){ //判断该函数有没有父函数

                functionInfo.parentFunction=functions.getLast();  //关联父函数
                functionInfo.isNested=true;  //更新嵌套函数标记
            }

            functionInfo.functionName="CAST";  //封装cast()函数名和参数列表
            functionInfo.argument=ctx.getText().substring("CAST".length());


            //更新函数层级,与查询列层级保持一致
            functionInfo.functionLever=selectItemLever+1;

            functions.add(functionInfo);   //将cast()函数添加进列表

            if(Objects.equals(selectItems.getLast().selectItemDisplayName, "")){
                selectItems.getLast().selectItemDisplayName=null;   //标记函数对应的查询列展示名字为空
            }

        }
        else if(ctx.atomExpression().caseExpression()!=null){  //查询列的case xxx when xxx多源条件表达式

            selectItem.transformType= TransformType.COMPLEX;   //确定物理字段与查询列关系为 复杂加工
            System.out.println("==访问case xxx when xxx节点==");

        }
        else if(ctx.atomExpression().whenExpression()!=null){  //查询列的case when xxx多源条件表达式

            selectItem.transformType= TransformType.COMPLEX;   //确定物理字段与查询列关系为 复杂加工
            System.out.println("==访问case when xxx节点==");
        }
        else if(ctx.atomExpression().constant()!=null){   //查询列的常量

            selectItem.transformType= TransformType.CONSTANT;   //确定物理字段与查询列关系为 常量

            if(Objects.equals(selectItem.selectItemDisplayName, "")){   //判断查询列展示名字是否仍为初始值
                selectItem.selectItemDisplayName=null;   //标记常量对应的查询列展示名字为空
            }

            System.out.println("==访问 常量  节点==");
        }
        else if(ctx.atomExpression().intervalExpression()!=null){   //查询列的时间间隔intervalExpression

            if(ctx.atomExpression().intervalExpression().expression()!=null){  //时间间隔中有物理字段依赖

                selectItem.transformType= TransformType.SIMPLE;   //确定物理字段与查询列关系为 简单加工

                if(Objects.equals(selectItem.selectItemDisplayName, "")){   //判断查询列展示名字是否仍为初始值
                    selectItem.selectItemDisplayName=null;   //标记常量对应的查询列展示名字为空
                }

            }
            else{    //时间间隔中没有物理字段依赖

                selectItem.transformType= TransformType.CONSTANT;   //确定物理字段与查询列关系为 常量

                if(Objects.equals(selectItem.selectItemDisplayName, "")){   //判断查询列展示名字是否仍为初始值
                    selectItem.selectItemDisplayName=null;   //标记常量对应的查询列展示名字为空
                }
            }
        }
        else if(ctx.atomExpression().extractExpression()!=null){   //查询列的抽取表达式extract()函数


            FunctionInfo functionInfo=new FunctionInfo();   //封装extract()函数信息

            if(!functions.isEmpty() &&functions.getLast()!=null
                    &&functions.getLast().functionLever==selectItemLever+1){ //判断该函数有没有父函数

                functionInfo.parentFunction=functions.getLast();  //关联父函数
                functionInfo.isNested=true;  //更新嵌套函数标记
            }

            functionInfo.functionName="EXTRACT";  //封装extract()函数名和参数列表
            functionInfo.argument=ctx.getText().substring("EXTRACT".length());


            //更新函数层级,与查询列层级保持一致
            functionInfo.functionLever=selectItemLever+1;

            functions.add(functionInfo);   //将cast()函数添加进列表

            if(Objects.equals(selectItems.getLast().selectItemDisplayName, "")){
                selectItems.getLast().selectItemDisplayName=null;   //标记函数对应的查询列展示名字为空
            }
        }
        else if(ctx.atomExpression().floorExpression()!=null){  //查询列的取整表达式floor()函数

            FunctionInfo functionInfo=new FunctionInfo();   //封装floor()函数信息

            if(!functions.isEmpty() &&functions.getLast()!=null
                    &&functions.getLast().functionLever==selectItemLever+1){ //判断该函数有没有父函数

                functionInfo.parentFunction=functions.getLast();  //关联父函数
                functionInfo.isNested=true;  //更新嵌套函数标记
            }

            functionInfo.functionName="FLOOR";  //封装floor()函数名和参数列表
            functionInfo.argument=ctx.getText().substring("FLOOR".length());


            //更新函数层级,与查询列层级保持一致
            functionInfo.functionLever=selectItemLever+1;

            functions.add(functionInfo);   //将cast()函数添加进列表

            if(Objects.equals(selectItems.getLast().selectItemDisplayName, "")){
                selectItems.getLast().selectItemDisplayName=null;   //标记函数对应的查询列展示名字为空
            }
        }
    }



    //获取并封装查询列的函数链
    public void exitPrecedenceFieldExpression(HiveParser.PrecedenceFieldExpressionContext ctx) {

        int count = 0;   //记录 与查询列是同一个层级的函数的个数

        //遍历并记录最新查询列的函数调用链
        for (FunctionInfo function : functions) {
            if(selectItems.getLast().selectItemLevel==function.functionLever){  //判断函数与查询列是同一个层级
                selectItems.getLast().functionChain.add(function);
                count+=1;        //函数个数加一
            }
        }

        if(count>1){
            selectItems.getLast().transformType= TransformType.COMPLEX;   //确定物理字段与查询列关系为 复杂加工
        }
        else if(count==1){
            selectItems.getLast().transformType= TransformType.SIMPLE;   //确定物理字段与查询列关系为 简单加工
        }

        //判断函数与查询列是同一个层级
        //从列表中删除对应函数
        functions.removeIf(function -> selectItems.getLast().selectItemLevel == function.functionLever);
    }


    //创建并封装查询列信息
    public void enterSelectItem(HiveParser.SelectItemContext ctx) {

        SelectItem selectItem=new SelectItem();  //用于封装SelectItem查询列

        selectItem.selectItemLevel= selectItemLever +1;  //更新查询列层级

        enterFrom =0;   //记录sql解析进度

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


    //将子查询中查询列的最终依赖的物理字段传递给对应的父查询列
    public void exitSelectItem(HiveParser.SelectItemContext ctx){

        //判断sql解析是否进入from xxx 阶段
        if(enterFrom==0){
            for(int i=0;i<selectItems.size();i++){
                if(selectItems.get(selectItems.size()-1-i).selectItemLevel==selectItemLever){   //判断查询列是否为父查询的查询列
                                                                    // 当前查询列层级为selectItemLever+1,其父查询列的层级为selectItemLever

                    //将子查询中查询列的最终依赖的物理字段传递给对应的父查询列
                    for(int j=0;j <selectItems.getLast().sourceColumns.size();j++){
                        selectItems.get(selectItems.size()-1-i).sourceColumns.add(selectItems.getLast().sourceColumns.get(j));
                    }
                    break;
                }
            }

            //记录sql解析进度
            enterFrom=1;
        }
    }

    //封装并记录函数信息
    public void enterFunction_(HiveParser.Function_Context ctx){

        //判断是否是表函数
        if(isTableFunction==0){

            FunctionInfo functionInfo=new FunctionInfo();   //封装函数信息

            if(!functions.isEmpty() &&functions.getLast()!=null
                    &&functions.getLast().functionLever==selectItemLever+1){ //判断该函数有没有父函数
                                //当前层级为selectItemLever+1


                functionInfo.parentFunction=functions.getLast();  //关联父函数
                functionInfo.isNested=true;  //更新嵌套函数标记
            }

            String functionName=ctx.functionName().getText();   //获取到函数名

            String functionArgument=ctx.getText().substring(functionName.length());  //获取函数参数列表

            //更新对应的函数信息
            functionInfo.functionName=functionName;
            functionInfo.argument=functionArgument;

            //更新函数层级,与查询列层级保持一致
            functionInfo.functionLever=selectItemLever+1;

            functions.add(functionInfo);   //将函数添加进列表

            if(Objects.equals(selectItems.getLast().selectItemDisplayName, "")){
                selectItems.getLast().selectItemDisplayName=null;   //标记函数对应的查询列别名为空
            }

        }
    }


    //获取并封装函数信息
    public void exitFunction_(HiveParser.Function_Context ctx){


    }

    //处理数据源中分区表函数的情况，标记分区表函数子查询
    //分区表函数（Hive 特有）
    //本质上就是 以表为参数，输出另一张表的内置函数
    public  void enterpartitionedTableFunction(HiveParser.PartitionedTableFunctionContext ctx) {

        isTableFunctionSub =1;    //标记分区表函数子查询

    }

    //处理数据源中表函数的情况，标记表函数
    public void enterlateralView(HiveParser.LateralViewContext ctx) {

        isTableFunction=1;   //标记表函数

    }

    //处理数据源中表函数的情况，重置表函数标记
    public void exitlateralView(HiveParser.LateralViewContext ctx) {

        isTableFunction=0;  //重置标识

    }


    //更新查询列层级和表层级，记录sql解析进度
    public void enterJoinSource(HiveParser.JoinSourceContext ctx){

        //查询列层级更新
        selectItemLever +=1;

        //表层级更新
        tableLever= selectItemLever;

        //记录sql解析进度
        enterFrom=1;

    }


    //判断并处理from 后的数据源类,查询列层级更新
    public void exitJoinSource(HiveParser.JoinSourceContext ctx){

        /*--------------------------------------------------------------------------------------------*/

        //第一个数据源

        TableInfo table= new TableInfo();   //存放表名与对应的表别名

        //最普通的物理表/视图
        if(ctx.atomjoinSource().tableSource()!=null){

            //判断有无库名
            if(ctx.atomjoinSource().tableSource().tableName().tab!=null){
                table.dataBaseName=ctx.atomjoinSource().tableSource().tableName().db.getText();  //记录库名

                table.tableName=ctx.atomjoinSource().tableSource().tableName().getText()
                        .substring(table.dataBaseName.length()+1); // 记录当前表名
            }
            else{  //无库名
                table.tableName=ctx.atomjoinSource().tableSource().tableName().getText(); // 记录当前表名
            }


            //判断表有没有别名
            if(ctx.atomjoinSource().tableSource().alias!=null){
                table.tableNameAlias=ctx.atomjoinSource().tableSource().alias.getText();  //记录当前表别名
            }
            else{
                table.tableNameAlias="";  //记录默认表别名
            }
            table.tableLevel=tableLever;   //记录数据源的层级

            table.tableType=TableType.PHYSICAL; //表类型为 物理表

        }
        else if(ctx.atomjoinSource().subQuerySource()!=null){  //子查询（嵌套的 SELECT）
            table.tableName=""; //记录默认表名

            //记录当前子查询对应的表别名
            table.tableNameAlias=ctx.atomjoinSource().subQuerySource().id_().getText();

            table.tableLevel=tableLever;    //记录数据源的层级

            table.tableType=TableType.SUBQUERY;  //表类型为 子查询

            table.subQuery=sqlInfos.getLast().childSqls.getLast();    //将表与其对应的子查询关联



        }
        else if(ctx.atomjoinSource().virtualTableSource()!=null){    //虚拟表/内联数据，
                                // TABLE (VALUES (1, 'Alice'), (2, 'Bob')) AS t (id, name)

            table.tableName="";  //记录默认表名

            //记录表别名
            table.tableNameAlias=ctx.atomjoinSource().virtualTableSource().tableAlias().getText();

            table.tableLevel=tableLever;    //记录数据源的层级

            table.tableType=TableType.VIRTUAL;  //表类型为 虚拟表/内联数据

            //未处理完,后续开发

        }
        else if(ctx.atomjoinSource().partitionedTableFunction()!=null){    //分区表函数（Hive 特有）
                                                                    //本质上就是 以表为参数，输出另一张表的内置函数
            table.tableName="";  //记录默认表名

            //记录函数输出的临时表别名
            table.tableNameAlias=ctx.atomjoinSource().partitionedTableFunction().alias.getText();

            table.tableType=TableType.FUNCTION;   //类型为  表函数

            table.tableLevel=tableLever;    //记录数据源的层级

            table.tableFunctionName.add(ctx.atomjoinSource().partitionedTableFunction().n.getText());  //获取表函数名字

            String tableFunctionArgument=ctx.atomjoinSource().partitionedTableFunction().getText();

            //删除开头(表函数名部分)
            if(tableFunctionArgument.startsWith(table.tableFunctionName.getLast())){
                tableFunctionArgument=tableFunctionArgument.substring(table.tableFunctionName.getLast().length());
            }

            //删除结尾(函数输出的临时表别名)
            if(tableFunctionArgument.endsWith(table.tableNameAlias)){
                tableFunctionArgument=tableFunctionArgument.substring(0, tableFunctionArgument.length()-table.tableNameAlias.length());

            }

            table.functionInputColumns.add(tableFunctionArgument);   //记录表函数入参字段

            isTableFunctionSub =0;  //重置标识

        }

        //存在表函数
        if(ctx.atomjoinSource().lateralView()!=null){

            table.tableType=TableType.FUNCTION; //更新表类型为 表函数

            //获取表函数的个数
            int tableLateralView= ctx.atomjoinSource().lateralView().size();

            for (int i=0;i<tableLateralView;i++){

                //获取表函数的函数名和参数列表
                String tableFunctionName = ctx.atomjoinSource().lateralView().get(i).function_().functionName().getText();
                String tableFunctionArgument = ctx.atomjoinSource().lateralView().get(i).function_().getText().substring(tableFunctionName.length());  //获取函数参数列表

                //记录表函数的函数名和参数列表
                table.tableFunctionName.add(tableFunctionName);
                table.functionInputColumns.add(tableFunctionArgument);

            }

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

                //判断有无库名
                if(ctx.joinSourcePart(i).tableSource().tableName().tab!=null){
                    table.dataBaseName=ctx.joinSourcePart(i).tableSource().tableName().db.getText();  //记录库名

                    table.tableName=ctx.joinSourcePart(i).tableSource().tableName().getText()
                            .substring(table.dataBaseName.length()+1); // 记录当前表名
                }
                else{  //无库名
                    table.tableName=ctx.joinSourcePart(i).tableSource().tableName().getText(); // 记录当前表名
                }


                //判断表有没有别名
                if(ctx.joinSourcePart(i).tableSource().alias!=null){
                    table.tableNameAlias=ctx.joinSourcePart(i).tableSource().alias.getText();  //记录当前表别名
                }
                else{
                    table.tableNameAlias="";  //记录默认表别名
                }
                table.tableLevel=tableLever;   //记录数据源的层级

                table.tableType=TableType.PHYSICAL; //表类型为 物理表

            }
            else if(ctx.joinSourcePart(i).subQuerySource()!=null){  //子查询（嵌套的 SELECT）
                table.tableName=""; //记录默认表名

                //记录当前子查询对应的表别名
                table.tableNameAlias=ctx.joinSourcePart(i).subQuerySource().id_().getText();

                table.tableLevel=tableLever;    //记录数据源的层级

                table.tableType=TableType.SUBQUERY;  //表类型为 子查询

                table.subQuery=sqlInfos.getLast().childSqls.getLast();    //将表与其对应的子查询关联

            }
            else if(ctx.joinSourcePart(i).virtualTableSource()!=null){    //虚拟表/内联数据，
                // TABLE (VALUES (1, 'Alice'), (2, 'Bob')) AS t (id, name)

                table.tableName="";  //记录默认表名

                //记录表别名
                table.tableNameAlias=ctx.joinSourcePart(i).virtualTableSource().tableAlias().getText();

                table.tableLevel=tableLever;    //记录数据源的层级

                table.tableType=TableType.VIRTUAL;  //表类型为 虚拟表/内联数据

                //未处理完,后续开发

            }
            else if(ctx.joinSourcePart(i).partitionedTableFunction()!=null){    //分区表函数（Hive 特有）
                //本质上就是 以表为参数，输出另一张表的内置函数
                table.tableName="";  //记录默认表名

                //记录函数输出的临时表别名
                table.tableNameAlias=ctx.joinSourcePart(i).partitionedTableFunction().alias.getText();

                table.tableType=TableType.FUNCTION;   //类型为  表函数

                table.tableLevel=tableLever;    //记录数据源的层级

                table.tableFunctionName.add(ctx.joinSourcePart(i).partitionedTableFunction().n.getText());  //获取表函数名字

                String tableFunctionArgument=ctx.joinSourcePart(i).partitionedTableFunction().getText();

                //删除开头(表函数名部分)
                if(tableFunctionArgument.startsWith(table.tableFunctionName.getLast())){
                    tableFunctionArgument=tableFunctionArgument.substring(table.tableFunctionName.getLast().length());
                }

                //删除结尾(函数输出的临时表别名)
                if(tableFunctionArgument.endsWith(table.tableNameAlias)){
                    tableFunctionArgument=tableFunctionArgument.substring(0, tableFunctionArgument.length()-table.tableNameAlias.length());

                }

                table.functionInputColumns.add(tableFunctionArgument);   //记录表函数入参字段

                isTableFunctionSub =0;  //重置标识

            }

            //存在表函数
            if(ctx.joinSourcePart(i).lateralView()!=null){

                table.tableType=TableType.FUNCTION; //表类型为 表函数

                //获取表函数的个数
                int tableLateralView= ctx.joinSourcePart(i).lateralView().size();

                for (int j = 0; j <tableLateralView; j++){

                    //获取表函数的函数名和参数列表
                    String tableFunctionName = ctx.joinSourcePart(i).lateralView().get(j).function_().functionName().getText();
                    String tableFunctionArgument = ctx.joinSourcePart(i).lateralView().get(j).function_().getText().substring(tableFunctionName.length());  //获取函数参数列表

                    //记录表函数的函数名和参数列表
                    table.tableFunctionName.add(tableFunctionName);
                    table.functionInputColumns.add(tableFunctionArgument);

                }

            }



            if(!sqlInfos.isEmpty()&&sqlInfos.getLast()!=null){
                SqlInfo sqlInfo = sqlInfos.getLast(); //获取到当前sql
                sqlInfo.tables.add(table);  //关联当前表与sql
            }

        }

        tableLever-=1;  //表层级更新

        //记录sql解析进度
        enterFrom=0;
    }


    //创建sql信息
    public void enterQueryStatementExpression(HiveParser.QueryStatementExpressionContext ctx){

        //判断是否为分区表函数内部的子查询
        if(isTableFunctionSub ==0){

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
        else{
            System.out.println("表函数内部的子查询");

            //暂不处理，待后续开发
        }

    }


    public void exitQueryStatementExpression(HiveParser.QueryStatementExpressionContext ctx){

        for (SelectItem selectItem : selectItems) {
            System.out.println("======" + selectItem);
        }

    }




    //创建或更新sql信息
    public void enterSelectStatement(HiveParser.SelectStatementContext ctx){

        //判断是否为分区表函数内部的子查询
        if(isTableFunctionSub ==0){

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
    }

    //更新sql层级，封装sql与对应selectItem查询列
    public void exitSelectStatement(HiveParser.SelectStatementContext ctx){

        //判断是否为表函数内部的子查询
        if(isTableFunctionSub ==0){

            sqlLever-=1; // 更新sql层级

            SqlInfo sqlInfo=sqlInfos.getLast();  //获取当前的查询sql

            for (SelectItem selectItem : selectItems) {   //遍历并记录该sql的selectItem查询列
                if (selectItem.selectItemLevel == sqlInfo.sqlLevel) {
                    sqlInfo.selectItems.add(selectItem);
                    selectItems.remove(selectItem);    //从列表中删除已记录的查询列
                }
            }


            //sql不是初始sql语句的话,就移除它
            if(sqlInfos.size()>1){
                sqlInfos.removeLast();  //移除当前sql，当前sql已解析结束
            }
        }
    }


}
