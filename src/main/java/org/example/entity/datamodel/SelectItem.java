package org.example.entity.datamodel;

import java.util.ArrayList;
import java.util.List;

public class SelectItem {

    // === 基础标识 ===
    public String selectItemAlias ="";                           // 用户定义的别名 (AS xxx)
    public String normalizedExpression="";            // 标准化后的查询列（整个表达式文本）
    public String selectItemDisplayName="";                     // 展示的名字（最终结果展示的名字，有别名，优先别名）

    // === 表达式结构（核心） ===
    //private ExpressionType exprType;                // DIRECT, FUNCTION, CASE, WILDCARD, SUBQUERY, CONSTANT
    //private ParseTree rawAstNode;                   // 保留原始AST节点，供后续深度分析

    // === 来源追踪 ===
    public List<String> sourceColumns = new ArrayList<>();         // 最终依赖的物理字段列表（多个表示多源组合）
    public List<FunctionInfo> functionChain= new ArrayList<>(); // 经过的函数调用链（按执行顺序）
    public TransformType transformType=null;            // DIRECT, SIMPLE, COMPLEX, CONSTANT

    // === 位置信息 ===
    public int selectItemLevel=-1;                    // 字段层级(主查询字段与子查询字段的区别),初始值为-1,最外层的子sql层级为0,向内层级逐渐增大
    //public String containingClause="";                // SELECT / WHERE / JOIN ON / HAVING

    // === 安全推导辅助 ===
    //public boolean isBlackFunctionProcessed=false;           // 是否经过黑名单函数加工
    //public int inferredSensitiveLevel=-1;             // 推导出的安全等级


}

