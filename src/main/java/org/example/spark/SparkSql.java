package org.example.spark;

import org.example.SqlParser;
import org.example.entity.datamodel.SqlInfo;


public class SparkSql implements SqlParser {

    //获取当前引擎的方言名称
    public String getDialectName() {
        return "Spark";
    }

    //用于封装 spark sql 解析过程，解析完成后，返回最外层的sql信息
    public SqlInfo parse(String sql) {
        System.out.println("sql:" + sql);
        return new SqlInfo();
    }

}
