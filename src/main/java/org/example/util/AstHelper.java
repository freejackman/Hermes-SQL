package org.example.util;


import org.antlr.v4.runtime.tree.ParseTree;
import org.example.entity.datamodel.ColumnRef;
import org.example.hive.HiveParser;

import java.util.ArrayList;
import java.util.List;

//工具类，用于辅助AST语法树的解析
public class AstHelper {


    /*
     * 递归遍历 AST 节点，提取当前子树中所有的 TableOrColumn 和 TableAlllColumns 节点(原始物理字段)
     * 无视嵌套层级（函数、CAST、CASE、算术表达式均可穿透）
     */
    public List<ColumnRef> extractColumnCandidates(ParseTree node) {

        //ParseTree 类是AST语法树中每一个节点的最高接口类，代表了语法分析树中的一个节点,语法树节点下面的各种子孙类，子孙接口均源自它
        // A instanceof B  : 用于判断A是不是 B的 同一类或子孙类,是---true，否---false



        List<ColumnRef> results = new ArrayList<>();   //用于存放提取到的所有物理字段

        if (node == null) {  //递归终止条件
            return results;
        }


        // 1.  命中业务字段（核心）
        // HiveParser 命中 tableOrColumn类代表 "id" 或 "t1.id"
        if (node instanceof HiveParser.TableOrColumnContext) {

            //子类强制转换为父类(如果node是子孙类的话)
            HiveParser.TableOrColumnContext tc = (HiveParser.TableOrColumnContext) node;

            String fullText = tc.getText();   //获取该节点的字符串形式

            ColumnRef candidate = parseTableOrColumn(fullText);   //将该字段信息封装并获取

            results.add(candidate);
        }
        // 2. 命中通配符（SELECT * 或 SELECT t1.*）
        else if (node instanceof HiveParser.TableAllColumnsContext) {
            // 这个规则匹配 "t1.*" 或 单独的 "*"
            // 通常用于 SELECT * 或 SELECT t.*，血缘需要展开

            //子类强制转换为父类(如果node是子孙类的话)
            HiveParser.TableAllColumnsContext tc = (HiveParser.TableAllColumnsContext) node;

            String fullText = tc.getText();   //获取该节点的字符串形式

            ColumnRef candidate = parseTableOrColumn(fullText);   //将该字段信息封装并获取

            results.add(candidate);
        }
        // 子查询内部的字段由子查询自身的 enterSelectItem 处理，不在这里递归
        else if (node instanceof HiveParser.SubQueryExpressionContext) {
            return results;
        }
        // 3. 正常递归深入子节点(深度遍历)（针对所有其他类型）
        else {
            // 注意：有的节点可能是 TerminalNode（叶子，如关键字、括号），
            // 它们没有子节点，直接跳过即可

            for (int i = 0; i < node.getChildCount(); i++) {
                ParseTree child = node.getChild(i);

                // 递归调用，收集子树中存在的 所有的物理字段
                results.addAll(extractColumnCandidates(child));
            }
        }

        return results;  //将收集到的所有字段信息 返回
    }


    /**
     * 解析 "t1.id" 或 "id" 为 (表别名, 列名)
     */
    private ColumnRef parseTableOrColumn(String fullText) {

        ColumnRef candidate = new ColumnRef();   //用于封装字段信息

        //。trim() 去掉字符串的前后空格

        if (fullText.contains(".")) {  //判断字段有无前缀

            String[] parts = fullText.split("[.]");  //拆分字段

            if (parts.length == 2) {

                // 场景：t1.id 、 表名.id
                String columnTextAlias = parts[0].trim();  //获取前缀(可能是表别名，也可能是原表名)

                String colName = parts[1].trim();  //获取物理字段名

                //记录前缀和字段
                candidate.tableAlias = columnTextAlias;
                candidate.columnName = colName;

                if(colName.equals("*")) {
                    candidate.isWildcard = true;  //标记为通配符
                }

                return candidate;

            }
            else if(parts.length == 3) {

                // 场景：库名.表名.字段名
                String columnTextAlias = parts[0].trim()+'.'+parts[1].trim();  //获取前缀(可能是表别名，也可能是原表名)

                String colName = parts[2].trim();  //获取物理字段名

                //记录前缀和字段
                candidate.tableAlias = columnTextAlias;
                candidate.columnName = colName;

                if(colName.equals("*")) {
                    candidate.isWildcard = true;  //标记为通配符
                }

                return candidate;
            }
        }

        //场景: 裸字段，id
        candidate.tableAlias = "";
        candidate.columnName = fullText.trim();

        if(candidate.columnName.equals("*")) {
            candidate.isWildcard = true;  //标记为通配符
        }

        return candidate;
    }


}
