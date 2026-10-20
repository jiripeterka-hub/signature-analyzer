@echo off
setlocal

rem Najde nejnovejsi nainstalovany Adoptium JDK. Verze se meni pri kazde aktualizaci,
rem takze pevna cesta prestane platit a Maven pak hlasi "JAVA_HOME is not defined correctly".
set "JDK_ROOT=C:\Program Files\Eclipse Adoptium"
for /f "delims=" %%d in ('dir /b /ad /on "%JDK_ROOT%\jdk-*-hotspot" 2^>nul') do set "JAVA_HOME=%JDK_ROOT%\%%d"

if not exist "%JAVA_HOME%\bin\java.exe" (
  echo.
  echo Nenalezen zadny JDK v "%JDK_ROOT%".
  echo Nastavte JAVA_HOME rucne v tomto souboru, nebo nainstalujte Eclipse Temurin JDK.
  echo.
  pause
  exit /b 1
)

echo Pouzity JDK: %JAVA_HOME%
set PATH=%JAVA_HOME%\bin;D:\Apps\maven\bin;%PATH%
set MAVEN_OPTS=-Djavax.net.ssl.trustStore=%USERPROFILE%\.jdk-cacerts -Djavax.net.ssl.trustStorePassword=changeit --enable-native-access=ALL-UNNAMED

cd /d "%~dp0app"
mvn javafx:run > "%~dp0run.log" 2>&1
echo.
echo --- maven exited with code %ERRORLEVEL% ---
echo Vystup je ulozen v: %~dp0run.log
echo.
type "%~dp0run.log" | more
pause
endlocal
