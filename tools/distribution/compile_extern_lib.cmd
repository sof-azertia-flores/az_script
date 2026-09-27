@echo off
setlocal DisableDelayedExpansion
set "AZSCRIPT_ROOT=%~dp0"
if not defined AZSCRIPT_INCLUDE set "AZSCRIPT_INCLUDE=%AZSCRIPT_ROOT%include"
if not defined AZSCRIPT_LIBDIR set "AZSCRIPT_LIBDIR=%AZSCRIPT_ROOT%lib"
set "AZSCRIPT_OUT="
set "AZSCRIPT_SOURCES="
:parse
if "%~1"=="" goto compile
if "%~1"=="-o" (
    set "AZSCRIPT_OUT=%~2"
    shift
    shift
    goto parse
)
set "AZSCRIPT_SOURCES=%AZSCRIPT_SOURCES% "%~1""
shift
goto parse
:compile
if "%AZSCRIPT_OUT%"=="" goto usage
if "%AZSCRIPT_SOURCES%"=="" goto usage
where cl >nul 2>&1
if errorlevel 1 (
    echo AzScript: cl.exe was not found on PATH. >&2
    exit /b 1
)
cl /nologo /std:c++20 /EHsc /LD /I "%AZSCRIPT_INCLUDE%" %AZSCRIPT_SOURCES% /link /LIBPATH:"%AZSCRIPT_LIBDIR%" abdInvoker.lib /OUT:"%AZSCRIPT_OUT%.dll"
exit /b %errorlevel%
:usage
echo Usage: compile_extern_lib.cmd -o stem source.cpp [more.cpp...] >&2
exit /b 2
