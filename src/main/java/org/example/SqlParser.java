package org.example;

import org.example.entity.datamodel.SqlInfo;

public interface SqlParser {

    //解析 SQL 字符串，返回完整的血缘与结构信息
    SqlInfo parse(String sql);


    //获取当前引擎的方言名称（如 Hive, Spark）
    String getDialectName();
}