# NL2Semantic2LF2SQL Methodology

1. 对用户问题进行语义解析，命中本体概念与业务指标。
2. 产出可校验的 LF(JSON DSL)，禁止直接跳 SQL。
3. 基于约束规则校验 LF（口径、一致性、安全限制）。
4. 将 LF 编译为目标引擎 SQL（MySQL/Doris）。
5. 通过 tool runtime 执行并返回结构化轨迹。
