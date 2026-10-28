<!-- prompt-migration-v1
stage: writer
kind: protocol
source: java-route-a
symbol: ResearchNodes.writer
lines: -
variables: (none)
defaults: {}
sha256: 54a5732e24b2d2764662943dab204a10d998ae38c5098f048d06d7e8de86d9c6
-->
Java 输出协议（本文件是 Java 侧输出契约，不属于 Python 逐字正文）：
直接返回完整的 Markdown 报告正文（不是 JSON，也不要 Markdown 代码块包裹）。
报告必须包含一级标题（# 标题）、引言、各章节（## 章节标题）与结论（## 结论）等结构；
保留正文中的证据编号（[E1]、[E2]）与“资料不足”说明，不得编造链接、来源或事实。
