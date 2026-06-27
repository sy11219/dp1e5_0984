@echo off
setlocal

where mvn.cmd >nul 2>nul
if %ERRORLEVEL% EQU 0 (
  for /f "delims=" %%M in ('where mvn.cmd') do (
    if /I not "%%~fM"=="%~f0" (
      call "%%~fM" %*
      exit /b %ERRORLEVEL%
    )
  )
)

where mvn >nul 2>nul
if %ERRORLEVEL% EQU 0 (
  mvn %*
  exit /b %ERRORLEVEL%
)

echo Maven no esta disponible en PATH.
exit /b 1
