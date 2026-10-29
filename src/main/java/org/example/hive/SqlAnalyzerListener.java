package org.example.hive;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.example.entity.datamodel.*;
import org.example.hive.HiveParser;
import org.example.hive.HiveParserBaseListener;
import org.example.util.AstHelper;

import java.util.*;


//该类用于第二次遍历语法树,利用第一阶段建好的 Scope，把sql语句里的每一个查询列，精准解析并填充到对应位置
public class SqlAnalyzerListener extends HiveParserBaseListener {


    public Deque<SqlInfo> sqlInfoStack = new ArrayDeque<>();  //用栈来记录和管理sql语句

    public Deque<Scope> scopeStack = new ArrayDeque<>();  //用栈来记录和管理作用域

    public Deque<SelectItem> selectItemStack = new ArrayDeque<>();  //用栈来记录和管理查询列

    public Deque<FunctionInfo> functionStack = new ArrayDeque<>();  //用于确定并关联函数之间的父子关系

    public List<FunctionInfo> functions=new ArrayList<>();  //临时存储单个查询列的函数

    AstHelper astHelper = new AstHelper();      //AST语法树工具类,辅助语法解析

    //用于接收第一阶段构建好的sqlinfo以及对应的scope作用域
    Map<ParserRuleContext, SqlInfo> sqlInfoMap ;

    //用于判断 当前是否在 窗口函数当中,避免将 over()中的字段当作函数参数挂载上去
    private boolean isInsideWindowSpec = false;




    //构造函数
    public SqlAnalyzerListener(Map<ParserRuleContext, SqlInfo> sqlInfoMap) {

        this.sqlInfoMap = sqlInfoMap;
    }


    //获取即将解析的sql语句和其对应的作用域
    public void pushSqlAndScope(ParseTree ctx){

        sqlInfoStack.push(sqlInfoMap.get(ctx));   //将目标sql信息压入栈

        if(sqlInfoMap.get(ctx).sqlScope!=null){
            scopeStack.push(sqlInfoMap.get(ctx).sqlScope);  //将目标sql对应的scope作用域压入栈
        }
        //sql对应的scope作用域为null
        else{
            throw new IllegalArgumentException(
                    "sql["+ctx.getText()+"]对应的scope作用域为null,阶段一解析异常"
            );
        }

    }

    //处理已经解析完毕的sql语句和其对应的作用域
    public void popSqlAndScope(ParseTree ctx){

        if(!sqlInfoStack.isEmpty() && sqlInfoStack.peek()!=null
                &&sqlInfoStack.peek()==sqlInfoMap.get(ctx)){

            sqlInfoStack.pop();   //sql信息补充，解析完毕.弹出
        }
        else{
            throw new IllegalArgumentException("sql["+ctx.getText()+"]在二阶段解析过程中发生错误");
        }


        if(!scopeStack.isEmpty() && scopeStack.peek()!=null
                && sqlInfoMap.get(ctx).sqlScope!=null
                &&scopeStack.peek()==sqlInfoMap.get(ctx).sqlScope){

            scopeStack.pop();    //scope作用域信息补充，解析完毕，弹出
        }
        else{
            throw new IllegalArgumentException("sql["+ctx.getText()+"]对应的scope作用域在二阶段解析过程中发生错误");
        }

    }


    //处理函数嵌套情况，函数参数为查询列的表达式
    //时间间隔
    //cast,case,floor
    public void optExpressArgument(ParseTree ctx,FunctionInfo functionInfo,FunctionArgument functionArgument){

        functionInfo.parentFunction=functionStack.peek();  //关联父函数
        functionInfo.isNested=true;  //更新嵌套函数标记

        functionArgument.rawText=ctx.getText();  //获取参数原始文本
        functionArgument.expression=functionInfo;    //关联表达式与父函数参数

        functionInfo.parentFunction.arguments.add(functionArgument);   //关联父函数与父函数参数
    }


    //处理case 多源表达式 的 不同位置的expression,字段级颗粒度
    public void optCaseExpression(ParseTree ctx,FunctionArgument functionArgument){

        // 向上找到最近的 CaseExpressionContext类或WhenExpressionContext类
        ParseTree current = ctx.getParent();
        HiveParser.CaseExpressionContext caseCtx = null;
        HiveParser.WhenExpressionContext whenCtx = null;

        while (current != null) {
            if (current instanceof HiveParser.CaseExpressionContext) {
                caseCtx = (HiveParser.CaseExpressionContext) current;
                break;
            }
            if (current instanceof HiveParser.WhenExpressionContext) {
                whenCtx = (HiveParser.WhenExpressionContext) current;
                break;
            }
            current = current.getParent();
        }

        if (caseCtx == null&& whenCtx == null) {
            throw new IllegalArgumentException("CASE 表达式解析异常：找不到 Case 祖先");
        }

        // 判断找到的是 whenCtx还是caseCtx
        ParseTree anchor = (caseCtx != null) ? caseCtx : whenCtx;

        //caseCtx为 CaseExpressionContext类节点
        //whenCtx为 WhenExpressionContext类节点
        // 遍历 caseCtx 或 whenCtx 的所有子节点，找到包含 ctx 的那个 ExpressionContext
        // 然后看它前面的关键字是 KW_CASE / KW_WHEN / KW_THEN / KW_ELSE

        //关键：从 ctx 逐层向上，找到 caseCtx 或 whenCtx 的直接子节点
        ParseTree child = ctx;
        ParseTree parent = ctx.getParent();
        while (parent != anchor) {
            child = parent;
            parent = parent.getParent();
        }



        // 找到 child 在 caseCtx 或 whenCtx 中的索引
        int idx = -1;
        for (int i = 0; i < anchor.getChildCount(); i++) {
            if (anchor.getChild(i) == child) {
                idx = i;
                break;
            }
        }

        if (idx <= 0) {
            throw new IllegalArgumentException("CASE 子节点定位失败");
        }

        //检查前一个兄弟节点
        ParseTree lbrother = anchor.getChild(idx - 1);

        //判断是否是终端节点
        if (!(lbrother instanceof TerminalNode)) {
            throw new IllegalArgumentException("CASE 子表达式前置节点不是 token");
        }
        int tokenType = ((TerminalNode) lbrother).getSymbol().getType();

        if (tokenType == HiveParser.KW_CASE) {
            functionArgument.type = ArgumentType.CASE_BASE;
        } else if (tokenType == HiveParser.KW_WHEN) {
            functionArgument.type = ArgumentType.CASE_WHEN_COND;
        } else if (tokenType == HiveParser.KW_THEN) {
            functionArgument.type = ArgumentType.CASE_WHEN_RESULT;
        } else if (tokenType == HiveParser.KW_ELSE) {
            functionArgument.type = ArgumentType.CASE_ELSE;
        } else {
            throw new IllegalArgumentException("CASE 子表达式前置 token 未知：" + lbrother.getText());
        }
    }


    //处理表达式对应的虚拟函数的相关信息
    public void optExpressFunctionInfo(FunctionInfo functionInfo){

        if(!selectItemStack.isEmpty()&& selectItemStack.peek()!=null){

            //更新函数层级,与查询列层级保持一致
            functionInfo.functionLever=selectItemStack.peek().selectItemLevel;
        }

        functions.add(functionInfo);   //将函数添加进函数列表

        functionStack.push(functionInfo); // 将函数压入栈

    }


