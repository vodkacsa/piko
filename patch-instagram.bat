@echo off
setlocal

if "%~1"=="" (
  echo Usage: patch-instagram.bat "C:\path\to\instagram.apkm"
  echo.
  echo Optional:
  echo   patch-instagram.bat "C:\path\to\instagram.apkm" -Install
  exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\patch-instagram.ps1" %*