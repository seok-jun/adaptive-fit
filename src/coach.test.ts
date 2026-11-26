import { fetch } from 'expo/fetch';
import {
  CoachError,
  DEFAULT_SELECTION,
  isPlan,
  isSelection,
  requestCoach,
  type Context,
  type Selection,
} from './coach';

jest.mock('expo/fetch', () => ({ fetch: jest.fn() }));

const fetchMock = jest.mocked(fetch);
const context: Context = { goal: '전신 운동', minutes: 15, equipment: '맨몸' };
const plan = {
  reason: '전신 준비에 도움이 됩니다.',
  exercises: [{ id: 'bodyweight-squat', minutes: 5, instruction: '천천히 앉고 일어나세요.' }],
};
const openAiKey = 'test-openai-key';
const claudeKey = 'test-anthropic-key';
const sonnet: Selection = { provider: 'anthropic', model: 'claude-sonnet-4-6', effort: 'medium' };
const haiku: Selection = { provider: 'anthropic', model: 'claude-haiku-4-5-20251001', effort: 'default' };

function openAiReply(planText: string = JSON.stringify(plan)) {
  return {
    status: 'completed',
    output: [
      { type: 'reasoning', summary: [] },
      {
        type: 'message',
        role: 'assistant',
        status: 'completed',
        content: [{ type: 'output_text', text: planText }],
      },
    ],
  };
}

function claudeReply(planText: string = JSON.stringify(plan)) {
  return {
    stop_reason: 'end_turn',
    content: [
      { type: 'thinking', thinking: '합성 테스트' },
      { type: 'text', text: planText },
    ],
  };
}

function respond(payload: unknown, status = 200) {
  const json = jest.fn().mockResolvedValue(payload);
  fetchMock.mockResolvedValueOnce({ ok: status === 200, status, json } as unknown as Awaited<ReturnType<typeof fetch>>);
  return json;
}

function requestBody() {
  const body = fetchMock.mock.calls[0][1]?.body;
  if (typeof body !== 'string') {
    throw new Error('Expected a serialized body');
  }
  return JSON.parse(body);
}

beforeEach(() => {
  fetchMock.mockReset();
});

it('sends only the selected context and OpenAI credential in a structured Responses request', async () => {
  respond(openAiReply());
  const input = { ...context, history: '합성 과거 기록' };
  await expect(requestCoach({ ...DEFAULT_SELECTION, effort: 'low' }, input, openAiKey)).resolves.toEqual(plan);

  expect(fetchMock).toHaveBeenCalledTimes(1);
  expect(fetchMock).toHaveBeenCalledWith('https://api.openai.com/v1/responses', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer test-openai-key' },
    body: expect.any(String),
    redirect: 'error',
    credentials: 'omit',
    signal: expect.anything(),
  });
  const body = requestBody();
  expect(body).toMatchObject({
    model: 'gpt-5.4-mini',
    store: false,
    reasoning: { effort: 'low' },
    text: { format: { type: 'json_schema', name: 'workout_plan', strict: true } },
  });
  expect(JSON.parse(body.input)).toEqual(context);
  expect(body.text.format.schema.properties.exercises.items.properties.id.enum)
    .not.toContain('dumbbell-squat');
  expect(JSON.stringify(body)).not.toContain(openAiKey);
});

it('sends the Anthropic credential, API version, schema, effort and supported thinking', async () => {
  respond(claudeReply());
  await expect(requestCoach(sonnet, context, claudeKey)).resolves.toEqual(plan);

  expect(fetchMock).toHaveBeenCalledTimes(1);
  expect(fetchMock).toHaveBeenCalledWith('https://api.anthropic.com/v1/messages', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'x-api-key': claudeKey,
      'anthropic-version': '2023-06-01',
    },
    body: expect.any(String),
    redirect: 'error',
    credentials: 'omit',
    signal: expect.anything(),
  });
  const body = requestBody();
  expect(body).toMatchObject({
    model: 'claude-sonnet-4-6',
    messages: [{ role: 'user', content: JSON.stringify(context) }],
    output_config: { format: { type: 'json_schema' }, effort: 'medium' },
    thinking: { type: 'adaptive' },
  });
  expect(JSON.stringify(body)).not.toContain(claudeKey);
});

it('omits default reasoning from OpenAI requests', async () => {
  respond(openAiReply());
  await requestCoach(DEFAULT_SELECTION, context, openAiKey);
  expect(requestBody()).not.toHaveProperty('reasoning');
});

it.each([{ ...sonnet, effort: 'default' }, haiku])(
  'omits default effort and thinking fields for $model',
  async selection => {
    respond(claudeReply());
    await requestCoach(selection, context, claudeKey);
    const body = requestBody();
    expect(body).toHaveProperty('output_config.format.type', 'json_schema');
    expect(body).not.toHaveProperty('output_config.effort');
    expect(body).not.toHaveProperty('thinking');
  },
);

