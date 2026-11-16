#!/usr/bin/env python3
"""
產生一份「假的」Beat Saber 歌曲與歌單資料，用來在沒有 Quest 的手機上測試 App。

用法：
    python3 tools/make_sample_data.py <輸出資料夾>

會在輸出資料夾裡建立和 Quest 一樣的結構：
    <輸出資料夾>/ModData/com.beatgames.beatsaber/Mods/SongCore/CustomLevels/...
    <輸出資料夾>/ModData/com.beatgames.beatsaber/Mods/PlaylistManager/Playlists/...

再用 adb 推到手機的 /sdcard/ 底下即可。

刻意放進去的測試情境：
- 舊格式（v2）和新格式（v4）的歌曲
- 大小寫不同、非英文的歌名（檢查 ASCII 排序）
- 一個沒有 Info.dat 的資料夾（應該被忽略）
- 一個 Info.dat 壞掉的資料夾（應該列為讀不到）
- 歌單裡有一首「未安裝」的歌
- 歌單有額外欄位（封面、customData），存檔後必須原封不動
- 一個壞掉的歌單檔
- 有些歌不在任何歌單裡
"""

import hashlib
import json
import os
import sys

if len(sys.argv) != 2:
    print(__doc__)
    sys.exit(1)

OUT = sys.argv[1]
GAME = os.path.join(OUT, "ModData", "com.beatgames.beatsaber")
SONGS = os.path.join(GAME, "Mods", "SongCore", "CustomLevels")
PLAYLISTS = os.path.join(GAME, "Mods", "PlaylistManager", "Playlists")

if os.path.exists(GAME):
    print(f"輸出資料夾已經有資料：{GAME}\n為避免覆蓋，請換一個新的資料夾。")
    sys.exit(1)

os.makedirs(SONGS)
os.makedirs(PLAYLISTS)


def write(path, data):
    """寫檔；data 是字典就存成 JSON，是文字就直接寫。"""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(json.dumps(data, ensure_ascii=False, indent=2) if isinstance(data, dict) else data)


def sha1_of(folder, files):
    """和 App 一樣的指紋算法：依序把檔案內容倒進 SHA-1。"""
    digest = hashlib.sha1()
    for name in files:
        path = os.path.join(folder, name)
        if os.path.isfile(path):
            with open(path, "rb") as f:
                digest.update(f.read())
    return digest.hexdigest()


def make_v2(folder_name, title, mapper, difficulties, sub=""):
    """建立一首舊格式（v2）歌曲，回傳指紋。"""
    folder = os.path.join(SONGS, folder_name)
    beatmaps = []
    for diff in difficulties:
        filename = f"{diff}Standard.dat"
        write(os.path.join(folder, filename), {"_version": "2.2.0", "_notes": [], "_obstacles": [], "_events": []})
        beatmaps.append({"_difficulty": diff, "_difficultyRank": 1, "_beatmapFilename": filename})
    write(os.path.join(folder, "Info.dat"), {
        "_version": "2.0.0",
        "_songName": title,
        "_songSubName": sub,
        "_songAuthorName": "Sample Artist",
        "_levelAuthorName": mapper,
        "_beatsPerMinute": 128,
        "_songFilename": "song.egg",
        "_coverImageFilename": "cover.jpg",
        "_difficultyBeatmapSets": [
            {"_beatmapCharacteristicName": "Standard", "_difficultyBeatmaps": beatmaps}
        ],
    })
    return sha1_of(folder, ["Info.dat"] + [b["_beatmapFilename"] for b in beatmaps])


