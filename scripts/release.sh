#!/usr/bin/env bash
# Выпуск новой версии одной командой:
#   ./scripts/release.sh 0.3.0 "Что нового в этой версии"
# Поднимает versionCode/versionName, собирает подписанный APK, коммитит, ставит тег
# и публикует GitHub Release — приложения пользователей увидят его как OTA-обновление.
set -euo pipefail

VERSION="${1:?Укажите версию, например: ./scripts/release.sh 0.3.0 \"Что нового\"}"
NOTES="${2:-}"
cd "$(dirname "$0")/.."

if [ ! -f keystore.properties ]; then
  echo "Нет keystore.properties — без ключа подписи обновление не встанет поверх старой версии." >&2
  exit 1
fi
if git rev-parse "v$VERSION" >/dev/null 2>&1; then
  echo "Тег v$VERSION уже есть." >&2
  exit 1
fi

GRADLE=app/build.gradle.kts
CODE=$(grep -oE 'versionCode = [0-9]+' "$GRADLE" | grep -oE '[0-9]+')
NEXT=$((CODE + 1))
sed -i -E "s/versionCode = [0-9]+/versionCode = $NEXT/; s/versionName = \"[^\"]+\"/versionName = \"$VERSION\"/" "$GRADLE"
echo "→ версия $VERSION (код $NEXT)"

./gradlew assembleRelease --console=plain -q
mkdir -p dist
APK="dist/Unison-$VERSION.apk"
cp app/build/outputs/apk/release/app-release.apk "$APK"
echo "→ собран $APK"

git add "$GRADLE" CHANGELOG.md
git commit -m "Release v$VERSION"
git tag "v$VERSION"
git push
git push origin "v$VERSION"

gh release create "v$VERSION" "$APK" --title "Unison $VERSION" --notes "${NOTES:-Подробности — в CHANGELOG.md}"
echo "✓ Релиз v$VERSION опубликован"
