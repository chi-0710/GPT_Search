export interface BaseData {
  type: string;
}

export interface BasicData extends BaseData {
  type: 'basic';
  content: string;
}

export interface LanggraphButtonData extends BaseData {
  type: 'langgraphButton';
  link: string;
}

export interface DifferencesData extends BaseData {
  type: 'differences';
  content: string;
  output: string;
}

export interface QuestionData extends BaseData {
  type: 'question';
  content: string;
}

export interface ChatData extends BaseData {
  type: 'chat';
  content: string;
  metadata?: any; // For storing search results and other contextual information
}

export type Data = BasicData | LanggraphButtonData | DifferencesData | QuestionData | ChatData;

export interface MCPConfig {
  name: string;
  command: string;
  args: string[];
  env: Record<string, string>;
}

export interface ChatBoxSettings {
  document_scope?: "all" | "selected";
  document_ids?: string[];
  report_type: string;
  report_source: string;
  tone: string;
  domains: string[];
  defaultReportType: string;
  layoutType: string;
  mcp_enabled: boolean;
  mcp_configs: MCPConfig[];
  mcp_strategy?: string;
  include_human_feedback?: boolean;
}

export interface Domain {
  value: string;
}

export interface ChatMessage {
  id?: string;
  role: "user" | "assistant" | "system";
  content: string;
  timestamp?: number;
  metadata?: any; // For storing search results and other contextual information
}

export interface ResearchHistoryItem {
  id: string;
  question: string;
  answer: string;
  timestamp: number;
  orderedData: Data[];
  chatMessages?: ChatMessage[];
  status?: 'queued' | 'running' | 'completed' | 'failed';
}
