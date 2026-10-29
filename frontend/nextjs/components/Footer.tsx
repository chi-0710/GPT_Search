import React, { useEffect } from 'react';
import Modal from './Settings/Modal';
import { ChatBoxSettings } from '@/types/data';

interface FooterProps {
  chatBoxSettings: ChatBoxSettings;
  setChatBoxSettings: React.Dispatch<React.SetStateAction<ChatBoxSettings>>;
}

const Footer: React.FC<FooterProps> = ({ chatBoxSettings, setChatBoxSettings }) => {
  // URL-provided domains apply to this session only.
  useEffect(() => {
    const urlParams = new URLSearchParams(window.location.search);
    const urlDomains = urlParams.get("domains");
    if (urlDomains) {
      setChatBoxSettings(previous => ({
        ...previous,
        domains: urlDomains.split(',').map(domain => domain.trim()).filter(Boolean),
      }));
    }
  }, [setChatBoxSettings]);

  return (
    <>
      <div className="container flex flex-col sm:flex-row min-h-[60px] sm:min-h-[72px] mt-2 items-center justify-center sm:justify-between border-t border-gray-700/30 px-4 pb-3 pt-4 sm:py-5 lg:px-0 bg-transparent backdrop-blur-sm gap-3 sm:gap-0">
        <Modal setChatBoxSettings={setChatBoxSettings} chatBoxSettings={chatBoxSettings} />
        <div className="text-xs sm:text-sm text-gray-100 text-center sm:text-left order-2 sm:order-1">
            © {new Date().getFullYear()} 知行研究助手。保留所有权利。
        </div>
      </div>
    </>
  );
};

export default Footer;
