<!-- prompt-migration-v1
stage: planner
kind: user
source: multi_agents/agents/editor.py
symbol: EditorAgent._create_planning_prompt / _format_planning_instructions
lines: 92-128
variables: today,initial_research,max_sections
defaults: {}
sha256: 63447e5853664b3c614ba36a38c8e5f1a5bb3e33d1c219f0730097da12aa1ac1
-->
今天的日期是 ${today}
                   研究摘要报告：'${initial_research}'
                   
                   
你的任务是根据上述研究摘要，为研究项目生成章节标题大纲。
                   最多生成 ${max_sections} 个章节标题。
                   子标题只能聚焦相关研究主题，不得包含引言、结论和参考文献。
                   研究标题和所有章节标题必须使用简体中文；专有名词可保留原语言。
                   除 JSON 外不要返回任何内容。JSON 必须保留字段 'title'（字符串）、'date' 和
                   'sections'（最多 ${max_sections} 个章节标题），结构如下：
                   '{title: 字符串形式的研究标题, date: 今天的日期,
                   sections: ['章节标题 1', '章节标题 2', '章节标题 3' ...]}'.