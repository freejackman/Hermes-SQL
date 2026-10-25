package org.example;


import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
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


    Deque<TableInfo> functionOutputStack = new ArrayDeque<>();   //暂存分区表函数 产生的临时表

    Deque<TableInfo> leftSourceStack = new ArrayDeque<>();   //暂存表函数对应的左输入源

    AstHelper astHelper = new AstHelper();      //AST语法树工具类,辅助语法解析


    // 用 IdentityHashMap来将节点与sql语句绑定，将该map作为参数，传递到下一次遍历中使用
    //节点采用ParserRuleContext类存储,因为其在比较的时候，默认使用 对象 的地址进行比较，
    // 因为两次遍历都是遍历的同一个parserTree，ParserRuleContext类可以保证节点的唯一性
    Map<ParserRuleContext, SqlInfo> sqlInfoMap ;  //将节点与sql语句信息进行绑定。并存储起来

    SqlInfo firstSqlInfo=null ;   //用于记录最外层的sql语句信息，方便打印


    //作用域构造类的构造函数
    public ScopeBuilderListener(Map<ParserRuleContext, SqlInfo> sqlInfoMap){

        this.sqlInfoMap = sqlInfoMap;
    }


    //获取表函数(UDTF)的默认输出列
    public List<String> getUdtfDefaultSelectItem(String functionName) {

        return switch (functionName.toLowerCase()) {
            case "explode" -> List.of("col"); // explode 默认输出列名为 "col"
            case "posexplode" -> List.of("pos", "val"); // posexplode 默认输出列名为 "pos"(元素下标),"val"(元素值)
            case "range" -> List.of("value");  //range  默认输出列名为 "value"
            default -> List.of("result"); // 其他函数的默认输出都依赖与输入参数，保守返回单列 "result",这种情况必须具有显式输出列名
        };
    }


    //将数据源注册进对应的scope作用域,并关联对应的sql语句
    public void optScopeAndSql(TableInfo table){

        if (table==null) {

            throw new IllegalArgumentException(
                    "表对象为null,无法注册到 Scope作用域中"
            );

        }

        //将表注册进对应的sql作用域中,即在作用域中关联表别名和表
        if (!scopeStack.isEmpty() && scopeStack.peek() != null) {

            scopeStack.peek().registerTable(table);
        }

        //关联当前数据源与sql语句
        if (!sqlInfoStack.isEmpty() && sqlInfoStack.peek() != null) {
            sqlInfoStack.peek().tables.add(table);
        }


    }


    //判断第一个数据源是否为LPAREN joinSource RPAREN 嵌套
    public Boolean isParenWrapped(HiveParser.JoinSourceContext ctx){

        //父节点不是AtomjoinSourceContext类
        if(!(ctx.getParent() instanceof HiveParser.AtomjoinSourceContext)){

            return false;

        }


        // 判断父节点是否是AtomjoinSourceContext类
        if (ctx.getParent() instanceof HiveParser.AtomjoinSourceContext) {
            HiveParser.AtomjoinSourceContext parent = (HiveParser.AtomjoinSourceContext) ctx.getParent();

            for(int i=0;i<parent.getChildCount();i++){

                ParseTree child = parent.getChild(i);   //获取孩子节点

                if (child != ctx) {   //定位到本节点
                    continue;
                }

                // 检查前一个兄弟节点是否是 LPAREN
                if (i == 0) {
                    return false;  // 没有前一个兄弟，不可能被包裹
                }

                ParseTree lbrother = parent.getChild(i-1);  //获取前一个兄弟节点

                if (!(lbrother instanceof TerminalNode)) {   //判断该兄弟节点是否为终端叶子节点类型
                    return false;
                }

                TerminalNode prevToken = (TerminalNode) lbrother;
                if (prevToken.getSymbol().getType() != HiveParser.LPAREN) {
                    return false;
                }

                //判断是否存在后一个兄弟节点
                if (i + 1 >= parent.getChildCount()) {
                    return false;
                }


                //判断后一个兄弟节点是否为RPAREN
                ParseTree rbrother = parent.getChild(i + 1);
                if (!(rbrother instanceof TerminalNode)) {
                    return false;
                }
                TerminalNode nextToken = (TerminalNode) rbrother;
                return nextToken.getSymbol().getType() == HiveParser.RPAREN;
            }

            // 找不到 ctx
            return false;
        }
        return false;

    }


    //将数据源压入左输入源栈leftSourceStack,供后续表函数使用
    public void pushLeftSourceStack(TableInfo table){

        if (!leftSourceStack.isEmpty()) {
            leftSourceStack.pop();  //弹栈，避免之前的数据干扰
        }

        leftSourceStack.push(table);  //将当前表函数压入 左输入源栈中
    }


    //将输出列与表函数/虚拟表(内联表) 进行关联，并将其注册进对应的Scope作用域
    public void optOutPutScopeAndTable(TableInfo table,String outPutColumn){

        if (table==null) {
            return;
        }

        if (table.tableOutPutColumn.contains(outPutColumn)) {
            return;  // 该输出列已经与表关联且注册进了Scope作用域，跳过
        }

        //将输出字段与表进行关联
        table.tableOutPutColumn.add(outPutColumn);

        if (!scopeStack.isEmpty() && scopeStack.peek() != null) {

            //将字段与表函数/虚拟表 的关系注册进作用域
            if (scopeStack.peek().columnToTableMap.containsKey(outPutColumn)) {

                //该字段在该作用域中已存在，将 表函数/虚拟表与该字段进行绑定

                if(scopeStack.peek().columnToTableMap.get(outPutColumn).contains(table)){
                    return;       //表函数/虚拟表 与该字段已经贝绑定
                }

                scopeStack.peek().columnToTableMap.get(outPutColumn).add(table);

            } else {  //该字段不存在于作用域

                List<TableInfo> t = new ArrayList<>();   //用于封装表信息
                t.add(table);  //记录当前 表函数/虚拟表 信息

                //将 表函数/虚拟表 与输出字段关联，注册进对应的scope作用域中
                scopeStack.peek().columnToTableMap.put(outPutColumn, t);
            }
        }

    }


    //处理最普通的物理表/视图
    public void optTableSource(HiveParser.TableSourceContext ctx, TableInfo table) {

        if (ctx == null) {
            return;
        }

        if (table==null)
        {
            table = new TableInfo();
        }

        //判断有无库名
        if (ctx.tableName().db != null) {
            table.dataBaseName = ctx.tableName().db.getText();  //记录库名

            table.tableName = ctx.tableName().tab.getText();  // 记录当前表名
        } else {  //无库名
            table.tableName = ctx.tableName().getText(); // 记录当前表名
        }


        //判断表有没有别名
        if (ctx.alias != null) {
            table.tableNameAlias = ctx.alias.getText();  //记录当前表别名
        } else {
            table.tableNameAlias = "";  //记录默认表别名
        }

        table.tableLevel = sqlInfoStack.size();   //记录数据源的层级,与当前sql栈 深度一致

        table.tableType = TableType.PHYSICAL; //表类型为 物理表


        //将数据源注册进对应的scope作用域,并关联对应的sql语句

        optScopeAndSql(table);
    }


    //处理子查询（嵌套的 SELECT）
    public void optSubQuerySource(HiveParser.SubQuerySourceContext ctx, TableInfo table) {

        if (ctx == null) {
            return;
        }

        if (table==null)
        {
            table = new TableInfo();
        }

        table.tableName = ""; //记录默认表名

        //记录当前子查询对应的表别名
        table.tableNameAlias = ctx.id_().getText();

        table.tableLevel = sqlInfoStack.size();    //记录数据源的层级

        table.tableType = TableType.SUBQUERY;  //表类型为 子查询

        //将子查询的sql与该表进行关联
        table.subQuery = sqlInfoMap.get(ctx.queryStatementExpression());


        //将数据源注册进对应的scope作用域,并关联对应的sql语句

        optScopeAndSql(table);

    }


    //处理 虚拟表/内联数据: TABLE (VALUES (1, 'Alice'), (2, 'Bob')) AS t (id, name)
    public void optVirtualTableSource(HiveParser.VirtualTableSourceContext ctx, TableInfo table) {

        if (ctx == null) {
            return;
        }

        if (table==null)
        {
            table = new TableInfo();
        }


        table.tableName = "";  //记录默认表名

        //记录表别名
        table.tableNameAlias = ctx.tableAlias().getText();

        table.tableLevel = sqlInfoStack.size();    //记录数据源的层级

        table.tableType = TableType.VIRTUAL;  //表类型为 虚拟表/内联数据

        int id_Count = ctx.id_().size();  //获取并记录内联表字段的个数

        if (id_Count == 0) {
            // 没有定义列名：Hive默认列名为 "col1", "col2", ...
            // 但这种情况极少见，先抛异常提示
            throw new IllegalArgumentException(
                    "虚拟表 " + table.tableNameAlias + " 未定义输出列，请使用 AS t(col1, col2,……) 格式"
            );
        }


        //将数据源注册进对应的scope作用域,并关联对应的sql语句

        optScopeAndSql(table);

        //封装内联表字段信息
        for (int i = 0; i < id_Count; i++) {

            //将输出的内敛字段信息与表关联,并注册进scope作用域
            optOutPutScopeAndTable(table,ctx.id_(i).getText());

        }
    }


    //处理表函数(UDTF)的输出参数
    public void optUdtfOutPut(HiveParser.LateralViewContext ctx,TableInfo functionTable){

        if(ctx==null){
            return;
        }

        if(functionTable==null){
            return;
        }


        //表函数输出参数显式存在，记录并处理表函数最终输出字段
        if (ctx.id_() != null) {

            //表函数 输出参数的个数
            int outPut = ctx.id_().size();

            for (int j = 0; j < outPut; j++) {

                //获取并记录字段名
                String columnName = ctx.id_().get(j).getText();

                //将输出列与表函数进行关联，并将其注册进对应的Scope作用域
                optOutPutScopeAndTable(functionTable,columnName);

            }

            // 获取表函数的函数名
            String functionName = functionTable.tableFunctionName.isEmpty() ? ""
                    : functionTable.tableFunctionName.getLast();



            //表函数显式输出参数只有一个,且表函数名为posexplode

            if (Objects.equals(functionName.toLowerCase(), "posexplode")
                    && outPut == 1) {

                //将输出列与表进行关联，并将其注册进对应的Scope作用域
                optOutPutScopeAndTable(functionTable,"val");

            }
        }
        //表函数无显式存在的输出参数
        else {

            //获取各个表函数默认的输出列
            List<String> outPutColumns = this.getUdtfDefaultSelectItem(functionTable.tableFunctionName.getLast());

            //将表函数默认的输出列与表关联，注册进对应的scope作用域
            for (String putColumn : outPutColumns) {

                //将输出列与表进行关联，并将其注册进对应的Scope作用域
                optOutPutScopeAndTable(functionTable,putColumn);

            }
        }
    }


    //处理表函数(UDTF)的输入参数(如果存在)
    public void optUdtfInPut(HiveParser.LateralViewContext ctx,TableInfo functionTable,List<ColumnRef> functionInputColumns){

        if(ctx==null){
            return;
        }

        if(functionTable==null){
            return;
        }

        if(functionInputColumns==null){
            functionInputColumns = new ArrayList<>();
        }

        if (ctx.function_() != null) {

            if (ctx.function_().functionName() != null) {
                //获取表函数的函数名
                String tableFunctionName = ctx.function_().functionName().getText();

                //记录表函数的函数名
                functionTable.tableFunctionName.add(tableFunctionName);

                //表函数参数为星号
                if (ctx.function_().star != null) {

                    throw new IllegalArgumentException(
                            "表函数参数不支持 *号"
                    );

                }

                //表函数参数为非星号
                if (ctx.function_().selectExpression() != null) {

                    //记录参数的个数
                    int selectExpressionCount = ctx.function_().selectExpression().size();

                    List<ColumnRef> t;

                    //收集并记录所有参数表达式对应的物理字段
                    for (int j = 0; j < selectExpressionCount; j++) {

                        //获取参数
                        t = astHelper.extractColumnCandidates(
                                ctx.function_().selectExpression().get(j));



                            //完善每一个参数的信息
                            for (ColumnRef columnRef : t) {

                            // 如果字段有表前缀，从 Scope 中解析真实归属
                                if (!Objects.equals(columnRef.tableAlias, "")) {

                                    // 从当前 Scope 的 aliasToTableMap 中查找表别名对应的数据源
                                    if(!scopeStack.isEmpty() && scopeStack.peek()!=null){

                                        TableInfo resolvedTable = scopeStack.peek().aliasToTableMap.get(columnRef.tableAlias);
                                        if(resolvedTable!=null){
                                            columnRef.originTable = resolvedTable;
                                        }
                                        else {
                                            throw new IllegalArgumentException(
                                                    columnRef.tableAlias+"."+columnRef.columnName+"的前缀别名在当前作用域不存在");
                                        }
                                    }
                                    else{
                                        throw new IllegalArgumentException(
                                                "当前作用域解析出错");
                                    }
                                }
                                else{
                                    //处理 无前缀的裸字段


                                    //表函数左输入源不为空
                                    if (functionTable.inputSourceTable != null) {
                                        columnRef.originTable = functionTable.inputSourceTable;
                                    }

                                    //表函数无左输入源
                                    else if(!scopeStack.isEmpty() && scopeStack.peek() != null){

                                        //获取该字段在对应作用域的表
                                        List<TableInfo> candidates = scopeStack.peek().columnToTableMap.get(columnRef.columnName);

                                        if (candidates != null && !candidates.isEmpty()) {
                                            columnRef.originTable = candidates.getFirst();  //获取该字段对应的第一张表

                                            if (candidates.size() > 1) {
                                                columnRef.isAmbiguous = true;  // 该字段归属多张表，标记歧义
                                            }

                                        }
                                        else{
                                            throw new IllegalArgumentException("无法确定字段"+columnRef.columnName+"的来源");
                                        }

                                    }
                                }
                            }

                        functionInputColumns.addAll(t);   //将收集到的输入参数信息合并
                    }
                }

                functionTable.functionInputColumns.addAll(functionInputColumns);   //将所有入参字段与表函数关联
            } else {

                throw new IllegalArgumentException(
                        "非法 表函数参数！");

            }
        }
    }


    //处理数据源后的单个表函数(UDTF)
    public void  optLateralView(HiveParser.LateralViewContext ctx,TableInfo functionTable) {

        if(ctx==null){
            return;
        }

        if (functionTable==null) {
            functionTable = new TableInfo();
        }

        functionTable.tableType = TableType.FUNCTION; //设置表类型为 表函数

        functionTable.tableLevel = sqlInfoStack.size();    //记录表层级

        functionTable.tableName = "";  //使用默认表名

        //获取并记录表别名
        functionTable.tableNameAlias = ctx.tableAlias().getText();

        //关联表函数的左输入源
        functionTable.inputSourceTable = !leftSourceStack.isEmpty()
                && leftSourceStack.peek() != null ? leftSourceStack.peek() : null;

        //表函数左输入源为空
        if(functionTable.inputSourceTable ==null){
            throw new IllegalStateException("LATERAL VIEW 缺少左输入源，SQL 格式非法: " + ctx.getText());
        }


        //处理表函数 输入参数和函数名
        List<ColumnRef> functionInputColumns = new ArrayList<>();  //用于封装表函数所有的输入参数


        //形式一: LATERAL VIEW explode(arr) t AS item
        //形式二: LATERAL VIEW OUTER explode(arr) t AS item
        optUdtfInPut(ctx,functionTable,functionInputColumns);

        //形式三: LATERAL VIEW TABLE(VALUES (1,'a')) t(id, name)



        //将数据源注册进对应的scope作用域,并关联对应的sql语句

        optScopeAndSql(functionTable);


        //处理表函数共同的输出参数部分

        optUdtfOutPut(ctx,functionTable);


        //将数据源压入左输入源栈leftSourceStack,供后续表函数使用

        pushLeftSourceStack(functionTable);

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


    //弹出sql语句,记录最外层sql语句信息，方便后续打印解析结果
    public void exitQueryStatementExpression(HiveParser.QueryStatementExpressionContext ctx){

        if (!sqlInfoStack.isEmpty()){
            this.firstSqlInfo=sqlInfoStack.pop();  //当前sql信息解析完成,弹出
        }

        // 顶层 SQL 解析完毕，清理所有临时栈
        if (sqlInfoStack.isEmpty()) {
            functionOutputStack.clear();
            leftSourceStack.clear();
            scopeStack.clear();
        }
    }


    //记录sql语句的类型，判断是否是查询列的子查询sql
    public void enterSelectStatement(HiveParser.SelectStatementContext ctx){

        //判断是否是查询列的子查询sql

        //判断 父节点是否是 SubQueryExpressionContext 类
        if(ctx.getParent() instanceof HiveParser.SubQueryExpressionContext && !sqlInfoMap.containsKey(ctx)){

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

        //记录当前sql的类型
        if (!sqlInfoStack.isEmpty() && sqlInfoStack.peek()!=null) {
            sqlInfoStack.peek().sqlType="DQL";  //sql语句为查询语句
        }
    }


    //弹出查询列的子查询sql语句
    public void exitSelectStatement(HiveParser.SelectStatementContext ctx){

        //判断当前sql语句是否是查询列的子查询
        if(sqlInfoMap.containsKey(ctx)){

            SqlInfo sqlInfo=sqlInfoMap.get(ctx);  //获取该节点对应的sql信息

            if (!sqlInfoStack.isEmpty() && sqlInfoStack.peek()== sqlInfo) {
                sqlInfoStack.pop();  //当前sql信息解析完成,弹出
            }
        }

    }



    //构建scope,完善并记录scope相关信息，将scope作用域与对应的sql语句绑定
    public void enterJoinSource(HiveParser.JoinSourceContext ctx){

        //判断第一个数据源是否为LPAREN joinSource RPAREN 嵌套
        Boolean parenWrapped =isParenWrapped(ctx);

        if (parenWrapped) {
            // 括号包裹不产生新作用域,因此直接返回
            return;
        }


        Scope sqlScope = new Scope();  //创建新的scope作用域

        if(!scopeStack.isEmpty()){
            sqlScope.parentScope=scopeStack.peek();   //关联父级scope作用域
        }

        scopeStack.push(sqlScope);  //将当前scope压入栈

        sqlScope.scopeLevel=sqlInfoStack.size();  //更新sqlScope对应的层级

        if (!sqlInfoStack.isEmpty() && sqlInfoStack.peek()!=null){

            sqlInfoStack.peek().sqlScope=sqlScope;   //将scope作用域与对应的sql语句绑定
        }


    }


    //处理不同的数据源(除分区表函数以外),以及表函数,弹出当前scope
    public void exitJoinSource(HiveParser.JoinSourceContext ctx) {

        /*--------------------------------------------------------------------------------------------*/

        //第一个数据源

        TableInfo table= new TableInfo();   //存放表名与对应的表别名

        //最普通的物理表/视图
        if(ctx.atomjoinSource().tableSource()!=null){
            optTableSource(ctx.atomjoinSource().tableSource(),table);
        }
        else if(ctx.atomjoinSource().subQuerySource()!=null){  //子查询（嵌套的 SELECT）
            optSubQuerySource(ctx.atomjoinSource().subQuerySource(),table);
        }
        else if(ctx.atomjoinSource().virtualTableSource()!=null){
            optVirtualTableSource(ctx.atomjoinSource().virtualTableSource(),table);
        }
        else if( ctx.atomjoinSource().partitionedTableFunction()!=null){  //将分区表函数节点压入 表函数左输入源

            if(!functionOutputStack.isEmpty()&&functionOutputStack.peek()!=null){

                if (!leftSourceStack.isEmpty()) {
                    leftSourceStack.pop();
                }

                leftSourceStack.push(functionOutputStack.pop());     //将表压入  表函数 左输入源栈
            }
        }

        //将表函数的左输入源压入栈(在数据源是非分区表函数的情况下)
        if(ctx.atomjoinSource().partitionedTableFunction()==null) {

            //将数据源压入左输入源栈leftSourceStack,供后续表函数使用

            pushLeftSourceStack(table);

        }




        //第一个数据源对应的表函数(UDTF)
        if(ctx.atomjoinSource().lateralView()!=null) {

            //获取表函数的个数
            int tableLateralView = ctx.atomjoinSource().lateralView().size();

            for (int i = 0; i < tableLateralView; i++) {


                TableInfo functionTable = new TableInfo();  //封装表函数

                optLateralView(ctx.atomjoinSource().lateralView().get(i), functionTable);

            }
        }


        /*--------------------------------------------------------------------------------------------*/

        //除第一个数据源以外的剩下数据源

        int joinCount=ctx.joinSourcePart().size();  //获取除第一个数据源以外的数据源个数

        for(int i=0;i<joinCount;i++) {


            table = new TableInfo();   //存放表名与对应的表别名

            //最普通的物理表/视图
            if(ctx.joinSourcePart(i).tableSource()!=null){
                optTableSource(ctx.joinSourcePart(i).tableSource(),table);
            }
            else if(ctx.joinSourcePart(i).subQuerySource()!=null){  //子查询（嵌套的 SELECT）

                optSubQuerySource(ctx.joinSourcePart(i).subQuerySource(),table);
            }
            else if(ctx.joinSourcePart(i).virtualTableSource()!=null){
                optVirtualTableSource(ctx.joinSourcePart(i).virtualTableSource(),table);
            }
            else if(ctx.joinSourcePart(i).partitionedTableFunction()!=null){  //将分区表函数节点压入 表函数左输入源

                if(!functionOutputStack.isEmpty()&&functionOutputStack.peek()!=null){

                    if (!leftSourceStack.isEmpty()) {
                        leftSourceStack.pop();
                    }

                    leftSourceStack.push(functionOutputStack.pop());     //将表压入  表函数 左输入源栈
                }
            }

            if(ctx.joinSourcePart(i).partitionedTableFunction()==null){

                //将数据源压入左输入源栈leftSourceStack,供后续表函数使用

                pushLeftSourceStack(table);
            }



            //剩下数据源对应的表函数(UDTF)
            if(ctx.joinSourcePart(i).lateralView()!=null){

                //获取表函数的个数
                int tableLateralView= ctx.joinSourcePart(i).lateralView().size();

                for (int j = 0; j <tableLateralView; j++) {

                    //遍历处理每一个表函数

                    TableInfo functionTable = new TableInfo();    //封装表函数

                    optLateralView(ctx.joinSourcePart(i).lateralView().get(j), functionTable);
                }


            }
        }



        //判断第一个数据源是否为LPAREN joinSource RPAREN 嵌套
        Boolean parenWrapped =isParenWrapped(ctx);

        if (parenWrapped) {

            //LPAREN joinSource RPAREN 嵌套的情况下，后面不会跟表函数，因此原本压入左输入源栈的数据必须弹出
            if (!leftSourceStack.isEmpty()) {
                leftSourceStack.pop();  // 清理括号内产生的左源
            }

            // 括号包裹不产生新作用域,因此直接返回
            return;
        }


        //解析结束，弹出scope作用域
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

        //将数据源注册进对应的scope作用域,并关联对应的sql语句

        optScopeAndSql(table);

        //分区表函数只存在一个显式的最终的输出列，且表函数名为 posexplode
        if(Objects.equals(table.tableFunctionName.getLast().toLowerCase(), "posexplode")&&outPutColumn==1){


            //将输出列与表进行关联，并将其注册进对应的Scope作用域
            optOutPutScopeAndTable(table,ctx.Identifier(0).getText());

            //将输出列与表进行关联，并将其注册进对应的Scope作用域
            optOutPutScopeAndTable(table,"val");

        }

        //分区表函数存在显式的最终的输出列
        else if(outPutColumn>0){
            //分区表函数存在显式的最终的输出列
            for(int i=0;i<outPutColumn;i++){

                //将输出列与表进行关联，并将其注册进对应的Scope作用域
                optOutPutScopeAndTable(table,ctx.Identifier(i).getText());

            }
        }



        //分区表函数无显式的最终的输出列
        if(outPutColumn<=0){

            //获取各个表函数默认的输出列
            List<String>outPutColumns = this.getUdtfDefaultSelectItem(table.tableFunctionName.getLast());

            //将表函数默认的输出列与表关联，注册进对应的scope作用域
            for (String putColumn : outPutColumns) {

                //将输出列与表进行关联，并将其注册进对应的Scope作用域
                optOutPutScopeAndTable(table,putColumn);

            }
        }



        /*--------------------------------------------------------------------------------------------*/

        //处理分区表函数的输入源

        if(ctx.ptfsrc.tableSource()!=null){  //输入源为 物理表

            table.inputSourceType= "TABLE";

            //记录表别名,库名，表名
            String inputAlias = "";
            String  inputDb="";
            String  inputTableName="";


            //判断有无库名
            if(ctx.ptfsrc.tableSource().tableName().db!=null){
                inputDb=ctx.ptfsrc.tableSource().tableName().db.getText();  //记录库名

                inputTableName=ctx.ptfsrc.tableSource().tableName().tab.getText();  // 记录当前表名
            }
            else{  //无库名
                inputTableName=ctx.ptfsrc.tableSource().tableName().getText(); // 记录当前表名
            }


            //判断表有没有别名
            if(ctx.ptfsrc.tableSource().alias!=null){
                inputAlias=ctx.ptfsrc.tableSource().alias.getText();  //记录当前表别名
            }


            TableInfo existingTable = null;  //用于记录已经注册的表

            //判断该输入源是否已经注册
            if(!scopeStack.isEmpty()&&scopeStack.peek()!=null) {
                Scope scope = scopeStack.peek();

                //输入源存在表别名
                if(!Objects.equals(inputAlias, "")){

                    //匹配到了表
                    if(scope.aliasToTableMap.containsKey(inputAlias)){
                        existingTable = scope.aliasToTableMap.get(inputAlias);
                    }

                }
                //输入源存在库名.表名
                else if(!Objects.equals(inputDb, "") && !Objects.equals(inputTableName, "")){

                    //匹配到了表
                    if(scope.aliasToTableMap.containsKey(inputDb+"."+inputTableName)){
                        existingTable = scope.aliasToTableMap.get(inputDb+"."+inputTableName);
                    }

                }
                //输入源存在表名
                else if(!Objects.equals(inputTableName, "")){

                    //匹配到了表
                    if(scope.aliasToTableMap.containsKey(inputTableName)){
                        existingTable = scope.aliasToTableMap.get(inputTableName);
                    }

                }
                //输入源信息解析失败，抛出异常
                else{
                    throw new IllegalArgumentException(
                            "分区表函数的输入源解析失败"
                    );
                }
            }


            if(existingTable!=null){   //输入源在当前scope作用域中已注册
                table.inputSourceTable=existingTable;  //将输入数据源与表进行关联
            }
            else{   //输入源在当前scope作用域中未注册

                TableInfo functionTable= new TableInfo();   //存放输入源的表名与对应的表别名

                //记录表名，表别名，库名
                functionTable.dataBaseName=inputDb;
                functionTable.tableName=inputTableName;
                functionTable.tableNameAlias=inputAlias;

                functionTable.tableLevel=sqlInfoStack.size();   //记录数据源的层级,与当前sql栈 深度一致

                functionTable.tableType= TableType.PHYSICAL; //表类型为 物理表

                table.inputSourceTable=functionTable;  //将输入数据源与表进行关联

                optScopeAndSql(functionTable);  //将物理表注册进scope作用域以及关联对应的sql语句

            }

        }
        else if(ctx.ptfsrc.subQuerySource()!=null){  //输入源为 子查询

            table.inputSourceType= "SUBQUERY";

            //将输入数据源与表进行关联
            table.inputSourceSubquery=sqlInfoMap.get(ctx.ptfsrc.subQuerySource().queryStatementExpression());




        }
        else if(ctx.ptfsrc.partitionedTableFunction()!=null){  //输入源为分区表函数嵌套

            if(!functionOutputStack.isEmpty() && functionOutputStack.peek()!=null){

                table.inputSourceType= "FUNCTION";

                table.inputSourceTable=functionOutputStack.pop(); //将输入数据源与表进行关联,同时弹栈，避免之前的数据干扰

            }

        }
        else {   //输入源为常量
            return;
        }

        functionOutputStack.push(table);  //将输入源压入栈中

    }

}
