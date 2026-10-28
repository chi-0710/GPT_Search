<!-- prompt-migration-v1
stage: visualizer
kind: protocol
source: java-route-a
symbol: ResearchNodes.visualizer
lines: -
variables: (none)
defaults: {}
sha256: 81150392bfdbe6fef1bbabc0c40552fbaefa9cad5140f3c3f9d0bbdd22c17816
-->
Java 输出协议（本文件是 Java 侧输出契约，不属于 Python 逐字正文）：
有适合可视化的数据时，只返回一个标准 Markdown mermaid 代码块，且仅使用 flowchart TD；
用 n1[中文标签] 定义节点，用 n1 --> n2 连接，不加边标签、样式或子图；最多 12 个节点，每个标签不超过 40 字。
没有适合绘图的数据时只返回 None。禁止 HTML、外部链接、click 指令和初始化配置；不得添加报告中不存在的事实。
