# 自動日本語字幕（AutoJimaku）

Android スマホで再生中の動画（YouTube・Udemy など）の音声を聞き取り、**日本語字幕を画面の上に重ねて表示**するアプリです。

- 動画アプリ側の字幕機能に関係なく、**聞こえている音声から**字幕を作ります
- 全画面・横向きの動画の上にも表示されます。字幕は常に画面内に収まり、つまみをドラッグして移動できます
- 翻訳した字幕は履歴として残り、字幕の箱を上下にスワイプすると前の字幕まで見返せます
- **無料・オフライン**（端末内の音声認識＋Google ML Kit 翻訳）で動きます
- 翻訳はオンラインの **Google翻訳**（月50万文字まで無料）、**Google Gemini**（無料枠あり）、**OpenAI**（有料）にも切り替えられます（どれも APIキーが必要）
- **82言語＋自動判別**に対応（タイ語・ベトナム語・インドネシア語・中国語・韓国語・英語など）。タイ語とベトナム語は専用の高精度モデルを使います
- 日本語の動画を日本語字幕にする（文字起こしのみ）こともできます
- 対応: Android 10 以降

## 仕組み

```
動画アプリの再生音 ──(Android の再生音キャプチャ)──▶ 音声認識 ──▶ 日本語に翻訳 ──▶ 画面に重ねて表示
                                                    │                     │
                                   端末内・軽量(Vosk)             端末内(ML Kit)
                                   端末内・多言語(Whisper/        or Google翻訳 / Gemini / OpenAI
                                     タイ語・ベトナム語専用)
                                   or OpenAI
```

画面の映像は一切記録しません。Android の仕組み上、再生音の取得には「画面の録画/キャスト」の確認画面が出ますが、使うのは音声だけです。

---

## 1. APK（インストールファイル）を作る

ソースコードからビルドする必要があります。どちらか好きな方法で。

### 方法A：パソコンの Android Studio で作る（いちばん簡単）

