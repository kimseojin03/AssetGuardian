@echo off
chcp 65001 > nul
cd /d "%~dp0"
echo [자산지킴이 에이전트 서버]
set "PY="
py -3 --version > nul 2> nul && set "PY=py -3"
if not defined PY (
  python --version > nul 2> nul && set "PY=python"
)
rem PATH에 없어도 흔히 설치되는 위치에서 python.exe 찾기 (VS Code가 쓰는 Python 포함)
if not defined PY for /d %%D in ("%LOCALAPPDATA%\Programs\Python\Python3*") do if exist "%%D\python.exe" set "PY=%%D\python.exe"
if not defined PY for %%P in ("%USERPROFILE%\anaconda3\python.exe" "%USERPROFILE%\miniconda3\python.exe" "%ProgramData%\anaconda3\python.exe" "%ProgramData%\miniconda3\python.exe") do if exist %%P set "PY=%%~P"
if not defined PY for /d %%D in ("C:\Python3*" "%ProgramFiles%\Python3*") do if exist "%%D\python.exe" set "PY=%%D\python.exe"
if not defined PY (
  echo.
  echo Python을 찾지 못했어요. 지금 열리는 페이지에서 Python을 설치해 주세요.
  echo 설치 첫 화면 아래 "Add python.exe to PATH" 를 꼭 체크하세요.
  echo 설치가 끝나면 이 창을 닫고 run_server.bat 을 다시 더블클릭하세요.
  start https://www.python.org/downloads/
  pause
  exit /b
)
echo 사용할 Python: %PY%
echo 필요한 라이브러리 설치 중... (처음 한 번은 1~2분)
"%PY%" -m pip install -q -r requirements.txt 2> nul || %PY% -m pip install -q -r requirements.txt
if errorlevel 1 (
  echo 라이브러리 설치에 실패했어요. 위 오류 메시지를 확인해 주세요.
  pause
  exit /b
)
echo.
echo 서버 주소: http://localhost:8000/health  (에뮬레이터 앱에서는 http://10.0.2.2:8000)
echo 끄려면 이 창에서 Ctrl+C
echo.
if "%PY%"=="py -3" (py -3 -m uvicorn agent_server:app --host 0.0.0.0 --port 8000) else ("%PY%" -m uvicorn agent_server:app --host 0.0.0.0 --port 8000)
pause
