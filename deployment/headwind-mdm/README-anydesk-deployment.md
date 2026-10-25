# Headwind MDM APK 배포 (D:\PROJECT의 APK 사용)

`deploy-d-project-apks.ps1`는 아래 APK를 검사하고 Headwind에 등록한 뒤 지정된 구성의 설치 대상으로 배정합니다.

| 파일 | 패키지 | 목적 |
|---|---|---|
| `D:\PROJECT\anydesk.apk` | `com.anydesk.anydeskandroid` | AnyDesk 원격지원 |
| `D:\PROJECT\adcontrol-ad1.apk` | `com.anydesk.adcontrol.ad1` | AnyDesk 제어 플러그인 AD1 |
| `D:\PROJECT\vehicle-number-1.1-code2-debug.apk` | `com.smartbis.vehiclenumber` | 차량번호 등록 앱 |
| `D:\PROJECT\smartbis-client-1.4-code5-debug.apk` | `com.smartbis.client` | SmartBIS Client (1.4, code 5) |

## 실행

PowerShell 7에서 저장소 루트로 이동합니다. 기본 서버 주소는 현재 개발 환경의 `https://192.168.1.5:8443`입니다. 다른 주소를 쓸 때 `-ServerUrl`로 지정하세요. 서버 인증서는 Windows에서 신뢰되어 있어야 합니다. 스크립트는 인증서 검증을 우회하지 않습니다.

```powershell
cd D:\PROJECT\SmartBIS_jangsung
pwsh -File .\deployment\headwind-mdm\deploy-d-project-apks.ps1
```

첫 실행은 APK 파일·패키지명·버전만 검사하고 서버를 변경하지 않습니다. 적용하려면 다음처럼 `-Apply`를 지정합니다.

```powershell
pwsh -File .\deployment\headwind-mdm\deploy-d-project-apks.ps1 -Apply
```

계정은 실행 중 `Get-Credential` 창에서 입력합니다. 비밀번호를 스크립트나 명령행 인자로 전달하거나 파일에 저장하지 않습니다. 적용 단계에서 `Common - Minimal`을 확인 문자열로 입력해야 변경이 진행됩니다. 다른 서버/구성을 대상으로 할 경우:

```powershell
pwsh -File .\deployment\headwind-mdm\deploy-d-project-apks.ps1 -ServerUrl 'https://서버주소:8443' -ConfigurationName '구성 이름' -Apply
```

## 적용 범위와 주의사항

- `Common - Minimal`을 쓰는 **모든 등록 단말**에 네 앱을 설치 대상으로 알립니다. 특정 기기만 시험하려면 그 기기만 포함된 별도 Headwind 구성을 지정해야 합니다.
- 기존 패키지/버전은 재사용하고, 더 높은 버전이 서버에 있으면 다운그레이드하지 않고 중단합니다. 파일명뿐 아니라 APK 내부 패키지명을 검사합니다.
- AnyDesk와 AD1의 배포만으로 Android 원격 제어 권한이 자동 승인되지는 않습니다. Android 설정의 접근성/원격 제어 승인 등 기기 측 허용이 필요할 수 있습니다. 차량용 운영 모드에서 사용자 조작 없이 제어 가능한지는 대상 기기에서 따로 확인해야 합니다.
- 새 버전 파일을 서버에 올린 뒤 후속 API 호출이 실패하면 Headwind 파일 저장소에 업로드 파일이 남을 수 있습니다. 스크립트는 기존 앱/파일을 삭제하지 않습니다.
- 기본 `runAfterInstall`과 `runAtBoot`은 꺼 둡니다. 차량번호 앱을 비롯해 설정 마법사/권한 확인이 필요한 앱이 예기치 않게 전면 실행되는 것을 막기 위한 설정입니다.

필수 도구: PowerShell 7+, Android SDK Build-Tools의 `aapt.exe`. 기본 `aapt.exe` 위치가 다르면 `-AaptPath`로 경로를 전달하세요.
