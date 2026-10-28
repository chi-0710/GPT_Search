<!-- prompt-migration-v1
stage: planner
kind: protocol
source: java-route-a
symbol: ResearchNodes.planner
lines: -
variables: (none)
defaults: {}
sha256: d9ad8b73334ff05e166d6d18587fd014c62e03de8b0361025c5da538e3e51f6f
-->
Java 输出协议（本文件是 Java 侧输出契约，不属于 Python 逐字正文）：
只返回一个 JSON 对象；不要使用 Markdown 代码块，不要输出 JSON 之外的文字。
JSON 必须包含 title（非空字符串）、date（字符串）、sections（非空字符串数组，元素非空）。
JSON 的键和字符串必须使用双引号；数组顺序即章节顺序；不得重复标题。