it('rejects unsupported or mismatched selections before networking', async () => {
  const unsupported = { ...haiku, effort: 'high' };
  const mismatched = { ...DEFAULT_SELECTION, provider: 'anthropic' as const };
  expect(isSelection(DEFAULT_SELECTION)).toBe(true);
  expect(isSelection(unsupported)).toBe(false);
  expect(isSelection(mismatched)).toBe(false);
  await expect(requestCoach(unsupported, context, claudeKey)).rejects.toThrow(CoachError);
  await expect(requestCoach(mismatched, context, claudeKey)).rejects.toThrow(CoachError);
  expect(fetchMock).not.toHaveBeenCalled();
});

it('rejects missing keys and values outside the three inputs before networking', async () => {
  await expect(requestCoach(DEFAULT_SELECTION, context, '')).rejects.toThrow('API 키');
  await expect(requestCoach(DEFAULT_SELECTION, { ...context, equipment: '바벨' }, openAiKey))
    .rejects.toThrow('목표, 시간과 기구');
  expect(fetchMock).not.toHaveBeenCalled();
});

it('permits a dumbbell workout with selected dumbbell equipment', async () => {
  const dumbbellPlan = {
    ...plan,
    exercises: [{ ...plan.exercises[0], id: 'dumbbell-row' }],
  };
  respond(openAiReply(JSON.stringify(dumbbellPlan)));
  await expect(requestCoach(DEFAULT_SELECTION, { ...context, equipment: '덤벨' }, openAiKey))
    .resolves.toEqual(dumbbellPlan);
});

it.each([
  ['unknown ID', [{ ...plan.exercises[0], id: 'unknown' }]],
  ['equipment mismatch', [{ ...plan.exercises[0], id: 'dumbbell-row' }]],
  ['over budget', [{ ...plan.exercises[0], minutes: 9 }, { ...plan.exercises[0], minutes: 7 }]],
  ['noninteger duration', [{ ...plan.exercises[0], minutes: 1.5 }]],
  ['nonpositive duration', [{ ...plan.exercises[0], minutes: 0 }]],
  ['no exercises', []],
  ['non-Korean instruction', [{ ...plan.exercises[0], instruction: 'Sit down slowly.' }]],
  ['unexpected key', [{ ...plan.exercises[0], weight: 25 }]],
])('rejects a plan with %s', async (_description, exercises) => {
  respond(openAiReply(JSON.stringify({ ...plan, exercises })));
  await expect(requestCoach(DEFAULT_SELECTION, context, openAiKey)).rejects.toThrow('코칭 응답');
});

it('validates approved plan structure and domain when loading it again', () => {
  expect(isPlan(plan)).toBe(true);
  expect(isPlan({ ...plan, reason: '   ' })).toBe(false);
  expect(isPlan({ ...plan, exercises: [{ ...plan.exercises[0], minutes: 46 }] })).toBe(false);
  expect(isPlan({ ...plan, exercises: [{ ...plan.exercises[0], id: '__proto__' }] })).toBe(false);
});

it('rejects an incomplete response, a refusal, and malformed JSON', async () => {
  respond({ ...openAiReply(), status: 'incomplete' });
  respond({ ...claudeReply(), stop_reason: 'refusal' });
  respond(openAiReply('malformed'));
  await expect(requestCoach(DEFAULT_SELECTION, context, openAiKey)).rejects.toThrow('코칭 응답');
  await expect(requestCoach(sonnet, context, claudeKey)).rejects.toThrow('코칭 응답');
  await expect(requestCoach(DEFAULT_SELECTION, context, openAiKey)).rejects.toThrow('코칭 응답');
  expect(fetchMock).toHaveBeenCalledTimes(3);
});

it.each([
  [401, '인증에 실패'],
  [404, '모델이나 추론 강도'],
  [429, '요청 한도'],
  [504, '시간이 초과'],
])('reports HTTP %i without reading or showing sensitive error bodies', async (status, message) => {
  const json = respond({ error: { message: claudeKey, context } }, status);
  await expect(requestCoach(sonnet, context, claudeKey)).rejects.toThrow(message);
  expect(json).not.toHaveBeenCalled();
  expect(fetchMock).toHaveBeenCalledTimes(1);
});

it('does not follow a redirect or expose the transport error', async () => {
  fetchMock.mockRejectedValueOnce(new Error(`redirected to https://invalid.example/ with ${claudeKey}`));
  await expect(requestCoach(sonnet, context, claudeKey)).rejects.toThrow('공급자에 연결하지 못했습니다');
  expect(fetchMock).toHaveBeenCalledTimes(1);
  expect(fetchMock.mock.calls[0][1]?.redirect).toBe('error');
});

it('applies the deadline to an unfinished response body and aborts without retrying', async () => {
  jest.useFakeTimers();
  try {
    const json = respond(null);
    let finishBody: (value: unknown) => void = () => {};
    json.mockImplementationOnce(() => new Promise(resolve => { finishBody = resolve; }));
    const result = requestCoach(sonnet, context, claudeKey);
    const assertion = expect(result).rejects.toThrow('시간이 초과');
    await Promise.resolve();
    expect(json).toHaveBeenCalledTimes(1);
    jest.advanceTimersByTime(90_000);
    await assertion;
    expect(fetchMock.mock.calls[0][1]?.signal?.aborted).toBe(true);
    finishBody(claudeReply());
    await assertion;
    expect(fetchMock).toHaveBeenCalledTimes(1);
  } finally {
    jest.useRealTimers();
  }
});
