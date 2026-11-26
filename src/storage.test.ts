import { NativeModules } from 'react-native';
import {DatabaseSync} from 'node:sqlite';
import * as SQLite from 'expo-sqlite';
import * as SecureStore from 'expo-secure-store';
import {approvePlan, loadPlan, saveSelection, loadSelection, readKey, hasKey, saveKey, deleteKey} from './storage';
import {DEFAULT_SELECTION} from './coach';

jest.mock('expo/fetch', () => ({fetch: jest.fn()}));
jest.mock('expo-sqlite', () => ({openDatabaseAsync: jest.fn()}));
jest.mock('expo-secure-store', () => ({getItemAsync:jest.fn(), setItemAsync:jest.fn(), deleteItemAsync:jest.fn()}));
const db = new DatabaseSync(':memory:');
const keys = new Map<string,string>();
beforeAll(() => {
  NativeModules.FitKeyStatus = {contains: async (provider:string) => keys.has('adaptivefit.key.' + provider)};
  (SQLite.openDatabaseAsync as jest.Mock).mockResolvedValue({
    execAsync: (sql:string) => db.exec(sql),
    runAsync: (sql:string,...args:string[]) => db.prepare(sql).run(...args),
    getFirstAsync: (sql:string) => db.prepare(sql).get(),
  });
  (SecureStore.getItemAsync as jest.Mock).mockImplementation(async (k:string) => keys.get(k) ?? null);
  (SecureStore.setItemAsync as jest.Mock).mockImplementation(async (k:string,v:string) => {keys.set(k,v);});
  (SecureStore.deleteItemAsync as jest.Mock).mockImplementation(async (k:string) => {keys.delete(k);});
});
afterAll(() => db.close());
test('keys are isolated, replaceable and removable; preferences contain no key', async () => {
  await saveKey('openai','synthetic-one');
  await saveKey('anthropic','synthetic-two');
  await saveKey('openai','synthetic-new');
  expect(await hasKey('openai')).toBe(true);
  expect(SecureStore.getItemAsync).not.toHaveBeenCalled();
  expect(await readKey('openai')).toBe('synthetic-new');
  await deleteKey('openai');
  expect(await hasKey('openai')).toBe(false);
  expect(await readKey('anthropic')).toBe('synthetic-two');
  await saveSelection(DEFAULT_SELECTION);
  expect(await loadSelection()).toEqual(DEFAULT_SELECTION);
  expect(JSON.stringify(db.prepare('SELECT * FROM settings').all())).not.toContain('synthetic');
});
test('approval appends unique revisions; failed writes preserve latest', async () => {
  const plan = {reason:'전신 준비에 도움이 됩니다.',exercises:[{id:'bodyweight-squat',minutes:5,instruction:'천천히 앉고 일어나세요.'}]};
  const next = {...plan,reason:'오늘 컨디션에 맞춘 운동입니다.'};
  await approvePlan('a',plan);
  await approvePlan('a',plan);
  await approvePlan('b',next);
  expect(db.prepare('SELECT COUNT(*) AS n FROM approved_plans').get()?.n).toBe(2);
  expect(await loadPlan()).toEqual(next);
  db.exec("CREATE TRIGGER reject_add BEFORE INSERT ON approved_plans BEGIN SELECT RAISE(ABORT,'test'); END;");
  await expect(approvePlan('c',plan)).rejects.toThrow('저장');
  expect(await loadPlan()).toEqual(next);
});
