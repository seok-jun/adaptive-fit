import { fetch } from 'expo/fetch';
import { z } from 'zod';

export type Provider = 'openai' | 'anthropic';
export type Selection = { provider: Provider; model: string; effort: string };
export type Context = { goal: string; minutes: number; equipment: string };

type Model = {
  provider: Provider;
  id: string;
  label: string;
  efforts: string[];
  thinking?: 'adaptive';
};

const openAiEfforts = ['default', 'none', 'low', 'medium', 'high', 'xhigh'];

export const MODELS: Model[] = [
  { provider: 'openai', id: 'gpt-5.4-mini', label: 'GPT-5.4 mini', efforts: openAiEfforts },
  { provider: 'openai', id: 'gpt-5.4', label: 'GPT-5.4', efforts: openAiEfforts },
  {
    provider: 'anthropic',
    id: 'claude-sonnet-4-6',
    label: 'Claude Sonnet 4.6',
    efforts: ['default', 'low', 'medium', 'high', 'max'],
    thinking: 'adaptive',
  },
  {
    provider: 'anthropic',
    id: 'claude-haiku-4-5-20251001',
    label: 'Claude Haiku 4.5',
    efforts: ['default'],
  },
];

export const DEFAULT_SELECTION: Selection = {
  provider: 'openai',
  model: 'gpt-5.4-mini',
  effort: 'default',
};

const selectionSchema = z.strictObject({
  provider: z.enum(['openai', 'anthropic']),
  model: z.string(),
  effort: z.string(),
});

function findModel(value: unknown): Model | undefined {
  const result = selectionSchema.safeParse(value);
  if (!result.success) {
    return undefined;
  }
  const { provider, model, effort } = result.data;
  return MODELS.find(candidate =>
    candidate.provider === provider && candidate.id === model && candidate.efforts.includes(effort),
  );
}

export function isSelection(value: unknown): value is Selection {
  return findModel(value) !== undefined;
}

const contextSchema = z.object({
  goal: z.enum(['전신 운동', '근력 향상', '컨디션 회복']),
  minutes: z.union([z.literal(15), z.literal(30), z.literal(45)]),
  equipment: z.enum(['맨몸', '덤벨']),
});

const exercises = {
  'bodyweight-squat': { label: '맨몸 스쿼트', equipment: '맨몸' },
  'wall-push-up': { label: '벽 푸시업', equipment: '맨몸' },
  'glute-bridge': { label: '글루트 브리지', equipment: '맨몸' },
  'bird-dog': { label: '버드독', equipment: '맨몸' },
  'dead-bug': { label: '데드버그', equipment: '맨몸' },
  'march-in-place': { label: '제자리 걷기', equipment: '맨몸' },
  'dumbbell-squat': { label: '덤벨 스쿼트', equipment: '덤벨' },
  'dumbbell-row': { label: '덤벨 로우', equipment: '덤벨' },
  'dumbbell-romanian-deadlift': { label: '덤벨 루마니안 데드리프트', equipment: '덤벨' },
  'dumbbell-floor-press': { label: '덤벨 플로어 프레스', equipment: '덤벨' },
} as const;

type ExerciseId = keyof typeof exercises;
const exerciseIds = Object.keys(exercises) as ExerciseId[];

export function exerciseLabel(id: string): string {
  return Object.prototype.hasOwnProperty.call(exercises, id)
    ? exercises[id as ExerciseId].label
    : id;
}

const planSchema = z.strictObject({
  reason: z.string(),
  exercises: z.array(z.strictObject({
    id: z.string(),
    minutes: z.number(),
    instruction: z.string(),
  })),
});

export type Plan = z.infer<typeof planSchema>;

const koreanText = /[\uac00-\ud7a3]/u;

function isCoachingText(text: string): boolean {
  return text.length <= 300 && koreanText.test(text);
}

function validPlan(plan: Plan, minutes: number, allowedIds: readonly string[]): boolean {
  if (!isCoachingText(plan.reason) || plan.exercises.length === 0) {
    return false;
  }
  let total = 0;
  for (const exercise of plan.exercises) {
    if (!allowedIds.includes(exercise.id) || !Number.isInteger(exercise.minutes) ||
        exercise.minutes < 1 || !isCoachingText(exercise.instruction)) {
      return false;
    }
    total += exercise.minutes;
  }
  return total <= minutes;
}