def make_v4(folder_name, title, mapper, difficulties, duration):
    """建立一首新格式（v4）歌曲，回傳指紋（含音訊資料檔與燈光檔）。"""
    folder = os.path.join(SONGS, folder_name)
    write(os.path.join(folder, "AudioData.dat"), {"version": "4.0.0", "songChecksum": "", "bpmData": []})
    beatmaps = []
    files = ["AudioData.dat"]
    for diff in difficulties:
        bm, ls = f"{diff}.beatmap.dat", f"{diff}.lightshow.dat"
        write(os.path.join(folder, bm), {"version": "4.0.0", "colorNotes": []})
        write(os.path.join(folder, ls), {"version": "4.0.0", "basicEvents": []})
        beatmaps.append({
            "characteristic": "Standard",
            "difficulty": diff,
            "beatmapAuthors": {"mappers": [mapper], "lighters": []},
            "beatmapDataFilename": bm,
            "lightshowDataFilename": ls,
        })
        files += [bm, ls]
    write(os.path.join(folder, "Info.dat"), {
        "version": "4.0.1",
        "song": {"title": title, "subTitle": "", "author": "Sample V4 Artist"},
        "audio": {"songFilename": "song.ogg", "songDuration": duration,
                  "audioDataFilename": "AudioData.dat", "bpm": 140},
        "coverImageFilename": "cover.png",
        "difficultyBeatmaps": beatmaps,
    })
    return sha1_of(folder, ["Info.dat"] + files)


# ── 歌曲 ──
h_neon = make_v2("1a2b (Neon Lights - MapperA)", "Neon Lights", "MapperA", ["Easy", "Normal", "Hard", "Expert"])
h_apple = make_v2("2b3c (apple pie - MapperB)", "apple pie", "MapperB", ["Hard", "ExpertPlus"])
h_zebra = make_v2("3c4d (Zebra Run - MapperC)", "Zebra Run", "MapperC", ["Expert", "ExpertPlus"], sub="Extended Mix")
h_jp = make_v2("4d5e (夜に駆ける - MapperD)", "夜に駆ける", "MapperD", ["Normal", "Expert"])
h_new = make_v2("5e6f (Brand New Download - MapperE)", "Brand New Download", "MapperE", ["ExpertPlus"])
h_v4 = make_v4("6f70 (Future Song - MapperV4)", "Future Song", "MapperV4", ["Hard", "Expert"], 183.4)

# 沒有 Info.dat 的資料夾：不是歌曲，應該被忽略
write(os.path.join(SONGS, "not-a-song", "readme.txt"), "這不是歌曲")
# Info.dat 壞掉：應該列為讀不到
write(os.path.join(SONGS, "7a8b (Broken - Nobody)", "Info.dat"), "{ 這不是正確的 JSON")

# ── 歌單 ──
missing_hash = "0" * 40  # 一首沒有安裝的歌

write(os.path.join(PLAYLISTS, "Anime.bplist"), {
    "playlistTitle": "Anime",
    "playlistAuthor": "tester",
    "songs": [
        {"key": "4d5e", "hash": h_jp.upper(), "songName": "夜に駆ける"},  # 故意用大寫指紋
        {"key": "6f70", "hash": h_v4, "songName": "Future Song"},
        {"key": "ffff", "hash": missing_hash, "songName": "Missing Song"},
    ],
})

write(os.path.join(PLAYLISTS, "workout.json"), {
    "playlistTitle": "workout",
    "playlistAuthor": "tester",
    "playlistDescription": "這段說明存檔後必須還在",
    "customData": {"syncURL": "https://api.beatsaver.com/playlists/id/123/download", "otherMod": True},
    "imageString": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
    "songs": [
        {"key": "1a2b", "hash": h_neon, "songName": "Neon Lights", "difficulties": [{"characteristic": "Standard", "name": "Expert"}]},
    ],
})

write(os.path.join(PLAYLISTS, "Broken.bplist"), "{ 壞掉的歌單")

print("已產生假資料：", GAME)
print("不在任何歌單的歌：apple pie、Zebra Run、Brand New Download")
for name, h in [("Neon Lights", h_neon), ("apple pie", h_apple), ("Zebra Run", h_zebra),
                ("夜に駆ける", h_jp), ("Brand New Download", h_new), ("Future Song (v4)", h_v4)]:
    print(f"  {h}  {name}")
