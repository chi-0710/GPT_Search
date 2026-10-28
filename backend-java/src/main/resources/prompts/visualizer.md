<!-- prompt-migration-v1
stage: visualizer
kind: user
source: multi_agents/agents/visualizer.py
symbol: VisualizerAgent.generate_visualizations
lines: 10-27
variables: data
defaults: {}
sha256: dad2bb5ea6140b09b5392bc38023ee9d2e75675d24e82381021d8b6baecd5f7e
-->
研究资料：
${data}

请生成一个 Mermaid 图表代码块，呈现这些资料中最有价值或最具结构性的内容。必须使用标准 Markdown ```mermaid ... ``` 语法，并让图表中的可见文字使用简体中文。如果完全没有适合绘图的数据，请仅返回字符串 'None'。