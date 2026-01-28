@echo off
echo Fixing P2P File Share Project...

REM Remove problematic files
if exist .idea rmdir /s /q .idea
if exist local.properties del local.properties
if exist *.iml del *.iml

REM Create gradle wrapper directory if missing
if not exist gradle\wrapper mkdir gradle\wrapper

echo.
echo Project fixed! Now follow these steps:
echo.
echo 1. Open Android Studio
echo 2. Click "Open an Existing Project"
echo 3. Select this folder: %CD%
echo 4. When Android Studio opens, it will automatically:
echo    - Download missing Gradle wrapper
echo    - Sync the project
echo    - Fix any configuration issues
echo.
echo 5. If you see "Gradle Sync" popup, click "Sync Now"
echo 6. Wait for sync to complete (check bottom progress bar)
echo.
echo The project should then work correctly!
echo.
pause