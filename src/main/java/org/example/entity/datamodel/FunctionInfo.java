package org.example.entity.datamodel;


//函数信息类,用于存储函数信息
public class FunctionInfo {
    public String functionName="";  //函数名
    public String argument="";  //参数列表
    public Boolean isBlackFunction=false;  //该函数是否为黑名单函数

    //public FunctionCategory category;              // STRING, DATE, AGG, WINDOW, UDTF, CAST
    //public List<FunctionArgument> arguments;       // 参数列表（结构化）
    public boolean isNested=false;                       // 是否嵌套在其他函数中
    public FunctionInfo parentFunction=null;            // 外层函数（用于嵌套分析）

    public String toString(){
        return "\n[函数名:"+functionName+"\n参数列表:"+argument+"\n该函数是否为黑名单函数：:"
                +isBlackFunction+"父函数:"+parentFunction+"------]\n";
    }
}
