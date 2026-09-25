@echo off
setlocal DisableDelayedExpansion
set "AZSCRIPT_ROOT=%~dp0"
set "AZSCRIPT_JAVA=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "AZSCRIPT_JAVA=%JAVA_HOME%\bin\java.exe"
if exist "%AZSCRIPT_ROOT%runtime\bin\java.exe" set "AZSCRIPT_JAVA=%AZSCRIPT_ROOT%runtime\bin\java.exe"
set "AZSCRIPT_LIB=%AZSCRIPT_ROOT%compiler\lib"
set "AZSCRIPT_CLASSPATH=%AZSCRIPT_LIB%\launcher.jar;%AZSCRIPT_LIB%\compiler.jar;%AZSCRIPT_LIB%\abdJava.jar;%AZSCRIPT_LIB%\gson-2.11.0.jar"
"%AZSCRIPT_JAVA%" -cp "%AZSCRIPT_CLASSPATH%" azertia.distribution.Compile %*
exit /b %errorlevel%
