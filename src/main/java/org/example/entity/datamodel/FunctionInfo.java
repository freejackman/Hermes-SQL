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


    // === 窗口函数相关属性 ===
    public boolean isWindowFunction = false;  //判断是否为窗口函数

    public String nullTreatment = "";      // 空值处理逻辑(忽略空值或尊重空值):
                                                // IGNORE NULLS / RESPECT NULLS

    public String nameWindow = "";         // 如果引用了命名窗口(其他已经定义了的窗口) ,就记录下该窗口名字，
                                                // 如 OVER （w1),就记录下w1

    public List<ColumnRef> partitionByColumns = new ArrayList<>(); // PARTITION BY 的字段

    public List<ColumnRef> orderByColumns = new ArrayList<>();     // ORDER BY 的字段

    public String windowFrame = "";        // 窗口函数的上下窗口范围



}
