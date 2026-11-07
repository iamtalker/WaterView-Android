# 물뷰 Android

데스크톱 물뷰의 사용감을 안드로이드로 확장하는 올인원 파일 뷰어입니다.

## 기반

이 앱은 [Gander](https://github.com/mokshablr/gander)를 Android 파일 처리·문서·미디어 뷰어 기반으로 포크했습니다. Gander의 MIT 라이선스와 포함된 라이브러리별 고지를 유지하며, 물뷰의 이름·색상·아이콘·읽기 경험을 별도로 적용합니다.

## 지원 목표

- 이미지: JPG, PNG, GIF, WEBP, BMP, TIFF, SVG 등
- 압축 만화: ZIP, CBZ, CBR, RAR, 7Z 계열
- 문서·텍스트: PDF, Markdown, TXT, DOCX, XLSX, PPTX 등
- 동영상·음악: MP4, MKV, AVI, MOV, MP3 등

Gander 기반 전환으로 이미지 확대, PDF 렌더링, ZIP 내부 보기, 문서·미디어 재생 구조를 먼저 확보하고 데스크톱 물뷰의 만화 보기 설정을 추가합니다.

## 업데이트

최신 APK는 [GitHub Releases](https://github.com/iamtalker/WaterView-Android/releases)에서 받습니다. 앱 메뉴의 **Check for updates**에서도 새 릴리즈를 확인하고 Android 설치 확인 화면을 열 수 있습니다. 저장소는 `iamtalker/WaterView-Android`입니다.

## 빌드

Windows 경로에 한글이 포함된 경우 Android AIDL 도구 문제를 피하려면 저장소의 junction 또는 영문 경로에서 빌드합니다.

```powershell
gradlew.bat assembleDebug
```

## 라이선스

물뷰 Android의 변경 코드는 MIT 라이선스로 배포합니다. Gander 및 `app/src/main/assets/licences.md`에 기록된 번들 라이브러리의 저작권·라이선스 고지는 제거하지 않습니다.
