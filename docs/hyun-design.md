# 현비서 디자인 스펙 (레퍼런스 조사 기반, 2026-11-08)

## 핵심
- 현대는 ccNC에 포켓몬/뽀로로/잔망루피 캐릭터 테마를 이미 판매(클러스터·내비 전체 + 시동 on/off 애니메이션). 우리 컨셉 해금 = 검증된 모델. 테마마다 시동 인트로/마무리 컷.
- Pleos 실화면: 둥근 카드(~24px), 시스템 블루 포인트, Pretendard계 굵은 숫자+가는 라벨, 선 아이콘. Gleo는 아바타 없음 → 캐릭터가 차별점.
- "학생작" 원인 = 타일 나열·다중 메시지·평면 일러스트. 원칙: 장면 하나에 주인공 하나 / 상태는 색·빛·표정 / 배경은 '세계', 정보는 최소.

## 차용
- MBUX VA: 상태를 눈빛 색·밝기로(듣기=시안, 생각=펄스, 경고=앰버).
- NIO NOMI: 귀여움+절제(고급감). 얼굴 외 장식 최소.
- MINI Spike: 먼저 말 걸기 빈도 엄격히 제한.
- Li Auto: '생각중'을 캐릭터 동작으로.
- Rivian 2025: 셀 셰이딩 평면색+강한 외곽선, 살아있는 배경 → 컨셉 배경 스타일 기준.
- Cerence: 데이터를 소품으로(날씨=돋보기/우산, 계절 의상).
- Finch: 중앙 캐릭터 + 하단 버튼 하나. 추천 하나, CTA 하나.
- Duolingo×Rive: 표정 35개를 상태머신(idle→listen→think→speak→react).
- Pokémon Sleep: 확인 탭 최소화. 포켓캠프: 꾸미기 카탈로그가 재미, 가챠 금지 → 포인트 확정 해금.
- 아이오닉5 ccNC: 블루→그린 그라데이션 포인트. BMW: 주행 상황별 색 온도 변화.
- Discord Nitro: 잠긴 테마도 미리보기(10초 체험).

## 레이아웃 (1920×1080 기준, 실제 앱 영역에 비율 적용)
- 12컬럼, 바깥 여백 48, 거터 24, 8px 단위.
- 캐릭터 존 왼쪽 7컬럼(~58%): 배경 일러스트 전체, 캐릭터는 하단 1/3 바닥선, 높이 50~60%. (11-09 개정: 상황 화면·취미 레이아웃이 들어오며 캐릭터 칸 30% · 높이 25%, 내용 칸 70% — `docs/hyun-app.md` 상황 화면 절)
- 메시지 존 오른쪽 5컬럼: 세로 중앙 카드 1장 = 상황 칩 + 제목 1줄 + 본문 ≤2줄 + 주 버튼 1(+보조 텍스트 버튼).
- 주행 중: 캐릭터 존 70%, 카드는 제목+아이콘만, 음성 위주, 2초 안에 읽힘.
- 포인트·테마 진입 = 우상단 작은 알약 버튼 1개. 대시보드 타일 금지.

## 타이포 (Pretendard)
상황 칩 22 SemiBold +2% / 제목 44 Bold lh1.25 / 본문 28 Regular lh1.45 / 수치 56 Bold tabular / 버튼 26 SemiBold / 캡션 20 Medium. 주행 중 최소 24.

## 컬러
라이트: bg #F4F6F9, surface #FFFFFF, surface-2 #EEF1F5, text #111827, text-2 #5B6472, line #E3E7ED, accent #1E6BFF, accent-soft #E6EEFF, success #12B886, warn #F59F00, eye-glow #4FC3FF
야간: bg #0E1116, surface #171B22, surface-2 #1F2530, text #F2F4F7, text-2 #9AA3B2, line #2A313C, accent #4C8DFF, eye-glow #6FD3FF(글로우 1.5x)

## 카드
반경 큰카드 28 / 칩 999 / 버튼 18. 라이트 그림자 0 8 24 rgba(17,24,39,.08) + 1px line. 야간은 그림자 없이 테두리+면 밝기차. 블러는 테마 배경 위에서만(흰 72% + blur 24).

## 모션
숨쉬기 1.00↔1.015 4s, 깜빡임 4~7s 랜덤. 메시지: 캐릭터 반응 먼저 → 120ms 뒤 카드 12px 상승+페이드 320ms cubic-bezier(.2,.8,.2,1). 주행 중 큰 움직임 off(루프만). 1회 연출은 정차·주차. 테마 해금 1.2s 문 열림 + 리액션(정차 중만).

## 컨셉 배경 프롬프트
공통: "cozy side view diorama, soft cel-shaded, flat color blocks with subtle grain, clean silhouettes, warm rim light, low detail on right 40% (negative space for UI card), pastel premium palette, no text, 16:9, Rivian-style stylized not photoreal"
카페: morning café counter, steam, wooden shelves, beige+sage / 우주정거장: space station window, earthrise, white modules, soft blue glow / 한옥: hanok courtyard, maru floor, paper lanterns, dusk ochre+ink / 네온시티: rainy neon alley, magenta+cyan reflections, night / 캠핑: campfire flicker, pine silhouettes, starry indigo / (바다·레트로 아케이드 등도 같은 공통 스타일로)
캐릭터는 별도 레이어, 바닥 그림자 공유.

