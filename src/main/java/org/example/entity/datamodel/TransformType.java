package org.example.entity.datamodel;


//最终物理字段与查询列的关系
public enum TransformType {
    COMPLEX, // 复杂加工：CONCAT(SUBSTR(...), ...)
    CONSTANT, // 常量：SELECT 'abc'
    DIRECT, // 直取：SELECT id
    SIMPLE, // 简单加工：SUBSTR(id,1,3)
    WILDCARD // 通配符：SELECT *
}
