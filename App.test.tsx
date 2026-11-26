import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import App from './App';
import { CoachError, requestCoach, type Plan, type Selection } from './src/coach';
import { approvePlan, deleteKey, hasKey, loadPlan, loadSelection, readKey, saveKey, saveSelection } from './src/storage';

jest.mock('react-native-safe-area-context', () => {
  const { View } = jest.requireActual<typeof import('react-native')>('react-native');
  return { SafeAreaProvider: View, SafeAreaView: View };
});

jest.mock('./src/coach', () => ({
  CoachError: class extends Error {},
  DEFAULT_SELECTION: { provider: 'openai', model: 'sample-openai', effort: 'default' },
  MODELS: [
    { provider: 'openai', id: 'sample-openai', label: 'OpenAI Standard', efforts: ['default', 'low', 'high'] },
    { provider: 'openai', id: 'sample-instant', label: 'OpenAI Instant', efforts: ['default'] },
    { provider: 'anthropic', id: 'sample-claude', label: 'Claude Standard', efforts: ['default', 'low', 'high'] },
  ],
  exerciseLabel: () => '맨몸 스쿼트',
  isSelection: (selection: Selection) => [
    ['openai', 'sample-openai', 'default'], ['openai', 'sample-openai', 'high'],
    ['openai', 'sample-instant', 'default'], ['anthropic', 'sample-claude', 'low'],
  ].some(([provider, model, effort]) => selection.provider === provider && selection.model === model && selection.effort === effort),
  requestCoach: jest.fn(),
}));

jest.mock('./src/storage', () => ({
  StorageError: class extends Error {},
  approvePlan: jest.fn(),
  deleteKey: jest.fn(),
  hasKey: jest.fn(),
  loadPlan: jest.fn(),
  loadSelection: jest.fn(),
  readKey: jest.fn(),
  saveKey: jest.fn(),
  saveSelection: jest.fn(),
}));

const selection: Selection = { provider: 'openai', model: 'sample-openai', effort: 'high' };
const original: Plan = {
  reason: '이전에 승인한 계획입니다.',
  exercises: [{ id: 'bodyweight-squat', minutes: 5, instruction: '가볍게 움직이세요.' }],
};
const proposal: Plan = {
  reason: '새로 제안한 운동입니다.',
  exercises: [{ id: 'bodyweight-squat', minutes: 10, instruction: '천천히 앉고 일어나세요.' }],
};

const mocks = {
  approvePlan: jest.mocked(approvePlan),
  deleteKey: jest.mocked(deleteKey),
  hasKey: jest.mocked(hasKey),
  loadPlan: jest.mocked(loadPlan),
  loadSelection: jest.mocked(loadSelection),
  readKey: jest.mocked(readKey),
  request: jest.mocked(requestCoach),
  saveKey: jest.mocked(saveKey),
  saveSelection: jest.mocked(saveSelection),
};

beforeEach(() => {
  jest.resetAllMocks();
  mocks.loadSelection.mockResolvedValue(selection);
  mocks.loadPlan.mockResolvedValue(original);
  mocks.hasKey.mockImplementation(async (provider) => provider === 'openai');
  mocks.readKey.mockResolvedValue('fixture-key');
  mocks.request.mockResolvedValue(proposal);
});

function press(name: string, role: 'button' | 'tab' | 'radio' = 'button') {
  fireEvent.press(screen.getByRole(role, { name }));
}

async function openCoach() {
  const view = render(<App />);
  await screen.findByRole('header', { name: '오늘의 코칭' });
  return view;
}

test('restores saved settings and a previous plan, and waits for explicit consent on every request', async () => {
  await openCoach();
  expect(screen.getByText('이전에 승인한 계획입니다.')).toBeTruthy();
  press('15분', 'radio');
  press('덤벨', 'radio');
  press('코칭 요청 검토');
  expect(screen.getByText(/개인 API 계정에 요금/)).toBeTruthy();
  expect(screen.getByText('OpenAI Standard')).toBeTruthy();
  expect(mocks.readKey).not.toHaveBeenCalled();
  expect(mocks.request).not.toHaveBeenCalled();
  press('취소');
  expect(mocks.request).not.toHaveBeenCalled();

  press('코칭 요청 검토');
  press('동의하고 전송');
  await screen.findByText('새로 제안한 운동입니다.');
  expect(mocks.readKey).toHaveBeenCalledTimes(1);
  expect(mocks.readKey).toHaveBeenCalledWith('openai');
  expect(mocks.request).toHaveBeenCalledTimes(1);
  expect(mocks.request).toHaveBeenCalledWith(selection, { goal: '전신 운동', minutes: 15, equipment: '덤벨' }, 'fixture-key');
  expect(mocks.approvePlan).not.toHaveBeenCalled();
  expect(screen.getByText('이전에 승인한 계획입니다.')).toBeTruthy();

  press('코칭 요청 검토');
  press('취소');
  expect(mocks.request).toHaveBeenCalledTimes(1);
  expect(screen.getByText('새로 제안한 운동입니다.')).toBeTruthy();
});

