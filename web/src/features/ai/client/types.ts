/** A§8.3 `ChatClient` contract, shared by both wire formats (web-spec §14.4). */
import type { AiResult } from "@/domain/ai/aiError";
import type { Price } from "@/domain/ai/modelCatalog";
import type { AuthStyle, ProviderApi } from "@/domain/ai/providers";

export interface Endpoint {
  readonly providerId: string;
  /** Normalised base URL (checkBaseUrl). */
  readonly baseUrl: string;
  readonly host: string;
  readonly api: ProviderApi;
  readonly auth: AuthStyle;
  readonly extraHeaders: Readonly<Record<string, string>>;
  readonly browserHeaders: Readonly<Record<string, string>>;
  readonly modelsPath: string | null;
  /** The model list answers without a key, so none is sent to it. */
  readonly modelsPublic: boolean;
}

export interface AiImage {
  readonly label: string;
  readonly mediaType: "image/jpeg";
  readonly base64: string;
}

export interface AiRequest {
  readonly model: string;
  readonly system: string;
  readonly user: string;
  readonly images?: readonly AiImage[];
  readonly temperature?: number | null;
  readonly maxTokens: number;
}

export interface ChatResult {
  readonly content: string;
  readonly finishReason: string | null;
  readonly promptTokens: number | null;
  readonly completionTokens: number | null;
  readonly servedModel: string | null;
  readonly latencyMs: number;
}

export interface TestResult {
  readonly servedModel: string | null;
  readonly latencyMs: number;
  readonly reply: string;
}

export interface RemoteModel {
  readonly id: string;
  readonly name: string;
  readonly contextLength: number | null;
  /** OpenRouter's public list carries live per-token prices. */
  readonly price: Price | null;
}

export interface ChatClient {
  complete(e: Endpoint, apiKey: string | null, r: AiRequest, signal?: AbortSignal, timeoutMs?: number): Promise<AiResult<ChatResult>>;
  testConnection(e: Endpoint, apiKey: string | null, model: string, signal?: AbortSignal): Promise<AiResult<TestResult>>;
  listModels(e: Endpoint, apiKey: string | null, signal?: AbortSignal): Promise<AiResult<RemoteModel[]>>;
}