export function isPlan(value: unknown): value is Plan {
  const result = planSchema.safeParse(value);
  return result.success && validPlan(result.data, 45, exerciseIds);
}

function structuredOutput(allowedIds: readonly ExerciseId[]) {
  return {
    type: 'json_schema' as const,
    schema: {
      type: 'object',
      additionalProperties: false,
      required: ['reason', 'exercises'],
      properties: {
        reason: { type: 'string' },
        exercises: {
          type: 'array',
          items: {
            type: 'object',
            additionalProperties: false,
            required: ['id', 'minutes', 'instruction'],
            properties: {
              id: { type: 'string', enum: allowedIds },
              minutes: { type: 'integer' },
              instruction: { type: 'string' },
            },
          },
        },
      },
    },
  };
}

const responsePart = z.object({ type: z.string(), text: z.string().optional() });

const openAiResponse = z.object({
  status: z.literal('completed'),
  output: z.array(z.object({
    type: z.string(),
    status: z.string().optional(),
    phase: z.string().nullish(),
    role: z.string().optional(),
    content: z.array(responsePart).optional(),
  })),
});

const claudeResponse = z.object({
  stop_reason: z.literal('end_turn'),
  content: z.array(responsePart),
});

const REQUEST_TIMEOUT_MS = 90_000;
const MAX_OUTPUT_TOKENS = 4_096;
const INVALID_RESPONSE = '코칭 응답을 확인하지 못했습니다. 운동 시간과 기구를 확인하고 다시 요청해 주세요.';
const NETWORK_ERROR = '공급자에 연결하지 못했습니다. 인터넷 연결을 확인하고 다시 요청해 주세요.';
const TIMEOUT_ERROR = '코칭 요청 시간이 초과됐습니다. 계정 사용량을 확인한 뒤 다시 요청해 주세요.';

export class CoachError extends Error {}

function httpError(status: number): CoachError {
  switch (status) {
    case 401:
    case 403:
      return new CoachError('인증에 실패했습니다. 등록된 개인 키와 모델 사용 권한을 확인해 주세요.');
    case 429:
      return new CoachError('공급자의 요청 한도에 도달했습니다. 사용량을 확인하고 잠시 뒤 다시 요청해 주세요.');
    case 400:
    case 404:
    case 422:
      return new CoachError('공급자가 요청을 거절했습니다. 모델이나 추론 강도를 다시 선택해 주세요.');
    case 408:
    case 504:
      return new CoachError(TIMEOUT_ERROR);
    default:
      return new CoachError('공급자가 코칭 요청을 완료하지 못했습니다. 잠시 뒤 다시 요청해 주세요.');
  }
}

function extractText(provider: Provider, raw: unknown): string {
  if (provider === 'openai') {
    const response = openAiResponse.safeParse(raw);
    if (!response.success) {
      throw new CoachError(INVALID_RESPONSE);
    }
    const messages = response.data.output.filter(item =>
      item.type === 'message' && item.role === 'assistant' && item.phase !== 'commentary',
    );
    const message = messages[0];
    const content = message?.content?.[0];
    if (messages.length === 1 && message.status === 'completed' &&
        message.content?.length === 1 && content?.type === 'output_text' && content.text) {
      return content.text;
    }
  } else {
    const response = claudeResponse.safeParse(raw);
    if (response.success) {
      const parts = response.data.content.filter(part => part.type === 'text');
      const content = parts[0];
      if (parts.length === 1 && content.text) {
        return content.text;
      }
    }
  }
  throw new CoachError(INVALID_RESPONSE);
}

