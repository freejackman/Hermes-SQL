package org.example.entity.datamodel;

import org.antlr.v4.runtime.tree.ParseTree;

import java.util.ArrayList;
import java.util.List;

public class JoinCondition {

    public ParseTree leftExpr;   // 左边原始 AST 节点
    public ParseTree rightExpr;  // 右边原始 AST 节点
    public String operator;      // =, >, <, >=, IN, LIKE, BETWEEN...

    // 血缘提取的终极结果
    public List<ColumnRef> leftColumns = new ArrayList<>();
    public List<ColumnRef> rightColumns = new ArrayList<>();

    //如果左边/右边是一个子查询，把其对应的 SqlInfo 存下来
    public List<SqlInfo> leftSubQueries = new ArrayList<>();
    public List<SqlInfo> rightSubQueries = new ArrayList<>();

    // 记录完整的原始文本，用于展示和兜底，如 "a.id IN (SELECT id FROM t2)"
    public String rawConditionText;
}
