/* Editable product presentation. All screen content comes from real app captures. */
const fs = require('node:fs');
const path = require('node:path');
const out = path.resolve(__dirname, '../presentation');
const pptxgen = require(require.resolve('pptxgenjs', { paths: [out, ...module.paths] }));
const { imageSize } = require(require.resolve('image-size', { paths: [out] }));
const deck = new pptxgen();
deck.layout = 'LAYOUT_WIDE';
deck.author = 'SpeechBridge';
deck.subject = '학교 과제 · 앱 소개와 시연';
deck.title = 'SpeechBridge — 번역에서 할 일까지';
deck.lang = 'ko-KR';
deck.theme = { headFontFace: 'Apple SD Gothic Neo', bodyFontFace: 'Apple SD Gothic Neo', lang: 'ko-KR' };
const C = { bg: 'F2F3F9', ink: '242938', muted: '626B80', blue: '405394', pale: 'DFE5FF', white: 'FFFFFF' };
const assets = path.join(out, 'assets');
const notes = [];
const captureWidth = 912, captureHeight = 1944, toolbarHeight = 120;

// Preserve the source captures. Document viewports exclude only the Simulator toolbar.
for (const name of ['talk', 'translation', 'history', 'summary', 'tasks', 'evidence']) {
    const capture = fs.readFileSync(path.join(assets, name + '.png'));
    const meta = imageSize(capture);
    if (meta.width !== captureWidth || meta.height !== captureHeight) throw new Error('Review screenshot viewport: ' + name);
    const mime = meta.type === 'jpg' ? 'image/jpeg' : 'image/png';
    fs.writeFileSync(path.join(assets, name + '.svg'), `<svg xmlns="http://www.w3.org/2000/svg" width="${captureWidth}" height="${captureHeight - toolbarHeight}" viewBox="0 ${toolbarHeight} ${captureWidth} ${captureHeight - toolbarHeight}"><title>SpeechBridge ${name}</title><image width="${captureWidth}" height="${captureHeight}" href="data:${mime};base64,${capture.toString('base64')}"/></svg>\n`);
}
function text(s, value, x, y, w, h, size = 24, color = C.ink, bold = false) {
    s.addText(value, { x, y, w, h, fontSize: size, color, bold, margin: 0, valign: 'mid', fontFace: 'Apple SD Gothic Neo', paraSpaceAfterPt: 0 });
}
function box(s, x, y, w, h, color = C.white) {
    s.addShape(deck.ShapeType.roundRect, { x, y, w, h, radius: .2, rectRadius: .2, line: { color, transparency: 100 }, fill: { color } });
}
function slide(title, note) {
    const s = deck.addSlide();
    s.background = { color: C.bg };
    const n = deck._slides.length;
    text(s, 'SpeechBridge', .6, .24, 4, .25, 12, C.blue, true);
    if (title) text(s, title, .6, .77, 12.1, .62, 34, C.ink, true);
    text(s, String(n).padStart(2, '0'), 12.2, 7.13, .5, .2, 10, C.muted);
    s.addNotes(note);
    notes.push({ n, title: title || 'SpeechBridge', note });
    return s;
}
function viewport(s, file, x, y, w, source) {
    const [left, top, width, height] = source;
    const scale = w / width;
    s.addImage({ path: path.join(assets, file + '.png'), x, y,
        w: captureWidth * scale, h: captureHeight * scale,
        sizing: { type: 'crop', x: left * scale, y: top * scale, w, h: height * scale } });
}
function phone(s, file, x, y, h) {
    viewport(s, file, x, y, h * captureWidth / (captureHeight - toolbarHeight), [0, toolbarHeight, captureWidth, captureHeight - toolbarHeight]);
}
function label(s, value, x, y, w = 7.8) {
    text(s, value, x, y, w, .45, 24, C.blue, true);
}

let s = slide('', 'SpeechBridge는 대화를 번역하고 기록·요약·할 일로 이어주는 앱입니다. Android와 iOS에서 사용할 수 있습니다.');
text(s, '대화에서\n할 일까지.', .7, 1.48, 7.4, 1.8, 52, C.ink, true);
text(s, 'SpeechBridge', .75, 3.75, 7.2, .65, 32, C.blue, true);
text(s, '번역  ·  기록  ·  요약  ·  할 일', .75, 4.8, 7.6, .5, 25, C.muted);
text(s, 'Android · iOS', .78, 6.38, 6, .35, 18, C.muted);
phone(s, 'talk', 9.25, .45, 6.45);

s = slide('음성 입력·번역·읽어주기', '언어를 선택하고 마이크로 녹음한 뒤 번역합니다. 한국어·영어·일본어를 지원합니다. 새 번역은 자동으로 읽어줍니다. 자동 읽기는 설정에서 끌 수 있습니다. 오른쪽 화면은 직접 입력한 문장의 번역 결과입니다.');
phone(s, 'translation', 9.1, 1.0, 5.9);
label(s, '음성 중심 입력', .75, 2.15, 7.6);
text(s, '한국어 · 영어 · 일본어', .75, 3.15, 7.6, .55, 29);
label(s, '새 번역 자동 읽기', .75, 4.5, 7.6);
text(s, '설정에서 끄기 · 수동 듣기', .75, 5.25, 7.6, .45, 24, C.muted);

