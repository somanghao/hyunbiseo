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
- 캐릭터 존 왼쪽 7컬럼(~58%): 배경 일러스트 전체, 캐릭터는 하단 1/3 바닥선, 높이 50~60%.
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
