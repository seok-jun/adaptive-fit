import * as SecureStore from 'expo-secure-store';
import * as SQLite from 'expo-sqlite';
import {isPlan, isSelection, type Plan, type Provider, type Selection} from './coach';

export class StorageError extends Error {}

let connection: Promise<SQLite.SQLiteDatabase> | undefined;

function database() {
  if (!connection) {
    connection = (async () => {
      const db = await SQLite.openDatabaseAsync('adaptive-fit.db');
      await db.execAsync(`
        CREATE TABLE IF NOT EXISTS settings (name TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS approved_plans (
          id INTEGER PRIMARY KEY, draft_id TEXT NOT NULL UNIQUE, payload TEXT NOT NULL
        );
      `);
      return db;
    })().catch(() => {
      connection = undefined;
      throw new StorageError('기기 저장소를 열 수 없어요. 앱을 다시 열어 주세요.');
    });
  }
  return connection;
}

async function safely<T>(message: string, operation: () => Promise<T>): Promise<T> {
  try {
    return await operation();
  } catch (error) {
    if (error instanceof StorageError) throw error;
    throw new StorageError(message);
  }
}

function slot(provider: Provider) {
  if (provider !== 'openai' && provider !== 'anthropic') throw new StorageError('공급자를 다시 선택해 주세요.');
  return 'adaptivefit.key.' + provider;
}

export function readKey(provider: Provider) {
  return safely('저장된 키를 읽을 수 없어요.', () => SecureStore.getItemAsync(slot(provider)));
}

export async function hasKey(provider: Provider) {
  return Boolean(await readKey(provider));
}

export function saveKey(provider: Provider, key: string) {
  const input = key.trim();
  if (!input || /\s/.test(input) || input.length > 1500) throw new StorageError('API 키 형식을 확인해 주세요.');
  return safely('키를 저장하지 못했어요. 다시 시도해 주세요.', () => SecureStore.setItemAsync(slot(provider), input));
}

export function deleteKey(provider: Provider) {
  return safely('키를 삭제하지 못했어요.', () => SecureStore.deleteItemAsync(slot(provider)));
}

export function loadSelection(): Promise<Selection | null> {
  return safely('저장된 코치 설정을 복원할 수 없어요. 다시 선택해 주세요.', async () => {
    const row = await (await database()).getFirstAsync<{value: string}>("SELECT value FROM settings WHERE name='coach'");
    if (!row) return null;
    const data: unknown = JSON.parse(row.value);
    if (!isSelection(data)) throw new StorageError('저장된 모델 설정이 만료됐어요. 다시 선택해 주세요.');
    return data;
  });
}

export function saveSelection(selection: Selection) {
  return safely('코치 설정을 저장하지 못했어요.', async () => {
    if (!isSelection(selection)) throw new StorageError('지원되는 모델과 강도를 선택해 주세요.');
    const {provider, model, effort} = selection;
    await (await database()).runAsync("INSERT OR REPLACE INTO settings(name,value) VALUES ('coach',?)",
      JSON.stringify({provider, model, effort}));
  });
}

export function loadPlan(): Promise<Plan | null> {
  return safely('마지막 승인 계획을 복원할 수 없어요.', async () => {
    const row = await (await database()).getFirstAsync<{payload: string}>('SELECT payload FROM approved_plans ORDER BY id DESC LIMIT 1');
    if (!row) return null;
    const data: unknown = JSON.parse(row.payload);
    if (!isPlan(data)) throw new StorageError('저장된 계획 형식을 확인할 수 없어요.');
    return data;
  });
}

export function approvePlan(draftId: string, plan: Plan) {
  return safely('계획을 저장하지 못했어요. 다시 시도해 주세요.', async () => {
    if (!draftId.trim() || !isPlan(plan)) throw new StorageError('승인할 계획을 다시 확인해 주세요.');
    await (await database()).runAsync(
      'INSERT INTO approved_plans(draft_id,payload) VALUES (?,?) ON CONFLICT(draft_id) DO NOTHING',
      draftId, JSON.stringify(plan));
  });
}