s = slide('대화 기록과 요약', '대화는 자동으로 저장됩니다. 대화가 끝나면 AI가 제목을 붙이고 분류와 요약을 정리합니다. 이후에는 사용자가 직접 수정합니다. 일상·업무·업무 폴더로 기록을 찾을 수 있습니다.');
phone(s, 'history', .7, 1.9, 4.95);
phone(s, 'summary', 3.45, 1.9, 4.95);
label(s, '자동 저장 · 종료 후 요약', 6.55, 2.4, 5.8);
text(s, '일상 / 업무 / 업무 폴더', 6.55, 3.7, 5.8, .55, 27);
label(s, '이후 편집은 직접', 6.55, 5.1, 5.8);

s = slide('할 일 관리와 원문 확인', '대화에서 찾은 할 일 후보는 원문을 확인한 뒤 등록하고 완료합니다. 할 일에서 원래 기록으로 돌아갈 수 있습니다.');
phone(s, 'evidence', .7, 1.85, 5.0);
phone(s, 'tasks', 3.5, 1.85, 5.0);
label(s, '원문 근거 확인', 6.6, 2.4, 5.8);
text(s, '검토 → 등록 → 완료', 6.6, 3.8, 5.8, .55, 28, C.blue, true);
text(s, '할 일과 대화 기록 연결', 6.6, 5.2, 5.8, .55, 25);

s = slide('AI 모델별 역할', 'Nemotron은 음성 인식, TranslateGemma는 번역, Gemma 4는 기록 정리, Qwen3-TTS는 번역문 음성 합성을 담당합니다.');
[
    ['Nemotron 3.5 ASR', '음성 인식 · 언어 감지'],
    ['TranslateGemma', '대화 번역'],
    ['Gemma 4', '제목 · 분류 · 요약 · 할 일 추출'],
    ['Qwen3-TTS', '번역문 음성 합성'],
].forEach(([name, role], i) => {
    const y = 1.85 + i * 1.18;
    box(s, .7, y, 11.9, .98, i % 2 === 0 ? C.white : C.pale);
    text(s, name, 1.02, y + .19, 4.7, .56, 27, C.blue, true);
    text(s, role, 6.02, y + .19, 6.15, .56, 25);
});

s = slide('앱은 휴대전화에서, AI는 Mac에서', '앱에서 보낸 회원 요청은 Mac 서버가 처리합니다. 사용자 데이터는 PostgreSQL에 저장합니다. 연결이 끊기면 변경 내용을 기기에 암호화해 잠시 보관했다가 다시 연결될 때 전송합니다.');
[
    ['모바일 앱', 'Android · iOS'],
    ['Mac 서버', '회원 인증 · AI 처리'],
    ['PostgreSQL', '계정별 기록 저장'],
].forEach(([title, body], i) => {
    const x = .7 + i * 4.13;
    box(s, x, 2.4, 3.66, 2.3, i === 1 ? C.pale : C.white);
    text(s, title, x + .26, 2.87, 3.14, .55, 29, C.blue, true);
    text(s, body, x + .26, 3.8, 3.14, .45, 22);
    if (i < 2) text(s, '→', x + 3.7, 3.27, .4, .4, 27, C.blue, true);
});
text(s, '연결이 복구되면 자동 동기화', .85, 5.65, 11.7, .6, 28, C.blue, true);

s = slide('직접 사용해 보기', 'Demo.command를 실행해 시연을 준비합니다. 보조 키보드로 “please prepare demo slides by friday”를 입력해 번역을 보여주고 대화를 종료해 요약을 확인한 뒤 할 일 근거를 검토합니다. 화면에 나온 내용은 시연용 가상 데이터입니다. 실제 휴대전화·마이크·청취 검증은 보류 중이라 이번에는 텍스트를 입력해 시연합니다.');
text(s, '“please prepare demo slides by friday”', .8, 2.1, 11.7, .7, 30, C.blue, true);
[['01', '번역'], ['02', '기록·요약'], ['03', '할 일']].forEach(([number, title], i) => {
    const x = .8 + i * 4.15;
    box(s, x, 3.6, 3.7, 1.8);
    text(s, number, x + .3, 3.85, 3.1, .3, 16, C.muted);
    text(s, title, x + .3, 4.45, 3.1, .55, 30, C.ink, true);
});
text(s, '텍스트 입력으로 시연 · 실기기·마이크·청취 검증은 보류', .8, 6.8, 11.5, .25, 15, C.muted);

fs.writeFileSync(path.join(out, '발표자-노트.md'), '# SpeechBridge 발표자 노트\n\n앱 화면과 핵심 기능 중심의 7장 구성입니다. 설명과 시연을 합쳐 약 5–7분입니다. 실제 앱 캡처에 가상 시연 데이터를 사용합니다.\n\n' + notes.map(v => `## ${v.n}. ${v.title}\n\n${v.note}\n`).join('\n'));
deck.writeFile({ fileName: path.join(out, 'SpeechBridge.pptx') });