    //处理最终物理来源字段列表中的来源字段 的来源为 表函数和分区表函数
    //血缘穿透：如果字段归属到 FUNCTION 类型的虚拟表（如 表函数LATERAL VIEW或分区表函数PTF 产生的），
    // 穿透到该虚拟表的输入参数，用输入参数替换当前的来源。
    private void resolveVirtualColumnOrigin(SelectItem selectItem) {

        List<ColumnRef> resolved = new ArrayList<>();   //存放所有字段处理的结果

        //遍历查询列的 最终物理来源字段列表
        for (ColumnRef col : selectItem.sourceColumns) {

            TableInfo origin = col.originTable;  //获取到字段的 来源

            // 只处理 FUNCTION 类型（LATERAL VIEW 表函数 / PTF分区表函数）
            if (origin != null && origin.tableType == TableType.FUNCTION) {

                TableInfo inputSource = origin.inputSourceTable;

                // 有输入源+ 获取输入源的输入字段信息 → 穿透
                if (inputSource != null && !origin.functionInputColumns.isEmpty()) {

                    for (ColumnRef inputCol : origin.functionInputColumns) {

                        ColumnRef newCol = new ColumnRef();  //用于封装真实来源信息


                        // 优先用 inputCol 自带的 originTable；否则用 inputSource
                        newCol.originTable = inputCol.originTable != null
                                ? inputCol.originTable : inputSource;

                        //记录物理字段名和表别名(前缀)
                        newCol.columnName = inputCol.columnName;
                        newCol.tableAlias = inputCol.tableAlias;

                        //无前缀
                        if (newCol.tableAlias == null || newCol.tableAlias.isEmpty()) {

                            if (newCol.originTable != null && !newCol.originTable.tableNameAlias.isEmpty()) {

                                newCol.tableAlias = newCol.originTable.tableNameAlias;
                            }
                        }

                        //记录这个封装好的物理字段信息
                        resolved.add(newCol);
                    }

                    // 经过 表函数和分区表函数 加工 → SIMPLE
                    selectItem.transformType = TransformType.SIMPLE;

                    continue;
                }
            }

            // 其他的最终物理来源字段，保持原样
            resolved.add(col);
        }

        selectItem.sourceColumns.clear();  //原来的最终物理来源字段被清除

        selectItem.sourceColumns.addAll(resolved);
    }

    //确定并封装各个字段的数据来源
    public void optColumnRefsList(List<ColumnRef> cols){

        for (ColumnRef columnRef : cols) {

            String tableAlias = columnRef.tableAlias;    //获取物理字段对应的表的别名或表名

            //表别名或表名存在
            if (!Objects.equals(tableAlias, "")) {

                if (!scopeStack.isEmpty() && scopeStack.peek() != null) {

                    //在对应作用域中获取到查询列对应的表
                    if (scopeStack.peek().aliasToTableMap.containsKey(tableAlias)) {

                        TableInfo tableInfo = scopeStack.peek().aliasToTableMap.get(tableAlias);

                        columnRef.originTable = tableInfo;   //将最终来源字段与表进行关联

                    } else {
                        System.err.println("[WARN] JOIN ON 中的字段前缀 [" + tableAlias + "] 未找到，跳过归属绑定");
                    }
                }

            }

            //表别名或表名不存在(裸字段)
            else {

                if (!scopeStack.isEmpty() && scopeStack.peek() != null) {

                    //在当前对应的scope作用域查找物理字段对应的表
                    if (scopeStack.peek().columnToTableMap.containsKey(columnRef.columnName)) {

                        List<TableInfo> tableInfos = scopeStack.peek().columnToTableMap.get(columnRef.columnName);

                        //物理字段只对应一张表
                        if (tableInfos.size() == 1) {

                            //将最终来源字段与表进行关联
                            columnRef.originTable = tableInfos.getFirst();
                        }

                        //物理字段只对应多张表,字段存在歧义
                        else if (tableInfos.size() > 1) {
                            columnRef.isAmbiguous = true;  //标记 字段存在歧义

                        } else {
                            throw new IllegalArgumentException("阶段一作用域字段与表关系columnToTableMap解析错误");
                        }

                    } else {
                        throw new IllegalArgumentException("物理字段[" + columnRef.columnName + "]在作用域中不存在");
                    }
                }

            }
        }
    }

    // 统一构建并封装 FilterCondition（包含对常量的处理）
    private void buildAndAddCondition(ParseTree leftNode, ParseTree opNode, ParseTree rightNode, List<FilterCondition> conditions,boolean isWhere) {

        FilterCondition condition = new FilterCondition();

        //获取并封装操作符
        condition.operator = opNode.getText();

        //记录原始文本
        condition.rawConditionText = leftNode.getText() + " " + condition.operator + " " + rightNode.getText();

        // 1. 提取常规字段（如果是子查询，这里提取不出来，会返回 一个空数组）
        List<ColumnRef> leftCols = astHelper.extractColumnCandidates(leftNode);
        List<ColumnRef> rightCols = astHelper.extractColumnCandidates(rightNode);

        //确定并分装各字段的数据来源
        optColumnRefsList(leftCols);
        optColumnRefsList(rightCols);

        //将左右各字段与join on 条件关联
        condition.leftColumns =  leftCols;
        condition.rightColumns = rightCols;

        // 2. 提取左侧可能存在的子查询
        findSubQueriesInNode(leftNode, condition.leftSubQueries);

        // 3. 提取右侧可能存在的子查询
        findSubQueriesInNode(rightNode, condition.rightSubQueries);

        // 4. 过滤：只要左边有字段/子查询，或者右边有字段/子查询，就记录下来（防止其中有一边是常量而导致整体被遗漏）
        boolean hasLeft = !condition.leftColumns.isEmpty() || !condition.leftSubQueries.isEmpty();
        boolean hasRight = !condition.rightColumns.isEmpty() || !condition.rightSubQueries.isEmpty();

        //判断是否是where 条件
        if (isWhere && hasLeft && hasRight) {

            if(!condition.leftColumns.isEmpty()&&!condition.rightColumns.isEmpty()){

                ColumnRef leftCol = condition.leftColumns.getFirst();
                ColumnRef rightCol = condition.rightColumns.getFirst();

                // 两边都有归属表，且不是同一张表，才是隐式 JOIN
                if (leftCol.originTable != null && rightCol.originTable != null
                        && leftCol.originTable != rightCol.originTable) {
                    condition.isImplicitJoin = true;
                }
            }
        }

        if (hasLeft || hasRight) {
            conditions.add(condition);
        }
    }

    // 处理join on 或where 条件中的子查询
    private void findSubQueriesInNode(ParseTree node, List<SqlInfo> targetList) {

        if (node == null) return;

        // 命中子查询
        if (node instanceof HiveParser.SubQueryExpressionContext) {

            // 子查询必定包裹着 selectStatement
            HiveParser.SelectStatementContext selectCtx =
                    ((HiveParser.SubQueryExpressionContext) node).selectStatement();

            // 从 map 中取出第一阶段建好的 SqlInfo
            if (selectCtx != null && sqlInfoMap.containsKey(selectCtx)) {
                targetList.add(sqlInfoMap.get(selectCtx));
            }
            return; // 找到了就不再往下递归，防止重复提取
        }

        // 没找到就继续遍历子节点
        for (int i = 0; i < node.getChildCount(); i++) {
            findSubQueriesInNode(node.getChild(i), targetList);
        }
    }

