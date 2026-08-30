package org.example.entity.datamodel;

import java.util.ArrayList;
import java.util.List;

//表信息类,用于存储表信息
public class TableInfo {
    public String dataBaseName="";  //库名
    public String tableName="";  //表名(物理表名)
    public String tableNameAlias ="";  //表别名

    public int tableLevel =-1;  //表层级(主查询字段与子查询字段的区别),初始值为-1,最内层的子sql层级为0,向外层级逐渐增大

    public TableType tableType=null;    //表的种类分为 PHYSICAL(物理表), SUBQUERY(子查询),
                                        // VIRTUAL(虚拟表/内联数据), FUNCTION(表函数)

    public SqlInfo subQuery=null;                  // 如果是子查询，存这里
    public List<String> tableFunctionName= new ArrayList<>();     // 如果是表函数（如explode），存这里
    public List<String> functionInputColumns = new ArrayList<>();  // 表函数的入参字段


}


