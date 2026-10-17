@echo off
setlocal
if exist "%~dp0gradle\wrapper\gradle-wrapper.jar" (
  if defined JAVA_HOME (
    "%JAVA_HOME%\bin\java.exe" -classpath "%~dp0gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
  ) else (
    java -classpath "%~dp0gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
  )
) else (
  where gradle >nul 2>nul
  if errorlevel 1 (
    echo Gradle wrapper JAR is not bundled. Use installed Gradle 8.10.2; CI provisions it.
    exit /b 1
  )
  call gradle %*
)
exit /b %ERRORLEVEL%
