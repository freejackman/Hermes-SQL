package org.example.entity.datamodel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

//作用域
//用于记录表别名与物理表的关系,所有物理字段与表的关系,该作用域的层级
public class Scope {

    public Scope parentScope=null;                              // 父级作用域（用于穿透(处理)子查询）
    public Map<String, TableInfo> aliasToTableMap = new HashMap<>();        // t1 -> TableInfo对象
    public Map<String, List<TableInfo>> columnToTableMap = new HashMap<>();     // "id" -> [t1, t2]
    public int scopeLevel;          // 当前层级(该作用域下所有的表,字段都是这个层级)

    // 将表注册到当前作用域
    public void registerTable(TableInfo table) {

        //表别名如果为初始值,就用表名充当"表别名"

        //表别名存在
        if(!Objects.equals(table.tableNameAlias, "")) {

            String tableNameAlias=table.tableNameAlias;
            if(!this.aliasToTableMap.containsKey(tableNameAlias)) {
                this.aliasToTableMap.put(tableNameAlias, table);   //将表与别名关系记录下来
            }
            else{

                throw new IllegalArgumentException(
                        "表别名 '" + tableNameAlias + "' 在当前作用域已存在，请使用别名区分");
            }

        }
        //库名，表名均存在
        else if(!Objects.equals(table.dataBaseName, "")&&!Objects.equals(table.tableName, "")) {

            String tableNameAlias=table.dataBaseName+"."+table.tableName;   //用库名.表名作为key
            if(!this.aliasToTableMap.containsKey(tableNameAlias)) {
                this.aliasToTableMap.put(tableNameAlias, table);   //将表与别名关系记录下来
            }
            else{

                throw new IllegalArgumentException(
                        "表名 '" + tableNameAlias + "' 在当前作用域已存在，请使用别名区分");
            }
        }

        //只有表名存在
        else if(!Objects.equals(table.tableName, "")) {

            String tableNameAlias=table.tableName;   //用表名作为key

            if(!this.aliasToTableMap.containsKey(tableNameAlias)) {
                this.aliasToTableMap.put(tableNameAlias, table);   //将表与别名关系记录下来
            }
            else{

                throw new IllegalArgumentException(
                        "表名 '" + tableNameAlias + "' 在当前作用域已存在，请使用别名区分");
            }

        }
        //不存在表名,也不存在别名
        else {

            throw new IllegalArgumentException(
                    "注册失败！请为表设置别名");
        }
    }
}