async function post(url: string, headers: Record<string, string>, body: object): Promise<unknown> {
  const controller = new AbortController();
  const expiresAt = Date.now() + REQUEST_TIMEOUT_MS;
  let timer: ReturnType<typeof setTimeout> | undefined;
  const deadline = new Promise<never>((_, reject) => {
    timer = setTimeout(() => {
      reject(new CoachError(TIMEOUT_ERROR));
      controller.abort();
    }, REQUEST_TIMEOUT_MS);
  });
  const readResponse = async (): Promise<unknown> => {
    const response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...headers },
      body: JSON.stringify(body),
      redirect: 'error',
      credentials: 'omit',
      signal: controller.signal,
    });
    if (!response.ok) {
      throw httpError(response.status);
    }
    try {
      return await response.json();
    } catch {
      throw new CoachError(INVALID_RESPONSE);
    }
  };
  try {
    const result = await Promise.race([deadline, readResponse()]);
    if (Date.now() >= expiresAt) throw new CoachError(TIMEOUT_ERROR);
    return result;
  } catch (error) {
    throw error instanceof CoachError ? error : new CoachError(NETWORK_ERROR);
  } finally {
    clearTimeout(timer);
  }
}

export async function requestCoach(selection: Selection, context: Context, key: string): Promise<Plan> {
  const model = findModel(selection);
  if (!model) {
    throw new CoachError('지원되는 공급자, 모델과 추론 강도를 다시 선택해 주세요.');
  }
  const cleanKey = key.trim();
  if (!/^[\x21-\x7e]+$/.test(cleanKey)) {
    throw new CoachError('설정에서 선택한 공급자의 개인 API 키를 확인해 주세요.');
  }
  const workout = contextSchema.safeParse(context);
  if (!workout.success) {
    throw new CoachError('운동 목표, 시간과 기구를 다시 선택해 주세요.');
  }

  const allowedIds = exerciseIds.filter(id =>
    exercises[id].equipment === '맨몸' || workout.data.equipment === '덤벨',
  );
  const catalog = allowedIds.map(id => `${id} (${exercises[id].label})`).join(', ');
  const instructions = [
    '입력한 목표, 시간, 기구만 바탕으로 오늘의 운동 한 회를 제안하세요.',
    `허용 운동 ID와 이름은 다음과 같습니다. ${catalog}`,
    '허용 ID의 운동만 제안하고 각 운동의 시간은 양의 정수 분으로 표시하세요.',
    '준비 운동과 쉬는 시간을 포함해 운동 시간 합이 입력 시간을 넘지 않도록 하세요.',
    '이유와 각 운동 지침을 한국어로 간결하게 쓰세요. 각 항목은 300자 이내입니다.',
    '운동 이력이나 건강 상태를 추정하지 마세요. 임의의 중량 증량, 강제 실패, 의료 조언은 피하고 통증이 생기면 중단하도록 안내하세요.',
  ].join(' ');
  const input = JSON.stringify(workout.data);
  const format = structuredOutput(allowedIds);
  const effort = selection.effort === 'default' ? undefined : selection.effort;

  let raw: unknown;
  if (model.provider === 'openai') {
    raw = await post('https://api.openai.com/v1/responses', { Authorization: `Bearer ${cleanKey}` }, {
      model: model.id,
      instructions,
      input,
      store: false,
      max_output_tokens: MAX_OUTPUT_TOKENS,
      text: { format: { ...format, name: 'workout_plan', strict: true } },
      ...(effort ? { reasoning: { effort } } : {}),
    });
  } else {
    raw = await post('https://api.anthropic.com/v1/messages', {
      'x-api-key': cleanKey,
      'anthropic-version': '2023-06-01',
    }, {
      model: model.id,
      system: instructions,
      messages: [{ role: 'user', content: input }],
      max_tokens: MAX_OUTPUT_TOKENS,
      output_config: { format, ...(effort ? { effort } : {}) },
      ...(effort && model.thinking ? { thinking: { type: model.thinking } } : {}),
    });
  }

  const text = extractText(model.provider, raw);
  let decoded: unknown;
  try {
    decoded = JSON.parse(text);
  } catch {
    throw new CoachError(INVALID_RESPONSE);
  }
  const result = planSchema.safeParse(decoded);
  if (!result.success || !validPlan(result.data, workout.data.minutes, allowedIds)) {
    throw new CoachError(INVALID_RESPONSE);
  }
  return result.data;
}
