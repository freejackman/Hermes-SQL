package org.example.entity.datamodel;


//函数参数
public class FunctionArgument {

    public ArgumentType type;                      // 参数类型
    public ColumnRef columnRef=null;                    // 如果是字段引用，存这里
    public Object constantValue=null;                   // 如果是常量，存这里
    public FunctionInfo nestedFunction=null;            // 如果是嵌套函数，存这里
    public SqlInfo subQueries =null;                   // 如果是标量子查询，存这里
    public Object expression =null;             // 如果是表达式(时间间隔，抽取表达式，cast表达式，case表达式，取整表达式), 存这里
    public String rawText="";                         // 原始文本（兜底）

}
