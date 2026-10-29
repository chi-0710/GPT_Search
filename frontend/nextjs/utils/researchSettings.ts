import type { ChatBoxSettings } from '../types/data';

export function validateResearchSettings(settings: ChatBoxSettings): string | null {
  const usesFiles = settings.report_source === 'local' || settings.report_source === 'hybrid';
  if (usesFiles && settings.document_scope === 'selected' && !settings.document_ids?.length) {
    return '请至少选择一份可检索文件';
  }
  return null;
}


/** Normalize persisted/embedded settings before starting a new run. */
export function normalizeResearchSettings(settings: ChatBoxSettings): ChatBoxSettings {
  return { ...settings, report_type: 'multi_agents', defaultReportType: 'multi_agents' };
}
