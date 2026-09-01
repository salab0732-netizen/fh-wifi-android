@echo off
REM Upload FHWifi Android Java project to GitHub
REM Save this as: F:\Downloads\upload.bat

cd /d F:\Downloads\fhwifi-android

git config --global user.name "salab0732-netizen"
git config --global user.email "dev@fhwifi.local"

REM Remove old files if git exists
if exist .git (
    echo Clearing repository...
    git rm -r --cached --force main.py android_wifi.py buildozer.spec github 2>nul
    git rm --cached --force .gitignore 2>nul
)

REM Initialize git
if not exist .git (
    git init
    git remote add origin https://github.com/salab0732-netizen/fh-wifi-android.git
)

REM Add all files
git add .
git commit -m "Replace Kivy with native Android Java - Gradle build system"

REM Push
git push -u origin main

echo.
echo ✅ Upload complete! Check: https://github.com/salab0732-netizen/fh-wifi-android
pause
