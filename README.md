# salp Linux Mobile PC v0.3.0 — Desktop & Files Beta

Android / XREAL Beam Pro向けのPC風デスクトップ環境プロトタイプ。

> ⚠️ v0.3.0 は **テスト版（Beta）** です。実機での動作確認中のため、大切なファイルはバックアップしてから編集してください。

## v0.3.0 features
- **Files（実ファイル）**: Android標準のフォルダ選択（Storage Access Framework / `ACTION_OPEN_DOCUMENT_TREE`）で選んだフォルダを閲覧
  - 全ストレージ権限（`MANAGE_EXTERNAL_STORAGE`）は使いません。選んだフォルダだけにアクセスします
  - ファイル/フォルダ一覧（名前・種類・サイズ）、フォルダへの移動、パンくず・戻るボタン
  - 権限を保持し、次回起動時に前回のフォルダを自動で開きます（権限が取り消された場合は再選択を案内）
  - テキストファイル（`.txt .md .json .js .html .css .kt .xml .csv .log` など）は Editor で、その他は対応アプリで開きます
- **Editor（実ファイル連携）**: 新規 / 開く / 保存（元ファイルに上書き）/ 名前を付けて保存、UTF-8
  - 上書き保存後に再読込して内容一致を確認。上書きできない場合は「名前を付けて保存」を案内
  - Editorで開けるのは 2MB まで
  - `Ctrl+S` で保存
- **デスクトップ**: タイトルバーのドラッグ移動（タッチ/マウス）、右下でリサイズ、最大化/元に戻す、最小化、閉じる、タスクバーから復帰、タップで最前面
  - 狭い画面（スマホ縦など）ではウィンドウを全画面表示
- **Browser**: Firefox GeckoView 内蔵ブラウザ（PC / Mobile 表示切替、WebViewフォールバック）— v0.2.0 から継続
- Terminal（簡易シェルのモック）、salp Tools ランチャー

## Build
GitHub Actions（`.github/workflows/build-apk.yml`）で main への push ごとに debug APK をビルドし、Artifact としてアップロードします。

ローカルでは Android SDK 35 / JDK 17 / Gradle 8.14 で:

```bash
gradle :app:assembleDebug -x checkDebugAarMetadata
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Next
- Files: コピー/移動/削除/リネーム、新規フォルダ
- 実Linux環境（Termux/PRoot）連携の検討
- Android アプリランチャー
