import React, { useState, useEffect } from "react";
import ToneSelector from "../Settings/ToneSelector";
import MCPSelector from "../Settings/MCPSelector";
import LayoutSelector from "../Settings/LayoutSelector";
import DomainFilter from "./DomainFilter";
import { useAnalytics } from "../../hooks/useAnalytics";
import { ChatBoxSettings, Domain, MCPConfig } from '@/types/data';

interface ResearchFormProps {
  chatBoxSettings: ChatBoxSettings;
  setChatBoxSettings: React.Dispatch<React.SetStateAction<ChatBoxSettings>>;
  onFormSubmit?: (
    task: string,
    reportType: string,
    reportSource: string,
    domains: Domain[]
  ) => void;
}

export default function ResearchForm({
  chatBoxSettings,
  setChatBoxSettings,
  onFormSubmit,
}: ResearchFormProps) {
  const { trackResearchQuery } = useAnalytics();
  const [task, setTask] = useState("");
  const [newDomain, setNewDomain] = useState('');

  // Destructure necessary fields from chatBoxSettings
  const { report_source, tone, layoutType } = chatBoxSettings;

  const [domains, setDomains] = useState<Domain[]>(() =>
    (chatBoxSettings.domains || []).map(value => ({ value })),
  );
  
  useEffect(() => {
    setChatBoxSettings(prev => ({
      ...prev,
      domains: domains.map(domain => domain.value)
    }));
  }, [domains, setChatBoxSettings]);

  const handleAddDomain = (e: React.FormEvent) => {
    e.preventDefault();
    if (newDomain.trim()) {
      setDomains([...domains, { value: newDomain.trim() }]);
      setNewDomain('');
    }
  };

  const handleRemoveDomain = (domainToRemove: string) => {
    setDomains(domains.filter(domain => domain.value !== domainToRemove));
  };

  const onFormChange = (e: { target: { name: any; value: any } }) => {
    const { name, value } = e.target;
    setChatBoxSettings((prevSettings: any) => ({
      ...prevSettings,
      [name]: value,
    }));
  };

  const onToneChange = (e: { target: { value: any } }) => {
    const { value } = e.target;
    setChatBoxSettings((prevSettings: any) => ({
      ...prevSettings,
      tone: value,
    }));
  };

  const onLayoutChange = (e: { target: { value: any } }) => {
    const { value } = e.target;
    setChatBoxSettings((prevSettings: any) => ({
      ...prevSettings,
      layoutType: value,
    }));
  };

  const onMCPChange = (enabled: boolean, configs: MCPConfig[]) => {
    setChatBoxSettings((prevSettings: any) => ({
      ...prevSettings,
      mcp_enabled: enabled,
      mcp_configs: configs,
    }));
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (onFormSubmit) {
      const updatedSettings = {
        ...chatBoxSettings,
        report_type: "multi_agents",
        domains: domains.map(domain => domain.value)
      };
      setChatBoxSettings(updatedSettings);
      onFormSubmit(task, "multi_agents", report_source, domains);
    }
  };

  return (
    <form
      method="POST"
      className="report_settings_static mt-3"
      onSubmit={handleSubmit}
    >
      <p className="agent_question">多智能体研究</p>

      <div className="form-group">
        <label htmlFor="report_source" className="agent_question">
          报告来源{" "}
        </label>
        <select
          name="report_source"
          value={report_source}
          onChange={onFormChange}
          className="form-control-static"
          required
        >
          <option value="web">仅网络</option>
          <option value="local">仅文件</option>
          <option value="hybrid">文件＋网络</option>
        </select>
      </div>

      

      <ToneSelector tone={tone} onToneChange={onToneChange} />

      <MCPSelector 
        mcpEnabled={chatBoxSettings.mcp_enabled || false}
        mcpConfigs={chatBoxSettings.mcp_configs || []}
        onMCPChange={onMCPChange}
      />

      <div className="form-group">
        <label className="flex items-center gap-2 cursor-pointer">
          <input
            type="checkbox"
            checked={chatBoxSettings.include_human_feedback === true}
            onChange={(event) => setChatBoxSettings({
              ...chatBoxSettings,
              include_human_feedback: event.target.checked,
            })}
          />
          <span>规划阶段人工审核</span>
        </label>
        <p className="text-xs text-gray-400 mt-1">
          开启后研究会在规划完成后等待你的意见（接受或修改）。
        </p>
      </div>

      <LayoutSelector layoutType={layoutType || 'copilot'} onLayoutChange={onLayoutChange} />

      {/** TODO: move the below to its own component */}
      {(chatBoxSettings.report_source === "web" || chatBoxSettings.report_source === "hybrid") && (
        <DomainFilter
          domains={domains}
          newDomain={newDomain}
          setNewDomain={setNewDomain}
          onAddDomain={handleAddDomain}
          onRemoveDomain={handleRemoveDomain}
        />
      )}
    </form>
  );
}
