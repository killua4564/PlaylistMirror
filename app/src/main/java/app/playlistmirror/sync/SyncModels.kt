package app.playlistmirror.sync

import kotlinx.serialization.json.JsonObject

/**
 * 一首歌在同步比對裡的「身分」。
 *
 * 優先用 BeatSaver 短 ID（key）：作者更新譜面後指紋會變，但短 ID 不變，
 * 這樣才不會把「同一首歌的新版本」誤判成「一首刪除、一首新增」。
 * 沒有短 ID 時才退而用指紋。
 */
fun songIdentity(key: String?, hash: String?): String? =
    key?.takeIf { it.isNotBlank() }?.lowercase()?.let { "key:$it" }
        ?: hash?.takeIf { it.isNotBlank() }?.lowercase()?.let { "hash:$it" }

/** BeatSaver 歌單裡的一首歌。hash 是 BeatSaver 上「目前最新版本」的指紋。 */
data class CloudSong(val key: String?, val hash: String, val songName: String?) {
    val identity: String get() = songIdentity(key, hash)!!
}

/**
 * 從 BeatSaver 下載的一個歌單。
 * raw 是 BeatSaver 給的完整歌單檔內容（含封面、syncURL），建立新的 Quest 歌單時直接用它。
 */
data class CloudPlaylist(val id: String, val title: String, val songs: List<CloudSong>, val raw: JsonObject)

// ─────────────── 上次同步的記錄（基準） ───────────────

/**
 * 上次同步成功時，某首歌在某個歌單裡的狀態。
 * installed：那時這首歌有沒有安裝在 Quest 上。用來分辨「你在遊戲裡刪掉」和「還沒下載」。
 */
data class SongRecord(val identity: String, val key: String?, val hash: String, val installed: Boolean)

/** 上次同步成功時，某個歌單（以 BeatSaver 歌單 ID 辨識）裡有哪些歌。 */
data class PlaylistRecord(val playlistId: String, val songs: Map<String, SongRecord>)

/**
 * 整份同步記錄。
 * 某個歌單沒有記錄，代表它「第一次同步」：兩邊的落差無法判斷來源，要請使用者決定。
 */
data class SyncRecord(val playlists: Map<String, PlaylistRecord> = emptyMap(), val syncedAt: Long? = null)

// ─────────────── 同步要做的事 ───────────────

/** 動作發生在哪一邊。摘要畫面依此分成「Quest 會變成」和「BeatSaver 會變成」兩區。 */
enum class Side { Quest, BeatSaver }

enum class SyncKind(val side: Side) {
    /** 下載歌曲並加進 Quest 歌單（BeatSaver 新加的歌） */
    Download(Side.Quest),

    /** 歌曲已安裝，只要加進 Quest 歌單 */
    AddToQuestPlaylist(Side.Quest),

    /** 從 Quest 歌單移出（BeatSaver 上移除了；歌曲檔案保留） */
    RemoveFromQuestPlaylist(Side.Quest),

    /** 作者發佈新版：下載新版、刪除舊版、所有歌單改指向新版 */
    Update(Side.Quest),

    /** BeatSaver 上新建的歌單：在 Quest 建立歌單檔 */
    CreateQuestPlaylist(Side.Quest),

    /** BeatSaver 上刪掉的歌單：保留 Quest 上的歌單，但解除對應（移除 syncURL） */
    UnlinkQuestPlaylist(Side.Quest),

    /** 你在 Quest 新加的歌：加到 BeatSaver 歌單 */
    AddToBeatSaver(Side.BeatSaver),

    /** 你在遊戲裡刪掉或從歌單移出的歌：從 BeatSaver 歌單移除 */
    RemoveFromBeatSaver(Side.BeatSaver),
}

/**
 * 摘要裡的一個項目。
 *
 * @property needsDecision true 表示 App 無法確定來源（例如第一次同步），列為「請你決定」、預設不勾選
 * @property hash 這個動作要用的指紋（下載或寫回用最新版；移出用歌單裡的那個）
 * @property oldHash 只有 Update 會用到：要被取代的舊版指紋
 */
data class SyncItem(
    val kind: SyncKind,
    val playlistId: String,
    val playlistTitle: String,
    val identity: String? = null,
    val key: String? = null,
    val hash: String? = null,
    val oldHash: String? = null,
    val songName: String? = null,
    val needsDecision: Boolean = false,
) {
    val side: Side get() = kind.side
}
