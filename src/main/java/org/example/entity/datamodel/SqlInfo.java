package org.example.entity.datamodel;


import java.util.ArrayList;
import java.util.List;

//sql语句信息类,用于存储sql信息
public class SqlInfo {

    public String sqlType="";           //sql语句类型
    public String originalSql="";        // 原始SQL文本

    public int sqlLevel = -1;                   // -1表示初始值，1表示最外层sql，向内层逐渐递增

    public SqlInfo parentSql=null;       // 父级SQL（用于子查询回溯）

    public List<SqlInfo> childSqls=new ArrayList<>();;    // 子查询sql列表（递归结构,可能同时存在多个子查询）

    public List<SelectItem> selectItems=new ArrayList<>();  //存储sql语句对应的所有字段信息
    public List<TableInfo> tables = new ArrayList<>();  //存储sql语句对应的所有表信息

    public Scope sqlScope = null;    //记录本层sql语句的scope作用域


    //public List<JoinCondition> joinConditions;      // JOIN条件（独立出来）
    //public List<ColumnRef> whereColumns;            // WHERE条件中的字段

    // 对比辅助字段
    //public String fingerprint;                      // 结构指纹（用于快速匹配）
    //public int depth;                               // 当前SQL的嵌套深度


}