    //处理join on 或 where 后面的条件
    public List<FilterCondition> optJoinAndWhereExpression(ParseTree node, boolean isWhere){

        List<FilterCondition> filterConditions = new ArrayList<>();

        if (node == null) return filterConditions;

        // 1. 处理 AND / OR 逻辑运算符
        if (node instanceof HiveParser.PrecedenceAndExpressionContext ||
                node instanceof HiveParser.PrecedenceOrExpressionContext) {

            for (int i = 0; i < node.getChildCount(); i++) {
                ParseTree child = node.getChild(i);
                // 跳过 AND / OR 操作符节点本身，继续向下递归
                if (!(child instanceof HiveParser.PrecedenceAndOperatorContext) &&
                        !(child instanceof HiveParser.PrecedenceOrOperatorContext)) {
                    filterConditions.addAll(optJoinAndWhereExpression(child,isWhere));
                }
            }
            return filterConditions;
        }


        // 2. 处理等值操作符 (=, !=, <>) [ 子节点顺序为 左表达式, 操作符, 右表达式]
        if (node instanceof HiveParser.PrecedenceEqualExpressionContext) {

            //操作符 = 存在
            if(node.getChildCount() > 1){
                for (int i = 1; i + 1 < node.getChildCount(); i += 2) {
                    ParseTree leftNode = node.getChild(i - 1);
                    ParseTree opNode = node.getChild(i);
                    ParseTree rightNode = node.getChild(i + 1);

                    //封装join on 或 where 条件
                    buildAndAddCondition(leftNode, opNode, rightNode, filterConditions,isWhere);
                }
                return filterConditions;
            }

            //操作符== 不存在，可能是 不等式或者单个数值
        }


        // 3. 处理非等值操作符 (>, <, >=, <=, LIKE, IN)
        // hive语法: precedenceSimilarExpressionMain :
        // a = precedenceBitwiseOrExpression part = precedenceSimilarExpressionPart?
        if (node instanceof HiveParser.PrecedenceSimilarExpressionMainContext) {
            HiveParser.PrecedenceSimilarExpressionMainContext mainCtx
                    = (HiveParser.PrecedenceSimilarExpressionMainContext) node;

            ParseTree leftNode = mainCtx.getChild(0); // 左边表达式

            // 如果存在 part（说明有操作符）
            if (mainCtx.part != null && mainCtx.part.getChildCount() >= 2) {
                ParseTree part = mainCtx.part;
                ParseTree child0 = part.getChild(0);

                // 情况 1: 比较操作符 (>, <, ,>=,<=,LIKE)
                if (child0 instanceof HiveParser.PrecedenceSimilarOperatorContext) {
                    ParseTree opNode = child0;

                    ParseTree rightNode = part.getChild(1); // 右边表达式

                    //封装join on 或 where 条件
                    buildAndAddCondition(leftNode, opNode, rightNode, filterConditions,isWhere);
                }


                else if (child0 instanceof HiveParser.PrecedenceSimilarExpressionAtomContext) {

                    HiveParser.PrecedenceSimilarExpressionAtomContext atomCtx =
                            (HiveParser.PrecedenceSimilarExpressionAtomContext) child0;

                    // 情况 2: IN 操作符
                    if (atomCtx.KW_IN() != null) {

                        ParseTree opNode = atomCtx.getChild(0); // KW_IN 节点

                        ParseTree rightNode = atomCtx.getChild(1); // IN 后面的括号或子查询

                        buildAndAddCondition(leftNode, opNode, rightNode, filterConditions,isWhere);
                    }

                    //情况3: between and
                    else if(atomCtx.KW_BETWEEN() != null) {

                        ParseTree rightNode = atomCtx.getChild(1); // KW_BETWEEN 后面的值

                        ParseTree andright = atomCtx.getChild(3);  //KW_AND 后面的值

                        //特殊情况,直接处理，没有使用封装函数
                        FilterCondition condition = new FilterCondition();

                        condition.operator = "BETWEEN";
                        condition.rawConditionText =
                                leftNode.getText() + " BETWEEN " + rightNode.getText()
                                        + " AND " + andright.getText();

                        condition.leftColumns = astHelper.extractColumnCandidates(leftNode);
                        optColumnRefsList(condition.leftColumns);

                        // 右边between后面的值和and后面的值都放到一起
                        condition.rightColumns = astHelper.extractColumnCandidates(rightNode);
                        condition.rightColumns.addAll(astHelper.extractColumnCandidates(andright));
                        optColumnRefsList(condition.rightColumns);


                        // between and  之间可能为 子查询
                        findSubQueriesInNode(rightNode, condition.rightSubQueries);
                        findSubQueriesInNode(andright, condition.rightSubQueries);

                        //判断是否是where 条件
                        if (isWhere) {

                            if(!condition.leftColumns.isEmpty()&&!condition.rightColumns.isEmpty()){

                                ColumnRef leftCol = condition.leftColumns.getFirst();
                                ColumnRef rightCol = condition.rightColumns.getFirst();

                                // 两边都有归属表，且不是同一张表，才是隐式 JOIN
                                if (leftCol.originTable != null && rightCol.originTable != null
                                        && leftCol.originTable != rightCol.originTable) {
                                    condition.isImplicitJoin = true;
                                }
                            }
                        }

                        filterConditions.add(condition);

                    }

                    //情况4: like xxx
                    else if(atomCtx.KW_LIKE() != null) {

                        ParseTree opNode = atomCtx.getChild(0);  //KW_LIKE 节点

                        ParseTree rightNode = atomCtx.getChild(2); // KW_LIKE (KW_ANY | KW_ALL) 后面的值

                        buildAndAddCondition(leftNode, opNode, rightNode, filterConditions,isWhere);
                    }

                }
            }
            return filterConditions; // 提取完直接返回，防止重复递归
        }

        // 4. 保底递归（防止漏掉深层嵌套的括号等）
        for (int i = 0; i < node.getChildCount(); i++) {
            filterConditions.addAll(optJoinAndWhereExpression(node.getChild(i),isWhere));
        }
        return filterConditions;
    }


    //查找对应的sql语句信息sqlinfo和作用域scope
    public void enterQueryStatementExpression(HiveParser.QueryStatementExpressionContext ctx){

        //匹配到了目标sql
        if(sqlInfoMap.containsKey(ctx)){
            pushSqlAndScope(ctx);  //获取即将解析的sql语句和其对应的作用域
        }
        //未匹配到目标sql
        else {
            throw new IllegalArgumentException(
                    "未匹配到目标sql["+ctx.getText()+"]"
            );
        }
    }


    //弹出对应sql信息和scope作用域，清理同层级的函数列表,避免数据源中的表函数干扰
    public void exitQueryStatementExpression(HiveParser.QueryStatementExpressionContext ctx)  {

        //清理同层级或高层级的函数列表,避免数据源中的表函数干扰
        functions.removeIf(f -> f.functionLever >= sqlInfoStack.size());

        if(sqlInfoMap.containsKey(ctx) ) {
            popSqlAndScope(ctx);   //处理已经解析完毕的sql语句和其对应的作用域
        }
        else{
            throw new IllegalArgumentException("sql["+ctx.getText()+"]在二阶段解析过程中丢失");
        }
    }


    //处理查询列中的子查询
    public void enterSelectStatement(HiveParser.SelectStatementContext ctx){

        //判断是否是查询列的子查询sql语句
        if(sqlInfoMap.containsKey(ctx)){
            pushSqlAndScope(ctx);  //获取即将解析的sql语句和其对应的作用域
        }
    }



    //弹出查询列对应的子查询sql语句与scope作用域,
    //将子查询sql与函数参数关联（如果子查询sql是函数参数的话）,
    // 关联子查询sql与对应的查询列,清理同层级的函数列表,
    // 避免查询列的子查询的数据源中的表函数干扰
    public void exitSelectStatement(HiveParser.SelectStatementContext ctx){

        if (selectItemStack.isEmpty()){
            //清理同层级和高层级的函数列表,避免数据源中的表函数干扰
            functions.removeIf(f -> f.functionLever >= sqlInfoStack.size());
        }

        //判断该sql语句是否为 查询列的子查询sql语句
        if(sqlInfoMap.containsKey(ctx)){

            SqlInfo sqlInfo=sqlInfoMap.get(ctx);  //获取该节点对应的sql信息

            if(!selectItemStack.isEmpty()&& selectItemStack.peek()!=null){

                // 总是挂到 selectItemSubQueries，保证血缘合并
                selectItemStack.peek().selectItemSubQueries.add(sqlInfo);

                // 如果同时是函数参数，额外再挂一份给函数
                if(!functionStack.isEmpty() && functionStack.peek()!=null
                        && functionStack.peek().functionLever == selectItemStack.peek().selectItemLevel){

                    if(!functionStack.peek().arguments.isEmpty()){
                        functionStack.peek().arguments.getLast().subQueries=sqlInfo;
                    }
                }
            }
            popSqlAndScope(ctx);   //处理已经解析完毕的sql语句和其对应的作用域
        }
    }



