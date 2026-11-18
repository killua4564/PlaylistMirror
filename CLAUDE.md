# PlaylistMirror 專案說明（給 Claude Code）

讓 Meta Quest 上的 Beat Saber 歌單與 BeatSaver 上的歌單保持一致的 Android App（Kotlin + Jetpack Compose）。
規格書與測項清單在 claude.ai 上（PlaylistMirror 規格），改行為前先對照規格書，規格與程式要一起更新。

## 工作規則

- **不要自己 commit 或 push。** 做完一個段落、使用者明確說要提交時才提交。
- 提交前先列出要提交的檔案逐一確認，不要把草稿或沒用到的檔案一起加進去（例如不要直接 `git add -A`）。
- 使用者要求整理歷史（合併、拆分提交）時，在本機整理好、確認每個提交都能建置，再由使用者自己 force push。
- 回覆、文件、程式註解一律使用繁體中文；程式碼要加白話註解。
- 重要決定（行為、範圍、刪除檔案）先問使用者再做。刪除前先列出清單確認，不使用 `rm -rf`。

## 建置與測試

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew assembleDebug        # 建置 App
./gradlew testDebugUnitTest    # 執行單元測試
```

- Android SDK 路徑寫在 `local.properties`（每台電腦不同，不進 Git）。
- 單元測試名稱以測項編號開頭（例如 `T1-05`），對應規格書的測項分頁；新增或修改測試時同步更新測項分頁的狀態與備註。
- GitHub Actions（`.github/workflows/check.yml`）在 push 與 PR 時建置並跑單元測試，**不產出 APK**：儲存庫公開，APK 內含的 Client Secret 可被拆出，所以可登入的 APK 只在本機建置、不分享。

## 機密與個人資料（儲存庫是公開的）

- BeatSaver 的 Client ID / Secret 放在 `secrets.properties`，**絕對不能提交**，也不要貼在對話以外的地方。
- 從 Quest 拉出來的真實資料有著作權也含個人收藏，只能放在 `testdata-local/`（已被 `.gitignore` 排除），不能提交。
- 提交與文件中不要放使用者的個人資訊。

## 已決定的行為（細節見規格書）

- 遊戲資料在 `/sdcard/ModData/com.beatgames.beatsaber`；歌曲在 `Mods/SongCore`（也讀 `Mods/SongLoader`），歌單在 `Mods/PlaylistManager/Playlists`。
- 歌曲指紋依 BeatSaver 的算法（舊格式已用真實資料驗證；新格式 v4 尚待真實歌曲驗證）。
- 寫歌單檔時只改 `songs` 欄位，其他欄位（封面、說明、其他模組資料）原封不動；唯一例外是同步「BeatSaver 上改的歌單名稱」，只換 `playlistTitle`（方向固定 BeatSaver → Quest）。**封面不同步**：BeatSaver 網站換不了封面，且遊戲載入時會把封面重存成 PNG，和 BeatSaver 給的 JPG 文字永遠不一樣，比對會誤判。
- 歌單只在 BeatSaver 網站建立或刪除，App 不提供新增、刪除歌單，只負責鏡像：BeatSaver 上刪掉的歌單，同步時列為「刪除 Quest 歌單」（只刪歌單檔，歌曲保留）。
- Quest 上的歌單一律用 `.bplist`；封面欄位 `.bplist` 用 `image`、舊的 `.json` 用 `imageString`，兩種都要能讀。
- Quest 歌單與 BeatSaver 歌單的對應：用歌單檔 `customData.syncURL` 裡的歌單 ID 判斷，不做手動連結或依名稱比對。
- 同步：一顆「同步全部歌單」按鈕，雙向偵測後顯示一份摘要，確認一次就全部執行。
- 名稱排序不分大小寫；時間長度只用新格式說明書裡的資料，舊格式顯示「—」。

## 參考

- 原作者的 [Quest Song Manager](https://github.com/exelix11/QuestSongManager)（GPL-3.0）：只參考行為與 BeatSaver API 的用法，不沿用程式碼（本專案為 MIT）。
- BeatSaver 的權限範圍與 API 細節，以 [beatsaver-main 原始碼](https://github.com/beatmaps-io/beatsaver-main) 為準，公開 API 文件並不完整。
