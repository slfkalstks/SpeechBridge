import { createProviders } from './provider.mjs';
import { writeFile } from 'node:fs/promises';
const provider = createProviders({ AI_PROVIDER: 'ollama-local', APP_ACCESS_TOKEN: 'evaluation-only-token-not-used-for-network' });
const cases = [
  { name: 'daily-ko', text: '이번 주말에 가족과 부산으로 여행 가자. 토요일 아침에 출발하고 저녁에는 바다를 보자.', category: 'DAILY', type: null },
  { name: 'work-meeting-folder', text: '아폴로 프로젝트 주간 회의를 시작합니다. 이번 릴리스는 금요일로 확정했습니다. QA 결과는 다음 회의에서 공유합시다.', category: 'WORK', type: 'MEETING', folder: 'apollo' },
  { name: 'work-task', text: '민수님, 고객에게 보낼 견적서를 내일 오전까지 준비해 주세요. 세금 항목도 확인해 주세요.', category: 'WORK', type: 'TASK' },
  { name: 'work-idea', text: '신제품 아이디어를 브레인스토밍합시다. 업무용 일정 공유 기능을 넣으면 어떨까요? 자동 일정 추천도 검토해 봅시다.', category: 'WORK', type: 'IDEA' },
  { name: 'personal-errand-en', language: 'en', text: 'Please pick up milk and bread on the way home. We need them for breakfast with the kids.', category: 'DAILY', type: null },
  { name: 'daily-ja', language: 'ja', text: '週末は家族で公園に行こう。お弁当を持っていって、午後はゆっくり休もう。', category: 'DAILY', type: null },
];
const rows = [];
for(const c of cases) {
  const start = Date.now();
  try {
    const result = await provider.organize({ language: c.language || 'ko', utterances: [{ id: 'u1', revision: 1, text: c.text }], folders: [{ id: 'apollo', name: '아폴로 프로젝트' }] });
    rows.push({ ...c, result, ms: Date.now() - start, pass: result.category === c.category && result.workType === c.type && (c.folder ? result.folderId === c.folder : result.folderId === null) });
  } catch(e) { rows.push({ ...c, error: e.code || 'failure', ms: Date.now()-start, pass: false }); }
  console.log(c.name, rows.at(-1).pass ? 'PASS' : 'CHECK', rows.at(-1).ms);
  await writeFile(new URL('./validation/organization-suite.json', import.meta.url), JSON.stringify({ at: new Date().toISOString(), model: 'gemma4:12b-it-qat', synthetic: true, rows }, null, 2));
}
