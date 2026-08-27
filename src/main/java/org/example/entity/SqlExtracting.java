package org.example.entity;

import java.util.ArrayList;
import java.util.List;

public class SqlExtracting {

    public List<String> tableNames;

    public List<String> columnNames;

    public List<String> functionNames;

    public SqlExtracting() {
        this.tableNames = new ArrayList<>();
        this.columnNames = new ArrayList<>();
        this.functionNames = new ArrayList<>();
    }

    public void Result(){

        if (!tableNames.isEmpty()) {
            System.out.println("提取的表名:"+tableNames);
        }
        if (!columnNames.isEmpty()) {
            System.out.println("提取的字段名:"+columnNames);
        }
        if (!functionNames.isEmpty()) {
            System.out.println("提取的函数名:"+functionNames);
        }
    }


}