    //创建并封装查询列信息
    public void enterSelectItem(HiveParser.SelectItemContext ctx) {

        SelectItem selectItem = new SelectItem();  //封装查询列信息

        selectItem.normalizedExpression = ctx.getText();  //获取标准化后的查询列（整个表达式文本）

        selectItem.selectItemLevel=sqlInfoStack.size();   //记录查询列层级

        List<ColumnRef> columnRefs=null;   //存放该查询列提取出来的 最终依赖的物理字段列表（多个表示多源组合）


        //该查询列为星号*
        if(ctx.tableAllColumns()!=null){

            //获取最终依赖的物理字段列表
            columnRefs = astHelper.extractColumnCandidates(ctx.tableAllColumns());

            //封装并记录 最终来源的物理字段
            if(!columnRefs.isEmpty()){

                String tableAlias=columnRefs.getFirst().tableAlias;    //获取物理字段对应的表的别名或表名

                //表别名或表名存在
                if(!Objects.equals(tableAlias, "")){

                    if(!scopeStack.isEmpty()&&scopeStack.peek()!=null){

                        //在对应作用域中获取到星号*对应的表
                        if(scopeStack.peek().aliasToTableMap.containsKey(tableAlias)){

                            TableInfo tableInfo=scopeStack.peek().aliasToTableMap.get(tableAlias);


                            //将 来源字段与表关联
                            columnRefs.getFirst().originTable=tableInfo;

                            //暂定，接入元数据后，删除
                            selectItem.sourceColumns.add(columnRefs.getFirst());


                            //接入元数据，遍历scope作用域中对应的元数据表的每个字段，
                            //将所有字段都封装注册进查询列selectItem.sourceColumns中
                            //将所有字段封装进对应表信息的 输出字段列表 中
                        }
                        else{
                            throw new IllegalArgumentException("物理字段对应的表别名["+tableAlias+"]在作用域中不存在");
                        }
                    }

                }

                //表别名或表名不存在(裸字段*号)
                else{

                    if(!sqlInfoStack.isEmpty()&&sqlInfoStack.peek()!=null){

                        //在对应sql语句信息中获取到星号*对应的表
                        if(!sqlInfoStack.peek().tables.isEmpty()){

                            //该sql语句只有一张表
                            if(sqlInfoStack.peek().tables.size()==1){
                                TableInfo tableInfo=sqlInfoStack.peek().tables.getFirst();

                                columnRefs.getFirst().originTable=tableInfo;  //将来源字段与表关联

                                //暂定，接入元数据后删除
                                selectItem.sourceColumns.add(columnRefs.getFirst());


                                //接入元数据，遍历scope作用域中对应的元数据表的每个字段，
                                // 将所有字段都封装注册进查询列selectItem.sourceColumns中
                                //将所有字段封装进对应表信息的 输出字段列表 中

                            }
                            //该sql语句存在多张表
                            else{

                                columnRefs.getFirst().isAmbiguous=true;  //标记字段对应的表，存在歧义

                                //将字段封装注册进查询列selectItem中
                                selectItem.sourceColumns.add(columnRefs.getFirst());

                            }
                        }
                    }
                }
            }
            else{
                throw new IllegalArgumentException("获取["+ctx.tableAllColumns().getText()+"]的物理字段失败");
            }


            //记录查询列selectItem的字段显示名displayName,因为是星号*,会展示所有最终来源的物理字段名

            for (int i=0;i<selectItem.sourceColumns.size();i++){

                String column = !Objects.equals(selectItem.sourceColumns.get(i).tableAlias, "") ?
                        selectItem.sourceColumns.get(i).tableAlias+"."+selectItem.sourceColumns.get(i).columnName
                        : selectItem.sourceColumns.get(i).columnName;

                selectItem.selectItemDisplayNameList.add(column);
            }


            //设置最终物理字段 来源类型

            selectItem.transformType=TransformType.WILDCARD;      //设置来源类型为 通配符


            //将查询列selectItem与对应的sql语句关联

            if(!sqlInfoStack.isEmpty()&&sqlInfoStack.peek()!=null){

                sqlInfoStack.peek().selectItems.add(selectItem);
            }

        }

        //查询列为非星号*，即正常表达式
        else {

            //astHelper.extractColumnCandidates 会过滤掉子查询
            // 含子查询的查询列：无对应的最终来源物理字段
            // 在exitSelectItem 封装好 别名和 displayName 显示名,
            // 合并子查询的血缘(将子查询中的所有查询列合并到父查询的 最终物理来源字段中)
            // 在exitSelectStatement 关联上对应的查询列子查询


                //获取最终依赖的物理字段列表
                columnRefs = astHelper.extractColumnCandidates(ctx.expression());


                //封装并记录 最终来源的物理字段
                if(!columnRefs.isEmpty()){

                    for (ColumnRef columnRef : columnRefs) {

                        String tableAlias = columnRef.tableAlias;    //获取物理字段对应的表的别名或表名

                        //表别名或表名存在
                        if (!Objects.equals(tableAlias, "")) {

                            if (!scopeStack.isEmpty() && scopeStack.peek() != null) {

                                //在对应作用域中获取到查询列对应的表
                                if (scopeStack.peek().aliasToTableMap.containsKey(tableAlias)) {

                                    TableInfo tableInfo = scopeStack.peek().aliasToTableMap.get(tableAlias);

                                    columnRef.originTable = tableInfo;   //将最终来源字段与表进行关联


                                    //将字段封装注册进查询列selectItem中
                                    selectItem.sourceColumns.add(columnRef);


                                    //将对应物理字段封装进对应表信息的 输出字段列表 中
                                    //封装格式: 表别名.字段名, 库名.表名.字段名

                                    String column = tableAlias + "." + columnRef.columnName;

                                    tableInfo.tableOutPutColumn.add(column);

                                } else {
                                    throw new IllegalArgumentException("物理字段对应的表名[" + tableAlias + "]在作用域中不存在");
                                }
                            }

                        }

                        //表别名或表名不存在(裸字段)
                        else {

                            if (!scopeStack.isEmpty() && scopeStack.peek() != null) {

                                //在当前对应的scope作用域查找物理字段对应的表
                                if (scopeStack.peek().columnToTableMap.containsKey(columnRef.columnName)) {

                                    List<TableInfo> tableInfos = scopeStack.peek().columnToTableMap.get(columnRef.columnName);

                                    //物理字段只对应一张表
                                    if (tableInfos.size() == 1) {

                                        //将最终来源字段与表进行关联
                                        columnRef.originTable = tableInfos.getFirst();

                                        //将对应物理字段封装进对应表信息的 输出字段列表 中
                                        tableInfos.getFirst().tableOutPutColumn.add(columnRef.columnName);

                                        //将字段封装注册进查询列selectItem中
                                        selectItem.sourceColumns.add(columnRef);
                                    }

                                    //物理字段只对应多张表,字段存在歧义
                                    else if (tableInfos.size() > 1) {
                                        columnRef.isAmbiguous = true;  //标记 字段存在歧义

                                        //将字段封装注册进查询列selectItem中
                                        selectItem.sourceColumns.add(columnRef);

                                    } else {
                                        throw new IllegalArgumentException("阶段一作用域字段与表关系columnToTableMap解析错误");
                                    }

                                } else {
                                    throw new IllegalArgumentException("物理字段[" + columnRef.columnName + "]在作用域中不存在");
                                }
                            }

                        }
                    }

                }

                //查询列为 常量(纯字面量 ‘abc’) 或 无字段表达式（如 1+2）,但没有血缘依赖
                //即物理字段列表为空，在exitPrecedenceFieldExpression和enterPrecedenceFieldExpression 均会处理




            //将查询列selectItem与对应的sql语句关联
            if(!sqlInfoStack.isEmpty()&&sqlInfoStack.peek()!=null){
                sqlInfoStack.peek().selectItems.add(selectItem);
            }

        }

        selectItemStack.push(selectItem);   //将查询列压入栈

    }