## Do / Don't
Do: 화면당 메시지1·버튼1 / 데이터는 소품·말로(배터리→도시락 크기, 와이퍼→우산) / 배경 오른쪽 40% 비움 / 라이트 기본은 Pleos 흰카드+블루 / 잠긴 테마 미리보기.
Don't: 수치 타일·게이지 나열 / 동시 3색 이상 / 이모지·스톡 아이콘 / 주행 중 3줄 초과 / 가챠 / 잦은 선제 발화 / 실사풍 배경.

참고 이미지: scratchpad/refimg/ (Pleos 보도 이미지)


## unslop 적용 (11-08)
디자인 정리 원칙은 **unslop by @heydamir** (https://github.com/heydamir/unslop, CC BY 4.0)의 감사 → 방향 → 9개 패스를 안드로이드 차량 앱에 맞게 옮겨 썼다.

방향(5줄)
- Product: 시동부터 시동 끔까지, 지금 상황에 필요한 한 가지를 말로 챙겨 주는 차 안 비서 — 시선을 뺏지 않는 게 원칙
- Character: 차분한 · 믿음직한 · 한 발짝 떨어진 다정함
- World: 야간 도로와 계기판 — 흑연빛 실내, 알루미늄 헤어라인, 청색 계기 불빛
- Moments: 메시지 카드 위 그림 칸(날씨 · 경로 · 기사 사진) 한 곳 + 캐릭터
- Signature: 캐릭터 옆 청색 세로 선 캡션(지금 하는 말)

적용한 규칙: 제목 굵기 500(위계는 크기·색) · 숫자 tabular · 대문자 자간 라벨 없음 · 느낌표 없는 평서문 · 라디얼 빛 번짐·후광 제거(듣기·생각·경고 같은 라이브 상태만 빛) · 숨쉬기 등 반복 모션 제거(라이브 마이크만) · 액센트는 주 버튼·현재 상태 1~2곳 · 크기별 반경(카드 24 · 작은 카드 16 · 버튼 12, 전부 알약 금지) · 다음 카드 폭 리듬(1.4:1:1) · 밤 시간엔 어두운 램프 · 주 버튼은 밤에 오프화이트+어두운 글씨 · 이모지 없음, 선 아이콘 한 세트·한 크기

## 테마 — "다른 앱처럼"(11-09, 그림 파일 없이 코드로)
테마마다 글꼴 · 카드 틀 · 버튼 · 구분선 · 배경 모티프 · 캐릭터 소품 · 인트로/마무리 · 대기 말투가 다르다(`ui/StageUi.kt` themeCard·button·divider·motif·aroundCharacter·accessory·themeIntro, 말투 THEME_LINES). 주행 중엔 입자·반복 모션이 꺼진다.

| 테마 | 글꼴 | 시그니처(2~3) | 인트로 / 마무리 |
|---|---|---|---|
| Pleos 기본 | Pretendard | 청색 세로선 캡션 · 절제된 하늘 | 이름 페이드 |
| 오로라 글래스 | Pretendard Regular | 흐르는 오로라 띠 · 서리 유리 카드 · 오로라 목도리 | 어둠 속 이름 |
| 카페 우드 | 나눔손글씨 펜(제목) | 칠판 메뉴판 카드(나무 틀) · 매달린 전구 · 베레모 · 좌우 반전 배치 · 분필 물결 구분선 | OPEN / CLOSED 팻말 흔들림 |
| 숲 캠핑 | 주아 | 솔숲 실루엣 · 반딧불 · 모닥불(장작·불꽃·아래서 비추는 빛) · 털모자 · 나무결 띠 카드 | 불빛이 차오름 / 꺼짐 |
| 바다 서핑 | 주아 | 바닥 파도 세 겹 · 부표 줄무늬 버튼 · 히비스커스 · 모래 띠 카드 | 파도가 덮고 빠짐 |
| 나이트 크루즈 | 도현 | 별밭 · 캐릭터 둘레 계기 고리(눈금·회전 호) · 안테나 불빛 · 모서리 괄호 카드 · 눈금자 구분선 | SYSTEM ONLINE / OFF 고리 |
| 한옥 사랑방 | 고운바탕 | 한지 결 · 문살 띠 카드 · 처마·단청 띠 · 갓 · 세로 글씨 · 붓질 + 붉은 낙관 | 창호지 문이 열림 / 닫힘 |
| 네온 시티 | 검은고딕 | 빛나는 윤곽 카드 · 젖은 바닥 반사 · 빗속 보케 · 바이저 네온 줄 | 간판 깜빡이며 켜짐 / 꺼짐 |
| 레트로 아케이드 | 갈무리11(픽셀) | CRT 주사선·가장자리 어둠 · 8비트 점수판(SCORE/HI) · 원근 격자 · 이중 네온 각진 카드 · 그림자 버튼 · 픽셀 별 머리띠 | INSERT COIN → PLAYER 1 START / GAME OVER |

공통: 버튼은 눌림 0.97(180ms) · 카드 등장은 한 번 짧게. 글꼴은 모두 OFL(assets/fonts/README.md).
