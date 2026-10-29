"use client";
import { validateResearchSettings } from "@/utils/researchSettings";

import { useRef, useState, useEffect } from "react";
import { useRouter } from "next/navigation";
import { useWebSocket } from '@/hooks/useWebSocket';
import { useResearchHistoryContext } from '@/hooks/ResearchHistoryContext';
import { useScrollHandler } from '@/hooks/useScrollHandler';
import { Data, ChatBoxSettings, QuestionData, ChatMessage, ChatData } from '../types/data';
import { preprocessOrderedData } from '../utils/dataProcessing';
import { toast } from "react-hot-toast";

import Hero from "@/components/Hero";
import ResearchContent from "@/components/research/ResearchContent";
import CopilotResearchContent from "@/components/research/CopilotResearchContent";
import HumanFeedback from "@/components/HumanFeedback";
import ResearchSidebar from "@/components/ResearchSidebar";
import { getAppropriateLayout } from "@/utils/getLayout";

// Import the mobile components
import MobileHomeScreen from "@/components/mobile/MobileHomeScreen";
import MobileResearchContent from "@/components/mobile/MobileResearchContent";

export default function Home() {
  const router = useRouter();
  const [promptValue, setPromptValue] = useState("");
  const [chatPromptValue, setChatPromptValue] = useState("");
  const [showResult, setShowResult] = useState(false);
  const [answer, setAnswer] = useState("");
  const [loading, setLoading] = useState(false);
  const [isInChatMode, setIsInChatMode] = useState(false);
  const [chatBoxSettings, setChatBoxSettings] = useState<ChatBoxSettings>(() => {
    // Default settings
    const defaultSettings = {
      report_type: "multi_agents",
      report_source: "hybrid",
      document_scope: "all" as const,
      document_ids: [],
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
  const [question, setQuestion] = useState("");
  const [orderedData, setOrderedData] = useState<Data[]>([]);
  const [showHumanFeedback, setShowHumanFeedback] = useState(false);
  const [questionForHuman, setQuestionForHuman] = useState<true | false>(false);
  const [allLogs, setAllLogs] = useState<any[]>([]);
  const [isStopped, setIsStopped] = useState(false);
  const mainContentRef = useRef<HTMLDivElement>(null);
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [currentResearchId, setCurrentResearchId] = useState<string | null>(null);
  const [isMobile, setIsMobile] = useState(false);
  const [isProcessingChat, setIsProcessingChat] = useState(false);

  // Use our custom scroll handler
  const { showScrollButton, scrollToBottom } = useScrollHandler(mainContentRef);

  // Check if we're on mobile
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

  const { 
    history, 
    saveResearch, 
    updateResearch,
    getResearchById, 
    deleteResearch,
    addChatMessage,
    getChatMessages,
    refreshHistory,
  } = useResearchHistoryContext();

  const { socket, initializeWebSocket, closeConnection, sendHumanFeedback } = useWebSocket(
    setOrderedData,
    setAnswer,
    setLoading,
    setShowHumanFeedback,
    setQuestionForHuman,
    (researchId) => setCurrentResearchId(researchId),
  );

  const handleFeedbackSubmit = (feedback: string | null) => {
    sendHumanFeedback(feedback);
    setShowHumanFeedback(false);
  };

  const handleChat = async (message: string) => {
    if (!currentResearchId && !answer) {
      // On mobile, if there's no research yet, treat this as a new research request
      if (isMobile) {
        // Show immediate feedback for better UX
        setShowResult(true);
        setPromptValue(message); // Keep the message visible
        
        // Start the research with the chat message
        handleDisplayResult(message);
        return;
      }
    }
    
    setShowResult(true);
    setIsProcessingChat(true);
    setChatPromptValue("");
    
    // Create a user message
    const userMessage: ChatMessage = {
      role: 'user',
      content: message,
      timestamp: Date.now()
    };
    
    // Add question to display in research results immediately
    const questionData: QuestionData = { type: 'question', content: message };
    setOrderedData(prevOrder => [...prevOrder, questionData]);
    
    // Add user message to history asynchronously
    if (currentResearchId) {
      addChatMessage(currentResearchId, userMessage).catch(error => {
        console.error('Error adding chat message to history:', error);
      });
    }
    
    // Mobile implementation - simplified for chat only
    if (isMobile) {
      try {
        // Direct API call instead of websockets
        const response = await fetch('/api/chat', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({
            messages: [{ role: 'user', content: message }],
            report: answer || '',
          }),
        });
        
        if (!response.ok) {
          throw new Error(`API error: ${response.status}`);
        }
        
        const data = await response.json();
        
        if (data.response && data.response.content) {
          // Add AI response to chat history asynchronously
          if (currentResearchId) {
            addChatMessage(currentResearchId, data.response).catch(error => {
              console.error('Error adding AI response to history:', error);
            });
            
            // Also update the research with the new messages
            const chatData: ChatData = { 
              type: 'chat', 
              content: data.response.content,
              metadata: data.response.metadata 
            };
            
            setOrderedData(prevOrder => [...prevOrder, chatData]);
            
            // Get current ordered data and add new messages
            const updatedOrderedData = [...orderedData, questionData, chatData];
            
            // Update research in history
            updateResearch(
              currentResearchId, 
              answer, 
              updatedOrderedData
            ).catch(error => {
              console.error('Error updating research:', error);
            });
          } else {
            // If no research ID, just update the UI
            setOrderedData(prevOrder => [...prevOrder, { 
              type: 'chat', 
              content: data.response.content,
              metadata: data.response.metadata
            } as ChatData]);
          }
        } else {
          // Show error message
          setOrderedData(prevOrder => [...prevOrder, { 
            type: 'chat', 
            content: '抱歉，出现了问题，请重试。'
          } as ChatData]);
        }
      } catch (error) {
        console.error('Error during chat:', error);
        
        // Add error message
        setOrderedData(prevOrder => [...prevOrder, { 
          type: 'chat', 
          content: '抱歉，处理你的请求时发生错误，请重试。'
        } as ChatData]);
      } finally {
        setIsProcessingChat(false);
      }
      return;
    }
    
    // Desktop implementation (unchanged)
    try {
      // Fetch all chat messages for this research
      let chatMessages: { role: string; content: string }[] = [];
      
      if (currentResearchId) {
        // If we have a research ID, get all messages from history
        chatMessages = getChatMessages(currentResearchId);
      }
      
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
          report: answer || "",
          messages: formattedMessages
        }),
      });
      
      if (!response.ok) {
        throw new Error(`Failed to get chat response: ${response.status}`);
      }
      
      const data = await response.json();
      
      if (data.response) {
        // Check if response contains valid content
        if (!data.response.content) {
          console.error('Response content is null or empty');
          // Show error message in results
          setOrderedData(prevOrder => [...prevOrder, { 
            type: 'chat', 
            content: '抱歉，未能生成合适的回答，请重新提问。'
          }]);
        } else {
          // Add AI response to chat history asynchronously
          if (currentResearchId) {
            addChatMessage(currentResearchId, data.response).catch(error => {
              console.error('Error adding AI response to history:', error);
            });
          }
          
          // Add response to display in research results
          setOrderedData(prevOrder => {
            return [...prevOrder, { 
              type: 'chat', 
              content: data.response.content,
              metadata: data.response.metadata
            }];
          });
        }
        
        // Explicitly enable chat mode after getting a response
        if (!isInChatMode) {
          setIsInChatMode(true);
        }
      } else {
        // Show error message
        setOrderedData(prevOrder => [...prevOrder, { 
          type: 'chat', 
          content: '抱歉，出现了问题，请重试。'
        }]);
      }
    } catch (error) {
      console.error('Error during chat:', error);
      
      // Add error message to display
      setOrderedData(prevOrder => [...prevOrder, { 
        type: 'chat', 
        content: '抱歉，处理你的请求时发生错误，请重试。'
      }]);
    } finally {
      setLoading(false);
      setIsProcessingChat(false);
    }
  };

  const handleDisplayResult = async (newQuestion: string) => {
    const validationError = validateResearchSettings(chatBoxSettings);
    if (validationError) {
      toast.error(validationError);
      return;
    }
    // Exit chat mode when starting a new research
    setIsStopped(false);
    setIsInChatMode(false);
    setShowResult(true);
    setLoading(true);
    setQuestion(newQuestion);
    setPromptValue("");
    setAnswer("");
    setCurrentResearchId(null); // Reset current research ID for new research
    setOrderedData((prevOrder) => [...prevOrder, { type: 'question', content: newQuestion }]);

    initializeWebSocket(newQuestion, { ...chatBoxSettings, report_type: "multi_agents" });
  };

  // All device sizes use the same research workflow and selected documents.
  const handleMobileDisplayResult = handleDisplayResult;

  // Mobile-specific chat handler
  const handleMobileChat = async (message: string) => {
    // Set states for UI feedback
    setIsProcessingChat(true);
    
    // Format user message
    const userMessage = {
      role: 'user',
      content: message
    };
    
    // Add question to UI immediately
    const questionData: QuestionData = { 
      type: 'question', 
      content: message 
    };
    
    setOrderedData(prevOrder => [...prevOrder, questionData]);
    
    try {
      // Direct API call instead of websockets
      const response = await fetch('/api/chat', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          messages: [userMessage],
          report: answer || '',
          report_source: chatBoxSettings.report_source || 'web',
          tone: chatBoxSettings.tone || 'Objective'
        }),
        // Set reasonable timeout
        signal: AbortSignal.timeout(20000) // 20-second timeout
      });
      
      if (!response.ok) {
        throw new Error(`API error: ${response.status}`);
      }
      
      const data = await response.json();
      
      if (data.response && data.response.content) {
        // Add AI response to chat history asynchronously
        if (currentResearchId) {
          addChatMessage(currentResearchId, data.response).catch(error => {
            console.error('Error adding AI response to history:', error);
          });
          
          // Also update the research with the new messages
          const chatData: ChatData = { 
            type: 'chat', 
            content: data.response.content,
            metadata: data.response.metadata 
          };
          
          setOrderedData(prevOrder => [...prevOrder, chatData]);
          
          // Get current ordered data and add new messages
          const updatedOrderedData = [...orderedData, questionData, chatData];
          
          // Update research in history
          updateResearch(
            currentResearchId, 
            answer, 
            updatedOrderedData
          ).catch(error => {
            console.error('Error updating research:', error);
          });
        } else {
          // If no research ID, just update the UI
          setOrderedData(prevOrder => [...prevOrder, { 
            type: 'chat', 
            content: data.response.content,
            metadata: data.response.metadata
          } as ChatData]);
        }
      } else {
        // Show error message
        setOrderedData(prevOrder => [...prevOrder, { 
          type: 'chat', 
          content: '抱歉，出现了问题，请重试。'
        } as ChatData]);
      }
    } catch (error) {
      console.error('Error during mobile chat:', error);
      
      // Add error message
      setOrderedData(prevOrder => [...prevOrder, { 
        type: 'chat', 
        content: '抱歉，处理你的请求时发生错误，请重试。'
      } as ChatData]);
    } finally {
      setIsProcessingChat(false);
      setChatPromptValue('');
    }
  };

  const reset = () => {
    // Reset UI states
    setShowResult(false);
    setPromptValue("");
    setIsStopped(false);
    setIsInChatMode(false);
    setCurrentResearchId(null); // Reset research ID
    setIsProcessingChat(false);
    
    // Clear previous research data
    setQuestion("");
    setAnswer("");
    setOrderedData([]);
    setAllLogs([]);

    // Reset feedback states
    setShowHumanFeedback(false);
    setQuestionForHuman(false);
    
    // Clean up connections
    closeConnection();
    setLoading(false);
  };

  const handleClickSuggestion = (value: string) => {
    setPromptValue(value);
    const element = document.getElementById('input-area');
    if (element) {
      element.scrollIntoView({ behavior: 'smooth' });
    }
  };

  /**
   * Handles stopping the current research
   * - Closes WebSocket connection
   * - Stops loading state
   * - Marks research as stopped
   * - Preserves current results
   */
  const handleStopResearch = () => {
    closeConnection();
    setLoading(false);
    setIsStopped(true);
    
    setShowHumanFeedback(false);
  };

  /**
   * Handles starting a new research
   * - Clears all previous research data and states
   * - Resets UI to initial state
   * - Closes any existing WebSocket connections
   */
  const handleStartNewResearch = () => {
    reset();
    setSidebarOpen(false);
  };

  const handleCopyUrl = () => {
    if (!currentResearchId) return;
    
    const url = `${window.location.origin}/research/${currentResearchId}`;
    navigator.clipboard.writeText(url)
      .then(() => {
        toast.success("链接已复制到剪贴板！");
      })
      .catch(() => {
        toast.error("复制链接失败");
      });
  };

  // Add a ref to track if an update is in progress to prevent infinite loops
  const isUpdatingRef = useRef(false);
  const lastSyncedResearchIdRef = useRef<string | null>(null);

  // Save or update research in history based on mode
  useEffect(() => {
    // Define an async function inside the effect
    const saveOrUpdateResearch = async () => {
      // Prevent infinite loops by checking if we're already updating
      if (isUpdatingRef.current) return;
      
      if (showResult && !loading && !isStopped && answer && question && orderedData.length > 0) {
        if (isInChatMode && currentResearchId) {
          // Prevent redundant updates by checking if data has changed
          try {
            const currentResearch = await getResearchById(currentResearchId);
            if (currentResearch && (currentResearch.answer !== answer || JSON.stringify(currentResearch.orderedData) !== JSON.stringify(orderedData))) {
              isUpdatingRef.current = true;
              await updateResearch(currentResearchId, answer, orderedData);
              // Reset the flag after a short delay to allow state updates to complete
              setTimeout(() => {
                isUpdatingRef.current = false;
              }, 100);
            }
          } catch (error) {
            console.error('Error updating research:', error);
            isUpdatingRef.current = false;
          }
        } else if (!isInChatMode && currentResearchId) {
          if (lastSyncedResearchIdRef.current !== currentResearchId) {
            lastSyncedResearchIdRef.current = currentResearchId;
            await refreshHistory();
          }
        } else if (!isInChatMode) {
          // Check if this is a new research (not loaded from history)
          const isNewResearch = !history.some(item => 
            item.question === question && item.answer === answer
          );
          
          if (isNewResearch) {
            isUpdatingRef.current = true;
            try {
              const newId = await saveResearch(question, answer, orderedData);
              setCurrentResearchId(newId);
              
              // Don't navigate to the research page URL anymore
              // Just save the ID for sharing purposes
              
            } catch (error) {
              console.error('Error saving research:', error);
            } finally {
              // Reset the flag after a short delay to allow state updates to complete
              setTimeout(() => {
                isUpdatingRef.current = false;
              }, 100);
            }
          }
        }
      }
    };
    
    // Call the async function
    saveOrUpdateResearch();
  }, [showResult, loading, isStopped, answer, question, orderedData, history, saveResearch, updateResearch, isInChatMode, currentResearchId, getResearchById, refreshHistory]);

  // Handle selecting a research from history
  const handleSelectResearch = async (id: string) => {
    try {
      const research = await getResearchById(id);
      if (research) {
        // Navigate to the research page instead of loading it here
        router.push(`/research/${id}`);
      }
    } catch (error) {
      console.error('Error selecting research:', error);
      toast.error('无法加载所选研究');
    }
  };

  // Toggle sidebar
  const toggleSidebar = () => {
    setSidebarOpen(!sidebarOpen);
  };

  /**
   * Processes ordered data into logs for display
   * Updates whenever orderedData changes
   */
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

  // Layout is a UI preference; research settings are deliberately not persisted.
  useEffect(() => {
    localStorage.setItem('gptrUiLayout', chatBoxSettings.layoutType || 'copilot');
  }, [chatBoxSettings.layoutType]);

  // Set chat mode when a report is complete
  useEffect(() => {
    if (showResult && !loading && !isStopped && answer && !isInChatMode) {
      setIsInChatMode(true);
    }
  }, [showResult, loading, isStopped, answer, isInChatMode]);

  // Update the renderMobileContent function to use both mobile-specific functions
  const renderMobileContent = () => {
    if (!showResult) {
      return (
        <MobileHomeScreen
          promptValue={promptValue}
          setPromptValue={setPromptValue}
          handleDisplayResult={handleMobileDisplayResult}
          isLoading={loading}
        />
      );
    } else {
      return (
        <MobileResearchContent
          orderedData={orderedData}
          answer={answer}
          loading={loading}
          isStopped={isStopped}
          chatPromptValue={chatPromptValue}
          setChatPromptValue={setChatPromptValue}
          handleChat={handleMobileChat} // Use mobile-specific chat handler
          isProcessingChat={isProcessingChat}
          onNewResearch={handleStartNewResearch}
          currentResearchId={currentResearchId || undefined}
          onShareClick={currentResearchId ? handleCopyUrl : undefined}
        />
      );
    }
  };

  return (
    <>
      {isMobile ? (
        // Mobile view - simplified layout with focus on chat
        getAppropriateLayout({
          loading,
          isStopped,
          showResult,
          onStop: handleStopResearch,
          onNewResearch: handleStartNewResearch,
          chatBoxSettings,
          setChatBoxSettings,
          mainContentRef,
          toggleSidebar,
          isProcessingChat,
          children: renderMobileContent()
        })
      ) : !showResult ? (
        // Desktop view - home page
        getAppropriateLayout({
          loading,
          isStopped,
          showResult,
          onStop: handleStopResearch,
          onNewResearch: handleStartNewResearch,
          chatBoxSettings,
          setChatBoxSettings,
          mainContentRef,
          showScrollButton,
          onScrollToBottom: scrollToBottom,
          children: (
            <>
              <ResearchSidebar
                history={history}
                onSelectResearch={handleSelectResearch}
                onNewResearch={handleStartNewResearch}
                onDeleteResearch={deleteResearch}
                isOpen={sidebarOpen}
                toggleSidebar={toggleSidebar}
              />
              
              <Hero
                promptValue={promptValue}
                setPromptValue={setPromptValue}
                handleDisplayResult={handleDisplayResult}
                chatBoxSettings={chatBoxSettings}
                setChatBoxSettings={setChatBoxSettings}
              />
            </>
          )
        })
      ) : (
        // Desktop view - research results
        getAppropriateLayout({
          loading,
          isStopped,
          showResult,
          onStop: handleStopResearch,
          onNewResearch: handleStartNewResearch,
          chatBoxSettings,
          setChatBoxSettings,
          mainContentRef,
          children: (
            <div className="relative">
              <ResearchSidebar
                history={history}
                onSelectResearch={handleSelectResearch}
                onNewResearch={handleStartNewResearch}
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
                  loading={loading}
                  isStopped={isStopped}
                  promptValue={promptValue}
                  chatPromptValue={chatPromptValue}
                  setPromptValue={setPromptValue}
                  setChatPromptValue={setChatPromptValue}
                  handleDisplayResult={handleDisplayResult}
                  handleChat={handleChat}
                  handleClickSuggestion={handleClickSuggestion}
                  currentResearchId={currentResearchId || undefined}
                  onShareClick={currentResearchId ? handleCopyUrl : undefined}
                  reset={reset}
                  isProcessingChat={isProcessingChat}
                  onNewResearch={handleStartNewResearch}
                  toggleSidebar={toggleSidebar}
                />
              ) : (
                <ResearchContent
                  showResult={showResult}
                  orderedData={orderedData}
                  answer={answer}
                  allLogs={allLogs}
                  chatBoxSettings={chatBoxSettings}
                  loading={loading}
                  isInChatMode={isInChatMode}
                  isStopped={isStopped}
                  promptValue={promptValue}
                  chatPromptValue={chatPromptValue}
                  setPromptValue={setPromptValue}
                  setChatPromptValue={setChatPromptValue}
                  handleDisplayResult={handleDisplayResult}
                  handleChat={handleChat}
                  handleClickSuggestion={handleClickSuggestion}
                  currentResearchId={currentResearchId || undefined}
                  onShareClick={currentResearchId ? handleCopyUrl : undefined}
                  reset={reset}
                  isProcessingChat={isProcessingChat}
                />
              )}
              
              {showHumanFeedback && false && (
                <HumanFeedback
                  questionForHuman={questionForHuman}
                  websocket={socket}
                  onFeedbackSubmit={handleFeedbackSubmit}
                />
              )}
            </div>
          )
        })
      )}
    </>
  );
}
