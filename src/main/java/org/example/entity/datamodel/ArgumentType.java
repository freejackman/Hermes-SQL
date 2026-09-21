package org.example.entity.datamodel;

//函数的参数的类型
public enum ArgumentType {

    COLUMN,  //物理字段
    CONSTANT,  //常量
    SUB_FUNCTION,  //子函数嵌套
    EXPRESSION,  //时间间隔表达式,抽取表达式，cast 表达式, case表达式，取值表达式
    SUBQUERIES , //标量子查询： (select max(id) from  student)+2

    //case when表达式 分支
    CASE_BASE,        // CASE 的基准表达式（CASE col WHEN ...）
    CASE_WHEN_COND,   // WHEN 后的条件
    CASE_WHEN_RESULT, // THEN 后的结果
    CASE_ELSE         // ELSE 后的结果

}
