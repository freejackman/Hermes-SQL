package org.example;


import org.antlr.v4.runtime.ParserRuleContext;
import org.example.entity.datamodel.*;
import org.example.hive.HiveParser;
import org.example.hive.HiveParserBaseListener;
import org.example.util.AstHelper;

import java.util.*;

//该类用于第一次遍历语法树，构建好所有的scope作用域以及与之对应的sql语句
// (sql语句对应的查询列selectItem信息要在第二次 遍历语法树时，才会填充)
public class ScopeBuilderListener extends HiveParserBaseListener {

    public Deque<SqlInfo> sqlInfoStack = new ArrayDeque<>();  //用栈来记录和管理sql语句

    public Deque<Scope> scopeStack = new ArrayDeque<>();  //用栈来记录和管理作用域


    // 用 IdentityHashMap来将节点与sql语句绑定，将该map作为参数，传递到下一次遍历中使用
    //节点采用ParserRuleContext类存储,因为其在比较的时候，默认使用 对象 的地址进行比较，
    // 因为两次遍历都是遍历的同一个parserTree，ParserRuleContext类可以保证节点的唯一性
    Map<ParserRuleContext, SqlInfo> sqlInfoMap = new IdentityHashMap<>();  //将节点与sql语句信息进行绑定。并存储起来


    Deque<TableInfo> functionOutputStack=new ArrayDeque<>();   //暂存分区表函数 产生的临时表,递归的时候当作栈,遍历的时候当作队列

    Deque<TableInfo> leftSourceStack=new ArrayDeque<>();   //暂存表函数对应的左输入源

    AstHelper astHelper=new AstHelper();      //AST语法树工具类,辅助语法解析


    //获取表函数(UDTF)的默认输出列
    public List<String> getUdtfDefaultSelectItem(String functionName){

        return switch (functionName.toLowerCase()) {
            case "explode" -> List.of("col"); // explode 默认输出列名为 "col"
            case "posexplode" -> List.of("pos", "val"); // posexplode 默认输出列名为 "pos"(元素下标),"val"(元素值)
            default -> List.of("result"); // 其他函数的默认输出都依赖与输入参数，保守返回单列 "result",这种情况必须具有显式输出列名
        };
    }

    //构建sql语句,记录相关sql信息
    public void enterQueryStatementExpression(HiveParser.QueryStatementExpressionContext ctx){

        SqlInfo sqlInfo=new SqlInfo();   //封装sql查询语句信息

        //获取原始sql文本
        sqlInfo.originalSql=ctx.getText();  //记录原始sql

        if (!sqlInfoStack.isEmpty() && sqlInfoStack.peek()!=null) { //判断有无父级sql语句
            sqlInfo.parentSql = sqlInfoStack.peek();  // 获取栈顶的sql,即父级sql,关联父子sql
            sqlInfoStack.peek().childSqls.add(sqlInfo);
        }

        sqlInfoStack.push(sqlInfo);  // 压入当前SQL
        sqlInfo.sqlLevel=sqlInfoStack.size();  //记录当前sql语句的层级
        sqlInfoMap.put(ctx, sqlInfo); //将节点与sql语句信息进行绑定。并存储起来

    }


    //弹出sql语句
    public void exitQueryStatementExpression(HiveParser.QueryStatementExpressionContext ctx){

        if (!sqlInfoStack.isEmpty()){
            sqlInfoStack.pop();  //当前sql信息解析完成,弹出
        }
    }


    //记录sql语句的类型
    public void enterSelectStatement(HiveParser.SelectStatementContext ctx){

        if (!sqlInfoStack.isEmpty() && sqlInfoStack.peek()!=null) {
            sqlInfoStack.peek().sqlType="DQL";  //sql语句为查询语句
        }
    }


    //构建scope,完善并记录scope相关信息，将scope作用域与对应的sql语句绑定
    public void enterJoinSource(HiveParser.JoinSourceContext ctx){

        // 判断当前 JoinSource 是否被 LPAREN RPAREN 包裹
        // 规则：如果父节点是 AtomjoinSourceContext，且父节点的第一个字符是 '('

        boolean isParenWrapped = false;   //标记JoinSource 是否被括号包裹

        if (ctx.getParent() instanceof HiveParser.AtomjoinSourceContext) {

            HiveParser.AtomjoinSourceContext parent =
                    (HiveParser.AtomjoinSourceContext) ctx.getParent();

            // 检查父节点的起始 子节点 是否为 LPAREN（左括号）
            if (parent.getStart() != null &&
                    parent.getStart().getType() == HiveParser.LPAREN) {
                isParenWrapped = true;
            }

        }

        if(isParenWrapped){   //被括号包裹,且为sql语句的第一个数据源

            return;
        }


        //没有被括号包裹或者不是sql语句的第一个数据源

        Scope sqlScope = new Scope();  //创建新的scope作用域

        if(!scopeStack.isEmpty()){
            sqlScope.parentScope=scopeStack.peek();   //关联父级scope作用域
        }

        scopeStack.push(sqlScope);  //将当前scope压入栈

        sqlScope.scopeLevel=sqlInfoStack.size();  //更新sqlScope对应的层级

        if (!sqlInfoStack.isEmpty() && sqlInfoStack.peek()!=null){

            sqlInfoStack.peek().sqlScope=sqlScope;   //将scope作用域与对应的sql语句绑定
            //List 类型是为了解决 JoinSource 节点嵌套的情况
        }
    }


