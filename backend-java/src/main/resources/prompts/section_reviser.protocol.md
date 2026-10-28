<!-- prompt-migration-v1
stage: section_reviser
kind: protocol
source: java-route-a
symbol: ResearchNodes.section_reviser
lines: -
variables: (none)
defaults: {}
sha256: c99c4253bb9992240b0ab3c49e3e653774fc7a832ec8c30f6f2d0f6cb7f11d30
-->
Java 输出协议（本文件是 Java 侧输出契约，不属于 Python 逐字正文）：
只返回一个 JSON 对象：{"draft": "修订后的章节正文 Markdown", "revision_notes": "向审阅者说明本次修改的简体中文说明"}。
不要输出 JSON 之外的文字，也不要用 Markdown 代码块包裹 JSON。
draft 必须是非空字符串（章节正文）；revision_notes 必须是字符串（可为空字符串，表示无需向审阅者说明）。
正文只使用给定证据与证据编号（[E1]、[E2]），不得新增无证据断言；未要求修改的内容保持不变。