1. [Android Studio](https://developer.android.com/studio) をインストール
2. ZIP を展開し、Android Studio の「Open」で `AutoJimaku` フォルダを開く（初回は必要なファイルの取得に数分かかります）
3. スマホを USB でつないで ▶（Run）を押す → スマホにインストールされます
   - APK ファイルだけ欲しい場合: メニューの **Build → Build App Bundle(s) / APK(s) → Build APK(s)**

### 方法B：GitHub で作る（パソコン不要・スマホのブラウザだけでOK）

GitHub の無料のビルド機能（GitHub Actions）を使います。

1. [github.com](https://github.com) でアカウントを作り、右上の「＋」→ **New repository**
   - 名前は何でもOK（例: `AutoJimaku`）。Private でも構いません → **Create repository**
2. 作成直後の画面の **「uploading an existing file」** を押し、`AutoJimaku.zip` を**ZIPのまま**アップロード → **Commit changes**
3. **Add file → Create new file** を押し、ファイル名の欄に `.github/workflows/build.yml` と入力（`/` を打つとフォルダになります）
4. 本文に、ZIP 内の `.github/workflows/build.yml` の中身（下にも同じものを掲載）を貼り付けて **Commit changes**
5. 上部の **Actions** タブを開くとビルドが始まっています（5〜10分）。緑のチェックになったら実行結果を開き、下の **Artifacts** にある **AutoJimaku-apk** をダウンロード
6. ダウンロードした ZIP を「ファイル」アプリで展開し、`app-release.apk` をタップしてインストール
   - 「提供元不明のアプリ」の許可を求められたら、ブラウザ（またはファイルアプリ）に許可してください

<details>
<summary>build.yml の中身（手順4で貼り付けるもの）</summary>

```yaml
name: Build APK

on:
  push:
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      # プロジェクトを ZIP のままアップロードした場合は展開して使う
      - name: Find project
        run: |
          if [ -f settings.gradle.kts ]; then
            echo "PROJECT_DIR=." >> "$GITHUB_ENV"
          else
            zip_file=$(ls *.zip | head -n 1)
            unzip -q "$zip_file" -d unpacked
            dir=$(dirname "$(find unpacked -name settings.gradle.kts | head -n 1)")
            echo "PROJECT_DIR=$dir" >> "$GITHUB_ENV"
          fi

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17

      - name: Build
        working-directory: ${{ env.PROJECT_DIR }}
        run: |
          chmod +x gradlew
          ./gradlew assembleRelease --no-daemon

      - uses: actions/upload-artifact@v4
        with:
          name: AutoJimaku-apk
          path: ${{ env.PROJECT_DIR }}/app/build/outputs/apk/release/*.apk
```
</details>

---

## 2. 使い方

1. アプリを開き、**「動画で話されている言語」**を選ぶ（Udemy の英語講座なら「英語」、タイの動画なら「タイ語」）
   - 言語の下に、その言語で使える音声認識と翻訳が表示されます。使えない音声認識を選んでいた場合は自動で切り替わります
2. **「字幕を開始」**を押す。初回だけ次の確認が出ます
   - 「他のアプリの上に重ねて表示」→ このアプリをオンにして戻る
   - 録音（音声の取得）の許可 → 許可
   - 音声認識モデル（英語・軽量で約40MB、タイ語専用で約160MB）と翻訳モデル（約30MB）のダウンロード → Wi-Fi 推奨。途中で止まっても次回は続きから再開します
3. 「録画またはキャストを開始しますか？」の画面で **「開始」**（Android 14 以降は「画面全体」を共有）
4. YouTube や Udemy を開いて動画を再生 → 日本語字幕が出ます
5. 止めるときは、通知の **「停止」** か、アプリの **「字幕を停止」**

### 字幕の箱の使い方

- **移動**: 箱の上にある「つまみ（横線）」をドラッグ。画面の端やステータスバー・ナビゲーションバーにはかからず、常に画面内に収まります（画面を回転しても同じ）
- **前の字幕を見る**: 箱を上下にスワイプ。履歴を見ている間は最新の字幕に勝手に戻りません。右上の「▼ 最新」で最新に戻ります
- **たたむ／開く**: しばらく字幕が出ないと、箱は小さなつまみだけになります。つまみをタップすると履歴を開けます（もう一度タップでたたむ）
- 履歴は字幕を止めるまで、最大300文まで残ります

### 表示の調整（字幕を出したまま変更できます）

| 設定 | 内容 |
|---|---|
| 文字サイズ | 14〜36 |
| 字幕の箱の高さ | 一度に見える行数（2〜8行）。それより前はスワイプで見ます |
| 原文も小さく表示 | 英語などの原文を日本語の下に表示（語学学習にも） |
| 字幕の上からでも動画を操作 | オンにすると字幕部分のタッチが下の動画アプリに届きます（その間は字幕の移動・履歴のスクロールはできません） |

---|---|
| 文字サイズ | 14〜36 |
| 原文も小さく表示 | 英語などの原文を日本語の下に表示（語学学習にも） |
| 字幕の上からでも動画を操作 | オンにすると字幕部分のタッチが下の動画アプリに届きます（その間は字幕を移動できません） |

字幕は指でドラッグして好きな位置に動かせます。位置は記憶されます。

---

## 3. 音声認識の選び方

| 音声認識 | 料金 | 特徴 | 字幕の出方 |
|---|---|---|---|
| **端末内・軽量**（Vosk） | 無料・オフライン | 軽くて速い。対応は約30言語 | 話している途中から暫定訳が出る |
| **端末内・多言語**（Whisper／専用モデル） | 無料・オフライン | より正確。82言語と自動判別に対応。**タイ語・ベトナム語は専用モデル** | 文の区切りごと（話し終わりから約0.5〜2秒） |
| **OpenAI** | 従量課金 | もっとも高精度（早口・専門用語に強い） | 文の区切りごと（約2〜4秒） |

### タイ語の動画を見るとき（おすすめ設定）

- 言語: **タイ語** ／ 音声認識: **端末内・多言語**（自動で選ばれます）→ タイ語専用モデル（約160MB）を使います
- 翻訳は「端末内」で無料のまま使えます。より自然な日本語にしたい場合は翻訳だけ「Gemini」（無料枠あり）か「OpenAI」に

開発時に、実際のタイ語の音声で専用モデルが正解の文とほぼ同じ文字起こしをすることを確認しています（開発用PCで1文あたり約0.2秒。スマホではこれより遅くなります）。

### 多言語モデル（Whisper）の大きさ

タイ語・ベトナム語以外を「端末内・多言語」で認識するときに使います。

| 大きさ | ダウンロード | 向いている端末 |
|---|---|---|
| tiny | 約100MB | 古い・安価なスマホ（精度は低め） |
| **base**（標準） | 約160MB | ほとんどのスマホ |
| small | 約380MB | 新しい高性能スマホ（精度が上がるが処理が重い） |

字幕が遅れる・「処理が追いつかない」と表示される場合は、小さいモデルにしてください。

### 自動判別について

- 文ごとに言語を判別して、それぞれ日本語に翻訳します（英語とタイ語が混ざる動画など）
- 一度「タイ語」「ベトナム語」を選んで専用モデルをダウンロードしておくと、自動判別でもその言語は専用モデルで認識します
- 判別はたまに外れます（似た言語や短い発話）。**動画の言語が決まっているなら、その言語を選ぶ方が正確です**

### 翻訳

| 翻訳 | 料金 | 特徴 |
|---|---|---|
| **端末内**（Google ML Kit） | 無料・オフライン | Google翻訳アプリのオフライン翻訳と同じ仕組み。速い。59言語。話している途中の暫定訳も出る |
| **Google翻訳（オンライン）** | 月50万文字まで無料（超えると100万文字あたり20ドル） | ウェブ版の Google翻訳と同じく、端末内より高精度。全言語に対応。速い |
| **Gemini** | 無料枠あり（超えると従量課金） | 文脈を踏まえた自然な日本語。全言語に対応 |
| **OpenAI** | 従量課金 | 文脈を踏まえた自然な日本語。全言語に対応 |

#### Google翻訳（オンライン）を使うには

字幕1文はおよそ50〜100文字なので、無料枠（月50万文字）で月5,000〜10,000文ほど翻訳できます。

1. [Google Cloud コンソール](https://console.cloud.google.com/) に Google アカウントでログインし、プロジェクトを作る（初めての場合）
2. [Cloud Translation API のページ](https://console.cloud.google.com/apis/library/translate.googleapis.com) で「有効にする」を押す（請求先アカウント＝クレジットカードの登録を求められます。無料枠内なら料金はかかりません）
3. 「APIとサービス」→「認証情報」→「認証情報を作成」→「APIキー」でキーを作る。作ったキーの設定で「APIの制限」を「Cloud Translation API」だけにしておくと安全です
4. アプリの翻訳で「Google翻訳」を選び、表示される欄に APIキー（`AIza…` で始まる文字列）を貼り付けて「字幕を開始」

- 使いすぎが心配な場合は、Google Cloud の「お支払い」→「予算とアラート」で予算の通知を設定できます
- 上限に達したときや通信が切れたときは、自動で端末内翻訳に切り替え、1分ほどしたら Google翻訳に戻ります

#### Gemini を使うには

1. [Google AI Studio の APIキーのページ](https://aistudio.google.com/apikey) を開き、Google アカウントでログインして「APIキーを作成」
2. アプリの翻訳で「Gemini」を選び、表示される欄に APIキー（`AIza…` で始まる文字列）を貼り付ける
3. いつもどおり「字幕を開始」

- 初期のモデルは `gemini-3.5-flash-lite`（いちばん速く安いモデル）。アプリ内で変更できます（例: より高精度な `gemini-3.8-flash`）
- 無料枠には1分あたり・1日あたりの回数制限があります。上限に達したときや通信が切れたときは、字幕が止まらないよう**自動で端末内翻訳に切り替え**、1分ほどしたら Gemini に戻ります
- 無料枠では、送った文章が Google のサービス改善に使われることがあります（有料枠では使われません）。料金は [Gemini API の料金ページ](https://ai.google.dev/gemini-api/docs/pricing) を確認してください

#### 端末内翻訳に未対応の言語

次の言語は端末内翻訳に未対応のため、翻訳を「Google翻訳」「Gemini」「OpenAI」のどれかにしてください: アゼルバイジャン語・アムハラ語・アルメニア語・ウズベク語・カザフ語・キルギス語・クメール語・シンハラ語・セルビア語・ソマリ語・タジク語・ネパール語・ハウサ語・パシュトー語・バスク語・パンジャブ語・ボスニア語・マラヤーラム語・ミャンマー語・モンゴル語・ヨルバ語・ラオ語・ラテン語

#### OpenAI を使うには

- [OpenAI の API キー](https://platform.openai.com/api-keys) が必要です（ChatGPT の有料プランとは別契約・従量課金）。料金は [OpenAI の料金ページ](https://openai.com/api/pricing/) を確認してください
- 初期のモデル: 音声認識 `gpt-4o-mini-transcribe` / 翻訳 `gpt-5-mini`（アプリ内で変更可）

APIキーは端末内にだけ保存されます。Google翻訳・Gemini・OpenAI を選んだ場合のみ、文字起こし（OpenAI の音声認識を選んだ場合は音声）がそのサービスに送信されます。

---

## 4. うまくいかないとき

**「他のアプリの上に重ねて表示」のスイッチがグレーで押せない**
Android 15 以降は、Google Play 以外から入れたアプリの一部の許可が「制限付き設定」でロックされます。次の手順で解除できます（アプリ内でも案内が出ます）。
1. 許可の画面でグレーのスイッチを一度タップする（「制限付き設定」の表示は OK で閉じる）
2. 設定 → アプリ → 自動日本語字幕 → 右上の︙（その他）→「制限付き設定を許可」→ 画面ロックで確認
3. アプリに戻って「字幕を開始」を押し、スイッチをオンにする

**字幕が出ない／「音声が届いていません」と表示される**
動画アプリによっては、他のアプリによる再生音の取得を禁止しています（Netflix などの有料配信は多くがこれに該当）。次を試してください。
- 同じ動画を **Chrome などのブラウザ**で再生する（Udemy・YouTube ともにブラウザで視聴できます）
- 音声の取得元を **「マイク」** にする（スマホのスピーカー音をマイクで拾います。イヤホン使用時は不可）

**字幕の精度が低い**
- 言語の選択が合っているか確認（自動判別より、言語を選んだ方が正確です）
- 音声認識を「端末内・多言語」にする、Whisper を small にする、または OpenAI に切り替える
- BGM が大きい動画は精度が落ちます

**字幕が遅れる／「処理が追いつかない」と表示される**
- 「端末内・多言語」なら Whisper を小さいモデル（base → tiny）に。タイ語・ベトナム語は専用モデルなので軽く動きます
- 他のアプリを閉じる

**ダウンロードしたモデルを消したい**
- 端末の設定 → アプリ → 自動日本語字幕 → ストレージ →「データを削除」（設定も初期化されます）

**しばらくすると止まる**
- 端末の設定 → アプリ → 自動日本語字幕 → バッテリー →「制限なし」にしてください（メーカーによって表記が異なります）

**通知が出ない**
- Android 13 以降で通知を許可しなかった場合でも字幕は動きます。停止はアプリの「字幕を停止」から行えます

---

## 技術メモ

- 言語: Kotlin / minSdk 29（Android 10）/ targetSdk 35 / 対応CPU: arm64-v8a・armeabi-v7a（PC のエミュレーターで試す場合は `app/build.gradle.kts` の `abiFilters` に `"x86_64"` を追加）
- 再生音の取得: `MediaProjection` ＋ `AudioPlaybackCaptureConfiguration`（フォアグラウンドサービス）。取得した音声はローパスフィルタ付きで 16kHz に変換
- 音声認識
  - 端末内・軽量: [Vosk](https://alphacephei.com/vosk/)
  - 端末内・多言語: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 1.13.8（Silero VAD で発話区間を切り出し、Whisper int8 またはタイ語 Zipformer（GigaSpeech2）・ベトナム語 Zipformer で認識）。AAR はビルド時に GitHub のリリースから取得します
  - OpenAI: `audio/transcriptions`（簡易 VAD で区間ごとに送信）
- 翻訳: [Google ML Kit Translation](https://developers.google.com/ml-kit/language/translation) ＋ [Language ID](https://developers.google.com/ml-kit/language/identification)（端末内）、Google Cloud Translation API Basic v2・Gemini API `generateContent`（どちらも利用上限・通信エラー時は端末内翻訳に自動切り替え）、OpenAI `chat/completions`（クラウドはどちらも直前2文を文脈として渡す）
- モデルの入手元: Whisper・ベトナム語は Hugging Face（csukuangfj）、タイ語は Hugging Face（yfyeung/icefall-asr-gigaspeech2-th-zipformer）、Silero VAD は sherpa-onnx の GitHub リリース
- 表示: `TYPE_APPLICATION_OVERLAY` のオーバーレイウィンドウ

| ファイル | 役割 |
|---|---|
| `MainActivity.kt` | 設定画面・権限・モデル準備・開始 |
| `Languages.kt` | 対応言語の一覧（言語ごとに使える音声認識・翻訳） |
| `CaptionService.kt` | 字幕表示中に動くフォアグラウンドサービス |
| `AudioCapture.kt` / `audio/Resampler16k.kt` | 再生音／マイク音声の取得と 16kHz への変換 |
| `speech/VoskEngine.kt` | 端末内・軽量の音声認識 |
| `speech/SherpaEngine.kt` | 端末内・多言語の音声認識（VAD・Whisper・専用モデル・言語判別） |
| `speech/OpenAiSpeechEngine.kt` | OpenAI の音声認識（発話区間の切り出し付き） |
| `translate/CaptionTranslator.kt` | ML Kit / Google翻訳 / Gemini / OpenAI の翻訳（自動判別時は文ごとに言語を判別） |
| `cloud/` | Google翻訳・Gemini・OpenAI の API 呼び出し |
| `CaptionPipeline.kt` | 認識→翻訳→表示の受け渡し |
| `CaptionOverlay.kt` | 画面に重ねる字幕 |
| `models/` | モデルのダウンロード（途中から再開可）と管理 |

個人で視聴する動画に字幕を付ける用途を想定しています。字幕や音声を録音・再配布する機能はありません。