test('requires a separate plan approval, ignores repeat approval and restores the saved result', async () => {
  let releaseSave: (() => void) | undefined;
  mocks.approvePlan.mockImplementation(() => new Promise<void>((resolve) => { releaseSave = resolve; }));
  const view = await openCoach();
  press('코칭 요청 검토');
  press('동의하고 전송');
  await screen.findByText('새로 제안한 운동입니다.');
  expect(mocks.approvePlan).not.toHaveBeenCalled();

  press('계획 승인');
  expect(screen.getByRole('button', { name: '계획 저장 중' })).toBeDisabled();
  press('계획 저장 중');
  expect(mocks.approvePlan).toHaveBeenCalledTimes(1);
  expect(mocks.approvePlan).toHaveBeenCalledWith(expect.any(String), proposal);
  expect(screen.getByText('이전에 승인한 계획입니다.')).toBeTruthy();
  await act(async () => releaseSave?.());
  await screen.findByText('운동 계획을 저장했어요.');
  expect(screen.queryByText('이전에 승인한 계획입니다.')).toBeNull();

  view.unmount();
  mocks.loadPlan.mockResolvedValue(proposal);
  await openCoach();
  expect(screen.getByRole('header', { name: '마지막 승인 계획' })).toBeTruthy();
  expect(screen.getByText('새로 제안한 운동입니다.')).toBeTruthy();
  expect(screen.queryByRole('button', { name: '계획 승인' })).toBeNull();
});

test('registers, replaces and deletes a provider-specific key and only persists explicitly selected options', async () => {
  await openCoach();
  press('설정', 'tab');
  expect(screen.getByRole('radio', { name: '높음' })).toBeChecked();
  press('OpenAI Instant', 'radio');
  expect(screen.queryByRole('radio', { name: '높음' })).toBeNull();
  expect(screen.getByRole('radio', { name: '모델 기본값' })).toBeChecked();
  press('Claude', 'radio');
  press('낮음', 'radio');
  expect(mocks.saveSelection).not.toHaveBeenCalled();

  fireEvent.changeText(screen.getByLabelText('API 키 입력'), 'fixture-claude-key');
  expect(screen.getByLabelText('API 키 입력').props.secureTextEntry).toBe(true);
  press('키 등록');
  await screen.findByText('Claude 키를 기기에 저장했어요.');
  expect(mocks.saveKey).toHaveBeenLastCalledWith('anthropic', 'fixture-claude-key');
  expect(screen.getByLabelText('API 키 입력').props.value).toBe('');

  fireEvent.changeText(screen.getByLabelText('API 키 입력'), 'fixture-replacement');
  press('키 교체');
  await waitFor(() => expect(mocks.saveKey).toHaveBeenLastCalledWith('anthropic', 'fixture-replacement'));
  await screen.findByText('Claude 키를 기기에 저장했어요.');
  press('키 삭제');
  press('취소');
  expect(mocks.deleteKey).not.toHaveBeenCalled();
  press('키 삭제');
  press('삭제 확인');
  await screen.findByText('Claude 키를 삭제했어요.');
  expect(mocks.deleteKey).toHaveBeenCalledWith('anthropic');
  expect(mocks.readKey).not.toHaveBeenCalled();

  press('코치 설정 저장');
  await screen.findByRole('header', { name: '오늘의 코칭' });
  expect(mocks.saveSelection).toHaveBeenCalledWith({ provider: 'anthropic', model: 'sample-claude', effort: 'low' });
  press('코칭 요청 검토');
  expect(screen.getByText(/Claude API 키를 먼저 등록/)).toBeTruthy();
  expect(mocks.request).not.toHaveBeenCalled();
});

test('preserves the previous plan after provider failure and suppresses internal exception text', async () => {
  const providerError = new Error('개인 계정의 요청 한도를 확인해 주세요.');
  Object.setPrototypeOf(providerError, CoachError.prototype);
  mocks.request.mockRejectedValueOnce(providerError).mockRejectedValueOnce(new Error('fixture-private-details'));
  await openCoach();

  press('코칭 요청 검토');
  press('동의하고 전송');
  await screen.findByText('개인 계정의 요청 한도를 확인해 주세요.');
  expect(screen.getByText('이전에 승인한 계획입니다.')).toBeTruthy();
  expect(mocks.approvePlan).not.toHaveBeenCalled();

  press('코칭 요청 검토');
  press('동의하고 전송');
  await screen.findByText(/연결과 코치 설정을 확인한 뒤/);
  expect(screen.queryByText('fixture-private-details')).toBeNull();
  expect(screen.getByText('이전에 승인한 계획입니다.')).toBeTruthy();
});

test('prompts reselection for an invalid stored model and sends nothing with a fallback', async () => {
  mocks.loadSelection.mockResolvedValue({ ...selection, model: 'unavailable' });
  render(<App />);
  await screen.findByText(/공급자와 모델을 다시 선택/);
  press('코칭', 'tab');
  expect(screen.getByText('이전에 승인한 계획입니다.')).toBeTruthy();
  press('코칭 요청 검토');
  expect(screen.getByRole('header', { name: '코치 설정' })).toBeTruthy();
  expect(mocks.readKey).not.toHaveBeenCalled();
  expect(mocks.request).not.toHaveBeenCalled();
});