    //清理同层级的函数列表,避免后续数据被干扰，
    // 关联函数与对应的查询列，设置查询列 函数嵌套的最终物理字段 来源类型和查询列表达式类型，
    // 设置查询列 的展示名 displayName
    //合并子查询的血缘(将子查询中的所有查询列合并到父查询的 最终物理来源字段中)
    // 弹出查询列信息
    public void exitSelectItem(HiveParser.SelectItemContext ctx){


        if(!selectItemStack.isEmpty() && selectItemStack.peek()!=null){

            //清理同层级函数栈,正常情况，此时的函数栈应该为空,此处是为了增强代码的鲁棒性
            if(!functionStack.isEmpty()){

                while(functionStack.peek()!=null
                        &&functionStack.peek().functionLever==selectItemStack.peek().selectItemLevel){
                    functionStack.pop();
                }

            }


            //将所有函数与对应的查询列进行关联，并设置最终物理字段 来源类型
            if(!functions.isEmpty()){

                for (FunctionInfo function : functions) {
                    if (function.functionLever == selectItemStack.peek().selectItemLevel) {
                        selectItemStack.peek().functionChain.add(function);
                    }
                }


                //获取嵌套函数个数和最终物理字段来源个数

                //根据嵌套函数的个数和最终来源字段个数 来设置 来源类型
                int colCount = selectItemStack.peek().sourceColumns.size();
                int funcCount = selectItemStack.peek().functionChain.size();

               if (funcCount <= 1 && colCount <= 1) {
                    if(selectItemStack.peek().transformType==null){   //参数为物理字段或常量
                        selectItemStack.peek().transformType=TransformType.SIMPLE; //设置来源类型为 简单加工
                    }
                }
                else {
                    selectItemStack.peek().transformType = TransformType.COMPLEX;  //设置来源类型为 复杂加工
                }


                //清理同层级的函数列表,避免后续数据被干扰
                functions.removeIf(f -> f.functionLever == selectItemStack.peek().selectItemLevel);

            }

            //记录查询列selectItem的字段显示名displayName以及查询列别名
            if(ctx.tableAllColumns()==null){    //查询列为非星号*

                SelectItem selectItem = selectItemStack.peek();

                //查询列存在别名
                if(ctx.id_()!=null&& !ctx.id_().isEmpty()&&selectItem!=null){

                    //遍历别名
                    for (int i=0;i<ctx.id_().size();i++){

                        //将查询列别名作为查询列的展示名dispalyName
                        selectItem.selectItemDisplayNameList.add(ctx.id_().get(i).getText());

                        //记录查询列别名
                        selectItem.selectItemAliasList.add(ctx.id_().get(i).getText());

                    }
                }

                //查询列不存在别名,且无函数嵌套,用最终来源字段名作为展示名字
                else if(selectItem!=null&& selectItem.functionChain.isEmpty()){

                    for (int i=0;i<selectItem.sourceColumns.size();i++){

                        String column = !Objects.equals(selectItem.sourceColumns.get(i).tableAlias, "") ?
                                selectItem.sourceColumns.get(i).tableAlias+"."+selectItem.sourceColumns.get(i).columnName
                                : selectItem.sourceColumns.get(i).columnName;

                        selectItem.selectItemDisplayNameList.add(column);
                    }

                    //查询列为常量,且无别名
                    if(selectItem.sourceColumns.isEmpty()){
                        //将常量本身作为查询列的展示名dispalyName
                        selectItem.selectItemDisplayNameList.add(selectItem.normalizedExpression);
                    }
                }

            }

            // 合并子查询的血缘(将子查询中的所有查询列合并到父查询的 最终物理来源字段中)
            if (!selectItemStack.peek().selectItemSubQueries.isEmpty()) {
                for (SqlInfo subSql : selectItemStack.peek().selectItemSubQueries) {
                    for (SelectItem subItem : subSql.selectItems) {

                        // 只合并直接血缘字段（sourceColumns），不合并子查询的 selectItemSubQueries
                        selectItemStack.peek().sourceColumns.addAll(subItem.sourceColumns);
                    }
                }
            }

            // 对最终物理来源字段 列表的所有数据进行穿透它们的来源
            resolveVirtualColumnOrigin(selectItemStack.peek());



            //对最终物理来源字段 列表的所有数据进行去重
            List<ColumnRef> raw = selectItemStack.peek().sourceColumns;
            if (raw.size() > 1) {

                //identityHashCode(originTable)：同一个物理表的多次引用是同一个对象实例，这样能正确识别"同一张表同一个字段"
                LinkedHashMap<String, ColumnRef> dedupMap = new LinkedHashMap<>();

                for (ColumnRef col : raw) {

                    // key键：originTable 的内存地址 + 列名,
                    // 来源表内存地址是为了区分 同字段名但不同表的情况
                    String key = (col.originTable == null
                            ? "null"
                            : String.valueOf(System.identityHashCode(col.originTable)))
                            + "|" + col.columnName;

                    dedupMap.putIfAbsent(key, col);   //保证数据唯一性
                }

                raw.clear();  //清空原来的最终物理来源字段 列表

                raw.addAll(dedupMap.values());
            }

            selectItemStack.pop();   //当前单个查询列解析完毕，弹出

        }
    }


