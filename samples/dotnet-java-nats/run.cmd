@echo off
rem Runs the .NET + Java sample from cmd.exe or by double-click: see run.ps1. Pass -Check to only check it.
where pwsh >nul 2>nul
if %ERRORLEVEL% equ 0 (
  pwsh -NoProfile -ExecutionPolicy Bypass -File "%~dp0run.ps1" %*
) else (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run.ps1" %*
)
exit /b %ERRORLEVEL%
