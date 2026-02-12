'use client';

import { useEffect, useRef, useState } from "react";
import { PipelineShell } from "@/components/PipelineShell";

type ChatMessage = {
  role: "user" | "assistant";
  content: string | Record<string, unknown> | unknown[];
};

const downloadJson = (payload: unknown, filename?: string) => {
  try {
    const json = JSON.stringify(payload, null, 2);
    const blob = new Blob([json], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = filename ?? `chatbot-result-${Date.now()}.json`;
    link.click();
    URL.revokeObjectURL(url);
  } catch {
    // best-effort
  }
};

function MessageBubble({ role, content }: ChatMessage) {
  const isUser = role === "user";
  const isJson = typeof content !== "string";

  return (
    <div className={`flex w-full ${isUser ? "justify-end" : "justify-start"}`}>
      <div
        className={`${isUser ? "bg-primary text-white" : "bg-white text-[#111215]"} max-w-[80%] rounded-2xl px-4 py-3 shadow`}
      >
        {isJson ? (
          <div className="flex flex-col gap-2">
            <div className="flex items-center justify-between text-xs text-slate-500">
              <span>JSON response</span>
              <button
                type="button"
                onClick={() => downloadJson(content)}
                className="rounded-full border border-slate-200 bg-white px-3 py-1 text-xs font-semibold text-slate-700 transition hover:bg-slate-100"
              >
                Download JSON
              </button>
            </div>
            <pre className="max-h-80 overflow-auto whitespace-pre-wrap break-words font-mono text-sm">
              {JSON.stringify(content, null, 2)}
            </pre>
          </div>
        ) : (
          <div className="whitespace-pre-wrap break-words text-sm">{content}</div>
        )}
      </div>
    </div>
  );
}

export default function ChatbotPage() {
  const [input, setInput] = useState("Need all content for video-section-header");
  const [messages, setMessages] = useState<ChatMessage[]>([
    { role: "assistant", content: 'Ask me about a section, e.g., "video-section-header"' },
  ]);
  const [isLoading, setIsLoading] = useState(false);
  const containerRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (containerRef.current) {
      containerRef.current.scrollTop = containerRef.current.scrollHeight;
    }
  }, [messages]);

  const send = async () => {
    const trimmed = input.trim();
    if (!trimmed) return;
    setMessages((previous) => [...previous, { role: "user", content: trimmed }]);
    setInput("");
    setIsLoading(true);
    try {
      const response = await fetch("/api/chatbot/query", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message: trimmed, limit: 1000 }),
      });
      const payload = await response.json();
      if (!response.ok) {
        throw new Error(payload?.error ?? "Chatbot request failed.");
      }
      const body = payload.body ?? payload;
      const summary = Array.isArray(body)
        ? `Found ${body.length} items for your request.`
        : "Here are your results.";
      setMessages((previous) => [
        ...previous,
        { role: "assistant", content: summary },
        { role: "assistant", content: body },
      ]);
    } catch (error) {
      setMessages((previous) => [
        ...previous,
        {
          role: "assistant",
          content: error instanceof Error ? error.message : "Something went wrong. Please try again.",
        },
      ]);
    } finally {
      setIsLoading(false);
    }
  };

  const onKeyDown = (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      void send();
    }
  };

  return (
    <PipelineShell currentStep="ingestion" showTracker={false}>
      <div className="p-4 lg:p-8 max-w-[1600px] mx-auto">
        <div className="mb-8"><h1 className="text-2xl lg:text-3xl font-bold">Chatbot</h1></div>

      <main className="mx-auto w-full max-w-[1600px]">
        <div className="flex flex-col gap-4 card px-4 py-6 lg:px-8">
          <div className="flex items-center justify-between">
            <h2 className="text-2xl font-semibold text-gray-900">Chatbot</h2>
            <a href="/search" className="text-sm font-semibold text-primary hover:underline">
              Back to Search
            </a>
          </div>

          <div
            ref={containerRef}
            className="flex h-[60vh] flex-col gap-3 overflow-auto rounded-xl bg-gray-100 p-4"
          >
            {messages.map((message, index) => (
              <MessageBubble key={`${message.role}-${index}`} role={message.role} content={message.content} />
            ))}
            {isLoading && <div className="text-center text-sm text-gray-500">Thinking…</div>}
          </div>

          <div className="flex items-end gap-2 rounded-xl bg-white p-2 shadow">
            <textarea
              value={input}
              onChange={(event) => setInput(event.target.value)}
              onKeyDown={onKeyDown}
              rows={2}
              placeholder="Type a message..."
              className="flex-1 resize-none border-0 p-2 text-sm outline-none"
            />
            <button
              type="button"
              onClick={() => void send()}
              disabled={isLoading}
              className="rounded-lg bg-primary px-4 py-2 text-sm font-semibold text-white transition hover:bg-accent disabled:opacity-50"
            >
              Send
            </button>
          </div>
        </div>
      </main>
      </div>
    </PipelineShell>
  );
}