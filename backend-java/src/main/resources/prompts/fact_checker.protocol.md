<!-- prompt-migration-v1
stage: fact_checker
kind: protocol
source: java-route-a
symbol: ResearchNodes.fact_checker
lines: -
variables: (none)
defaults: {}
sha256: 210506b757339acbe3788b0df128ac52c97c951860d6b705345e3ea3f39c1c3a
-->
Java 输出协议（本文件是 Java 侧输出契约，不属于 Python 逐字正文）：
只返回一个 JSON 对象：{"accepted": true 或 false, "feedback": "简体中文问题清单"}。
不要输出 JSON 之外的文字。accepted=false 时逐条列出事实错误、虚构引用或无证据断言；
仅当报告在事实层面可靠时 accepted=true，此时 feedback 可为空字符串。
