<!-- prompt-migration-v1
stage: section_reviewer
kind: protocol
source: java-route-a
symbol: ResearchNodes.section_reviewer
lines: -
variables: (none)
defaults: {}
sha256: f9a60138fe756a28f47517bae3757d0a9ff1231a4f09e09bf0e8f781c1440192
-->
Java 输出协议（本文件是 Java 侧输出契约，不属于 Python 逐字正文）：
只返回一个 JSON 对象：{"accepted": true 或 false, "feedback": "简体中文修订意见"}。
不要输出 JSON 之外的文字。accepted=false 时 feedback 必须具体列出问题；accepted=true 时 feedback 可为空字符串。
事实错误、虚构引用或无证据断言必须 accepted=false。
