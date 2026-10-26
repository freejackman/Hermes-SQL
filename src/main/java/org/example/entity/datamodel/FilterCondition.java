package org.example.entity.datamodel;

import java.util.ArrayList;
import java.util.List;

//用于封装 join on 或 where 后面的条件
public class FilterCondition {

    //如果 WHERE 条件里，左边和右边都有物理字段（比如 a.id = b.id），
    // 且左边表不等于右边表，那它实际上是一个隐式 JOIN
    // 这种情况要做好标记，为true，反之只要某一边是纯常量(a.status="1"),则为false
    public boolean isImplicitJoin=false;

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
