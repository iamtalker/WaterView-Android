# 물뷰 Android

데스크톱 물뷰의 사용감을 안드로이드로 확장하는 올인원 파일 뷰어입니다.

## 기반

이 앱은 [Gander](https://github.com/mokshablr/gander)를 Android 파일 처리·문서·미디어 뷰어 기반으로 포크했습니다. Gander의 MIT 라이선스와 포함된 라이브러리별 고지를 유지하며, 물뷰의 이름·색상·아이콘·읽기 경험을 별도로 적용합니다.

## 지원 목표

- 이미지: JPG, PNG, GIF, WEBP, BMP, TIFF, SVG 등
- 압축 만화: ZIP, CBZ, CBR, RAR, 7Z 계열
- 문서·텍스트: PDF, Markdown, TXT, DOCX, XLSX, PPTX 등
- 동영상·음악: MP4, MKV, AVI, MOV, MP3 등

## 현재 구현

Gander의 Android 파일·문서 처리 기반 위에 물뷰의 한국어 UI와 읽기 경험을 적용했습니다. 일반 뷰어 기능에 더해 다음 항목을 구현했습니다.

- **만화:** 일본식 오른쪽에서 왼쪽 읽기, 한 화면 한 페이지 보기, 넓은 그림 반쪽 분할 보기
- **ZIP 만화:** 그림 위주 ZIP을 누르면 압축 목록 대신 만화 뷰어로 바로 열기
- **TXT·Markdown:** 스크롤 읽기와 좌우 탭 페이지 넘김 지원
- **PDF:** 세로 스크롤 또는 한 페이지씩 가로 넘김 선택, 넓은 페이지 반쪽 분할 옵션
- **PDF 조작:** 페이지 위치 막대, 가로 넘김 모드의 이전·다음 버튼, 읽기 도구 모음 표시·숨김
- **설정 반영:** PDF 보기 방식을 바꾸면 문서를 닫지 않고 현재 화면에 적용; 세로 스크롤에서는 하단 페이지 넘김 버튼 숨김
- **전자책·문서:** EPUB 장별 읽기와 DOCX·XLSX·PPTX 등 문서 뷰어
- **안정성:** 만화 페이지 전환 깜빡임 완화와 PDF 페이지 미리 준비

## 다음 단계

- EPUB 호환성과 데스크톱 물뷰와의 읽기 경험 맞추기
- 대용량 ZIP의 메모리 사용량 및 페이지 전환 성능 개선
- 실제 기기와 다양한 파일로 뷰어 호환성 보강

기능은 실제 Android 에뮬레이터와 사용자가 제공한 파일로 확인합니다.

## 업데이트

최신 APK는 [GitHub Releases](https://github.com/iamtalker/WaterView-Android/releases)에서 받습니다. 앱 메뉴의 **업데이트 확인**에서도 새 릴리스를 확인하고 Android 설치 화면을 열 수 있습니다. 저장소는 `iamtalker/WaterView-Android`입니다.

## 빌드

Windows 경로에 한글이 포함된 경우 Android AIDL 도구 문제를 피하려면 저장소의 junction 또는 영문 경로에서 빌드합니다.

```powershell
gradlew.bat assembleDebug
```

## 라이선스

물뷰 Android의 변경 코드는 MIT 라이선스로 배포합니다. Gander 및 `app/src/main/assets/licences.md`에 기록된 번들 라이브러리의 저작권·라이선스 고지는 제거하지 않습니다.
