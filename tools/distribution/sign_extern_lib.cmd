@echo off
setlocal DisableDelayedExpansion
set "AZSCRIPT_ROOT=%~dp0"
set "AZSCRIPT_TOOL=%AZSCRIPT_ROOT%bin\azscript-sign-extern.exe"
if defined AZSCRIPT_SIGN_EXTERN set "AZSCRIPT_TOOL=%AZSCRIPT_SIGN_EXTERN%"
if "%~1"=="--generate-key" (
    if "%~3"=="" goto usage_key
    "%AZSCRIPT_TOOL%" genkey --private "%~2" --public "%~3"
    exit /b %errorlevel%
)
if "%~2"=="" goto usage_sign
"%AZSCRIPT_TOOL%" sign --key "%~1" --library "%~2"
exit /b %errorlevel%
:usage_key
echo Usage: sign_extern_lib.cmd --generate-key private.pem public.pem >&2
exit /b 2
:usage_sign
echo Usage: sign_extern_lib.cmd private.pem library-file >&2
exit /b 2
