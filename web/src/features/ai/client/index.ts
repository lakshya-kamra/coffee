// Picks the client by the provider's wire format (A§8.3 AnalysisRepository).
import type { ProviderApi } from "@/domain/ai/providers";
import { anthropicMessagesClient } from "./anthropicMessages";
import { openAiChatClient } from "./openaiChat";
import type { ChatClient } from "./types";

export function clientFor(api: ProviderApi): ChatClient {
  return api === "anthropic-messages" ? anthropicMessagesClient : openAiChatClient;
}

export { CancelledError } from "./transport";
export type * from "./types";
