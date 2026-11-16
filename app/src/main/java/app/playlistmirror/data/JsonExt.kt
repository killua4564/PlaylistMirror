package app.playlistmirror.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// 這個檔案放幾個「讀 JSON 的小幫手」。
// JSON 檔裡的欄位可能不存在、或型別不對，這些函式在那種情況下回傳 null 或空清單，
// 而不是讓 App 當掉。

/** 讀文字欄位；不存在或不是文字就回傳 null。 */
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** 讀物件欄位；不存在或不是物件就回傳 null。 */
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** 讀陣列欄位；不存在或不是陣列就回傳空清單。 */
internal fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? JsonArray) ?: emptyList()

/** 空白字串當成「沒有」。 */
internal fun String?.blankToNull(): String? = this?.takeIf { it.isNotBlank() }
