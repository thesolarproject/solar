<img width="480" height="360" alt="Screenshot_20260612_165305" src="https://github.com/user-attachments/assets/b532a49f-195b-41c8-a5c2-819b10a6960a" />
<img width="480" height="360" alt="Screenshot_20260612_165211" src="https://github.com/user-attachments/assets/53fda29d-a73b-4daf-a413-53bcb6768fdf" />
<img width="480" height="360" alt="Screenshot_20260612_165113" src="https://github.com/user-attachments/assets/b442dd2a-779a-4589-be2f-22b1ee9d4fdd" />
<img width="3024" height="4032" alt="IMG_0197" src="https://github.com/user-attachments/assets/db935999-1054-4487-8979-60e2e8e547a9" />
<img width="470" height="359" alt="스크린샷 2026-06-10 232901" src="https://github.com/user-attachments/assets/dda1f81e-2a86-401a-a2ba-7c3c246ee2da" />
<img width="473" height="357" alt="스크린샷 2026-06-10 232931" src="https://github.com/user-attachments/assets/58917453-8ef8-45b0-b555-83d6ab6d5798" />
<img width="476" height="353" alt="스크린샷 2026-06-10 232835" src="https://github.com/user-attachments/assets/bba82be3-81a9-4e00-b4ce-141fbe3b5431" />
<img width="475" height="354" alt="스크린샷 2026-06-10 233044" src="https://github.com/user-attachments/assets/79c51331-aa14-4b01-bbf3-051531f79d2c" />
<img width="475" height="353" alt="스크린샷 2026-06-10 233120" src="https://github.com/user-attachments/assets/fe30d13f-308d-465b-a2e7-435ed61a6421" />
<img width="473" height="358" alt="스크린샷 2026-06-10 233009" src="https://github.com/user-attachments/assets/976ca048-4755-49cb-b4d2-913c76f84694" />

현재 만들고 있는 런처입니다 MO-ON Launcher

주요기능
1.자체 미디어 스캐너로 빠른 음악 라이브러리 자동 분류 가능

2.셔플, 반복 재생 및 기기 내장 이퀄라이저(EQ) 프리셋 설정 가능

3.앱 내 어느 화면에서든 물리 버튼을 이용해 이전/다음 곡 넘기기 가능

4.재생 중인 곡 변경 시 메인 화면의 앨범 아트 및 곡 정보 실시간 연동

5.파일 복사를 통해 무한대로 나만의 커스텀 테마(색상) 추가 및 변경 가능

6.현재 재생 중인 앨범 아트를 활용한 고화질 블러 메인 배경화면 제공

7.런처 설정 창을 나갈 필요 없이 블루투스 기기 직접 스캔 및 페어링 가능

8.자체 휠 전용 키보드를 이용한 와이파이 암호 입력 및 다이렉트 연결 가능

9.와이파이 웹 서버 기능을 통한 무선 PC 파일(음악) 업로드 가능

 
설치 방법
록 박스를 설치하고 설치하셔야 전원종료 기능을 모두 사용하실 수 있습니다 
1. 록박스 설치
2. Reboot to Stock Firmware
3. adb tool로 새로운 런처설치
  adb install app-release.apk
4. 기본 런처 막기
  adb shell pm disable com.innioasis.y1
5. 재부팅

adb tool
https://dl.google.com/android/repository/platform-tools-latest-windows.zip?hl=ko


주의 사항
1. 블루투스 헤드폰이 없어서 아직 테스트를 못해봤습니다
2. 일부 한국어로 되어있는 것이 있는데 나중에 모두 영어로 바꿀 예정입니다
3. 스크린이 꺼진 상태에서 휠이나 버튼은 작동하지 않습니다
