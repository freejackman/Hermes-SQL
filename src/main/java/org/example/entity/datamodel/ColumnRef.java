package org.example.entity.datamodel;


//字段类，用于记录字段与对应的数据源，以及字段前缀和字段是否有歧义
public class ColumnRef {

    public TableInfo originTable=null;    // 指向物理表或子查询虚拟表
    public String tableAlias="";  // 可能为 ""（裸字段）,t1.id
    public String columnName="";        // 物理字段名（如 "id"）
    public boolean isWildcard=false; // 是否为 * 通配符
    public boolean isAmbiguous=false;      // true表示无法确定归属（如多表都有id且无别名）
}
