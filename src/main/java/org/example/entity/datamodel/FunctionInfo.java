package org.example.entity.datamodel;

import java.util.ArrayList;
import java.util.List;

//函数信息类,用于存储函数信息
public class FunctionInfo {
    public String functionName="";  //函数名
    //public Boolean isBlackFunction=false;  //该函数是否为黑名单函数

    //public FunctionCategory category;              // STRING, DATE, AGG, WINDOW, UDTF, CAST
    public List<FunctionArgument> arguments= new ArrayList<>();       // 参数列表（结构化）
    public boolean isNested=false;                       // 是否嵌套在其他函数中
    public FunctionInfo parentFunction=null;            // 外层函数（用于嵌套分析）
    public int functionLever = -1;  //函数层级(主查询与子查询的区别),初始值为-1,最外层的子sql层级为0,向内层级逐渐增大

}