    //完善select 查询列信息
    public void enterPrecedenceFieldExpression(HiveParser.PrecedenceFieldExpressionContext ctx){

        //判断 当前是否在 窗口函数的over()当中
        if (isInsideWindowSpec) {
            return;
        }

        if(!selectItemStack.isEmpty() && selectItemStack.peek()!=null){

            SelectItem selectItem = selectItemStack.peek();    //获取到当前正在解析的查询列

            //查询列为物理字段
            if(ctx.atomExpression().tableOrColumn() != null){

                //判断当前物理字段是否为 函数的  参数
                if(!functionStack.isEmpty()&&functionStack.peek()!=null
                        &&functionStack.peek().functionLever== selectItem.selectItemLevel){

                    FunctionInfo functionInfo = functionStack.peek();    //获取正在解析的函数

                    List<ColumnRef> columnRefs= astHelper.extractColumnCandidates(ctx);  //获取并封装 物理字段

                    //封装并记录 最终来源的物理字段
                    if(!columnRefs.isEmpty()){

                        //确定并封装每个字段的数据来源
                        optColumnRefsList(columnRefs);
                    }

                    FunctionArgument functionArgument = new FunctionArgument();   //用于封装函数参数

                    functionArgument.columnRef=columnRefs.getFirst();  //将物理字段与函数参数关联

                    functionArgument.rawText=ctx.getText();  //记录参数原始文本

                    functionInfo.arguments.add(functionArgument);   //将函数参数与函数关联

                    //判断是否是 case()函数的参数，它的参数类型要特别处理
                    if(Objects.equals(functionStack.peek().functionName.toUpperCase(), "CASE")){

                        //处理case 多源表达式 的 不同位置的expression
                        optCaseExpression(ctx,functionArgument);

                    }
                    else{
                        functionArgument.type=ArgumentType.COLUMN;  //设置函数参数类型
                    }

                }

            }

            //查询列为子查询嵌套
            else if(ctx.atomExpression().subQueryExpression() != null) {

                //判断当前子查询是否为 函数的 参数
                if(!functionStack.isEmpty()&&functionStack.peek()!=null
                        &&functionStack.peek().functionLever== selectItem.selectItemLevel){

                    FunctionInfo functionInfo = functionStack.peek();    //获取正在解析的函数

                    FunctionArgument functionArgument = new FunctionArgument();   //用于封装函数参数

                    //子查询sql与函数参数在 exitSelectStatement函数中关联

                    functionArgument.rawText=ctx.getText();  //记录参数原始文本

                    functionInfo.arguments.add(functionArgument);   //将函数参数与函数关联

                    //判断是否是 case()函数的参数，它的参数类型要特别处理
                    if(Objects.equals(functionStack.peek().functionName.toUpperCase(), "CASE")){

                        //处理case 多源表达式 的 不同位置的expression
                        optCaseExpression(ctx,functionArgument);
                    }
                    else{
                        functionArgument.type=ArgumentType.SUBQUERIES;  //设置函数参数类型
                    }

                }

            }

            //查询列为cast()类型转换
            else if(ctx.atomExpression().castExpression() != null){

                FunctionInfo functionInfo=new FunctionInfo();   //封装函数信息

                //判断该函数有没有父函数
                if(!functionStack.isEmpty() && functionStack.peek()!=null
                        &&functionStack.peek().functionLever==selectItemStack.peek().selectItemLevel){

                    FunctionArgument functionArgument = new FunctionArgument();   //用于封装父函数参数

                    optExpressArgument(ctx,functionInfo,functionArgument);   //处理表达式 函数参数

                    //判断是否是 case()函数的参数，它的参数类型要特别处理
                    if(Objects.equals(functionStack.peek().functionName.toUpperCase(), "CASE")){

                        //处理case 多源表达式 的 不同位置的expression
                        optCaseExpression(ctx,functionArgument);
                    }
                    else{
                        functionArgument.type=ArgumentType.EXPRESSION;     //设置父函数参数类型 为cast表达式
                    }

                }

                //更新对应的函数信息
                functionInfo.functionName="CAST";   //获取并记录函数名

                //处理表达式对应的虚拟函数的相关信息
                optExpressFunctionInfo(functionInfo);
            }

            //查询列为case xxx when xxx多源条件表达式
            else if(ctx.atomExpression().caseExpression()!=null){

                FunctionInfo functionInfo=new FunctionInfo();   //封装函数信息

                //判断该函数有没有父函数
                if(!functionStack.isEmpty() && functionStack.peek()!=null
                        &&functionStack.peek().functionLever==selectItemStack.peek().selectItemLevel){

                    FunctionArgument functionArgument = new FunctionArgument();   //用于封装父函数参数

                    optExpressArgument(ctx,functionInfo,functionArgument);   //处理表达式 函数参数

                    //判断是否是 case()函数的参数，它的参数类型要特别处理
                    if(Objects.equals(functionStack.peek().functionName.toUpperCase(), "CASE")){
                        //处理case 多源表达式 的 不同位置的expression
                        optCaseExpression(ctx,functionArgument);
                    }
                    else{

                        functionArgument.type=ArgumentType.EXPRESSION;     //设置父函数参数类型 为case表达式
                    }
                }

                //更新对应的函数信息
                functionInfo.functionName="CASE";   //获取并记录函数名

                //处理表达式对应的虚拟函数的相关信息
                optExpressFunctionInfo(functionInfo);
            }

            //查询列为case when xxx多源条件表达式
            else if(ctx.atomExpression().whenExpression()!=null){

                FunctionInfo functionInfo=new FunctionInfo();   //封装函数信息

                //判断该函数有没有父函数
                if(!functionStack.isEmpty() && functionStack.peek()!=null
                        &&functionStack.peek().functionLever==selectItemStack.peek().selectItemLevel){

                    FunctionArgument functionArgument = new FunctionArgument();   //用于封装父函数参数

                    optExpressArgument(ctx,functionInfo,functionArgument);   //处理表达式 函数参数

                    //判断是否是 case()函数的参数，它的参数类型要特别处理
                    if(Objects.equals(functionStack.peek().functionName.toUpperCase(), "CASE")){

                        //处理case 多源表达式 的 不同位置的expression
                        optCaseExpression(ctx,functionArgument);
                    }
                    else{
                        functionArgument.type=ArgumentType.EXPRESSION;     //设置父函数参数类型 为case表达式
                    }
                }

                //更新对应的函数信息
                functionInfo.functionName="CASE";   //获取并记录函数名

                //处理表达式对应的虚拟函数的相关信息
                optExpressFunctionInfo(functionInfo);

            }

            //查询列为常量
            else if(ctx.atomExpression().constant()!=null){

                //判断该常量是否是函数参数
                if(!functionStack.isEmpty() && functionStack.peek()!=null
                        &&functionStack.peek().functionLever== selectItemStack.peek().selectItemLevel){


                    FunctionArgument functionArgument = new FunctionArgument();   //用于封装父函数参数

                    functionArgument.rawText=ctx.getText();  //获取参数原始文本

                    functionArgument.constantValue=ctx.getText();  //存放常量

                    functionStack.peek().arguments.add(functionArgument); //关联常量与父函数参数

                    //判断是否是 case()函数的参数，它的参数类型要特别处理
                    if(Objects.equals(functionStack.peek().functionName.toUpperCase(), "CASE")){

                        //处理case 多源表达式 的 不同位置的expression
                        optCaseExpression(ctx,functionArgument);

                    }
                    else{
                        functionArgument.type=ArgumentType.CONSTANT;     //设置父函数参数类型 为嵌套函数
                    }
                }
            }

            //查询列的时间间隔intervalExpression
            else if(ctx.atomExpression().intervalExpression()!=null){


                FunctionInfo functionInfo=new FunctionInfo();   //封装时间间隔表达式信息

                //判断该时间间隔有没有父函数
                if(!functionStack.isEmpty() && functionStack.peek()!=null
                        &&functionStack.peek().functionLever==selectItemStack.peek().selectItemLevel){

                    FunctionArgument functionArgument = new FunctionArgument();   //用于封装父函数参数

                    optExpressArgument(ctx,functionInfo,functionArgument);   //处理表达式 函数参数

                    //判断是否是 case()函数的参数，它的参数类型要特别处理
                    if(Objects.equals(functionStack.peek().functionName.toUpperCase(), "CASE")){
                        //处理case 多源表达式 的 不同位置的expression
                        optCaseExpression(ctx,functionArgument);
                    }
                    else{
                        //判断时间间隔表达式中是否存在 物理字段
                        if(ctx.atomExpression().intervalExpression().expression()!=null){

                            functionArgument.type=ArgumentType.EXPRESSION;     //设置父函数参数类型 为时间间隔表达式

                        }

                        //时间间隔只有常量
                        else{
                            functionArgument.type=ArgumentType.CONSTANT;     //设置父函数参数类型 为常量
                        }
                    }
                }

                //更新对应的函数信息
                functionInfo.functionName="INTERVAL";   //获取并记录函数名

                //处理表达式对应的虚拟函数的相关信息
                optExpressFunctionInfo(functionInfo);
            }

            //查询列为抽取表达式extract()函数
            else if(ctx.atomExpression().extractExpression()!=null) {

                FunctionInfo functionInfo = new FunctionInfo();   //封装抽取表达式信息

                //判断该函数有没有父函数
                if (!functionStack.isEmpty() && functionStack.peek() != null
                        && functionStack.peek().functionLever == selectItemStack.peek().selectItemLevel) {

                    FunctionArgument functionArgument = new FunctionArgument();   //用于封装父函数参数

                    optExpressArgument(ctx,functionInfo,functionArgument);   //处理表达式 函数参数

                    //判断是否是 case()函数的参数，它的参数类型要特别处理
                    if (Objects.equals(functionStack.peek().functionName.toUpperCase(), "CASE")) {
                        //处理case 多源表达式 的 不同位置的expression
                        optCaseExpression(ctx,functionArgument);
                    }
                    else {
                        functionArgument.type = ArgumentType.EXPRESSION;     //设置父函数参数类型 为抽取表达式
                    }
                }

                //更新对应的函数信息
                functionInfo.functionName="EXTRACT";   //获取并记录函数名

                //处理表达式对应的虚拟函数的相关信息
                optExpressFunctionInfo(functionInfo);

            }

            //查询列为取整表达式floor()函数
            else if(ctx.atomExpression().floorExpression()!=null){

                FunctionInfo functionInfo = new FunctionInfo();   //封装抽取表达式信息

                //判断该函数有没有父函数
                if (!functionStack.isEmpty() && functionStack.peek() != null
                        && functionStack.peek().functionLever == selectItemStack.peek().selectItemLevel) {

                    FunctionArgument functionArgument = new FunctionArgument();   //用于封装父函数参数

                    optExpressArgument(ctx,functionInfo,functionArgument);   //处理表达式 函数参数

                    //判断是否是 case()函数的参数，它的参数类型要特别处理
                    if (Objects.equals(functionStack.peek().functionName.toUpperCase(), "CASE")) {
                        //处理case 多源表达式 的 不同位置的expression
                        optCaseExpression(ctx,functionArgument);
                    }
                    else {
                        functionArgument.type = ArgumentType.EXPRESSION;     //设置父函数参数类型 为取整表达式
                    }

                }

                //更新对应的函数信息
                functionInfo.functionName="FLOOR";   //获取并记录函数名

                //处理表达式对应的虚拟函数的相关信息
                optExpressFunctionInfo(functionInfo);

            }

        }
    }


