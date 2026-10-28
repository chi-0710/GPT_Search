declare module 'gpt-researcher-ui' {
  import React from 'react';

  export interface ResearchProps {
    apiUrl?: string;
    apiKey?: string;
    defaultPrompt?: string;
    onResultsChange?: (results: any) => void;
    theme?: any;
  }

  export const Research: React.FC<ResearchProps>;
}