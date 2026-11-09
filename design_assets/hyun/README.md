# 현비서(Hyun) 캐릭터 에셋 패키지 가이드

> **기준 의뢰서**: [`dashboard/hyun/character-brief.html`](file:///c:/Users/soman/AntiProject/makecar-hy/dashboard/hyun/character-brief.html) (`http://localhost:8765/dashboard/hyun/report.html#character`)  
> **저장 경로**:
> - [`c:\Users\soman\AntiProject\makecar-hy\design_assets\hyun\`](file:///c:/Users/soman/AntiProject/makecar-hy/design_assets/hyun/) (프로젝트 메인 디자인 에셋 폴더)
> - [`c:\Users\soman\AntiProject\makecar-hy\dashboard\hyun\assets\`](file:///c:/Users/soman/AntiProject/makecar-hy/dashboard/hyun/assets/) (웹 대시보드 프리뷰 및 프로토타입 연동 폴더)

---

## 1. 개요 및 디자인 방향

의뢰서의 규격(**캔버스 1024×1024, 구슬 중심 `(512, 512)`, 지름 `640px` 고정, 투명 PNG & 벡터 SVG, Android 리소스 명명 규칙**)을 100% 준수하여 **A · 빛 구슬 캐릭터(말랑 젤리 3D & 플랫 스티커)**와 **B · 세련되고 고급스러운 사람 비서 캐릭터** 전 종을 제작했습니다.

---

## 2. 폴더 구조 및 납품 파일 매핑

### A · 빛 구슬 캐릭터 (`01~11`)

| 의뢰서 항목 | 파일 경로 (`design_assets/hyun/`) | 규격 · 배경 | 용도 |
|---|---|---|---|
| **① 기준 캐릭터 calm 정면** | [`hyun_face_calm_dark_1024.png`](hyun_face_calm_dark_1024.png) | `1024×1024` · 투명 PNG | 3D 젤리 유리 구슬 기준 정면 |
| **② 표정 시트 12 (어두운 유리 몸)** | [`faces_dark/hyun_face_<expr>_dark_1024.png`](faces_dark/) (`calm`, `happy`, `fun`, `care`, `alert`, `listen`, `think`, `speak`, `blink`, `rest`, `quiet`, `proud`) + 모음 시트 [`faces_dark/hyun_face_sheet_3d_ai.png`](faces_dark/hyun_face_sheet_3d_ai.png) | 각 `1024×1024` · 투명 PNG | 차량 정차 화면 · 시연 · 기획서 |
| **③ 표정 시트 12 (파스텔 몸)** | [`faces_light/hyun_face_<expr>_light_1024.png`](faces_light/) + [`hyun_face_calm_light_1024.png`](hyun_face_calm_light_1024.png) | 각 `1024×1024` · 투명 PNG | 폰 짭비서 흰 배경 테마 |
| **④ 주행용 간소판 (플랫 스티커 B안)** | [`drive/hyun_drive_<expr>_256.png`](drive/) · `128.png` (`calm`, `happy`, `care`, `alert`, `listen`) + [`hyun_drive_alert_256.png`](hyun_drive_alert_256.png) | `256²` · `128²` · 투명 PNG | 주행 중 정지 구슬 (64px 이하 가독성 확보) |
| **⑤ 레이어 분리 1세트** | [`layers/hyun_layer_<part>_1024.png`](layers/) (`body`, `aurora`, `aurora_mask`, `highlight`, `rim`, `cheek`, `eye_l`, `eye_r`, `mouth`) | `1024×1024` · 투명 PNG | 코드 애니메이션 및 기분 색 동적 합성 |
| **⑦ 액세서리 6종 + 착용 예시 6종** | [`accessories/hyun_acc_<item>_3d_1024.png`](accessories/) · [`accessories/hyun_acc_<item>_equipped_1024.png`](accessories/) · [`svg/hyun_acc_<item>.svg`](svg/) (`cap`, `ribbon`, `glasses`, `bag`, `aurora_sunset`, `aurora_forest`) | `1024×1024` 투명 PNG + 벡터 SVG | 정차 중 구슬 꾸미기 레이어 |
| **⑧ 상점 썸네일 6종** | [`shop/hyun_shop_<item>_256.png`](shop/) + [`hyun_shop_cap_256.png`](hyun_shop_cap_256.png) | `256×256` · 투명 PNG | 온기 포인트 꾸미기 상점 |
| **⑨ 앱 아이콘 (적응형 & 통짜)** | [`hyun_icon_fg_432.png`](hyun_icon_fg_432.png) (안전 원 264px 준수) · [`hyun_icon_full_512.png`](hyun_icon_full_512.png) | `432²` · `512²` · 투명 PNG | Android 런처 아이콘 |
| **⑩ 스플래시** | [`hyun_splash_1152.png`](hyun_splash_1152.png) | `1152×1152` (지름 768 원 내) | 앱 시작 스플래시 화면 |
| **⑪ 위젯 얼굴** | [`hyun_widget_192.png`](hyun_widget_192.png) | `192×192` · 투명 PNG | 폰 홈 위젯 4×1 (짭비서) |
| **⑫ 대표 히어로 이미지** | [`hyun_hero_1920x1080.png`](hyun_hero_1920x1080.png) | `1920×1080` · 16:9 장면 | 공모전 기획서 · 발표 표지 |

---

### B · 사람 비서 캐릭터 (`B1~B6` — 세련되고 고급스러운 AI 비서)

| 의뢰서 항목 | 파일 경로 (`design_assets/hyun/human/`) | 규격 · 배경 | 설명 |
|---|---|---|---|
| **메인 포트레이트 (안 1 여성 비서)** | [`sec_a_main_portrait.png`](sec_a_main_portrait.png) · [`human/sec_a_main_portrait.png`](human/sec_a_main_portrait.png) | `1024×1365` · 투명 PNG | 세련된 단발 · 네이비 재킷 · 청록 스카프 · 미니 구슬 브로치 · 홀로그램 태블릿 |
| **H1 콘셉트 시안 안 1 · 안 2 전신** | [`human/sec_a_concept_front_1024x2048.png`](human/sec_a_concept_front_1024x2048.png) · [`human/sec_b_concept_front_1024x2048.png`](human/sec_b_concept_front_1024x2048.png) · [`human/sec_concept_pair_ab.png`](human/sec_concept_pair_ab.png) | `1024×2048` · 투명 PNG | 여성 단발 비서(안 1) & 남성 단정한 비서(안 2) 정면 전신 |
| **H2 턴어라운드 4뷰 (정면·3/4·옆·뒤)** | [`human/sec_a_turnaround_sheet.png`](human/sec_a_turnaround_sheet.png) · `sec_a_turnaround_<front|q34|side|back>_1024x2048.png` | 각 `1024×2048` · 투명 PNG | 의상 및 파츠 위치 기준 시트 |
| **H4 포즈 6종** | [`human/sec_a_poses_sheet_6.png`](human/sec_a_poses_sheet_6.png) · `sec_a_pose_<greet|guide|think|tablet|clap|rest>_1024.png` | 각 `1024×1024` · 투명 PNG | 인사(목례) · 안내 · 생각 · 태블릿 · 칭찬(박수) · 휴식(커피) |
| **H7 SD(치비) 2.5등신 표정 6종** | [`human/sec_a_sd_expressions_sheet_6.png`](human/sec_a_sd_expressions_sheet_6.png) · `sec_a_sd_face_<calm|happy|fun|care|think|rest>_512.png` (`1024.png`) | `512²` · `1024²` · 투명 PNG | 정차 미니 카드 및 폰 짭비서 화면용 |
| **H8 SD 위젯 얼굴** | [`human/sec_a_sd_widget_192.png`](human/sec_a_sd_widget_192.png) | `192×192` · 투명 PNG | 폰 홈 위젯 SD 비서 아이콘 |

