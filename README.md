# PlaylistMirror

讓 Meta Quest 上的 Beat Saber 歌單，和你在 [BeatSaver](https://beatsaver.com) 上的歌單保持一致的 Android App。

按一顆「同步全部歌單」按鈕，App 會同時偵測 Quest 與 BeatSaver 兩邊的變更，把兩邊會發生的動作整理成一份摘要，你確認一次就全部執行。

> 個人使用的工具，參考 [Quest Song Manager](https://github.com/exelix11/QuestSongManager) 的行為與 BeatSaver 公開的 API，程式碼全部重新撰寫。

## 目前進度

| 階段 | 內容 | 狀態 |
| --- | --- | --- |
| 1. 讀取與顯示 | 讀取 Quest 上的歌曲與歌單；搜尋、篩選「不在任何歌單」、排序；管理歌曲所屬歌單；刪除歌曲 | 開發中 |
| 2. 同步比對 | 讀取 BeatSaver 歌單、記錄上次同步狀態、判斷兩邊的變更 | 未開始 |
| 3. 寫回 | 登入 BeatSaver、同步摘要確認、寫回歌單 | 等待 BeatSaver 授權 |

## 功能重點

- **歌曲清單**：顯示歌名、製譜者、時間長度、難度；可用歌名搜尋，或只看「不在任何歌單」的歌
- **歌曲頁面**：直接勾選這首歌屬於哪些歌單；也可以刪除歌曲（會同時從所有歌單移除）
- **歌單**：依名稱排序；歌單內的歌曲依歌名排序，可把歌移出歌單。歌單本身在 BeatSaver 網站建立或刪除，App 只負責鏡像到 Quest
- **安全的寫檔**：只修改需要改的欄位，歌單檔裡的封面、說明與其他模組的資料都原封不動

## 遊戲資料位置

App 讀寫的是 Beat Saber（含模組）在 Quest 上的資料夾：

| 項目 | 路徑 |
| --- | --- |
| 歌曲 | `/sdcard/ModData/com.beatgames.beatsaber/Mods/SongCore/CustomLevels` |
| 歌單 | `/sdcard/ModData/com.beatgames.beatsaber/Mods/PlaylistManager/Playlists` |

需要「所有檔案存取」權限才能讀寫這些資料夾。

## 建置

需要 JDK 17 與 Android SDK（compileSdk 35）。

```bash
./gradlew assembleDebug
```

產生的 App 在 `app/build/outputs/apk/debug/app-debug.apk`。

`tools/make_sample_data.py` 可以產生一份假的歌曲與歌單資料，方便在沒有 Quest 的手機上測試。

## 授權

[MIT](LICENSE)
