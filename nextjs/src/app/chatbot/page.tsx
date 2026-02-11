'use client';

import { useEffect, useRef, useState } from "react";
import { PipelineShell } from "@/components/PipelineShell";
import { StageHero } from "@/components/StageHero";
import clsx from "clsx";

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
        className={clsx(
          "max-w-[90%] sm:max-w-[80%] rounded-2xl px-4 py-3 shadow-sm",
          isUser ? "bg-black text-white" : "bg-white text-[#111215] border border-slate-100"
        )}
      >
        {isJson ? (
          <div className="flex flex-col gap-2">
            <div className="flex items-center justify-between text-[10px] font-bold uppercase tracking-wider text-slate-400">
              <span>JSON response</span>
              <button
                type="button"
                onClick={() => downloadJson(content)}
                className="rounded-full border border-slate-200 bg-white px-2 py-0.5 text-[10px] font-bold text-slate-700 transition hover:bg-slate-100"
              >
                Download
              </button>
            </div>
            <pre className="max-h-60 sm:max-h-80 overflow-auto whitespace-pre-wrap break-words font-mono text-[11px] sm:text-xs text-slate-600 bg-slate-50 p-2 rounded-lg">
              {JSON.stringify(content, null, 2)}
            </pre>
          </div>
        ) : (
          <div className="whitespace-pre-wrap break-words text-sm leading-relaxed">{content}</div>
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
      <StageHero
        title="Chatbot"
        description="Ask natural-language questions about your content. Responses stream from the Spring Boot ChatBotController."
      />

      <main className="mx-auto w-full max-w-4xl px-4 py-6 sm:px-6 sm:py-10">
        <div className="flex flex-col gap-4 rounded-3xl border border-slate-200 bg-white px-4 py-6 shadow-sm md:px-8">
          <div className="flex items-center justify-between border-b border-slate-50 pb-4">
            <div>
              <h2 className="text-xl font-bold text-black">Assistant</h2>
              <p className="text-xs font-medium text-slate-400">Powered by Spring Boot ChatBot</p>
            </div>
            <a href="/search" className="text-xs font-bold uppercase tracking-widest text-slate-400 hover:text-black transition-colors">
              Back to Search
            </a>
          </div>

          <div
            ref={containerRef}
            className="flex h-[50vh] sm:h-[60vh] flex-col gap-4 overflow-auto rounded-2xl bg-slate-50/50 p-4 scrollbar-thin scrollbar-thumb-slate-200"
          >
            {messages.map((message, index) => (
              <MessageBubble key={`${message.role}-${index}`} role={message.role} content={message.content} />
            ))}
            {isLoading && (
              <div className="flex items-center gap-2 self-start rounded-2xl bg-white px-4 py-3 text-sm text-slate-400 shadow-sm border border-slate-100">
                <span className="flex gap-1">
                  <span className="size-1.5 animate-bounce rounded-full bg-slate-300" style={{ animationDelay: '0ms' }} />
                  <span className="size-1.5 animate-bounce rounded-full bg-slate-300" style={{ animationDelay: '150ms' }} />
                  <span className="size-1.5 animate-bounce rounded-full bg-slate-300" style={{ animationDelay: '300ms' }} />
                </span>
                Thinking…
              </div>
            )}
          </div>

          <div className="flex items-end gap-2 rounded-2xl border border-slate-200 bg-white p-2 focus-within:border-black transition-colors shadow-sm">
            <textarea
              value={input}
              onChange={(event) => setInput(event.target.value)}
              onKeyDown={onKeyDown}
              rows={2}
              placeholder="Type a message..."
              className="flex-1 resize-none border-0 p-3 text-sm outline-none placeholder:text-slate-400"
            />
            <button
              type="button"
              onClick={() => void send()}
              disabled={isLoading || !input.trim()}
              className="rounded-xl bg-black px-5 py-3 text-xs font-bold uppercase tracking-widest text-white transition hover:bg-slate-800 disabled:opacity-20 shadow-lg shadow-black/10"
            >
              Send
            </button>
          </div>
        </div>
      </main>
    </PipelineShell>
  );
}