    //弹出已经解析完的虚拟函数，
    // 根据不同情况,记录selectItem 查询列的 表达式类型 和 物理来源字段与查询列的关系
    public void exitPrecedenceFieldExpression(HiveParser.PrecedenceFieldExpressionContext ctx){

        if(!selectItemStack.isEmpty() && selectItemStack.peek()!=null){

            SelectItem selectItem = selectItemStack.peek();    //获取到当前正在解析的查询列

            //查询列为物理字段
            if(ctx.atomExpression().tableOrColumn() != null){

                //如果该字段是函数参数
                if (!functionStack.isEmpty() && functionStack.peek() != null
                        && functionStack.peek().functionLever == selectItemStack.peek().selectItemLevel) {

                    //设置最终物理字段 来源类型
                    selectItem.transformType=null;     //暂时设置为空

                    //设置查询列表达式类型
                    selectItem.exprType=null;  //暂时设置为空

                }

                //如果该字段不是函数参数
                else{

                    //设置最终物理字段 来源类型
                    selectItem.transformType=TransformType.DIRECT;      //设置来源类型为 直取

                    //设置查询列表达式类型
                    selectItem.exprType=ExpressionType.DIRECT;  //设置类型为 直取

                }
            }

            //查询列为子查询嵌套
            else if(ctx.atomExpression().subQueryExpression() != null) {

                //设置最终物理字段 来源类型
                selectItem.transformType=TransformType.COMPLEX;      //设置来源类型为 复杂加工

                //设置查询列表达式类型
                selectItem.exprType=ExpressionType.SUBQUERY;  //设置类型为 子查询

            }

            //查询列为cast()类型转换
            else if(ctx.atomExpression().castExpression() != null){

                //设置最终物理字段 来源类型
                selectItem.transformType=TransformType.SIMPLE;      //设置来源类型为 简单加工

                //设置查询列表达式类型
                selectItem.exprType=ExpressionType.CAST;  //设置类型为 cast表达式

                //将 表达式弹出，表示解析完毕
                if(!functionStack.isEmpty()&&functionStack.peek()!=null
                        &&functionStack.peek().functionLever==selectItemStack.peek().selectItemLevel){

                    functionStack.pop();
                }

            }

            //查询列为case xxx when xxx多源条件表达式
            else if(ctx.atomExpression().caseExpression()!=null){

                //设置最终物理字段 来源类型
                selectItem.transformType= TransformType.COMPLEX;   //确定物理字段与查询列关系为 复杂加工

                //设置查询列表达式类型
                selectItem.exprType=ExpressionType.CASE;  //设置类型为 case表达式

                //将 表达式弹出，表示解析完毕
                if(!functionStack.isEmpty()&&functionStack.peek()!=null
                        &&functionStack.peek().functionLever==selectItemStack.peek().selectItemLevel){

                    functionStack.pop();
                }

            }

            //查询列为case when xxx多源条件表达式
            else if(ctx.atomExpression().whenExpression()!=null){

                //设置最终物理字段 来源类型
                selectItem.transformType= TransformType.COMPLEX;   //确定物理字段与查询列关系为 复杂加工

                //设置查询列表达式类型
                selectItem.exprType=ExpressionType.CASE;  //设置类型为 case 表达式

                //将 表达式弹出，表示解析完毕
                if(!functionStack.isEmpty()&&functionStack.peek()!=null
                        &&functionStack.peek().functionLever==selectItemStack.peek().selectItemLevel){

                    functionStack.pop();
                }

            }

            //查询列为常量
            else if(ctx.atomExpression().constant()!=null){

                //如果该常量是函数参数
                if (!functionStack.isEmpty() && functionStack.peek() != null
                        && functionStack.peek().functionLever == selectItemStack.peek().selectItemLevel) {

                    //设置最终物理字段 来源类型
                    selectItem.transformType=null;     //暂时设置为空

                    //设置查询列表达式类型
                    selectItem.exprType=null;  //暂时设置为空

                }

                //如果该常量不是函数参数
                else{

                    //设置最终物理字段 来源类型
                    selectItem.transformType= TransformType.CONSTANT;   //确定物理字段与查询列关系为 常量

                    //设置查询列表达式类型
                    selectItem.exprType=ExpressionType.CONSTANT;  //设置类型为 常量

                }

            }

            //查询列的时间间隔intervalExpression
            else if(ctx.atomExpression().intervalExpression()!=null){

                //判断时间间隔表达式中是否存在 物理字段
                if(ctx.atomExpression().intervalExpression().expression()!=null){

                    //设置最终物理字段 来源类型
                    selectItem.transformType= TransformType.SIMPLE;   //确定物理字段与查询列关系为 简单加工

                    //设置查询列表达式类型
                    selectItem.exprType=ExpressionType.INTERVAL;  //设置类型为 时间间隔

                    //将 表达式弹出，表示解析完毕
                    if(!functionStack.isEmpty()&&functionStack.peek()!=null
                            &&functionStack.peek().functionLever==selectItemStack.peek().selectItemLevel){

                        functionStack.pop();
                    }

                }

                //时间间隔只有常量
                else{
                    //设置最终物理字段 来源类型
                    selectItem.transformType= TransformType.CONSTANT;   //确定物理字段与查询列关系为 常量

                    //设置查询列表达式类型
                    selectItem.exprType=ExpressionType.CONSTANT;  //设置类型为 常量
                }
            }

            //查询列为抽取表达式extract()函数
            else if(ctx.atomExpression().extractExpression()!=null){

                //设置最终物理字段 来源类型
                selectItem.transformType= TransformType.SIMPLE;   //确定物理字段与查询列关系为 简单加工

                //设置查询列表达式类型
                selectItem.exprType=ExpressionType.EXTRACT;  //设置类型为 抽取表达式

                //将 表达式弹出，表示解析完毕
                if(!functionStack.isEmpty()&&functionStack.peek()!=null
                        &&functionStack.peek().functionLever==selectItemStack.peek().selectItemLevel){

                    functionStack.pop();
                }

            }

            //查询列为取整表达式floor()函数
            else if(ctx.atomExpression().floorExpression()!=null){

                //设置最终物理字段 来源类型
                selectItem.transformType= TransformType.SIMPLE;   //确定物理字段与查询列关系为 简单加工

                //设置查询列表达式类型
                selectItem.exprType=ExpressionType.FLOOR;  //设置类型为 取整表达式

                //将 表达式弹出，表示解析完毕
                if(!functionStack.isEmpty()&&functionStack.peek()!=null
                        &&functionStack.peek().functionLever==selectItemStack.peek().selectItemLevel){

                    functionStack.pop();
                }
            }

        }

    }


