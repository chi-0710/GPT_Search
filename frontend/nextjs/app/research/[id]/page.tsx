"use client";

import React, { useEffect, useState, useRef } from "react";
import { useRouter } from "next/navigation";
import { useResearchHistoryContext } from "@/hooks/ResearchHistoryContext";
import { preprocessOrderedData } from "@/utils/dataProcessing";
import { ChatBoxSettings, Data, ChatData, ChatMessage, QuestionData } from "@/types/data";
import { toast } from "react-hot-toast";
import { getAppropriateLayout } from "@/utils/getLayout";

import ResearchPageLayout from "@/components/layouts/ResearchPageLayout";
import CopilotLayout from "@/components/layouts/CopilotLayout";
import ResearchContent from "@/components/research/ResearchContent";
import CopilotResearchContent from "@/components/research/CopilotResearchContent";
import NotFoundContent from "@/components/research/NotFoundContent";
import LoadingDots from "@/components/LoadingDots";
import ResearchSidebar from "@/components/ResearchSidebar";

// Import mobile components
import MobileResearchContent from "@/components/mobile/MobileResearchContent";

export default function ResearchPage({ params }: { params: { id: string } }) {
  const router = useRouter();
  const { id } = params;
  const [loading, setLoading] = useState(true);
  const [question, setQuestion] = useState("");
  const [answer, setAnswer] = useState("");
  const [chatPromptValue, setChatPromptValue] = useState("");
  const [orderedData, setOrderedData] = useState<Data[]>([]);
  const [allLogs, setAllLogs] = useState<any[]>([]);
  const [isStopped, setIsStopped] = useState(false);
  const [currentResearchId, setCurrentResearchId] = useState<string | null>(null);
  const [isProcessingChat, setIsProcessingChat] = useState(false);
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [isMobile, setIsMobile] = useState(false);
  const [chatBoxSettings, setChatBoxSettings] = useState<ChatBoxSettings>(() => {
    // Default settings
    const defaultSettings = {
      report_source: "web",
      report_type: "multi_agents",
      tone: "Objective",
      domains: [],
      defaultReportType: "multi_agents",
      layoutType: 'copilot',
      mcp_enabled: false,
      mcp_configs: [],
      mcp_strategy: "fast",
    };

    // Persist only the visual layout; research parameters are session-scoped.
    if (typeof window !== 'undefined') {
      const savedLayout = localStorage.getItem('gptrUiLayout');
      if (savedLayout) return { ...defaultSettings, layoutType: savedLayout };
    }
    return defaultSettings;
  });
  const [notFound, setNotFound] = useState(false);
  const [fetchAttempted, setFetchAttempted] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);
  const toastShownRef = useRef(false);

  const { 
    history,
    getResearchById, 
    addChatMessage,
    getChatMessages,
    updateResearch,
    deleteResearch
  } = useResearchHistoryContext();

  // Toggle sidebar
  const toggleSidebar = () => {
    setSidebarOpen(!sidebarOpen);
  };

  // Handle selecting a research from the sidebar
  const handleSelectResearch = async (researchId: string) => {
    if (researchId !== id) {
      router.push(`/research/${researchId}`);
    }
    setSidebarOpen(false);
  };

  // Layout is a UI preference; research settings are deliberately not persisted.
  useEffect(() => {
    localStorage.setItem('gptrUiLayout', chatBoxSettings.layoutType || 'copilot');
  }, [chatBoxSettings.layoutType]);

  // Load research data on mount
  useEffect(() => {
    // Prevent multiple fetch attempts for the same ID
    if (fetchAttempted) {
      console.log(`Skipping duplicate fetch for research ${id} (already attempted)`);
      return;
    }
    
    const fetchResearch = async () => {
      setLoading(true);
      setFetchAttempted(true); // Mark that we've attempted a fetch
      console.log(`Attempting to load research ${id}...`);
      
      // Reset toast tracking on each fetch attempt
      toastShownRef.current = false;
      
      try {
        const response = await fetch(`/api/reports/${id}`);
        if (response.ok) {
          const data = await response.json();
          if (!data.report) throw new Error('Backend response is missing report data');
          setQuestion(data.report.question);
          setAnswer(data.report.answer || '');
          setOrderedData(Array.isArray(data.report.orderedData) ? data.report.orderedData : []);
          setCurrentResearchId(id);
        } else {
          setNotFound(true);
          if (response.status !== 404 && !toastShownRef.current) {
            toast.error('无法从服务器读取该研究。', { id: `server-error-${id}` });
            toastShownRef.current = true;
          }
        }
      } catch (error) {
        console.error('Error fetching from backend:', error);
        if (!toastShownRef.current) {
          toast.error('无法连接服务器。', { id: `fetch-error-${id}` });
          toastShownRef.current = true;
        }
        setNotFound(true);
      } finally {
        setLoading(false);
      }
    };
    
    fetchResearch();
  }, [id, fetchAttempted]);

  // Process ordered data into logs for display
  useEffect(() => {
    const groupedData = preprocessOrderedData(orderedData);
    const statusReports = ["agent_generated", "starting_research", "planning_research", "error", "evidence_retrieval", "citation_warning"];
    
    const newLogs = groupedData.reduce((acc: any[], data) => {
      // Process accordion blocks (grouped data)
      if (data.type === 'accordionBlock') {
        const logs = data.items.map((item: any, subIndex: any) => ({
          header: item.content,
          text: item.output,
          metadata: item.metadata,
          key: `${item.type}-${item.content}-${subIndex}`,
        }));
        return [...acc, ...logs];
      } 
      // Process status reports
      else if (statusReports.includes(data.content)) {
        return [...acc, {
          header: data.content,
          text: data.output,
          metadata: data.metadata,
          key: `${data.type}-${data.content}`,
        }];
      }
      return acc;
    }, []);
    
    setAllLogs(newLogs);
  }, [orderedData]);

  // Scroll to bottom when chat updates
  const scrollToBottom = () => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  useEffect(() => {
    // Scroll to bottom when orderedData changes
    if (isProcessingChat === false && orderedData.length > 0) {
      setTimeout(scrollToBottom, 100); // Small delay to ensure content is rendered
    }
  }, [orderedData, isProcessingChat]);

  // Check if on mobile
  useEffect(() => {
    const checkIfMobile = () => {
      setIsMobile(window.innerWidth < 768);
    };
    
    // Initial check
    checkIfMobile();
    
    // Add event listener for window resize
    window.addEventListener('resize', checkIfMobile);
    
    // Cleanup
    return () => window.removeEventListener('resize', checkIfMobile);
  }, []);

  const handleChat = async (message: string) => {
    if (!currentResearchId || !answer) return;
    
    setIsProcessingChat(true);
    setChatPromptValue("");
    
    // Create a user message
    const userMessage: ChatMessage = {
      role: 'user',
      content: message,
      timestamp: Date.now()
    };
    
    // Create question data object to be shown immediately
    const questionData: QuestionData = { type: 'question', content: message };
    
    // IMPORTANT CHANGE: Add user question to UI immediately for better responsiveness
    setOrderedData(prevOrder => [...prevOrder, questionData]);
    
    // Then add to history asynchronously
    addChatMessage(currentResearchId, userMessage).catch(error => {
      console.error('Error adding chat message to history:', error);
    });
    
    try {
      // Get all chat messages for this research
      const chatMessages = getChatMessages(currentResearchId);
      
      // Format messages to ensure they only contain role and content properties
      const formattedMessages = [...chatMessages, userMessage].map(msg => ({
        role: msg.role,
        content: msg.content
      }));
      
      // Call the chat API
      const response = await fetch(`/api/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          report: answer,
          messages: formattedMessages
        }),
      });
      
      if (!response.ok) {
        throw new Error(`Failed to get chat response: ${response.status}`);
      }
      
      const data = await response.json();
      
      if (data.response) {
        // Add AI response to chat history asynchronously
        addChatMessage(currentResearchId, data.response).catch(error => {
          console.error('Error adding AI response to history:', error);
        });
        
        // Add response to the UI with any metadata
        const chatData: ChatData = { 
          type: 'chat', 
          content: data.response.content,
          metadata: data.response.metadata // Include metadata from the response
        };
        setOrderedData(prevOrder => [...prevOrder, chatData]);
        
        // Update research in history with both question and response asynchronously
        // Create a copy of the current orderedData plus the new items
        const updatedOrderedData = [...orderedData, questionData, chatData];
        updateResearch(currentResearchId, answer, updatedOrderedData).catch(error => {
          console.error('Error updating research:', error);
        });
      } else {
        // Show error message
        const errorChatData: ChatData = { 
          type: 'chat', 
          content: '抱歉，出现了问题，请重试。'
        };
        setOrderedData(prevOrder => [...prevOrder, errorChatData]);
      }
    } catch (error) {
      console.error('Error during chat:', error);
      
      // Add error message
      const errorChatData: ChatData = { 
        type: 'chat', 
        content: '抱歉，处理你的请求时发生错误，请重试。'
      };
      setOrderedData(prevOrder => [...prevOrder, errorChatData]);
    } finally {
      setIsProcessingChat(false);
    }
  };

  const handleNewResearch = () => {
    router.push('/');
  };

  const handleCopyUrl = () => {
    const url = window.location.href;
    navigator.clipboard.writeText(url)
      .then(() => {
        toast.success("链接已复制到剪贴板！", {
          id: `copy-success-${id}`, // Unique ID per research
        });
      })
      .catch(() => {
        toast.error("复制链接失败", {
          id: `copy-error-${id}`, // Unique ID per research
        });
      });
  };

  // Custom toast options for this page
  const toastOptions = {
    duration: 4000,
    id: 'research-page-toast',
    style: {
      background: '#363636',
      color: '#fff',
    }
  };

  // Render mobile content
  const renderMobileContent = () => {
    if (notFound) {
      return <NotFoundContent onNewResearch={handleNewResearch} />;
    }
    
    if (loading) {
      return (
        <div className="min-h-[100vh] flex items-center justify-center">
          <LoadingDots />
        </div>
      );
    }
    
    // Make sure we're loading chat messages for the current research
    const chatMessages = currentResearchId ? getChatMessages(currentResearchId) : [];
    
    return (
      <MobileResearchContent
        orderedData={orderedData}
        answer={answer}
        loading={false}
        isStopped={isStopped}
        chatPromptValue={chatPromptValue}
        setChatPromptValue={setChatPromptValue}
        handleChat={handleChat}
        isProcessingChat={isProcessingChat}
        onNewResearch={handleNewResearch}
        currentResearchId={currentResearchId || undefined}
        onShareClick={handleCopyUrl}
      />
    );
  };

  // Loading state
  if (loading && !isMobile) {
    return getAppropriateLayout({
      loading,
      isStopped,
      showResult: true,
      onNewResearch: handleNewResearch,
      chatBoxSettings,
      setChatBoxSettings,
      toastOptions,
      children: (
        <div className="min-h-[100vh] pt-[120px] flex items-center justify-center">
          <LoadingDots />
        </div>
      )
    });
  }

  // Not found state for desktop
  if (notFound && !isMobile) {
    return getAppropriateLayout({
      loading: false,
      isStopped: false,
      showResult: false,
      onNewResearch: handleNewResearch,
      chatBoxSettings,
      setChatBoxSettings,
      toastOptions,
      children: <NotFoundContent onNewResearch={handleNewResearch} />
    });
  }

  // Mobile layout
  if (isMobile) {
    return getAppropriateLayout({
      loading,
      isStopped,
      showResult: true,
      onNewResearch: handleNewResearch,
      chatBoxSettings,
      setChatBoxSettings,
      toastOptions,
      toggleSidebar,
      isProcessingChat,
      children: renderMobileContent()
    });
  }

  // Normal state - research found on desktop
  return getAppropriateLayout({
    loading: false,
    isStopped,
    showResult: true,
    onNewResearch: handleNewResearch,
    chatBoxSettings,
    setChatBoxSettings,
    toastOptions,
    children: (
      <div className="relative">
        <ResearchSidebar
          history={history}
          onSelectResearch={handleSelectResearch}
          onNewResearch={handleNewResearch}
          onDeleteResearch={deleteResearch}
          isOpen={sidebarOpen}
          toggleSidebar={toggleSidebar}
        />
        
        {chatBoxSettings.layoutType === 'copilot' ? (
          <CopilotResearchContent
            orderedData={orderedData}
            answer={answer}
            allLogs={allLogs}
            chatBoxSettings={chatBoxSettings}
            loading={false}
            isStopped={isStopped}
            promptValue=""
            chatPromptValue={chatPromptValue}
            setPromptValue={() => {}}
            setChatPromptValue={setChatPromptValue}
            handleDisplayResult={() => {}}
            handleChat={handleChat}
            handleClickSuggestion={() => {}}
            currentResearchId={currentResearchId || undefined}
            onShareClick={handleCopyUrl}
            isProcessingChat={isProcessingChat}
            onNewResearch={handleNewResearch}
          />
        ) : (
          <ResearchContent
            showResult={true}
            orderedData={orderedData}
            answer={answer}
            allLogs={allLogs}
            chatBoxSettings={chatBoxSettings}
            loading={false}
            isInChatMode={true}
            isStopped={isStopped}
            promptValue=""
            chatPromptValue={chatPromptValue}
            setPromptValue={() => {}}
            setChatPromptValue={setChatPromptValue}
            handleDisplayResult={() => {}}
            handleChat={handleChat}
            handleClickSuggestion={() => {}}
            currentResearchId={currentResearchId || undefined}
            onShareClick={handleCopyUrl}
            isProcessingChat={isProcessingChat}
          />
        )}
      </div>
    )
  });
}
