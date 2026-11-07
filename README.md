# 물뷰 Android

데스크톱 물뷰의 사용감을 안드로이드로 확장하는 올인원 파일 뷰어입니다.

## 기반

이 앱은 [Gander](https://github.com/mokshablr/gander)를 Android 파일 처리·문서·미디어 뷰어 기반으로 포크했습니다. Gander의 MIT 라이선스와 포함된 라이브러리별 고지를 유지하며, 물뷰의 이름·색상·아이콘·읽기 경험을 별도로 적용합니다.

## 지원 목표

- 이미지: JPG, PNG, GIF, WEBP, BMP, TIFF, SVG 등
- 압축 만화: ZIP, CBZ, CBR, RAR, 7Z 계열
- 문서·텍스트: PDF, Markdown, TXT, DOCX, XLSX, PPTX 등
- 동영상·음악: MP4, MKV, AVI, MOV, MP3 등

## 현재 구현 및 다음 단계

기본 뷰어와 ZIP 내부 탐색을 바탕으로 물뷰의 읽기 경험을 적용하고 있습니다.

- **완료:** 일본 만화 읽기 방향, 한쪽·양쪽 보기, 넓은 표지·스캔 페이지 분할
- **완료:** 그림만 들어 있는 ZIP/CBZ를 눌렀을 때 만화 뷰어로 바로 열기
- **완료:** TXT·Markdown·PDF의 좌우 탭 넘김과 하단 이전/다음 도구 모음
- **완료:** PDF 페이지 넘김 방식, 만화 읽기 설정 화면
- **다음:** EPUB 읽기, 대용량 ZIP의 메모리 사용량과 페이지 넘김 성능 개선

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
