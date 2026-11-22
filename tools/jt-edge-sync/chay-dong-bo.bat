@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist node_modules (
  echo Lan dau chay: cai thu vien...
  call npm install
)
node sync.mjs %*
echo.
pause
