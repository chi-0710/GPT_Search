<!-- prompt-migration-v1
stage: fact_reviser
kind: protocol
source: java-route-a
symbol: ResearchNodes.fact_reviser
lines: -
variables: (none)
defaults: {}
sha256: f71043fd221c9a876bd5730107d1f35407c905a50622ac95be3818210617b890
-->
Java 输出协议（本文件是 Java 侧输出契约，不属于 Python 逐字正文）：
只返回修订后的章节正文 Markdown 文本；不要 JSON 包裹、不要 Markdown 代码块、不要解释说明。
必须删除无法由证据支持的断言并保留有效证据编号（[E1]、[E2]）；未要求修改的内容保持不变。
