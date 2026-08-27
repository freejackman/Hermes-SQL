package org.example.entity.datamodel;

//表信息类,用于存储表信息
public class TableInfo {
    public String tableName="";  //表名
    public String tableNameAlias ="";  //表别名
    public int tableLevel =-1;  //表层级(主查询字段与子查询字段的区别),初始值为-1,最内层的子sql层级为0,向外层级逐渐增大

    public String toString(){
        return "\n[表名:"+tableName+"\n表别名:"+tableNameAlias+"\n表层级:"+tableLevel+"]\n";
    }

}
