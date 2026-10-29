import React, { ChangeEvent } from 'react';

interface ToneSelectorProps {
  tone: string;
  onToneChange: (event: ChangeEvent<HTMLSelectElement>) => void;
}
export default function ToneSelector({ tone, onToneChange }: ToneSelectorProps) {
  return (
    <div className="form-group">
      <label htmlFor="tone" className="agent_question">写作语气 </label>
      <select 
        name="tone" 
        id="tone" 
        value={tone} 
        onChange={onToneChange} 
        className="form-control-static"
        required
      >
        <option value="Objective">客观－公正、无偏见地陈述事实和发现</option>
        <option value="Formal">正式－以严谨语言和结构遵循学术规范</option>
        <option value="Analytical">分析－批判性评估并深入研究数据和理论</option>
        <option value="Persuasive">说服－使读者接受特定观点或论证</option>
        <option value="Informative">信息型－清晰、全面地介绍主题</option>
        <option value="Explanatory">解释－阐明复杂概念和过程</option>
        <option value="Descriptive">描述－详细描绘现象、实验或案例</option>
        <option value="Critical">批判－判断研究及其结论的有效性和相关性</option>
        <option value="Comparative">比较－对照不同理论、数据或方法的异同</option>
        <option value="Speculative">推测－探索假设、潜在影响或未来研究方向</option>
        <option value="Reflective">反思－审视研究过程以及个人洞见或经验</option>
        <option value="Narrative">叙事－通过故事说明研究发现或方法</option>
        <option value="Humorous">幽默－轻松有趣，使内容更易理解</option>
        <option value="Optimistic">乐观－突出积极发现和潜在收益</option>
        <option value="Pessimistic">审慎－聚焦局限、挑战或负面结果</option>
        <option value="Simple">简明－面向年轻读者，使用基础词汇和清晰解释</option>
        <option value="Casual">轻松－采用自然、日常的对话式表达</option>
      </select>
    </div>
  );
}
