@echo off
setlocal DisableDelayedExpansion
"%~dp0bin\azscript-run.exe" %*
exit /b %errorlevel%