    //处理不同的数据源(除分区表函数以外),以及表函数,弹出当前scope
    public void exitJoinSource(HiveParser.JoinSourceContext ctx) {

        /*--------------------------------------------------------------------------------------------*/

        //第一个数据源

        TableInfo table= new TableInfo();   //存放表名与对应的表别名

        //最普通的物理表/视图
        if(ctx.atomjoinSource().tableSource()!=null){

            //判断有无库名
            if(ctx.atomjoinSource().tableSource().tableName().db!=null){
                table.dataBaseName=ctx.atomjoinSource().tableSource().tableName().db.getText();  //记录库名

                table.tableName=ctx.atomjoinSource().tableSource().tableName().tab.getText();  // 记录当前表名
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

            table.tableLevel=sqlInfoStack.size();   //记录数据源的层级,与当前sql栈 深度一致

            table.tableType= TableType.PHYSICAL; //表类型为 物理表

            if(!scopeStack.isEmpty() && scopeStack.peek()!=null){

                scopeStack.peek().registerTable(table);   //将表注册进对应的sql作用域中,即在作用域中关联表别名和表
            }

            //关联当前数据源与sql语句
            if (!sqlInfoStack.isEmpty()&&sqlInfoStack.peek()!=null) {
                sqlInfoStack.peek().tables.add(table);
            }

        }
        else if(ctx.atomjoinSource().subQuerySource()!=null){  //子查询（嵌套的 SELECT）

            table.tableName=""; //记录默认表名

            //记录当前子查询对应的表别名
            table.tableNameAlias=ctx.atomjoinSource().subQuerySource().id_().getText();

            table.tableLevel=sqlInfoStack.size();    //记录数据源的层级

            table.tableType=TableType.SUBQUERY;  //表类型为 子查询

            //将子查询的sql与该表进行关联
            table.subQuery=sqlInfoMap.get(ctx.atomjoinSource().subQuerySource().queryStatementExpression());

            if(scopeStack.peek()!=null){

                scopeStack.peek().registerTable(table);   //将表注册进对应的sql作用域中,即在作用域中关联表别名和表
            }

            //关联当前数据源与sql语句
            if (!sqlInfoStack.isEmpty()&&sqlInfoStack.peek()!=null) {
                sqlInfoStack.peek().tables.add(table);
            }

        }
        else if(ctx.atomjoinSource().virtualTableSource()!=null){    //虚拟表/内联数据，
            // TABLE (VALUES (1, 'Alice'), (2, 'Bob')) AS t (id, name)

            table.tableName="";  //记录默认表名

            //记录表别名
            table.tableNameAlias=ctx.atomjoinSource().virtualTableSource().tableAlias().getText();

            table.tableLevel=sqlInfoStack.size();    //记录数据源的层级

            table.tableType=TableType.VIRTUAL;  //表类型为 虚拟表/内联数据

            int id_Count= ctx.atomjoinSource().virtualTableSource().id_().size();  //获取并记录内联表字段的个数

            Scope sqlScope = !scopeStack.isEmpty() && sqlInfoStack.peek()!=null ?
                    scopeStack.peek():null;

            //封装内联表字段信息
            for (int i=0;i<id_Count;i++){

                //将字段添加进表
                table.tableOutPutColumn.add(ctx.atomjoinSource().virtualTableSource().id_(i).getText());

                if(sqlScope!=null){
                    //将字段与表的关系注册进作用域
                    if(sqlScope.columnToTableMap.containsKey(ctx.atomjoinSource().virtualTableSource().id_(i).getText())){
                        //该字段在该作用域中已存在，将表与该字段进行绑定

                        sqlScope.columnToTableMap.get(ctx.atomjoinSource().virtualTableSource().id_(i).getText())
                                .add(table);
                    }
                    else{  //该字段不存在于作用域

                        List<TableInfo>  t =new ArrayList<>();  //构造列表
                        t.add(table);  //封装表信息
                        //将表与该字段进行绑定,并注册进作用域
                        sqlScope.columnToTableMap.put(ctx.atomjoinSource().virtualTableSource().id_(i).getText(),t);

                    }
                }


            }

            if(sqlScope!=null){

                sqlScope.registerTable(table);   //将表注册进对应的sql作用域中,即在作用域中关联表别名和表
            }

            //关联当前数据源与sql语句
            if (!sqlInfoStack.isEmpty()&&sqlInfoStack.peek()!=null) {
                sqlInfoStack.peek().tables.add(table);
            }

        } else if( ctx.atomjoinSource().partitionedTableFunction()!=null){  //将分区表函数节点压入 表函数左输入源

            if(!functionOutputStack.isEmpty()&&functionOutputStack.peek()!=null){

                //functionOutputStack此时被当作队列,.poll()首删
                leftSourceStack.push(functionOutputStack.poll());     //将表压入  表函数 左输入源栈
            }
        }

        //将表函数的左输入源压入栈
        if(ctx.atomjoinSource().partitionedTableFunction()==null) {

            if (!leftSourceStack.isEmpty()) {
                leftSourceStack.pop();  //弹栈，避免之前的数据干扰
            }

            leftSourceStack.push(table);  //将表压入  表函数 左输入源栈

        }




        //第一个数据源对应的表函数(UDTF)
        if(ctx.atomjoinSource().lateralView()!=null){

            //获取表函数的个数
            int tableLateralView= ctx.atomjoinSource().lateralView().size();

            for (int i=0;i<tableLateralView;i++){

                //处理所有表函数共有部分

                TableInfo functionTable = new TableInfo();

                functionTable.tableType=TableType.FUNCTION; //设置表类型为 表函数

                functionTable.tableLevel=sqlInfoStack.size();    //记录表层级

                functionTable.tableName="";  //使用默认表名

                //获取并记录表别名
                functionTable.tableNameAlias=ctx.atomjoinSource().lateralView().get(i).tableAlias().getText();

                //关联表函数的左输入源
                functionTable.inputSourceTable= !leftSourceStack.isEmpty()
                        && leftSourceStack.peek()!=null? leftSourceStack.peek():null;




                //处理表函数 输入参数和函数名
                List<ColumnRef> functionInputColumns=new ArrayList<>();  //用于封装表函数所有的输入参数


                //形式一: LATERAL VIEW explode(arr) t AS item
                //形式二: LATERAL VIEW OUTER explode(arr) t AS item
                if(ctx.atomjoinSource().lateralView().get(i).function_()!=null){

                    if(ctx.atomjoinSource().lateralView().get(i).function_().functionName()!=null){
                        //获取表函数的函数名
                        String tableFunctionName = ctx.atomjoinSource().lateralView().get(i).function_().functionName().getText();

                        //记录表函数的函数名
                        functionTable.tableFunctionName.add(tableFunctionName);

                        //表函数参数为星号
                        if(ctx.atomjoinSource().lateralView().get(i).function_().star!=null){

                            System.out.println("表函数参数不支持 *号");
                        }

                        //表函数参数为非星号
                        if(ctx.atomjoinSource().lateralView().get(i).function_().selectExpression()!=null){

                            //记录参数的个数
                            int selectExpressionCount=ctx.atomjoinSource().lateralView().get(i).function_().selectExpression().size();

                            List<ColumnRef>  t;

                            //收集并记录所有参数表达式对应的物理字段
                            for (int j=0;j<selectExpressionCount;j++){

                                //获取参数
                                t=astHelper.extractColumnCandidates(
                                                ctx.atomjoinSource().lateralView().get(i).function_().selectExpression().get(j));

                                if(functionTable.inputSourceTable!=null){

                                    //完善每一个参数的信息
                                    for (ColumnRef columnRef : t) {

                                        columnRef.originTable = functionTable.inputSourceTable;
                                    }

                                }

                                functionInputColumns.addAll(t);   //将收集到的输入参数信息合并
                            }
                        }

                        functionTable.functionInputColumns.addAll(functionInputColumns);   //将所有入参字段与表函数关联
                    }
                    else{
                        System.out.println("非法 表函数参数！");
                        return;
                    }
                }
                //形式三: LATERAL VIEW TABLE(VALUES (1,'a')) t(id, name)


                //处理表函数共同的参数部分

                //表函数输出参数显式存在，记录并处理表函数最终输出字段
                if(ctx.atomjoinSource().lateralView().get(i).id_()!=null){

                    //表函数 输出参数的个数
                    int outPut =ctx.atomjoinSource().lateralView().get(i).id_().size();

                    for (int j=0;j<outPut;j++){

                        ColumnRef colRef = new ColumnRef();  //用于封装字段信息

                        colRef.originTable=functionTable;  //关联输出字段与表

                        colRef.tableAlias=functionTable.tableNameAlias;  //记录字段对应数据源的别名

                        //获取并记录字段名
                        colRef.columnName=ctx.atomjoinSource().lateralView().get(i).id_().get(j).getText();

                        //将输出字段与表进行关联
                        functionTable.tableOutPutColumn.add(colRef.columnName);

                        if(!scopeStack.isEmpty()&&scopeStack.peek()!=null){

                            //将字段与表函数的关系注册进作用域
                            if(scopeStack.peek().columnToTableMap.containsKey(colRef.columnName)){
                                //该字段在该作用域中已存在，将表函数与该字段进行绑定

                                scopeStack.peek().columnToTableMap.get(colRef.columnName).add(functionTable);
                            }
                            else{  //该字段不存在于作用域

                                List<TableInfo>  t =new ArrayList<>();   //用于封装表信息
                                t.add(functionTable);  //记录当前表函数信息

                                //将表函数与输出字段关联，注册进对应的scope作用域中
                                scopeStack.peek().columnToTableMap.put(colRef.columnName,t);
                            }
                        }
                    }

                    //表函数显式输出参数只有一个,且表函数名为posexplode

                    if(!Objects.equals(ctx.atomjoinSource().lateralView().get(i).id_().getFirst().getText(), "posexplode")
                            &&outPut==1){

                        if(!scopeStack.isEmpty()&&scopeStack.peek()!=null){

                            //将字段与表函数的关系注册进作用域
                            if(scopeStack.peek().columnToTableMap.containsKey(ctx.atomjoinSource().lateralView().get(i).id_().getFirst().getText())){
                                //该字段在该作用域中已存在，将表函数与该字段进行绑定

                                scopeStack.peek().columnToTableMap.get(ctx.atomjoinSource().lateralView().get(i).id_().getFirst().getText())
                                        .add(functionTable);
                            }
                            else{  //该字段不存在于作用域

                                List<TableInfo>  t =new ArrayList<>();   //用于封装表信息
                                t.add(functionTable);  //记录当前表函数信息

                                //将表函数与输出字段关联，注册进对应的scope作用域中
                                scopeStack.peek().columnToTableMap.put(ctx.atomjoinSource().lateralView().get(i).id_().getFirst().getText(),t);
                            }


                            //将字段与表函数的关系注册进作用域
                            if(scopeStack.peek().columnToTableMap.containsKey("val")){
                                //该字段在该作用域中已存在，将表函数与该字段进行绑定

                                scopeStack.peek().columnToTableMap.get("val").add(functionTable);

                            }
                            else{  //该字段不存在于作用域

                                List<TableInfo>  t =new ArrayList<>();   //用于封装表信息
                                t.add(functionTable);  //记录当前表函数信息

                                //将表函数与输出字段关联，注册进对应的scope作用域中
                                scopeStack.peek().columnToTableMap.put("val",t);
                            }
                        }
                    }
                }
                //表函数无显式存在的输出参数
                else{

                    //获取各个表函数默认的输出列
                    List<String>outPutColumns = this.getUdtfDefaultSelectItem(functionTable.tableFunctionName.getLast());

                    //将表函数默认的输出列与表关联，注册进对应的scope作用域
                    for (String putColumn : outPutColumns) {

                        functionTable.tableOutPutColumn.add(putColumn);

                        if (!scopeStack.isEmpty() && scopeStack.peek() != null) {

                            //关联输出列与表，并将它们注册进对应的scope作用域中

                            if(scopeStack.peek().columnToTableMap.containsKey(putColumn)){
                                //该输出列名字在该作用域中已存在，将表与该名字进行绑定

                                scopeStack.peek().columnToTableMap.get(putColumn).add(functionTable);
                            }
                            else{  //该输出列名字不存在于作用域

                                List<TableInfo>  t =new ArrayList<>();   //用于封装表信息
                                t.add(functionTable);  //记录当前表函数信息

                                //将表与输出列关联，注册进对应的scope作用域中
                                scopeStack.peek().columnToTableMap.put(putColumn,t);
                            }


                        }

                    }

                }






                if(!scopeStack.isEmpty() && scopeStack.peek()!=null){

                    scopeStack.peek().registerTable(functionTable);   //将表函数注册进对应的sql作用域中,即在作用域中关联表别名和表
                }

                //关联当前数据源与sql语句
                if (!sqlInfoStack.isEmpty()&&sqlInfoStack.peek()!=null) {
                    sqlInfoStack.peek().tables.add(functionTable);
                }


                if (!leftSourceStack.isEmpty()) {
                    leftSourceStack.pop();  //弹栈，避免之前的数据干扰
                }

                leftSourceStack.push(functionTable);  //将当前表函数压入 左输入源栈中

            }

        }



        /*--------------------------------------------------------------------------------------------*/

        //除第一个数据源以外的剩下数据源

        int joinCount=ctx.joinSourcePart().size();  //获取除第一个数据源以外的数据源个数

        for(int i=0;i<joinCount;i++) {


            table = new TableInfo();   //存放表名与对应的表别名

            //最普通的物理表/视图
            if(ctx.joinSourcePart(i).tableSource()!=null){

                //判断有无库名
                if(ctx.joinSourcePart(i).tableSource().tableName().db!=null){
                    table.dataBaseName=ctx.joinSourcePart(i).tableSource().tableName().db.getText();  //记录库名

                    table.tableName=ctx.joinSourcePart(i).tableSource().tableName().tab.getText();  // 记录当前表名
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

                table.tableLevel=sqlInfoStack.size();   //记录数据源的层级,与当前sql栈 深度一致

                table.tableType= TableType.PHYSICAL; //表类型为 物理表

                if(!scopeStack.isEmpty() && scopeStack.peek()!=null){

                    scopeStack.peek().registerTable(table);   //将表注册进对应的sql作用域中,即在作用域中关联表别名和表
                }

                //关联当前数据源与sql语句
                if (!sqlInfoStack.isEmpty()&&sqlInfoStack.peek()!=null) {
                    sqlInfoStack.peek().tables.add(table);
                }

            }
            else if(ctx.joinSourcePart(i).subQuerySource()!=null){  //子查询（嵌套的 SELECT）

                table.tableName=""; //记录默认表名

                //记录当前子查询对应的表别名
                table.tableNameAlias=ctx.joinSourcePart(i).subQuerySource().id_().getText();

                table.tableLevel=sqlInfoStack.size();    //记录数据源的层级

                table.tableType=TableType.SUBQUERY;  //表类型为 子查询

                //将子查询的sql与该表进行关联
                table.subQuery=sqlInfoMap.get(ctx.joinSourcePart(i).subQuerySource().queryStatementExpression());

                if(scopeStack.peek()!=null){

                    scopeStack.peek().registerTable(table);   //将表注册进对应的sql作用域中,即在作用域中关联表别名和表
                }

                //关联当前数据源与sql语句
                if (!sqlInfoStack.isEmpty()&&sqlInfoStack.peek()!=null) {
                    sqlInfoStack.peek().tables.add(table);
                }

            }
            else if(ctx.joinSourcePart(i).virtualTableSource()!=null){    //虚拟表/内联数据，
                // TABLE (VALUES (1, 'Alice'), (2, 'Bob')) AS t (id, name)

                table.tableName="";  //记录默认表名

                //记录表别名
                table.tableNameAlias=ctx.joinSourcePart(i).virtualTableSource().tableAlias().getText();

                table.tableLevel=sqlInfoStack.size();    //记录数据源的层级

                table.tableType=TableType.VIRTUAL;  //表类型为 虚拟表/内联数据

                int id_Count= ctx.joinSourcePart(i).virtualTableSource().id_().size();  //获取并记录内联表字段的个数

                Scope sqlScope = !scopeStack.isEmpty() && sqlInfoStack.peek()!=null ?
                        scopeStack.peek():null;

                //封装内联表字段信息
                for (int j = 0; j <id_Count; j++){

                    //将字段添加进表
                    table.tableOutPutColumn.add(ctx.joinSourcePart(i).virtualTableSource().id_(j).getText());

                    if(sqlScope!=null){
                        //将字段与表的关系注册进作用域
                        if(sqlScope.columnToTableMap.containsKey(ctx.joinSourcePart(i).virtualTableSource().id_(j).getText())){
                            //该字段在该作用域中已存在，将表与该字段进行绑定

                            sqlScope.columnToTableMap.get(ctx.joinSourcePart(i).virtualTableSource().id_(j).getText())
                                    .add(table);
                        }
                        else{  //该字段不存在于作用域

                            List<TableInfo>  t =new ArrayList<>();  //构造列表
                            t.add(table);  //封装表信息
                            //将表与该字段进行绑定,并注册进作用域
                            sqlScope.columnToTableMap.put(ctx.joinSourcePart(i).virtualTableSource().id_(j).getText(),t);

                        }
                    }
                }

                if(sqlScope!=null){

                    sqlScope.registerTable(table);   //将表注册进对应的sql作用域中,即在作用域中关联表别名和表
                }

                //关联当前数据源与sql语句
                if (!sqlInfoStack.isEmpty()&&sqlInfoStack.peek()!=null) {
                    sqlInfoStack.peek().tables.add(table);
                }

            }
            else if(ctx.joinSourcePart(i).partitionedTableFunction()!=null){  //将分区表函数节点压入 表函数左输入源

                if(!functionOutputStack.isEmpty()&&functionOutputStack.peek()!=null){

                    //functionOutputStack此时被当作队列,.poll()首删
                    leftSourceStack.push(functionOutputStack.poll());     //将表压入  表函数 左输入源栈
                }
            }

            if(ctx.joinSourcePart(i).partitionedTableFunction()==null){
                if (!leftSourceStack.isEmpty()) {
                    leftSourceStack.pop();  //弹栈，避免之前的数据干扰
                }

                leftSourceStack.push(table);  //将当前表函数压入 左输入源栈中
            }



            //剩下数据源对应的表函数(UDTF)
            if(ctx.joinSourcePart(i).lateralView()!=null){

                //获取表函数的个数
                int tableLateralView= ctx.joinSourcePart(i).lateralView().size();

                for (int j = 0; j <tableLateralView; j++){

                    //处理表函数公共部分

                    TableInfo functionTable = new TableInfo();

                    functionTable.tableType=TableType.FUNCTION; //设置表类型为 表函数

                    functionTable.tableLevel=sqlInfoStack.size();    //记录表层级

                    functionTable.tableName="";  //使用默认表名

                    //获取并记录表别名
                    functionTable.tableNameAlias=ctx.joinSourcePart(i).lateralView().get(j).tableAlias().getText();

                    //关联表函数的左输入源
                    functionTable.inputSourceTable= !leftSourceStack.isEmpty()
                            && leftSourceStack.peek()!=null? leftSourceStack.peek():null;



                    //表函数输出参数显式存在，记录并处理表函数最终输出字段
                    if(ctx.joinSourcePart(i).lateralView().get(j).id_()!=null){

                        //表函数 输出参数的个数
                        int outPut =ctx.joinSourcePart(i).lateralView().get(j).id_().size();

                        for (int k = 0; k <outPut; k++){

                            ColumnRef colRef = new ColumnRef();  //用于封装字段信息

                            colRef.originTable=functionTable;  //关联输出字段与表

                            colRef.tableAlias=functionTable.tableNameAlias;  //记录字段对应数据源的别名

                            //获取并记录字段名
                            colRef.columnName=ctx.joinSourcePart(i).lateralView().get(j).id_().get(k).getText();

                            //将输出字段与表进行关联
                            functionTable.tableOutPutColumn.add(colRef.columnName);

                            if(!scopeStack.isEmpty()&&scopeStack.peek()!=null){

                                //将字段与表函数的关系注册进作用域
                                if(scopeStack.peek().columnToTableMap.containsKey(colRef.columnName)){
                                    //该字段在该作用域中已存在，将表函数与该字段进行绑定

                                    scopeStack.peek().columnToTableMap.get(colRef.columnName).add(functionTable);
                                }
                                else{  //该字段不存在于作用域

                                    List<TableInfo>  t =new ArrayList<>();   //用于封装表信息
                                    t.add(functionTable);  //记录当前表函数信息

                                    //将表函数与输出字段关联，注册进对应的scope作用域中
                                    scopeStack.peek().columnToTableMap.put(colRef.columnName,t);
                                }
                            }
                        }
                    }



                    //处理表函数 参数和函数名
                    List<ColumnRef> functionInputColumns=new ArrayList<>();  //用于封装表函数参数


                    //形式一: LATERAL VIEW explode(arr) t AS item
                    //形式二: LATERAL VIEW OUTER explode(arr) t AS item
                    if(ctx.joinSourcePart(i).lateralView().get(j).function_()!=null){

                        if(ctx.joinSourcePart(i).lateralView().get(j).function_().functionName()!=null){
                            //获取表函数的函数名
                            String tableFunctionName = ctx.joinSourcePart(i).lateralView().get(j).function_().functionName().getText();

                            //记录表函数的函数名
                            functionTable.tableFunctionName.add(tableFunctionName);

                            //表函数参数为星号
                            if(ctx.joinSourcePart(i).lateralView().get(j).function_().star!=null){

                                System.out.println("表函数参数不支持 *号");
                            }

                            //表函数参数为非星号
                            if(ctx.joinSourcePart(i).lateralView().get(j).function_().selectExpression()!=null){

                                //记录参数的个数
                                int selectExpressionCount=ctx.joinSourcePart(i).lateralView().get(j).function_().selectExpression().size();

                                List<ColumnRef>  t;

                                //收集并记录所有参数表达式对应的物理字段
                                for (int k = 0; k <selectExpressionCount; k++){

                                    //获取参数
                                    t=astHelper.extractColumnCandidates(
                                            ctx.joinSourcePart(i).lateralView().get(j).function_().selectExpression().get(k));

                                    //完善每一个参数的信息
                                    for (ColumnRef columnRef : t) {

                                        columnRef.originTable = functionTable.inputSourceTable;
                                    }

                                    functionInputColumns.addAll(t);
                                }
                            }

                            functionTable.functionInputColumns.addAll(functionInputColumns);   //将所有入参字段与表函数关联
                        }
                        else{
                            System.out.println("非法 表函数参数！");
                            return;
                        }
                    }
                    //形式三: LATERAL VIEW TABLE(VALUES (1,'a')) t(id, name)
                    //形式三已经在前面表函数的公共部分处理完了


                    if(!scopeStack.isEmpty() && scopeStack.peek()!=null){

                        scopeStack.peek().registerTable(functionTable);   //将表函数注册进对应的sql作用域中,即在作用域中关联表别名和表
                    }

                    //关联当前数据源与sql语句
                    if (!sqlInfoStack.isEmpty()&&sqlInfoStack.peek()!=null) {
                        sqlInfoStack.peek().tables.add(functionTable);
                    }

                    if (!leftSourceStack.isEmpty()) {
                        leftSourceStack.pop();  //弹栈，避免之前的数据干扰
                    }

                    leftSourceStack.push(functionTable);  //将当前表函数压入 左输入源栈中
                }

            }
        }



        // 判断当前 JoinSource 是否被 LPAREN RPAREN 包裹
        // 规则：如果父节点是 AtomjoinSourceContext，且父节点的第一个字符是 '('

        boolean isParenWrapped = false;   //标记JoinSource 是否被括号包裹

        if (ctx.getParent() instanceof HiveParser.AtomjoinSourceContext) {

            HiveParser.AtomjoinSourceContext parent =
                    (HiveParser.AtomjoinSourceContext) ctx.getParent();

            // 检查父节点的起始 子节点 是否为 LPAREN（左括号）
            if (parent.getStart() != null &&
                    parent.getStart().getType() == HiveParser.LPAREN) {
                isParenWrapped = true;
            }
        }

        if(isParenWrapped){   //被括号包裹,且为sql语句的第一个数据源

            return;
        }

        //没有被括号包裹或者不是sql语句的第一个数据源

        if(!scopeStack.isEmpty()){
            scopeStack.pop();   //当前的·scope已经处理完毕，弹出
        }
    }


    //单独处理数据源为 Hive 特有的分区表函数(PTF)的情况
    public void exitPartitionedTableFunction(HiveParser.PartitionedTableFunctionContext ctx){

        TableInfo table= new TableInfo();   //存放表名与对应的表别名

        //本质上就是 以表为参数，输出另一张表的内置函数
        table.tableName="";  //记录虚拟表的默认表名

        if(ctx.alias!=null){  //判断是否存在虚拟表的表别名

            //记录函数输出的虚拟表别名
            table.tableNameAlias=ctx.alias.getText();
        }
        else{  //虚拟表不存在别名

            //确保别名作为key存在且唯一,（但一般不用于查询）
            table.tableNameAlias=ctx.n.getText()+"--"+System.identityHashCode(ctx);
        }

        table.tableType=TableType.FUNCTION;   //类型为  表函数（分区表函数）

        table.tableLevel=sqlInfoStack.size();    //记录数据源的层级

        //获取表函数名字(分区表函数的函数名)
        table.tableFunctionName.add(ctx.n.getText());

        int outPutColumn = ctx.Identifier()!=null? ctx.Identifier().size():0;  //获取到分区表函数的最终 输出列名个数

        //分区表函数只存在一个显式的最终的输出列，且表函数名为 posexplode
        if(Objects.equals(table.tableFunctionName.getLast(), "posexplode")&&outPutColumn==1){

            //将输出列名 添加进表的输出列表中
            table.tableOutPutColumn.add(ctx.Identifier(0).getText());

            table.tableOutPutColumn.add("val");

            if(!scopeStack.isEmpty() && scopeStack.peek()!=null){

                //关联输出列与表，并将它们注册进对应的scope作用域中

                if(scopeStack.peek().columnToTableMap.containsKey(ctx.Identifier(0).getText())){
                    //该输出列名字在该作用域中已存在，将表与该名字进行绑定

                    scopeStack.peek().columnToTableMap.get(ctx.Identifier(0).getText()).add(table);
                }
                else{  //该输出列名字不存在于作用域

                    List<TableInfo>  t =new ArrayList<>();   //用于封装表信息
                    t.add(table);  //记录当前表函数信息

                    //将表与输出列关联，注册进对应的scope作用域中
                    scopeStack.peek().columnToTableMap.put(ctx.Identifier(0).getText(),t);
                }


                if(scopeStack.peek().columnToTableMap.containsKey("val")){
                    //该输出列名字在该作用域中已存在，将表与该名字进行绑定

                    scopeStack.peek().columnToTableMap.get("val").add(table);
                }
                else{  //该输出列名字不存在于作用域

                    List<TableInfo>  t =new ArrayList<>();   //用于封装表信息
                    t.add(table);  //记录当前表函数信息

                    //将表与输出列关联，注册进对应的scope作用域中
                    scopeStack.peek().columnToTableMap.put("val",t);
                }


            }
        }

        //分区表函数存在显式的最终的输出列
        else if(outPutColumn>0){
            //分区表函数存在显式的最终的输出列
            for(int i=0;i<outPutColumn;i++){

                //将输出列名 添加进表的输出列表中
                table.tableOutPutColumn.add(ctx.Identifier(i).getText());

                if(!scopeStack.isEmpty() && scopeStack.peek()!=null){

                    //关联输出列与表，并将它们注册进对应的scope作用域中

                    if(scopeStack.peek().columnToTableMap.containsKey(ctx.Identifier(i).getText())){
                        //该输出列名字在该作用域中已存在，将表与该名字进行绑定

                        scopeStack.peek().columnToTableMap.get(ctx.Identifier(i).getText()).add(table);
                    }
                    else{  //该输出列名字不存在于作用域

                        List<TableInfo>  t =new ArrayList<>();   //用于封装表信息
                        t.add(table);  //记录当前表函数信息

                        //将表与输出列关联，注册进对应的scope作用域中
                        scopeStack.peek().columnToTableMap.put(ctx.Identifier(i).getText(),t);
                    }

                }
            }
        }



        //分区表函数无显式的最终的输出列
        if(outPutColumn<=0){

            //获取各个表函数默认的输出列
            List<String>outPutColumns = this.getUdtfDefaultSelectItem(table.tableFunctionName.getLast());

            //将表函数默认的输出列与表关联，注册进对应的scope作用域
            for (String putColumn : outPutColumns) {

                table.tableOutPutColumn.add(putColumn);

                if (!scopeStack.isEmpty() && scopeStack.peek() != null) {

                    //关联输出列与表，并将它们注册进对应的scope作用域中

                    if(scopeStack.peek().columnToTableMap.containsKey(putColumn)){
                        //该输出列名字在该作用域中已存在，将表与该名字进行绑定

                        scopeStack.peek().columnToTableMap.get(putColumn).add(table);
                    }
                    else{  //该输出列名字不存在于作用域

                        List<TableInfo>  t =new ArrayList<>();   //用于封装表信息
                        t.add(table);  //记录当前表函数信息

                        //将表与输出列关联，注册进对应的scope作用域中
                        scopeStack.peek().columnToTableMap.put(putColumn,t);
                    }


                }

            }

        }

        if(scopeStack.peek()!=null){

            scopeStack.peek().registerTable(table);   //将表注册进对应的sql作用域中,即在作用域中关联表别名和表
        }

        //关联当前数据源与sql语句
        if (!sqlInfoStack.isEmpty()&&sqlInfoStack.peek()!=null) {
            sqlInfoStack.peek().tables.add(table);
        }


        /*--------------------------------------------------------------------------------------------*/

        //处理分区表函数的输入源

        if(ctx.ptfsrc.tableSource()!=null){  //输入源为 物理表

            table.inputSourceType= "TABLE";

            TableInfo functionTable= new TableInfo();   //存放输入源的表名与对应的表别名

            //判断有无库名
            if(ctx.ptfsrc.tableSource().tableName().db!=null){
                functionTable.dataBaseName=ctx.ptfsrc.tableSource().tableName().db.getText();  //记录库名

                functionTable.tableName=ctx.ptfsrc.tableSource().tableName().tab.getText();  // 记录当前表名
            }
            else{  //无库名
                functionTable.tableName=ctx.ptfsrc.tableSource().tableName().getText(); // 记录当前表名
            }


            //判断表有没有别名
            if(ctx.ptfsrc.tableSource().alias!=null){
                functionTable.tableNameAlias=ctx.ptfsrc.tableSource().alias.getText();  //记录当前表别名
            }
            else{
                functionTable.tableNameAlias="";  //记录默认表别名
            }

            functionTable.tableLevel=sqlInfoStack.size();   //记录数据源的层级,与当前sql栈 深度一致

            functionTable.tableType= TableType.PHYSICAL; //表类型为 物理表

            table.inputSourceTable=functionTable;  //将输入数据源与表进行关联
        }
        else if(ctx.ptfsrc.subQuerySource()!=null){  //输入源为 子查询

            table.inputSourceType= "SUBQUERY";

            TableInfo functionTable= new TableInfo();   //存放输入源的表名与对应的表别名

            //将输入数据源与表进行关联
            table.inputSourceSubquery=sqlInfoMap.get(ctx.ptfsrc.subQuerySource().queryStatementExpression());

            functionTable.tableName=""; //记录默认表名

            //记录当前子查询对应的表别名
            functionTable.tableNameAlias=ctx.ptfsrc.subQuerySource().id_().getText();

            functionTable.tableLevel=sqlInfoStack.size();    //记录数据源的层级

            functionTable.tableType=TableType.SUBQUERY;  //表类型为 子查询

            //将子查询的sql与该表进行关联
            functionTable.subQuery=sqlInfoMap.get(ctx.ptfsrc.subQuerySource().queryStatementExpression());

            table.inputSourceTable=functionTable;  //将输入数据源与表进行关联

        }
        else if(ctx.ptfsrc.partitionedTableFunction()!=null){  //输入源为分区表函数嵌套

            if(!functionOutputStack.isEmpty() && functionOutputStack.peek()!=null){

                table.inputSourceType= "FUNCTION";

                table.inputSourceTable=functionOutputStack.peek(); //将输入数据源与表进行关联

                functionOutputStack.pop();  //弹栈，避免之前的数据干扰
            }

        }
        else {   //输入源为常量
            return;
        }

        functionOutputStack.push(table);  //将输入源压入栈中

    }



}