    //记录查询列中的函数信息,封装函数为其父函数的参数,并关联父函数
    public void enterFunction_(HiveParser.Function_Context ctx){

        //判断是否正在解析查询列select
        if(!selectItemStack.isEmpty()&& selectItemStack.peek()!=null){

            FunctionInfo functionInfo=new FunctionInfo();   //封装函数信息

            if(!functionStack.isEmpty() && functionStack.peek()!=null
                    &&functionStack.peek().functionLever==selectItemStack.peek().selectItemLevel){ //判断该函数有没有父函数

                functionInfo.parentFunction=functionStack.peek();  //关联父函数
                functionInfo.isNested=true;  //更新嵌套函数标记

                FunctionArgument functionArgument = new FunctionArgument();   //用于封装父函数参数
                functionArgument.rawText=ctx.getText();  //获取参数原始文本
                functionArgument.type=ArgumentType.SUB_FUNCTION;     //设置父函数参数类型 为嵌套函数
                functionArgument.nestedFunction=functionInfo;    //关联嵌套函数与父函数参数

                functionInfo.parentFunction.arguments.add(functionArgument);   //关联父函数与父函数参数

            }

            //更新对应的函数信息
            functionInfo.functionName=ctx.functionName().getText();   //获取并记录函数名

            //更新函数层级,与查询列层级保持一致
            functionInfo.functionLever=selectItemStack.peek().selectItemLevel;;


            functions.add(functionInfo);   //将函数添加进函数列表

            functionStack.push(functionInfo); // 将函数压入栈

        }
    }


    //设置查询列表达式类型为 FUNCTION,函数处理完毕,弹出栈,处理窗口函数
    public void exitFunction_(HiveParser.Function_Context ctx){

        if(!selectItemStack.isEmpty() && selectItemStack.peek()!=null){
            selectItemStack.peek().exprType=ExpressionType.FUNCTION;
        }

        FunctionInfo functionInfo= null;   //用于记录弹出的functionInfo

        if(!functionStack.isEmpty()
                && !selectItemStack.isEmpty()
                && functionStack.peek().functionLever == selectItemStack.peek().selectItemLevel){

            functionInfo=functionStack.pop();  //函数弹出
        }

        if(functionInfo==null){
            return;
        }

        //处理窗口函数部分



        //语法为  WITHIN GROUP (有序聚合函数)
        if(ctx.within!=null){

            //标记为窗口函数
            functionInfo.isWindowFunction=true;

            //获取 order by 节点
            HiveParser.OrderByClauseContext orderCtx = ctx.ordBy;

            //通过order by 节点获取并封装该节点对应的所有 字段
            List<ColumnRef> orderCols = astHelper.extractColumnCandidates(orderCtx);
            optColumnRefsList(orderCols);

            //将functionInfo与order by 字段信息相关联
            functionInfo.orderByColumns.addAll(orderCols);

        }


        //语法为 OVER(……)
        if (ctx.KW_OVER() != null && ctx.ws != null) {

            //标记为窗口函数
            functionInfo.isWindowFunction = true;

            // 检查 null_treatment(空值处理策略)
            if (ctx.nt != null) {
                functionInfo.nullTreatment = ctx.nt.getText(); // 记录下 null_treatment(空值处理策略)
            }

            // 2. 解析 window_specification(over后面括号内部的内容)
            HiveParser.Window_specificationContext wsCtx = ctx.ws;



            // 特殊情况：如果只是 OVER w1，即引用了一个 命名窗口，则这里可能只是个 id_
            // 命名窗口 存在的意义就是避免 窗口被重复定义，其追求的是 一次定义，多次使用
            /*
            SELECT
                val,
                ROW_NUMBER() OVER w AS rn,
                RANK()       OVER w AS rk,
                DENSE_RANK() OVER w AS dr
            FROM numbers
            WINDOW w AS (ORDER BY val); -- 定义一次，命名为 w
            */
            if (wsCtx.id_() != null) {

                functionInfo.nameWindow = wsCtx.id_().getText();

            }


            // PARTITION BY 等一系列表达式 存在
            else if (wsCtx.partitioningSpec() != null) {

                HiveParser.PartitioningSpecContext spec = wsCtx.partitioningSpec();

                // 提取 PARTITION BY(如果存在)
                if (spec.partitionByClause() != null) {

                    List<ColumnRef> partCols = astHelper.extractColumnCandidates(spec.partitionByClause().expressions());
                    optColumnRefsList(partCols);

                    //将functionInfo与 PARTITION BY 字段信息相关联
                    functionInfo.partitionByColumns.addAll(partCols);
                }

                // 提取 ORDER BY(如果存在)
                if (spec.orderByClause() != null) {

                    List<ColumnRef> orderCols = astHelper.extractColumnCandidates(spec.orderByClause());
                    optColumnRefsList(orderCols);

                    //将functionInfo与order by 字段信息相关联
                    functionInfo.orderByColumns.addAll(orderCols);
                }
            }


            // 提取 window_frame (窗口函数的上下窗口范围)(如果存在)
            if (wsCtx.window_frame() != null) {
                functionInfo.windowFrame = wsCtx.window_frame().getText(); // 直接拿原始文本
                                                            // 如:"ROWS BETWEEN 1 PRECEDING AND CURRENT ROW"
            }
        }


    }


    //处理join on 条件，将其与对应的sql语句进行关联
    public void exitJoinSource(HiveParser.JoinSourceContext ctx){

        if(!ctx.KW_ON().isEmpty()){

            int onCount= ctx.KW_ON().size();   //on 条件的个数

            List<FilterCondition> filterConditions =new ArrayList<>();  //用于存放所有on条件中的字段

            // 遍历所有的 ON 表达式
            for (HiveParser.ExpressionContext exprCtx : ctx.expression()) {
                filterConditions.addAll(optJoinAndWhereExpression(exprCtx,false));
            }


            //将join on 条件关联对应的sql语句
            if(!sqlInfoStack.isEmpty()&&sqlInfoStack.peek()!=null){
                if(!filterConditions.isEmpty()){
                    sqlInfoStack.peek().joinConditions.addAll(filterConditions);
                }
            }

        }

    }


    //处理where 条件，将其与对应的sql语句进行关联
    public void exitSearchCondition(HiveParser.SearchConditionContext ctx) {

        if (ctx!= null && ctx.expression() != null) {

            // 复用 optJoinAndWhereExpression 函数
            List<FilterCondition> conditions = optJoinAndWhereExpression(ctx.expression(),true);

            // 将解析出来的where条件挂到对应的 SqlInfo 上
            if (!conditions.isEmpty() && !sqlInfoStack.isEmpty() && sqlInfoStack.peek() != null) {
                sqlInfoStack.peek().whereConditions.addAll(conditions);
            }
        }

    }

    //处理 group by后面的字段，将其与对应的sql语句关联
    public void exitGroupByClause(HiveParser.GroupByClauseContext ctx) {

        // 获取group by 后面的所有字段
        List<ColumnRef> groupCols = astHelper.extractColumnCandidates(ctx.groupby_expression());

        //查找并绑定各字段的数据来源
        optColumnRefsList(groupCols);

        if (sqlInfoStack.peek() != null) {
            sqlInfoStack.peek().groupByColumns.addAll(groupCols);
        }
    }


    //处理 having 条件，将其与对应的sql语句关联
    public void exitHavingClause(HiveParser.HavingClauseContext ctx) {
        if (ctx.havingCondition() != null && ctx.havingCondition().expression() != null) {

            //复用optJoinAndWhereExpression函数
            List<FilterCondition> conditions = optJoinAndWhereExpression(ctx.havingCondition().expression(), true);

            // 将解析出来的having条件挂到对应的 SqlInfo 上
            if (!conditions.isEmpty() && !sqlInfoStack.isEmpty()) {
                sqlInfoStack.peek().havingConditions.addAll(conditions);
            }
        }
    }


    //设置 是否在窗口函数中 的标识为true
    public void enterWindow_specification(HiveParser.Window_specificationContext ctx) {
        isInsideWindowSpec = true;
    }

    //更新 是否在窗口函数中 的标识为false
    public void exitWindow_specification(HiveParser.Window_specificationContext ctx) {
        isInsideWindowSpec = false;
    }

}
