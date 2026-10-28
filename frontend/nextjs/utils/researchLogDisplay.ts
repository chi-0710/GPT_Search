const fieldLabels: Record<string, string> = {
  task: '任务',
  initial_research: '初步研究',
  sections: '章节',
  research_data: '研究资料',
  human_feedback: '人工反馈',
  plan_revision_count: '计划修订次数',
  title: '标题',
  headers: '报告标题结构',
  date: '日期',
  table_of_contents: '目录',
  introduction: '引言',
  conclusion: '结论',
  sources: '来源',
  report: '报告',
  fact_check_notes: '事实核查意见',
  diagrams: '图表',
  fact_check_revision_count: '事实核查修订次数',
  topic: '主题',
  draft: '草稿',
  review: '审阅意见',
  revision_notes: '修订说明',
  draft_revision_count: '草稿修订次数',
};

export function getResearchFieldLabel(field: string): string {
  return Object.prototype.hasOwnProperty.call(fieldLabels, field)
    ? fieldLabels[field]
    : field;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function formatResearchLogValue(value: unknown): string {
  if (value == null) return '';
  if (Array.isArray(value)) return value.map(formatResearchLogValue).join('\n');
  if (isRecord(value)) {
    return Object.entries(value)
      .map(([key, item]) => `${getResearchFieldLabel(key)}：${formatResearchLogValue(item)}`)
      .join('\n');
  }
  return String(value);
}

export function getResearchLogFields(
  header: string,
  text: string,
): Record<string, unknown> | null {
  if (header !== 'differences' && header !== 'research_layout_content') return null;
  try {
    const parsed: unknown = JSON.parse(text);
    if (!isRecord(parsed)) return null;
    if (header === 'research_layout_content') return parsed;
    if (!isRecord(parsed.data)) return null;
    const entries = Object.entries(parsed.data);
    if (!entries.every(([, change]) => isRecord(change))) return null;
    return Object.fromEntries(entries.map(([field, value]) => {
      const change = value as Record<string, unknown>;
      return [field, 'after' in change ? change.after : change.before];
    }));
  } catch {
    return null;
  }
}
