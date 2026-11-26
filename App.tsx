import { useEffect, useRef, useState, type ReactNode } from 'react';
import {
  ActivityIndicator,
  Modal,
  Pressable,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import {
  CoachError,
  DEFAULT_SELECTION,
  MODELS,
  exerciseLabel,
  isSelection,
  requestCoach,
  type Context,
  type Plan,
  type Provider,
  type Selection,
} from './src/coach';
import {
  StorageError,
  approvePlan,
  deleteKey,
  hasKey,
  loadPlan,
  loadSelection,
  readKey,
  saveKey,
  saveSelection,
} from './src/storage';

const providers: Provider[] = ['openai', 'anthropic'];
const providerName: Record<Provider, string> = { openai: 'OpenAI', anthropic: 'Claude' };
const effortName: Record<string, string> = {
  default: '모델 기본값',
  none: '추론 안 함',
  minimal: '최소',
  low: '낮음',
  medium: '보통',
  high: '높음',
  xhigh: '매우 높음',
  max: '최대',
};
const goals = ['전신 운동', '근력 향상', '컨디션 회복'];
const durations = [15, 30, 45];
const equipment = ['맨몸', '덤벨'];
const initialContext: Context = { goal: goals[0], minutes: durations[1], equipment: equipment[0] };
const initialKeyStatus = { openai: null, anthropic: null };

type Page = 'coach' | 'settings';
type Operation = 'selection' | 'key' | 'delete' | 'send' | 'approve';
type KeyStatus = Record<Provider, boolean | null>;
type Review = { selection: Selection; context: Context };
type Draft = { id: string; plan: Plan };
type Notice = { message: string; tone: 'error' | 'success' };

function modelLabel(selection: Selection) {
  return MODELS.find((model) => model.id === selection.model && model.provider === selection.provider)?.label
    ?? selection.model;
}

function CoachApp() {
  const [page, setPage] = useState<Page>('coach');
  const [loading, setLoading] = useState(true);
  const [restoreAttempt, setRestoreAttempt] = useState(0);
  const [restoreFailed, setRestoreFailed] = useState(false);
  const [selection, setSelection] = useState<Selection | null>(null);
  const [editor, setEditor] = useState<Selection>(DEFAULT_SELECTION);
  const [keyStatus, setKeyStatus] = useState<KeyStatus>(initialKeyStatus);
  const [keyInput, setKeyInput] = useState('');
  const [deleteProvider, setDeleteProvider] = useState<Provider | null>(null);
  const [context, setContext] = useState<Context>(initialContext);
  const [review, setReview] = useState<Review | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [savedPlan, setSavedPlan] = useState<Plan | null>(null);
  const [operation, setOperation] = useState<Operation | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const activeOperation = useRef<Operation | null>(null);
  const pendingReview = useRef<Review | null>(null);
  const currentDraft = useRef<Draft | null>(null);
  const scroll = useRef<ScrollView>(null);

  useEffect(() => {
    let cancelled = false;

    async function restore() {
      const [preferences, plan, openAiKey, claudeKey] = await Promise.allSettled([
        loadSelection(), loadPlan(), hasKey('openai'), hasKey('anthropic'),
      ]);
      if (cancelled) return;

      let validSelection = false;
      let invalidSelection = false;
      if (preferences.status === 'fulfilled') {
        const stored = preferences.value;
        if (stored && isSelection(stored)) {
          setSelection(stored);
          setEditor(stored);
          validSelection = true;
        } else {
          setSelection(null);
          invalidSelection = stored !== null;
        }
      } else {
        setSelection(null);
        invalidSelection = true;
      }
      if (plan.status === 'fulfilled') setSavedPlan(plan.value);
      setKeyStatus({
        openai: openAiKey.status === 'fulfilled' ? openAiKey.value : null,
        anthropic: claudeKey.status === 'fulfilled' ? claudeKey.value : null,
      });
      const storageUnavailable = [preferences, plan, openAiKey, claudeKey]
        .some((result) => result.status === 'rejected');
      setRestoreFailed(storageUnavailable);
      if (invalidSelection) {
        setNotice({ message: '저장한 코치 설정을 사용할 수 없어요. 공급자와 모델을 다시 선택해 주세요.', tone: 'error' });
      } else if (storageUnavailable) {
        setNotice({ message: '저장한 항목 일부를 열지 못했어요. 다시 불러올 수 있어요.', tone: 'error' });
      }
      setPage(validSelection ? 'coach' : 'settings');
      setLoading(false);
    }

    void restore();
    return () => { cancelled = true; };
  }, [restoreAttempt]);

  function start(next: Operation) {
    if (activeOperation.current) return false;
    activeOperation.current = next;
    setOperation(next);
    setNotice(null);
    return true;
  }

  function finish() {
    activeOperation.current = null;
    setOperation(null);
  }

  function navigate(next: Page) {
    if (activeOperation.current || next === page) return;
    setPage(next);
    setKeyInput('');
    setNotice(null);
    scroll.current?.scrollTo({ y: 0, animated: false });
  }

  function editProvider(provider: Provider) {
    if (editor.provider === provider) return;
    const model = MODELS.find((entry) => entry.provider === provider);
    if (!model) return;
    setEditor({ provider, model: model.id, effort: 'default' });
    setKeyInput('');
    setNotice(null);
  }

  async function saveCoachSettings() {
    if (!isSelection(editor)) {
      setNotice({ message: '지원되는 모델과 추론 강도를 다시 선택해 주세요.', tone: 'error' });
      return;
    }
    if (!start('selection')) return;
    try {
      await saveSelection(editor);
      setSelection(editor);
      setKeyInput('');
      setPage('coach');
      setNotice({ message: '코치 설정을 저장했어요.', tone: 'success' });
      scroll.current?.scrollTo({ y: 0, animated: false });
    } catch (error) {
      setNotice({ message: storageMessage(error, '코치 설정을 저장하지 못했어요. 잠시 후 다시 시도해 주세요.'), tone: 'error' });
    } finally {
      finish();
    }
  }

  async function storeKey() {
    const value = keyInput.trim();
    if (!value || !start('key')) return;
    const provider = editor.provider;
    try {
      await saveKey(provider, value);
      setKeyStatus((previous) => ({ ...previous, [provider]: true }));
      setKeyInput('');
      setNotice({ message: `${providerName[provider]} 키를 기기에 저장했어요.`, tone: 'success' });
    } catch (error) {
      setNotice({ message: storageMessage(error, '키를 저장하지 못했어요. 입력값을 확인하고 다시 시도해 주세요.'), tone: 'error' });
    } finally {
      finish();
    }
  }

  async function removeKey() {
    const provider = deleteProvider;
    if (!provider || !start('delete')) return;
    setDeleteProvider(null);
    try {
      await deleteKey(provider);
      setKeyStatus((previous) => ({ ...previous, [provider]: false }));
      setKeyInput('');
      setNotice({ message: `${providerName[provider]} 키를 삭제했어요.`, tone: 'success' });
    } catch (error) {
      setNotice({ message: storageMessage(error, '키를 삭제하지 못했어요. 다시 시도해 주세요.'), tone: 'error' });
    } finally {
      finish();
    }
  }

  function beginReview() {
    if (activeOperation.current || pendingReview.current) return;
    if (!selection || !isSelection(selection)) {
      navigate('settings');
      setNotice({ message: '코칭에 쓸 공급자와 모델을 선택해 저장해 주세요.', tone: 'error' });
      return;
    }
    if (keyStatus[selection.provider] === false) {
      setEditor(selection);
      navigate('settings');
      setNotice({ message: `${providerName[selection.provider]} API 키를 먼저 등록해 주세요.`, tone: 'error' });
      return;
    }
    setNotice(null);
    const snapshot = { selection, context };
    pendingReview.current = snapshot;
    setReview(snapshot);
  }

  function closeReview() {
    pendingReview.current = null;
    setReview(null);
  }

  async function sendReview() {
    const snapshot = pendingReview.current;
    if (!snapshot || !start('send')) return;
    closeReview();
    currentDraft.current = null;
    setDraft(null);
    try {
      const key = await readKey(snapshot.selection.provider);
      if (!key) {
        setKeyStatus((previous) => ({ ...previous, [snapshot.selection.provider]: false }));
        setEditor(snapshot.selection);
        setPage('settings');
        setNotice({ message: '이 공급자의 API 키를 등록해 주세요.', tone: 'error' });
        return;
      }
      const plan = await requestCoach(snapshot.selection, snapshot.context, key);
      const id = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
      const result = { id, plan };
      currentDraft.current = result;
      setDraft(result);
    } catch (error) {
      const message = error instanceof CoachError
        ? error.message
        : storageMessage(error, '코칭을 받지 못했어요. 연결과 코치 설정을 확인한 뒤 다시 시도해 주세요.');
      setNotice({ message, tone: 'error' });
    } finally {
      finish();
    }
  }

  async function commitDraft() {
    const candidate = currentDraft.current;
    if (!candidate || !start('approve')) return;
    try {
      await approvePlan(candidate.id, candidate.plan);
      setSavedPlan(candidate.plan);
      currentDraft.current = null;
      setDraft(null);
      setNotice({ message: '운동 계획을 저장했어요.', tone: 'success' });
    } catch (error) {
      setNotice({ message: storageMessage(error, '계획을 저장하지 못했어요. 이 제안을 다시 승인해 주세요.'), tone: 'error' });
    } finally {
      finish();
    }
  }

  const locked = operation !== null;
  const activeModel = MODELS.find((model) => model.provider === editor.provider && model.id === editor.model);
  const chosenKey = keyStatus[editor.provider];

  return (
    <SafeAreaView style={styles.safe}>
      <StatusBar barStyle="dark-content" />
      <View style={styles.frame}>
        <View style={styles.header}>
          <View style={styles.brandMark} accessibilityElementsHidden importantForAccessibility="no">
            <View style={styles.brandBarShort} />
            <View style={styles.brandBar} />
            <View style={styles.brandBarTall} />
          </View>
          <Text style={styles.wordmark}>Adaptive Fit</Text>
        </View>

        {loading ? (
          <View style={styles.loading} accessibilityRole="progressbar">
            <ActivityIndicator color={colors.teal} />
            <Text style={styles.body}>저장한 코치를 불러오는 중</Text>
          </View>
        ) : (
          <ScrollView ref={scroll} keyboardShouldPersistTaps="handled" contentContainerStyle={styles.content}>
            {page === 'coach' ? (
              <>
                <View style={styles.hero}>
                  <View style={styles.heroRingOuter} pointerEvents="none" />
                  <View style={styles.heroRingInner} pointerEvents="none" />
                  <Text style={styles.heroTitle} accessibilityRole="header">오늘의 코칭</Text>
                  <Text style={styles.heroCopy}>
                    {selection ? `${providerName[selection.provider]} · ${modelLabel(selection)}` : '코치 설정에서 시작하세요.'}
                  </Text>
                </View>

                <Feedback notice={notice} />
                {draft && (
                  <PlanCard plan={draft.plan} title="코치가 제안한 운동" proposed>
                    <Button label={operation === 'approve' ? '계획 저장 중' : '계획 승인'} disabled={locked} onPress={() => void commitDraft()} />
                  </PlanCard>
                )}
                <View style={styles.card}>
                  <Text style={styles.sectionTitle} accessibilityRole="header">운동 조건</Text>
                  <ChoiceGroup label="목표" choices={goals.map((goal) => ({ value: goal, label: goal }))} value={context.goal} disabled={locked} onChange={(goal) => setContext((previous) => ({ ...previous, goal }))} />
                  <ChoiceGroup label="가용 시간" choices={durations.map((minutes) => ({ value: minutes, label: `${minutes}분` }))} value={context.minutes} disabled={locked} onChange={(minutes) => setContext((previous) => ({ ...previous, minutes }))} />
                  <ChoiceGroup label="장비" choices={equipment.map((name) => ({ value: name, label: name }))} value={context.equipment} disabled={locked} onChange={(name) => setContext((previous) => ({ ...previous, equipment: name }))} />
                  <Button label={operation === 'send' ? '코칭을 받는 중…' : '코칭 요청 검토'} disabled={locked} onPress={beginReview} />
                </View>
                {savedPlan && <PlanCard plan={savedPlan} title="마지막 승인 계획" />}
              </>
            ) : (
              <>
                <View style={styles.intro}>
                  <Text style={styles.screenTitle} accessibilityRole="header">코치 설정</Text>
                  <Text style={styles.body}>공급자와 모델을 선택하고 개인 API 키를 등록하세요.</Text>
                </View>
                <Feedback notice={notice} />
                <View style={styles.card}>
                  <ChoiceGroup label="공급자" choices={providers.map((provider) => ({ value: provider, label: providerName[provider] }))} value={editor.provider} disabled={locked} onChange={editProvider} />
                  <ChoiceGroup label="모델" choices={MODELS.filter((model) => model.provider === editor.provider).map((model) => ({ value: model.id, label: model.label }))} value={editor.model} disabled={locked} onChange={(model) => setEditor((previous) => ({ ...previous, model, effort: 'default' }))} />
                  <ChoiceGroup label="추론 강도" choices={(activeModel?.efforts ?? ['default']).map((effort) => ({ value: effort, label: effortName[effort] ?? effort }))} value={editor.effort} disabled={locked} onChange={(effort) => setEditor((previous) => ({ ...previous, effort }))} />
                </View>
                <View style={styles.card}>
                  <View style={styles.keyHeader}>
                    <Text style={styles.sectionTitle} accessibilityRole="header">{providerName[editor.provider]} API 키</Text>
                    <Text style={[styles.keyStatus, chosenKey && styles.keyPresent]}>
                      {chosenKey ? '등록됨' : chosenKey === false ? '미등록' : '확인 필요'}
                    </Text>
                  </View>
                  <Text style={styles.body}>키는 이 기기에 보관하며 선택한 공급자에게만 보냅니다.</Text>
                  <TextInput
                    accessibilityLabel="API 키 입력"
                    placeholder={chosenKey ? '새 API 키 입력' : '개인 API 키 입력'}
                    placeholderTextColor={colors.muted}
                    value={keyInput}
                    onChangeText={setKeyInput}
                    editable={!locked}
                    spellCheck={false}
                    autoCorrect={false}
                    autoCapitalize="none"
                    autoComplete="off"
                    importantForAutofill="no"
                    secureTextEntry
                    style={styles.input}
                  />
                  <View style={styles.buttonRow}>
                    <View style={styles.flex}>
                      <Button label={chosenKey ? '키 교체' : '키 등록'} disabled={locked || !keyInput.trim()} onPress={() => void storeKey()} />
                    </View>
                    {chosenKey !== false && (
                      <Button label="키 삭제" variant="secondary" disabled={locked} onPress={() => setDeleteProvider(editor.provider)} />
                    )}
                  </View>
                </View>
                <Button label="코치 설정 저장" disabled={locked || !isSelection(editor)} onPress={() => void saveCoachSettings()} />
              </>
            )}
            {restoreFailed && (
              <Button label="저장 정보 다시 불러오기" variant="secondary" disabled={locked} onPress={() => {
                setNotice(null);
                setKeyInput('');
                setLoading(true);
                setRestoreAttempt((attempt) => attempt + 1);
              }} />
            )}
          </ScrollView>
        )}

        {!loading && (
          <View style={styles.nav}>
            <NavButton label="코칭" active={page === 'coach'} disabled={locked} onPress={() => navigate('coach')} />
            <NavButton label="설정" active={page === 'settings'} disabled={locked} onPress={() => navigate('settings')} />
          </View>
        )}
      </View>

      {review && (
        <Sheet title="전송 내용 확인" onClose={closeReview}>
          <View style={styles.reviewDetails}>
            <Detail label="공급자" value={providerName[review.selection.provider]} />
            <Detail label="모델" value={modelLabel(review.selection)} />
            <Detail label="목표" value={review.context.goal} />
            <Detail label="가용 시간" value={`${review.context.minutes}분`} />
            <Detail label="장비" value={review.context.equipment} />
          </View>
          <Text style={styles.billing}>이 운동 정보를 선택한 공급자에게 보냅니다. 개인 API 계정에 요금이 발생할 수 있어요.</Text>
          <View style={styles.buttonRow}>
            <Button label="취소" variant="secondary" onPress={closeReview} />
            <View style={styles.flex}><Button label="동의하고 전송" onPress={() => void sendReview()} /></View>
          </View>
        </Sheet>
      )}

      {deleteProvider && (
        <Sheet title={`${providerName[deleteProvider]} 키 삭제`} onClose={() => setDeleteProvider(null)}>
          <Text style={styles.body}>이 기기에서 키를 삭제합니다. 이 공급자로 코칭을 받으려면 키를 다시 등록해야 해요.</Text>
          <View style={styles.buttonRow}>
            <Button label="취소" variant="secondary" onPress={() => setDeleteProvider(null)} />
            <View style={styles.flex}><Button label="삭제 확인" variant="danger" onPress={() => void removeKey()} /></View>
          </View>
        </Sheet>
      )}
    </SafeAreaView>
  );
}

export default function App() {
  return <SafeAreaProvider><CoachApp /></SafeAreaProvider>;
}

function storageMessage(error: unknown, fallback: string) {
  return error instanceof StorageError ? error.message : fallback;
}

function Feedback({ notice }: { notice: Notice | null }) {
  if (!notice) return null;
  return (
    <View style={[styles.feedback, notice.tone === 'error' ? styles.error : styles.success]}>
      <Text accessibilityRole={notice.tone === 'error' ? 'alert' : undefined} accessibilityLiveRegion="polite"
        style={[styles.feedbackText, notice.tone === 'error' ? styles.errorText : styles.successText]}>
        {notice.message}
      </Text>
    </View>
  );
}

function Button({ label, onPress, disabled, variant = 'primary' }: {
  label: string;
  onPress: () => void;
  disabled?: boolean;
  variant?: 'primary' | 'secondary' | 'danger';
}) {
  return (
    <Pressable accessibilityRole="button" accessibilityState={{ disabled }} disabled={disabled} onPress={onPress}
      style={({ pressed }) => [styles.button, styles[variant], disabled && styles.disabled, pressed && styles.pressed]}>
      <Text style={[styles.buttonText, variant === 'secondary' && styles.secondaryText]}>{label}</Text>
    </Pressable>
  );
}

function NavButton({ label, active, disabled, onPress }: { label: string; active: boolean; disabled: boolean; onPress: () => void }) {
  return (
    <Pressable accessibilityRole="tab" accessibilityState={{ selected: active, disabled }} disabled={disabled} onPress={onPress}
      style={[styles.navButton, active && styles.activeNav]}>
      <Text style={[styles.navText, active && styles.activeNavText]}>{label}</Text>
    </Pressable>
  );
}

function ChoiceGroup<T extends string | number>({ label, choices, value, disabled, onChange }: {
  label: string;
  choices: { value: T; label: string }[];
  value: T;
  disabled: boolean;
  onChange: (choice: T) => void;
}) {
  return (
    <View style={styles.field}>
      <Text style={styles.fieldLabel}>{label}</Text>
      <View style={styles.choices}>
        {choices.map((choice) => {
          const selected = choice.value === value;
          return (
            <Pressable key={choice.value} accessibilityRole="radio" accessibilityState={{ checked: selected, disabled }}
              disabled={disabled} onPress={() => onChange(choice.value)}
              style={[styles.choice, selected && styles.selectedChoice, disabled && styles.disabled]}>
              <Text style={[styles.choiceText, selected && styles.selectedChoiceText]}>{choice.label}</Text>
            </Pressable>
          );
        })}
      </View>
    </View>
  );
}

function PlanCard({ plan, title, proposed, children }: { plan: Plan; title: string; proposed?: boolean; children?: ReactNode }) {
  const total = plan.exercises.reduce((minutes, exercise) => minutes + exercise.minutes, 0);
  return (
    <View style={[styles.card, proposed && styles.draftCard]}>
      <View style={styles.keyHeader}>
        <Text style={styles.sectionTitle} accessibilityRole="header">{title}</Text>
        <Text style={styles.totalTime}>{total}분</Text>
      </View>
      <Text style={styles.body}>{plan.reason}</Text>
      <View style={styles.exercises}>
        {plan.exercises.map((exercise, index) => (
          <View key={`${exercise.id}-${index}`} style={styles.exercise}>
            <View style={styles.exerciseHeader}>
              <Text style={styles.exerciseTitle}>{exerciseLabel(exercise.id)}</Text>
              <Text style={styles.exerciseTime}>{exercise.minutes}분</Text>
            </View>
            <Text style={styles.body}>{exercise.instruction}</Text>
          </View>
        ))}
      </View>
      {children}
    </View>
  );
}

function Detail({ label, value }: { label: string; value: string }) {
  return <View style={styles.detail}><Text style={styles.body}>{label}</Text><Text style={styles.detailValue}>{value}</Text></View>;
}

function Sheet({ children, title, onClose }: { children: ReactNode; title: string; onClose: () => void }) {
  return (
    <Modal transparent animationType="fade" onRequestClose={onClose}>
      <SafeAreaView style={styles.scrim}>
        <ScrollView contentContainerStyle={styles.sheetContainer} keyboardShouldPersistTaps="handled">
          <View style={styles.sheet} accessibilityViewIsModal>
            <Text style={styles.sheetTitle} accessibilityRole="header">{title}</Text>
            {children}
          </View>
        </ScrollView>
      </SafeAreaView>
    </Modal>
  );
}

const colors = {
  background: '#F3F5F4', white: '#FFFFFF', navy: '#132B34', ink: '#172C29', muted: '#4C655F',
  line: '#DCE6E1', teal: '#126251', pale: '#E5F3EC', lime: '#D3F28F',
};

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  frame: { flex: 1, width: '100%', maxWidth: 560, alignSelf: 'center' },
  header: { flexDirection: 'row', alignItems: 'center', paddingHorizontal: 22, paddingVertical: 14, gap: 10 },
  brandMark: { width: 34, height: 34, borderRadius: 11, backgroundColor: colors.navy, flexDirection: 'row', alignItems: 'flex-end', justifyContent: 'center', paddingBottom: 9, gap: 3 },
  brandBarShort: { width: 3, height: 7, borderRadius: 2, backgroundColor: colors.lime },
  brandBar: { width: 3, height: 11, borderRadius: 2, backgroundColor: colors.lime },
  brandBarTall: { width: 3, height: 16, borderRadius: 2, backgroundColor: colors.lime },
  wordmark: { color: colors.navy, fontSize: 21, fontWeight: '800', letterSpacing: -0.65 },
  loading: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 14 },
  content: { paddingHorizontal: 18, paddingTop: 5, paddingBottom: 22, gap: 14 },
  hero: { backgroundColor: colors.navy, padding: 25, minHeight: 128, borderRadius: 25, justifyContent: 'center', gap: 8, overflow: 'hidden' },
  heroTitle: { color: colors.white, fontSize: 30, fontWeight: '800', letterSpacing: -1.3 },
  heroCopy: { color: '#D9EBE4', fontSize: 14, fontWeight: '600', maxWidth: '78%' },
  heroRingOuter: { width: 145, height: 145, borderRadius: 74, borderWidth: 1, borderColor: '#53766A', position: 'absolute', right: -43, bottom: -58 },
  heroRingInner: { width: 103, height: 103, borderRadius: 55, borderWidth: 1, borderColor: '#A5BF77', position: 'absolute', right: -22, bottom: -37 },
  intro: { paddingHorizontal: 4, paddingVertical: 8, gap: 6 },
  screenTitle: { color: colors.ink, fontSize: 29, fontWeight: '800', letterSpacing: -1.15 },
  card: { backgroundColor: colors.white, borderColor: colors.line, borderWidth: 1, borderRadius: 22, padding: 16, gap: 13 },
  draftCard: { borderColor: '#97BFAA', backgroundColor: '#F9FFFB' },
  sectionTitle: { fontSize: 19, color: colors.ink, fontWeight: '800', letterSpacing: -0.3, flexShrink: 1 },
  body: { fontSize: 14, lineHeight: 21, color: colors.muted },
  field: { gap: 6 },
  fieldLabel: { color: colors.ink, fontSize: 14, fontWeight: '700' },
  choices: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  choice: { minHeight: 44, justifyContent: 'center', borderRadius: 12, paddingHorizontal: 12, paddingVertical: 7, backgroundColor: '#F4F7F5', borderWidth: 1, borderColor: colors.line },
  selectedChoice: { borderColor: '#8AB6A3', backgroundColor: colors.pale },
  choiceText: { color: colors.muted, fontSize: 13, fontWeight: '600' },
  selectedChoiceText: { color: colors.teal, fontWeight: '800' },
  button: { minHeight: 46, borderRadius: 13, paddingHorizontal: 15, paddingVertical: 10, alignItems: 'center', justifyContent: 'center' },
  primary: { backgroundColor: colors.teal },
  secondary: { backgroundColor: '#F1F6F3', borderColor: colors.line, borderWidth: 1 },
  danger: { backgroundColor: '#9C352B' },
  buttonText: { color: colors.white, fontSize: 15, fontWeight: '800', textAlign: 'center' },
  secondaryText: { color: colors.ink },
  disabled: { opacity: 0.53 },
  pressed: { opacity: 0.77 },
  buttonRow: { flexDirection: 'row', gap: 8 },
  flex: { flex: 1 },
  nav: { flexDirection: 'row', paddingHorizontal: 18, paddingTop: 6, paddingBottom: 6, gap: 8, borderTopWidth: 1, borderColor: colors.line },
  navButton: { flex: 1, minHeight: 45, justifyContent: 'center', alignItems: 'center', borderRadius: 13 },
  activeNav: { backgroundColor: colors.pale },
  navText: { color: colors.muted, fontSize: 14, fontWeight: '700' },
  activeNavText: { color: colors.teal, fontWeight: '800' },
  keyHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 8 },
  keyStatus: { color: colors.muted, fontSize: 12, fontWeight: '700', backgroundColor: colors.background, borderRadius: 8, overflow: 'hidden', paddingHorizontal: 8, paddingVertical: 5 },
  keyPresent: { color: colors.teal, backgroundColor: colors.pale },
  input: { color: colors.ink, fontSize: 15, borderRadius: 12, borderWidth: 1, borderColor: colors.line, backgroundColor: '#F4F7F5', minHeight: 47, paddingHorizontal: 12, paddingVertical: 9 },
  totalTime: { color: colors.teal, fontSize: 14, fontWeight: '800' },
  exercises: { gap: 9 },
  exercise: { paddingTop: 9, gap: 3, borderTopWidth: 1, borderColor: colors.line },
  exerciseHeader: { flexDirection: 'row', alignItems: 'flex-start', justifyContent: 'space-between', gap: 8 },
  exerciseTitle: { color: colors.ink, fontSize: 15, fontWeight: '800', flexShrink: 1 },
  exerciseTime: { color: colors.teal, fontSize: 13, fontWeight: '700' },
  feedback: { borderRadius: 13, padding: 12, borderWidth: 1 },
  error: { backgroundColor: '#FFF2EF', borderColor: '#F2D0C8' },
  success: { backgroundColor: colors.pale, borderColor: '#BDDDCE' },
  feedbackText: { fontSize: 14, lineHeight: 21, fontWeight: '700' },
  errorText: { color: '#8D372B' },
  successText: { color: '#185446' },
  scrim: { flex: 1, backgroundColor: 'rgba(12, 34, 32, 0.50)' },
  sheetContainer: { flexGrow: 1, justifyContent: 'flex-end', padding: 12 },
  sheet: { width: '100%', maxWidth: 536, alignSelf: 'center', borderRadius: 23, padding: 18, backgroundColor: colors.white, gap: 13 },
  sheetTitle: { color: colors.ink, fontWeight: '800', fontSize: 23, letterSpacing: -0.65 },
  reviewDetails: { backgroundColor: '#F4F7F5', borderRadius: 13, paddingHorizontal: 11, paddingVertical: 8, gap: 3 },
  detail: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start', gap: 10 },
  detailValue: { flexShrink: 1, textAlign: 'right', fontSize: 14, lineHeight: 21, color: colors.ink, fontWeight: '700' },
  billing: { backgroundColor: '#FCF4DC', borderRadius: 10, padding: 9, color: '#5E471A', fontSize: 14, lineHeight: 21, fontWeight: '600' },
});
