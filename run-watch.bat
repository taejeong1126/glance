@echo off
setlocal

set /p WATCH_IP=172.30.1.32:33831

start cmd /k "npx react-native start"

timeout /t 5 > nul

adb connect %WATCH_IP%

call npx react-native run-android --deviceId %WATCH_IP%

pause