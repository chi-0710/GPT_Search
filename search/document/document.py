import asyncio
import os
from typing import List, Union
from langchain_community.document_loaders import (
    PyMuPDFLoader,
    TextLoader,
    UnstructuredCSVLoader,
    UnstructuredExcelLoader,
    UnstructuredMarkdownLoader,
    UnstructuredPowerPointLoader,
    UnstructuredWordDocumentLoader
)
from langchain_community.document_loaders import BSHTMLLoader


class DocumentLoader:

    def __init__(self, path: Union[str, List[str]]):
        self.path = path

    async def load(self) -> list:
        tasks = []
        if isinstance(self.path, list):
            for file_path in self.path:
                if os.path.isfile(file_path):  # Ensure it's a valid file
                    filename = os.path.basename(file_path)
                    file_name, file_extension_with_dot = os.path.splitext(filename)
                    file_extension = file_extension_with_dot.strip(".").lower()
                    tasks.append(self._load_document(file_path, file_extension))
                    
        elif isinstance(self.path, (str, bytes, os.PathLike)):
            for root, dirs, files in os.walk(self.path):
                for file in files:
                    file_path = os.path.join(root, file)
                    file_name, file_extension_with_dot = os.path.splitext(file)
                    file_extension = file_extension_with_dot.strip(".").lower()
                    tasks.append(self._load_document(file_path, file_extension))
                    
        else:
            raise ValueError("Invalid type for path. Expected str, bytes, os.PathLike, or list thereof.")

        # for root, dirs, files in os.walk(self.path):
        #     for file in files:
        #         file_path = os.path.join(root, file)
        #         file_name, file_extension_with_dot = os.path.splitext(file_path)
        #         file_extension = file_extension_with_dot.strip(".")
        #         tasks.append(self._load_document(file_path, file_extension))

        docs = []
        for pages in await asyncio.gather(*tasks):
            for page in pages:
                if page.page_content and page.page_content.strip():
                    docs.append({
                        "raw_content": page.page_content,
                        "url": os.path.basename(page.metadata.get("source", "document")),
                        "metadata": {**page.metadata, "page_number": (page.metadata["page"] + 1)
                                     if isinstance(page.metadata.get("page"), int) else None}
                    })
                    
        if not docs:
            raise ValueError("未提取到文本；扫描版 PDF 需要 OCR，本版本暂不支持。")

        return docs

    async def _load_document(self, file_path: str, file_extension: str) -> list:
        loader_dict = {
                "pdf": PyMuPDFLoader,
                "txt": TextLoader,
                "doc": UnstructuredWordDocumentLoader,
                "docx": UnstructuredWordDocumentLoader,
                "pptx": UnstructuredPowerPointLoader,
                "csv": lambda path: UnstructuredCSVLoader(path, mode="elements"),
                "xls": lambda path: UnstructuredExcelLoader(path, mode="elements"),
                "xlsx": lambda path: UnstructuredExcelLoader(path, mode="elements"),
                "md": UnstructuredMarkdownLoader,
                "html": BSHTMLLoader,
                "htm": BSHTMLLoader
        }
        loader = loader_dict.get(file_extension)
        if loader is None:
            raise ValueError(f"不支持的文件格式：{file_extension}")
        try:
            return await asyncio.to_thread(lambda: loader(file_path).load())
        except Exception as exc:
            raise ValueError(f"{file_extension} 文件解析失败：{exc}") from exc
