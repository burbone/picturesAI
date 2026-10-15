export type GenerationStatus = "QUEUED" | "PROCESSING" | "COMPLETED" | "FAILED" | "CANCELLED";

export type Plan = "FREE" | "PRO";

export interface GenerationView {
  id: string;
  status: GenerationStatus;
  imageUrl: string | null;
  thumbnailUrl: string | null;
  prompt: string;
  tier: string | null;
  createdAt: string;
  completedAt: string | null;
}

export interface CursorPage<T> {
  items: T[];
  nextCursor: string | null;
}

export interface CreateGenerationRequest {
  prompt: string;
  negativePrompt?: string;
  model: string;
  size: string;
  count: number;
  isPublic: boolean;
}

export interface CreateGenerationResponse {
  jobId: string;
  batchId: string;
  count: number;
  status: GenerationStatus;
  tier: string | null;
}

export interface Author {
  id: string;
  username: string;
  displayName: string | null;
  avatarUrl: string | null;
}

export interface ImageResponse {
  id: string;
  userId: string;
  prompt: string;
  imageUrl: string;
  thumbnailUrl: string;
  provider: string;
  completedAt: string;
  tags: string[];
  author: Author | null;
}

export interface UserProfile {
  id: string;
  username: string;
  displayName: string | null;
  avatarUrl: string | null;
  bio: string | null;
  isPublic: boolean;
  plan: Plan;
  status: string;
}

export interface UpdateUserRequest {
  displayName?: string;
  avatarUrl?: string;
  bio?: string;
  isPublic?: boolean;
}

export interface BillingInfo {
  plan: Plan;
  dailyLimit: number;
  usedToday: number;
  remaining: number;
}

export interface BillingPlan {
  plan: Plan;
  dailyLimit: number;
}

export interface WsGenerationEvent {
  type: "generation.completed" | "generation.failed" | "generation.cancelled";
  generationId: string;
  userId: string;
  payload: {
    imageUrl?: string;
    thumbnailUrl?: string;
    provider?: string;
    completedAt?: string;
    reason?: string;
  };
}

export interface ApiErrorBody {
  code?: string;
  message?: string;
  error?: string;
}