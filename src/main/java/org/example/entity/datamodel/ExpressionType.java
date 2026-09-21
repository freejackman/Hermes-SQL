package org.example.entity.datamodel;


//查询列表达式的类型
public enum ExpressionType {
    DIRECT,   // 直取：SELECT id
    FUNCTION,  // 函数：SELECT max(id)
    CASE,     // case表达式： SELECT case when
    WILDCARD,  // 通配符：SELECT *
    SUBQUERY,  // 子查询： SELECT （SELECT id）
    CONSTANT,  // 常量：SELECT 'abc'
    CAST,    // cast表达式： SELECT cast(id,int)
    INTERVAL,  // 时间间隔表达式  SELECT INTERVAL (col + 1) DAY
    EXTRACT,   //抽取表达式  SELECT extract()
    FLOOR     // 取整表达式  SELECT floor()
}
