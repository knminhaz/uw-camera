@echo off
cd /d "%~dp0"
for /f "usebackq delims=" %%P in (`powershell -NoProfile -Command "(Get-Content -Raw -LiteralPath '%~dp0desktop\launch.local.json' ^| ConvertFrom-Json).python"`) do set "CAMERA_LAB_PYTHON=%%P"
if not defined CAMERA_LAB_PYTHON (
 echo Please configure desktop\launch.local.json. See desktop\README.md.
 pause
 exit /b 1
)
"%CAMERA_LAB_PYTHON%" "%~dp0desktop\launch.py"
if errorlevel 1 pause
