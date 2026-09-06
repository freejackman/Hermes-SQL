package org.example;

import org.example.entity.datamodel.*;
import org.example.hive.HiveParser;
import org.example.hive.HiveParserBaseListener;


import java.util.*;


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

    public Deque<Scope> scopeStack = new ArrayDeque<>();       //用栈来记录和管理作用域

    public Deque<SqlInfo>sqlInfoStack = new ArrayDeque<>();    //用栈来记录和管理sql语句


    //构建物理字段到表的映射，并将其与对应的查询列关联
    public void resolveColumnToScope(String columnText, SelectItem selectItem) {

        // 判断有无 前缀 (例如 "t1.id")
        //为表设置了别名后,同一个层级中，别名会覆盖掉源表名,原表名如果再作为前缀使用，就会报错
        if (columnText.contains(".")) {  //字段有前缀
            String[] parts = columnText.split("[.]");

            String columnTextAlias = parts[0];  //获取前缀(可能是表别名，也可能是原表名)

            String colName = parts[1];  //获取物理字段名


            // 从栈顶开始查找每个作用域Scope中的表别名
            for (Scope scope : scopeStack) {
                if (scope.aliasToTableMap.containsKey(columnTextAlias)) {  //找到了表别名与表的关联

                    ColumnRef columnRef = new ColumnRef();  //用于封装表与物理字段的关系

                    columnRef.columnName = colName; //记录字段名

                    columnRef.originTable=scope.aliasToTableMap.get(columnTextAlias);  //获取并记录别名对应的表

                    selectItem.sourceColumns.add(columnRef);  //将字段与表关系封装，并将其关联上对应的查询列
                    return;
                }
            }
            // 找不到物理字段对应的表，则报错
            ColumnRef columnRef = new ColumnRef();  //用于封装表与物理字段的关系

            columnRef.columnName = colName; //记录字段名

            columnRef.originTable=null;  //将对应的表标记为未知

            selectItem.sourceColumns.add(columnRef);
            return;
        }

        // 裸字段 (例如 "id") ，即字段无前缀
        boolean found = false;  //用于记录是否找到字段与表的关系

        // 从栈顶开始查找每个作用域Scope中的所有字段
        for (Scope scope : scopeStack) {

            if (scope.columnToTableMap.containsKey(columnText)) {  //找到了字段与表的关联

                List<TableInfo> tables = scope.columnToTableMap.get(columnText);  //获取到与该字段能够匹配的所有表

                if (tables.size() == 1) {   //只有一张表能匹配该字段

                    ColumnRef columnRef = new ColumnRef();

                    columnRef.columnName = columnText;  //记录字段名

                    columnRef.originTable=tables.getFirst();  //记录字段名匹配的表

                    selectItem.sourceColumns.add(columnRef);  //将字段与表关系封装，并将其关联上对应的查询列

                    found = true;  //标记已找到表与物理字段的关系
                    break;


                } else {

                    // 多张表都能匹配这个字段，标记为歧义
                    ColumnRef columnRef = new ColumnRef();

                    columnRef.columnName = columnText;  //记录字段名

                    columnRef.originTable=null;  //将匹配的表置空

                    columnRef.isAmbiguous = true;  //标记字段匹配的表有多个

                    selectItem.sourceColumns.add(columnRef);  //将字段与表关系封装，并将其关联上对应的查询列

                    found = true;   //标记已找到表与物理字段的关系
                    break;
                }
            }
        }

        if (!found) {
            // 穿透到最外层依然找不到，说明是非真实存在的物理字段,可能是常量或函数生成的虚拟列，暂时留空

            ColumnRef columnRef = new ColumnRef();  //用于封装表与物理字段的关系

            columnRef.columnName = columnText; //记录字段名

            columnRef.originTable=null;  //将匹配的表置空

            selectItem.sourceColumns.add(columnRef);
        }
    }



    //分类型处理查询列
    public void enterPrecedenceFieldExpression(HiveParser.PrecedenceFieldExpressionContext ctx){

        SelectItem selectItem= !selectItems.isEmpty() ? selectItems.getLast():null;   //获取最新查询列

        if(selectItem==null){   //无查询列,异常状况，直接退出
            return;
        }

        if(ctx.atomExpression().tableOrColumn() != null){  //查询列的物理字段

            if(Objects.equals(selectItem.selectItemDisplayName, "")){   //判断查询列展示名字是否仍为初始值
                selectItem.selectItemDisplayName=ctx.getText();   //物理字段名作为查询列展示名字
            }
            if(selectItem.selectItemDisplayName != null){
                selectItem.transformType= TransformType.DIRECT;   //确定物理字段与查询列关系为 直取
            }

            //关联物理字段与查询列
            resolveColumnToScope(ctx.getText(), selectItem);
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

        Scope scope = new Scope();  //创建新的scope作用域

        //关联父级作用域
        scope.parentScope = scopeStack.isEmpty() ? null : scopeStack.peek();

        //设置作用域层级
        scope.scopeLevel = scopeStack.size() + 1; // 直接用栈大小决定层级

        //将作用域入栈
        scopeStack.push(scope);

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
            table.tableLevel=sqlInfoStack.size();   //记录数据源的层级,与当前作用域深度一致

            table.tableType=TableType.PHYSICAL; //表类型为 物理表

        }
        else if(ctx.atomjoinSource().subQuerySource()!=null){  //子查询（嵌套的 SELECT）
            table.tableName=""; //记录默认表名

            //记录当前子查询对应的表别名
            table.tableNameAlias=ctx.atomjoinSource().subQuerySource().id_().getText();

            table.tableLevel=sqlInfoStack.size();    //记录数据源的层级

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
                //table.functionInputColumns.add(tableFunctionArgument);

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

            if (!sqlInfoStack.isEmpty()) { //判断有无父级sql语句
                sqlInfo.parentSql = sqlInfoStack.peek();  // 获取栈顶的sql,即父级sql
                sqlInfoStack.peek().childSqls.add(sqlInfo);
            }
            sqlInfo.sqlLevel=sqlInfoStack.size()+1;  //记录sql语句的层级
            sqlInfoStack.push(sqlInfo);  // 压入当前SQL



        }
        else{
            System.out.println("表函数内部的子查询");

            //暂不处理，待后续开发
        }

    }


    public void exitQueryStatementExpression(HiveParser.QueryStatementExpressionContext ctx){




    }




    //创建或更新sql信息
    public void enterSelectStatement(HiveParser.SelectStatementContext ctx){

        //判断是否为分区表函数内部的子查询
        if(isTableFunctionSub ==0){

            SqlInfo sqlInfo;
            if(sqlOk==1 && !sqlInfoStack.isEmpty()){  //已有构造号的sql信息

                sqlInfo=sqlInfoStack.peek(); //获取已经构造好的sql
                sqlOk=0;  //标识重置
            }
            else{        //无构造号的sql信息

                sqlInfo=new SqlInfo();   //封装sql查询语句信息
                //记录原始sql文本
                sqlInfo.originalSql=ctx.getText();

                if (!sqlInfoStack.isEmpty()) {  //判断有无父级sql语句
                    sqlInfo.parentSql=sqlInfoStack.peek();  //关联父级sql语句
                    sqlInfoStack.peek().childSqls.add(sqlInfo);  //关联孩子sql语句
                }
                sqlInfo.sqlLevel=sqlInfoStack.size()+1;   //记录sql语句的层级

                sqlInfoStack.push(sqlInfo);   //将sql加入sql列表
            }
            sqlInfo.sqlType="DQL";    //记录sql语句类型为查询语句
        }
    }

    //更新sql层级，封装sql与对应selectItem查询列
    public void exitSelectStatement(HiveParser.SelectStatementContext ctx){

        //判断是否为表函数内部的子查询
        if(isTableFunctionSub ==0){

            SqlInfo sqlInfo=sqlInfoStack.isEmpty()? null: sqlInfoStack.peek();  //获取栈顶sql(当前查询的sql)

            for (SelectItem selectItem : selectItems) {   //遍历并记录该sql的selectItem查询列
                if (sqlInfo != null && selectItem.selectItemLevel == sqlInfo.sqlLevel) {
                    sqlInfo.selectItems.add(selectItem);
                    selectItems.remove(selectItem);    //从列表中删除已记录的查询列
                }
            }

            //sql不是初始sql语句的话,就移除它
            if(sqlInfoStack.size()>1){
                sqlInfoStack.pop();   //将栈顶的sql(当前sql)弹出，表示该sql已经解析完成
            }
        }

    }


}
