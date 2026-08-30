package org.example.entity.datamodel;

//记录表的类型
public enum TableType {
    PHYSICAL, // 物理表,实实在在存储在硬盘上的表、视图或 HDFS 文件
    SUBQUERY, // 子查询
    VIRTUAL, // 虚拟表/内联数据，通过 VALUES 子句硬编码的数据 FROM (VALUES (1,'a')) AS t(id, name)
    FUNCTION, // 表函数,
}
